package com.driving.hud.usbcamera.detect

/**
 * Builds the model-input tensor from an NV21 frame.
 *
 * [Letterbox] keeps the aspect ratio and pads; [Stretch] fills the input with the whole frame.
 * The two differ because the models were trained differently, and a mismatch here is invisible
 * in the output shape but shows up as boxes that are systematically offset.
 */
internal interface Preprocessor {

    /** Fills and returns the reused CHW buffer for this frame. */
    fun fill(nv21: ByteArray, w: Int, h: Int): FloatArray

    /** Model-input pixels per source pixel, horizontally. */
    val scaleX: Float

    /** Model-input pixels per source pixel, vertically. */
    val scaleY: Float

    /** Left/top padding of the picture inside the input, 0 when stretched. */
    val dx: Float
    val dy: Float
}
