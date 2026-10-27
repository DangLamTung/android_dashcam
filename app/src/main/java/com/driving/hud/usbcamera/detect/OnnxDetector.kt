package com.driving.hud.usbcamera.detect

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxTensorLike
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import ai.onnxruntime.providers.NNAPIFlags
import com.driving.hud.usbcamera.util.HudLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.EnumSet
import java.util.Locale

/** One detected object, coordinates normalised 0..1 relative to the source frame. */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/**
 * Thin, model-agnostic wrapper around ONNX Runtime for on-device object detection.
 *
 * The engine gives us NV21 frames (see AusbcCameraController). They are letterboxed to
 * the model input size, normalised to 0..1, converted to NCHW float32, and pushed
 * through the session. The output is decoded for the two most common YOLO layouts:
 *
 *   - channels-first  `[1, 4+nc, N]`  (YOLOv8/YOLOv11 export)
 *   - row-first       `[1, N, 5+nc]`  (YOLOv5 export, cx cy w h obj cls...)
 *
 * If your model uses a different head, override [decode] — the shape is logged at
 * load time so you can see what you are dealing with.
 */
class OnnxDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val inputName: String,
    private val inputW: Int,
    private val inputH: Int,
    private val labels: Array<String>,
    private val nanoDet: Boolean = false,
) : Detector {

    companion object {
        private const val TAG = "OnnxDetector"

        /**
         * Provider the live session actually runs on, picked because it timed fastest.
         * Shown on the HUD, so it reflects a measurement rather than a requested option.
         */
        @Volatile
        var activeProvider: String = "-"
            private set

        /** Last provider shoot-out table, for display on the home screen. */
        @Volatile
        var lastBenchmark: String = "(not run yet)"
            private set

        /**
         * Model asset that actually got loaded. Normally the requested one, but falls back to
         * a bundled `.onnx` when the preference is stale, so it is worth writing back.
         */
        @Volatile
        var activeAsset: String = ""
            private set

        /** Class labels, shared so every backend names objects identically. */
        fun labels(): Array<String> = COCO_LABELS

        /** Automotive subset labels for pruned models (4 box + 6 classes = 10 channels). */
        val AUTOMOTIVE_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "bus", "truck"
        )

        /** YOLO letterbox fill (gray 114). */
        private const val PAD = 114f / 255f

        private val COCO_LABELS = arrayOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
            "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
            "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
            "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
            "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
            "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
            "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
            "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
            "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
            "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
            "hair drier", "toothbrush"
        )

        /** True when the asset actually exists (a missing model must not crash the app). */
        fun assetExists(context: Context, name: String): Boolean = try {
            context.assets.list("")?.contains(name) == true
        } catch (t: Throwable) {
            HudLogger.w(TAG, "assetExists failed: ${t.message}")
            false
        }

        /**
         * Resolve the configured asset.
         *
         * A **blank** preference may be satisfied by anything bundled — a fresh install should not
         * start with detection off. A **named** model that is not in the APK is *not* substituted:
         * the decoder and the pre-processing are chosen from the requested name, so handing a
         * different family's graph to them produces no boxes at all, with the only clue a warning
         * in a log the driver never sees. Refusing is the honest answer; Settings then points at a
         * model that actually ships.
         */
        private fun resolveAsset(context: Context, requested: String): String? {
            val exists = assetExists(context, requested)
            val available = try {
                context.assets.list("")?.filter { it.endsWith(".onnx") }.orEmpty()
            } catch (t: Throwable) {
                emptyList()
            }
            val chosen = chooseAsset(requested, exists, available)
            when {
                chosen == null -> HudLogger.w(
                    TAG,
                    "model asset '$requested' is not bundled and will not be substituted " +
                        "(available: $available) — detection stays off for this model"
                )
                chosen != requested -> HudLogger.i(TAG, "no model preferred; using bundled '$chosen'")
                else -> Unit
            }
            return chosen
        }

        /**
         * Which asset to load, given what is bundled. Pure so it is unit tested: the rule that
         * matters is that a *named* missing model never silently becomes a different one.
         *
         * @param exists whether [requested] is present in `assets/`
         * @param available every bundled `.onnx` name
         */
        internal fun chooseAsset(requested: String, exists: Boolean, available: List<String>): String? {
            if (exists) return requested
            if (requested.isNotBlank()) return null
            // Prefer a real model: test_pattern* is the synthetic plumbing self-test.
            return available.sortedWith(compareBy({ it.startsWith("test") }, { it })).firstOrNull()
        }

        /**
         * Load the model, then choose an execution provider by **measuring** each candidate.
         *
         * Availability is not usefulness. NNAPI and XNNPACK can both build a session and one
         * is often several times slower than the other; a QNN session can also be created
         * successfully while running every node on CPU. So every candidate gets a real timed
         * pass through the actual pre-processing + inference path and only the fastest wins.
         *
         * @param frameW/frameH preview size, used to size the benchmark input
         * @param onlyProvider when set, skip the shoot-out and use just this provider,
         *                     falling back to CPU if it cannot be built. Lets a repeat launch
         *                     skip a benchmark that costs a couple of seconds.
         */
        fun create(
            context: Context,
            requestedAsset: String,
            frameW: Int,
            frameH: Int,
            onlyProvider: String? = null,
            nanoDet: Boolean = false,
        ): OnnxDetector? {
            return try {
                val assetName = resolveAsset(context, requestedAsset) ?: return null
                activeAsset = assetName
                val bytes = context.assets.open(assetName).use { it.readBytes() }
                HudLogger.i(TAG, "loading ONNX model '$assetName' (${bytes.size / 1024} KB)")
                val env = OrtEnvironment.getEnvironment()
                val available = OrtEnvironment.getAvailableProviders()
                HudLogger.i(TAG, "providers compiled in: $available")

                val all = buildCandidates(available)
                val order = if (onlyProvider == null) {
                    all
                } else {
                    all.filter { it.first == onlyProvider }.ifEmpty { all }
                }

                val benchW = if (frameW > 0) frameW else 640
                val benchH = if (frameH > 0) frameH else 480
                val probe = ByteArray(benchW * benchH * 3 / 2)

                var best: OnnxDetector? = null
                var bestName = "?"
                var bestMs = Float.MAX_VALUE
                val report = StringBuilder()

                for ((name, configure) in order) {
                    var opts: OrtSession.SessionOptions? = null
                    var created: OnnxDetector? = null
                    try {
                        opts = OrtSession.SessionOptions().apply {
                            setIntraOpNumThreads(2)
                            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                            configure(this)
                        }
                        val session = env.createSession(bytes, opts)
                        val det = buildDetector(env, session, nanoDet)
                        created = det
                        // One untimed pass to warm caches, then time two more. The confidence
                        // threshold is irrelevant here — this measures the full pass cost.
                        det.detect(probe, benchW, benchH, 0.99f)
                        val wall0 = System.nanoTime()
                        val cpu0 = selfCpuMs()
                        repeat(2) { det.detect(probe, benchW, benchH, 0.99f) }
                        val cpu1 = selfCpuMs()
                        val ms = (System.nanoTime() - wall0) / 2_000_000f
                        // Latency and cost are different things: a provider can finish sooner
                        // only because it spreads the same work over more threads, which does
                        // nothing for heat or battery. CPU-ms is the honest comparison, so it
                        // decides the winner; the wall figure is reported for reference.
                        val cpuMs = if (cpu0 >= 0 && cpu1 >= cpu0) (cpu1 - cpu0) / 2f else Float.MAX_VALUE
                        report.append(
                            String.format(
                                Locale.US,
                                "%n  %-11s %7.1f ms  %7.1f CPU-ms",
                                name,
                                ms,
                                if (cpuMs == Float.MAX_VALUE) 0f else cpuMs
                            )
                        )
                        if (cpuMs < bestMs) {
                            best?.close()
                            best = det
                            bestName = name
                            bestMs = cpuMs
                            created = null // ownership moved to `best`
                        }
                    } catch (t: Throwable) {
                        HudLogger.w(TAG, "$name rejected: ${t.message}")
                        report.append("\n  $name  failed")
                    } finally {
                        created?.close()
                        try {
                            opts?.close()
                        } catch (_: Throwable) {
                        }
                    }
                }

                val winner = best
                if (winner == null) {
                    HudLogger.e(TAG, "no execution provider could build a session")
                    activeProvider = "-"
                    return null
                }
                activeProvider = bestName
                lastBenchmark = report.toString()
                HudLogger.i(
                    TAG,
                    "execution provider shoot-out, per detection pass " +
                        "(winner picked on CPU-ms):$report\n  -> using $bestName"
                )
                winner
            } catch (t: Throwable) {
                HudLogger.e(TAG, "failed to create ONNX session", t)
                activeProvider = "-"
                null
            }
        }

        /** Wraps a session in a detector, reading the input size from the graph. */
        private fun buildDetector(
            env: OrtEnvironment,
            session: OrtSession,
            nanoDet: Boolean
        ): OnnxDetector {
            val inputName = session.inputNames.first()
            val shape = (session.inputInfo[inputName]?.info as? TensorInfo)?.shape
                ?: longArrayOf(1, 3, 640, 640)
            val h = shape.getOrNull(2)?.toInt()?.takeIf { it > 0 } ?: 640
            val w = shape.getOrNull(3)?.toInt()?.takeIf { it > 0 } ?: 640
            HudLogger.i(TAG, "model input '$inputName' ${shape.toList()} -> ${w}x$h")
            var outputChannels = 84
            session.outputInfo.forEach { (n, i) ->
                val s = (i.info as? TensorInfo)?.shape?.toList() ?: emptyList<Long>()
                HudLogger.i(TAG, "  output '$n' shape=$s")
                if (s.size >= 2) {
                    val c = s[1].toInt()
                    if (c in 5..200) outputChannels = c
                }
            }
            // NanoDet's head is 4*(reg_max+1)+classes wide, so the channel count says nothing
            // about the label set; it is always COCO in the bundled export.
            val labels = when {
                nanoDet -> COCO_LABELS
                outputChannels == 4 + AUTOMOTIVE_LABELS.size -> AUTOMOTIVE_LABELS
                outputChannels == 4 + COCO_LABELS.size -> COCO_LABELS
                else -> {
                    // A head this wide belongs to no label set we know. Decoding it would index
                    // the class list out of range, so the mismatch is reported rather than
                    // silently covered up by one label for every box.
                    HudLogger.w(
                        TAG,
                        "model head is $outputChannels wide, which matches no known label set " +
                            "(${4 + AUTOMOTIVE_LABELS.size} or ${4 + COCO_LABELS.size} classes) " +
                            "— class names will be wrong"
                    )
                    COCO_LABELS
                }
            }
            HudLogger.i(TAG, "model has $outputChannels channels -> using ${labels.size} labels")
            return OnnxDetector(env, session, inputName, w, h, labels, nanoDet)
        }

        /** CPU cost of a candidate, shared with the cross-backend shoot-out. */
        private fun selfCpuMs(): Long = SelfCpu.millis()

        /**
         * Execution-provider candidates, most capable first.
         *
         * Membership is gated on what the runtime actually compiled in, but nothing is
         * trusted until it has been timed — see [create].
         *
         * - `QNN/HTP` and `QNN/GPU`: the Qualcomm NPU/GPU backends; only compiled into the
         *   `-qnn` build
         * - `NNAPI/fp16` and `NNAPI/fp32`: the vendor's neural-network HAL, which is the
         *   only way this tablet can reach the same silicon, since it ships no Qualcomm
         *   network runtime. fp16 is usually much faster on Adreno; fp32 is the safety net
         * - `XNNPACK`: heavily optimised ARM CPU kernels, often beats the reference CPU EP
         * - `CPU`: the reference implementation, always available
         */
        private fun buildCandidates(
            available: Set<OrtProvider>
        ): List<Pair<String, (OrtSession.SessionOptions) -> Unit>> {
            val list = ArrayList<Pair<String, (OrtSession.SessionOptions) -> Unit>>()
            if (available.contains(OrtProvider.QNN)) {
                list += "QNN/HTP" to { o: OrtSession.SessionOptions ->
                    o.addQnn(
                        mapOf(
                            "backend_path" to "libQnnHtp.so",
                            "htp_performance_mode" to "burst",
                            "enable_htp_fp16_precision" to "1"
                        )
                    )
                }
                list += "QNN/GPU" to { o: OrtSession.SessionOptions ->
                    o.addQnn(
                        mapOf(
                            "backend_path" to "libQnnGpu.so",
                            "qnn_log_level" to "verbose"
                        )
                    )
                }
            }
            if (available.contains(OrtProvider.NNAPI)) {
                list += "NNAPI/fp16" to { o: OrtSession.SessionOptions ->
                    o.addNnapi(EnumSet.of(NNAPIFlags.USE_FP16))
                }
                list += "NNAPI/fp32" to { o: OrtSession.SessionOptions -> o.addNnapi() }
            }
            if (available.contains(OrtProvider.XNNPACK)) {
                list += "XNNPACK" to { o: OrtSession.SessionOptions -> o.addXnnpack(emptyMap()) }
            }
            list += "CPU" to { _: OrtSession.SessionOptions -> }
            return list
        }
    }

    override fun close() {
        try {
            session.close()
        } catch (_: Throwable) {
        }
    }

    // ----------------------------------------------------------- inference

    /** NV21 -> CHW preprocessing, shared with every other backend. */
    private val pre: Preprocessor = if (nanoDet) Stretch(inputW, inputH) else Letterbox(inputW, inputH)

    /** Head decoding + NMS, shared with every other backend. */
    private val head: HeadDecoder =
        if (nanoDet) NanoDetHead(labels, inputW, TAG) else YoloHead(labels, TAG)

    /** Reused direct buffer handed straight to ORT (no per-frame allocation). */
    private val inputShape = longArrayOf(1, 3, inputH.toLong(), inputW.toLong())
    private val inputBuffer: FloatBuffer = ByteBuffer
        .allocateDirect(3 * inputW * inputH * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    /**
     * @param nv21  NV21 bytes (Y plane w*h, then interleaved VU), length >= w*h*3/2
     * @param w     source width
     * @param h     source height
     * @param conf  minimum score to keep
     */
    @Synchronized
    override fun detect(nv21: ByteArray, w: Int, h: Int, conf: Float): List<Detection> {
        if (w <= 0 || h <= 0 || nv21.size < w * h * 3 / 2) return emptyList()

        val chw = pre.fill(nv21, w, h)

        return try {
            inputBuffer.clear()
            inputBuffer.put(chw)
            inputBuffer.rewind()
            OnnxTensor.createTensor(env, inputBuffer, inputShape).use { input ->
                session.run(mapOf<String, OnnxTensorLike>(inputName to input)).use { out ->
                    val tensor = out[0] as OnnxTensor
                    val read = readHead(tensor) ?: return emptyList()
                    head.decode(
                        read.first,
                        read.second[0],
                        read.second[1],
                        conf,
                        w,
                        h,
                        pre.scaleX,
                        pre.scaleY,
                        pre.dx,
                        pre.dy
                    )
                }
            }
        } catch (t: Throwable) {
            HudLogger.e(TAG, "inference failed", t)
            emptyList()
        }
    }

    /**
     * Unwrap the nested arrays ORT hands back into a per-row view, plus the dims to read it
     * with. A QNN session reports no static output shape at all, so the declared shape is a
     * hint that is only trusted when it agrees with the data actually returned.
     */
    private fun readHead(tensor: OnnxTensor): Pair<Array<*>, IntArray>? {
        // Squeeze leading batch/unit dims: a head may come back as [1][N][C] or as
        // [1][1][N][C] depending on how the graph was exported.
        var cur = tensor.value as? Array<*> ?: return null
        var guard = 0
        while (guard++ < 4) {
            val first = cur.firstOrNull() ?: return null
            if (first is Array<*>) cur = first else break
        }
        val outer = cur
        if (outer.isEmpty()) return null
        val dataA = outer.size
        val dataB = (outer[0] as? FloatArray)?.size ?: return null
        if (dataB <= 0) return null

        // OnnxTensor.info is already a TensorInfo, so no cast is required here.
        val declared = tensor.info.shape
            ?.filter { it > 0 }?.map { it.toInt() }
            ?.takeIf { it.size >= 2 }
        return if (declared != null && declared[declared.size - 2] == dataA && declared.last() == dataB) {
            outer to intArrayOf(declared[declared.size - 2], declared.last())
        } else {
            outer to intArrayOf(dataA, dataB)
        }
    }
}
