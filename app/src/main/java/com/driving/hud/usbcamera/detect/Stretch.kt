package com.driving.hud.usbcamera.detect

import com.driving.hud.usbcamera.util.HudLogger

/**
 * Pre-processing for NanoDet-Plus, which was trained very differently from the YOLO exports:
 * `keep_ratio: False` (the frame is stretched onto the square input, not letterboxed), BGR
 * channel order and per-channel mean/std in 0-255 units. Feeding it the YOLO letterbox is not
 * a crash — it just quietly shifts and squashes every box.
 */
internal class Stretch(private val inputW: Int, private val inputH: Int) : Preprocessor {

    private companion object {
        const val TAG = "Stretch"

        /** BGR means, straight from `config/nanodet-plus-m_320.yml`. */
        const val MEAN_B = 103.53f
        const val MEAN_G = 116.28f
        const val MEAN_R = 123.675f
        const val STD_B = 57.375f
        const val STD_G = 57.12f
        const val STD_R = 58.395f
    }

    val chw = FloatArray(3 * inputW * inputH)

    private var geoW = -1
    private var geoH = -1
    private var srcX = IntArray(0)

    override var scaleX = 1f
        private set
    override var scaleY = 1f
        private set
    override val dx: Float = 0f
    override val dy: Float = 0f

    override fun fill(nv21: ByteArray, w: Int, h: Int): FloatArray {
        if (geoW != w || geoH != h) {
            geoW = w
            geoH = h
            scaleX = inputW.toFloat() / w
            scaleY = inputH.toFloat() / h
            srcX = IntArray(inputW) { ((it / scaleX).toInt()).coerceIn(0, w - 1) }
            HudLogger.i(TAG, "stretch ${w}x$h -> ${inputW}x$inputH (no padding)")
        }
        val uvBase = w * h
        val plane = inputW * inputH
        val xm = srcX
        for (ty in 0 until inputH) {
            val sy = (ty / scaleY).toInt().coerceIn(0, h - 1)
            val srcRow = sy * w
            val uvRow = uvBase + (sy shr 1) * w
            val dstRow = ty * inputW
            for (tx in 0 until inputW) {
                val sx = xm[tx]
                val y = nv21[srcRow + sx].toInt() and 0xFF
                val uvIdx = uvRow + ((sx shr 1) shl 1)
                val v = nv21[uvIdx].toInt() and 0xFF
                val u = nv21[uvIdx + 1].toInt() and 0xFF

                // BT.601 studio-swing YUV -> RGB, in 0-255 units.
                val c = y - 16
                val d = u - 128
                val e = v - 128
                val r = 1.164f * c + 1.596f * e
                val g = 1.164f * c - 0.392f * d - 0.813f * e
                val b = 1.164f * c + 2.017f * d

                val p = dstRow + tx
                chw[p] = (b - MEAN_B) / STD_B
                chw[plane + p] = (g - MEAN_G) / STD_G
                chw[2 * plane + p] = (r - MEAN_R) / STD_R
            }
        }
        return chw
    }
}
