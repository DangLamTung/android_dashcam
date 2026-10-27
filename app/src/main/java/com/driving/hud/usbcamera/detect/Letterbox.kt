package com.driving.hud.usbcamera.detect

import com.driving.hud.usbcamera.util.HudLogger

/**
 * NV21 frame -> letterboxed, normalised NCHW RGB float buffer.
 *
 * Shared by every detector backend, because this cost is identical whichever library runs
 * the graph. It used to live inside the ONNX detector; keeping one copy means a second
 * backend cannot silently drift away from the preprocessing the boxes were tuned against.
 *
 * The source size is fixed for the life of a session, so the scale/offset, the source-index
 * maps and the YUV->RGB lookup tables are built once per size rather than doing a float
 * divide per pixel per frame. The grey padding only occupies the letterbox bars, which never
 * change, so they are painted once and the per-frame loop walks only the image content.
 */
internal class Letterbox(val inputW: Int, val inputH: Int) : Preprocessor {
    private companion object {
        const val TAG = "Letterbox"

        /** YOLO letterbox fill (gray 114). */
        const val PAD = 114f / 255f
    }

    /** CHW float buffer handed straight to the runtime (reused, never reallocated). */
    val chw = FloatArray(3 * inputW * inputH)

    /** Model-input pixels per source pixel, and the letterbox offset. */
    var scale = 1f
        private set

    /** A letterbox is uniform: the same scale applies on both axes. */
    override val scaleX: Float get() = scale
    override val scaleY: Float get() = scale

    override var dx = 0f
        private set
    override var dy = 0f
        private set

    private var geoW = -1
    private var geoH = -1
    private var xMap = IntArray(0)
    private var yMap = IntArray(0)
    private var regionX0 = 0
    private var regionX1 = 0
    private var regionY0 = 0
    private var regionY1 = 0

    private val yTab = FloatArray(256)
    private val rTab = FloatArray(256)
    private val gTabU = FloatArray(256)
    private val gTabV = FloatArray(256)
    private val bTabU = FloatArray(256)

    init {
        val inv = 1f / 255f
        for (i in 0 until 256) {
            yTab[i] = i * inv
            val c = i - 128
            rTab[i] = 1.402f * c * inv
            gTabU[i] = -0.344136f * c * inv
            gTabV[i] = -0.714136f * c * inv
            bTabU[i] = 1.772f * c * inv
        }
    }

    /**
     * Letterbox [nv21] and return the shared CHW buffer. Valid until the next call.
     *
     * @param nv21 NV21 bytes (Y plane w*h, then interleaved VU), length >= w*h*3/2
     */
    @Synchronized
    override fun fill(nv21: ByteArray, w: Int, h: Int): FloatArray {
        prepareGeometry(w, h)
        fillContent(nv21, w, h)
        return chw
    }

    /**
     * Build the letterbox mapping for a source size. Cached: only recomputed when the frame
     * size actually changes (camera reconnect at a different resolution).
     */
    private fun prepareGeometry(w: Int, h: Int) {
        if (geoW == w && geoH == h) return
        geoW = w
        geoH = h
        scale = minOf(inputW.toFloat() / w, inputH.toFloat() / h)
        dx = (inputW - w * scale) / 2f
        dy = (inputH - h * scale) / 2f
        xMap = IntArray(inputW) {
            val s = ((it - dx) / scale).toInt()
            if (s in 0 until w) s else -1
        }
        yMap = IntArray(inputH) {
            val s = ((it - dy) / scale).toInt()
            if (s in 0 until h) s else -1
        }
        regionX0 = xMap.indexOfFirst { it >= 0 }.let { if (it < 0) 0 else it }
        regionX1 = xMap.indexOfLast { it >= 0 }.let { if (it < 0) 0 else it + 1 }
        regionY0 = yMap.indexOfFirst { it >= 0 }.let { if (it < 0) 0 else it }
        regionY1 = yMap.indexOfLast { it >= 0 }.let { if (it < 0) 0 else it + 1 }
        java.util.Arrays.fill(chw, PAD)
        HudLogger.i(
            TAG,
            "letterbox ${w}x$h -> ${inputW}x$inputH: scale=$scale pad=($dx,$dy) " +
                "content=${regionX1 - regionX0}x${regionY1 - regionY0}"
        )
    }

    private fun fillContent(nv21: ByteArray, w: Int, h: Int) {
        val uvBase = w * h
        val plane = inputW * inputH
        val xm = xMap
        val ym = yMap
        for (ty in regionY0 until regionY1) {
            val sy = ym[ty]
            val dstRow = ty * inputW
            val srcRow = sy * w
            val uvRow = uvBase + (sy shr 1) * w
            for (tx in regionX0 until regionX1) {
                val sx = xm[tx]
                val yy = nv21[srcRow + sx].toInt() and 0xFF
                // NV21: interleaved V,U after the Y plane
                val uvIdx = uvRow + ((sx shr 1) shl 1)
                val vv = nv21[uvIdx].toInt() and 0xFF
                val uu = nv21[uvIdx + 1].toInt() and 0xFF
                val yf = yTab[yy]
                val p = dstRow + tx
                val r = yf + rTab[vv]
                val g = yf + gTabU[uu] + gTabV[vv]
                val b = yf + bTabU[uu]
                chw[p] = if (r < 0f) 0f else if (r > 1f) 1f else r
                chw[plane + p] = if (g < 0f) 0f else if (g > 1f) 1f else g
                chw[2 * plane + p] = if (b < 0f) 0f else if (b > 1f) 1f else b
            }
        }
    }
}
