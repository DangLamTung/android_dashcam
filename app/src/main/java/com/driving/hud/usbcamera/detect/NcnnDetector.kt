package com.driving.hud.usbcamera.detect

import android.content.Context
import com.driving.hud.usbcamera.util.HudLogger

/**
 * YOLO detector running the graph on ncnn, optionally on the Adreno GPU through Vulkan.
 *
 * Why this backend exists: on this tablet every accelerator reachable through ONNX Runtime
 * measured no cheaper than plain CPU — NNAPI was ~2.4x *worse*, XNNPACK matched CPU-ms while
 * only winning on wall time (threads, not less work) — and Qualcomm's own QNN runtime is not
 * installed on the device at all. ncnn ships its own hand-written ARM kernels, and its own
 * Vulkan backend, which is the only route to this GPU that does not go through the vendor's
 * NNAPI HAL.
 *
 * Pre- and post-processing are shared with the ONNX backend ([Letterbox], [YoloHead]), so both
 * backends turn the same frame into the same boxes and a decoder fix cannot apply to only one.
 */
class NcnnDetector private constructor(
    private val handle: Long,
    private val inputW: Int,
    private val inputH: Int,
    private val labels: Array<String>,
    val useVulkan: Boolean
) : Detector {

    companion object {
        private const val TAG = "NcnnDetector"

        const val PARAM_ASSET_320 = "yolov10n_320.ncnn.param"
        const val BIN_ASSET_320 = "yolov10n_320.ncnn.bin"
        const val PARAM_ASSET_224 = "yolov10n_224.ncnn.param"
        const val BIN_ASSET_224 = "yolov10n_224.ncnn.bin"

        // Legacy constants
        const val PARAM_ASSET = PARAM_ASSET_320
        const val BIN_ASSET = BIN_ASSET_320

        init {
            // libncnn is linked as a dependency of the main native library, but loading it
            // explicitly makes the order deterministic.
            try {
                System.loadLibrary("ncnn")
            } catch (t: Throwable) {
                HudLogger.w(TAG, "explicit libncnn load failed: ${t.message}")
            }
            System.loadLibrary("native-camera-hud")
        }

        @JvmStatic external fun nativeGpuCount(): Int

        @JvmStatic external fun nativeInit(
            param: ByteArray,
            model: ByteArray,
            inW: Int,
            inH: Int,
            inC: Int,
            threads: Int,
            vulkan: Boolean,
            fp16: Boolean
        ): Long

        @JvmStatic external fun nativeRun(handle: Long, input: FloatArray, output: FloatArray): Int

        @JvmStatic external fun nativeOutputShape(handle: Long): IntArray

        @JvmStatic external fun nativeRelease(handle: Long)

        /** ncnn reports how many GPUs it can use; 0 disables the Vulkan backend. */
        fun gpuCount(): Int = try {
            nativeGpuCount()
        } catch (t: Throwable) {
            HudLogger.w(TAG, "nativeGpuCount failed: ${t.message}")
            0
        }

        /**
         * Build a session, or null if ncnn refuses. Returns null rather than throwing so the
         * caller can record this backend as unavailable and keep going.
         */
        fun create(
            context: Context,
            inputW: Int,
            inputH: Int,
            labels: Array<String>,
            vulkan: Boolean,
            threads: Int = 2,
            fp16: Boolean = true,
            paramAsset: String = if (inputW == 224) PARAM_ASSET_224 else PARAM_ASSET_320,
            binAsset: String = if (inputW == 224) BIN_ASSET_224 else BIN_ASSET_320
        ): NcnnDetector? {
            return try {
                val param = context.assets.open(paramAsset).use { it.readBytes() }
                val model = context.assets.open(binAsset).use { it.readBytes() }
                HudLogger.i(TAG, "loading ncnn assets: $paramAsset, $binAsset (${model.size / 1024} KB)")
                // The ncnn param carries no baked input shape (pnnx leaves the Input layer
                // shape-free), so the shape is declared here and the Mat we feed defines it.
                val handle = nativeInit(param, model, inputW, inputH, 3, threads, vulkan, fp16)
                if (handle == 0L) {
                    HudLogger.w(TAG, "ncnn init refused (vulkan=$vulkan)")
                    null
                } else {
                    val det = NcnnDetector(handle, inputW, inputH, labels, vulkan)
                    // One run now, so the output buffers can be sized before the caller times
                    // anything and so a broken session fails here rather than mid-drive.
                    if (!det.prepare()) {
                        det.close()
                        null
                    } else {
                        det
                    }
                }
            } catch (t: Throwable) {
                HudLogger.e(TAG, "ncnn session creation failed", t)
                null
            }
        }
    }

    /** Native output, flattened. Reused so a frame does not allocate ~700 KB. */
    private var outFlat = FloatArray(0)

    /** The same data seen as rows, which is the view the shared decoder walks. */
    private var rowView = Array(0) { FloatArray(0) }
    private var rows = 0
    private var cols = 0
    private var shapeLogged = false

    private val letterbox = Letterbox(inputW, inputH)
    private val head = YoloHead(labels, TAG)

    /**
     * A warm-up pass that also sizes the output buffers. ncnn's output shape is only knowable
     * after a run, so this has to happen before the first real frame.
     */
    private fun prepare(): Boolean {
        val scratch = FloatArray(3 * inputW * inputH)
        val generous = FloatArray(500_000)
        val written = try {
            nativeRun(handle, scratch, generous)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "ncnn warm-up run threw", t)
            return false
        }
        if (written <= 0) {
            HudLogger.w(TAG, "ncnn warm-up run returned $written")
            return false
        }
        val shape = nativeOutputShape(handle)
        val dims = shape.getOrElse(0) { 0 }
        val w = shape.getOrElse(1) { 0 }
        val h = shape.getOrElse(2) { 0 }
        val c = shape.getOrElse(3) { 1 }
        // ncnn stores w fastest, so a flat buffer splits into (rows of length w) rows.
        // dims 2 = (w, h): h rows of w. dims 3 = (w, h, c): c rows of h*w. dims 1 = one row.
        when (dims) {
            1 -> {
                rows = 1
                cols = w
            }
            2 -> {
                rows = h
                cols = w
            }
            3 -> {
                rows = c
                cols = h * w
            }
            else -> {
                HudLogger.w(TAG, "unexpected ncnn output dims=$dims (w=$w h=$h c=$c)")
                return false
            }
        }
        if (rows <= 0 || cols <= 0 || rows * cols != written) {
            HudLogger.w(TAG, "inconsistent output shape rows=$rows cols=$cols vs written=$written")
            return false
        }
        outFlat = FloatArray(written)
        rowView = Array(rows) { FloatArray(cols) }
        HudLogger.i(
            TAG,
            "ncnn ready (vulkan=$useVulkan): input ${inputW}x$inputH, output dims=$dims " +
                "$rows x $cols, gpuCount=${gpuCount()}"
        )
        return true
    }

    override fun detect(nv21: ByteArray, w: Int, h: Int, conf: Float): List<Detection> {
        if (w <= 0 || h <= 0 || nv21.size < w * h * 3 / 2) return emptyList()
        if (outFlat.isEmpty()) return emptyList()

        val chw = letterbox.fill(nv21, w, h)
        val written = try {
            nativeRun(handle, chw, outFlat)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "ncnn run failed", t)
            return emptyList()
        }
        if (written != outFlat.size) {
            HudLogger.w(TAG, "unexpected output size $written (expected ${outFlat.size})")
            return emptyList()
        }
        if (!shapeLogged) {
            shapeLogged = true
            HudLogger.i(TAG, "ncnn output $rows x $cols")
        }

        // Split the flat buffer into the per-row view the shared decoder expects.
        var p = 0
        for (r in 0 until rows) {
            System.arraycopy(outFlat, p, rowView[r], 0, cols)
            p += cols
        }

        return head.decode(
            rowView,
            rows,
            cols,
            conf,
            w,
            h,
            letterbox.scale,
            letterbox.dx,
            letterbox.dy
        )
    }

    override fun close() {
        try {
            nativeRelease(handle)
        } catch (t: Throwable) {
            HudLogger.w(TAG, "ncnn release failed: ${t.message}")
        }
    }
}
