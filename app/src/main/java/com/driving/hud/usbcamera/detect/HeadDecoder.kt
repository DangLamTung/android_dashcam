package com.driving.hud.usbcamera.detect

/**
 * Turns a raw detection-head tensor into normalised [Detection]s.
 *
 * Two families are bundled and their heads are not interchangeable: YOLO emits
 * `4 + classes` values per anchor, NanoDet emits `4·(reg_max+1) + classes` (a distribution per
 * box side). Keeping the decoders behind one interface means a backend (ncnn, ONNX Runtime)
 * can serve either family without knowing which one it was handed.
 */
internal interface HeadDecoder {

    /**
     * @param outer     per-row data; `outer[i]` is one head row as a `FloatArray`
     * @param dimA      leading dimension of [outer]
     * @param dimB      trailing dimension of each row
     * @param conf      minimum score to keep
     * @param srcW/srcH source frame size, for clamping to the picture
     * @param scaleX    model-input pixels per source pixel, horizontally
     * @param scaleY    same vertically (differs from [scaleX] when the frame is stretched)
     * @param dx/dy     letterbox padding offset, 0 for a stretched input
     */
    fun decode(
        outer: Array<*>,
        dimA: Int,
        dimB: Int,
        conf: Float,
        srcW: Int,
        srcH: Int,
        scaleX: Float,
        scaleY: Float,
        dx: Float,
        dy: Float
    ): List<Detection>
}
