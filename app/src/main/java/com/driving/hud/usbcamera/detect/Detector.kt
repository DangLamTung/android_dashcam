package com.driving.hud.usbcamera.detect

/**
 * A detection backend: an NV21 frame in, normalised boxes out.
 *
 * Both backends implement this so the camera layer does not care which library is running,
 * and — more usefully — so they can be timed through the *identical* entry point. Timing a
 * backend through its own private path is how you end up comparing pre-processing costs and
 * calling it an inference result.
 */
interface Detector {

    /**
     * @param nv21 NV21 bytes (Y plane w*h, then interleaved VU), length >= w*h*3/2
     * @param w    source width
     * @param h    source height
     * @param conf minimum score to keep
     */
    fun detect(nv21: ByteArray, w: Int, h: Int, conf: Float): List<Detection>

    fun close()
}
