package com.driving.hud.usbcamera.ui

import android.graphics.RectF

/**
 * Fits the camera image into whatever window it has been given, preserving aspect ratio.
 *
 * Without this the preview is stretched to the view: a 16:9 frame in a 16:10 window is already
 * ~11% distorted, and in a tall split-screen half it is badly squashed. It matters beyond looks
 * — the detection boxes and the ground-distance ribbons are normalised against the *frame*, so
 * they only line up with the picture if they are mapped onto the same rectangle the picture
 * actually occupies.
 *
 * Both the SurfaceView sizing and the overlay drawing call this, so the two can never disagree.
 */
internal object VideoFit {

    /**
     * Largest rectangle of the given aspect that fits inside `viewW` x `viewH`, centred. Falls
     * back to the whole view when the aspect is unknown, which keeps the previous behaviour
     * before the first frame arrives.
     */
    fun fit(viewW: Int, viewH: Int, aspect: Float): RectF {
        if (viewW <= 0 || viewH <= 0) return RectF()
        if (aspect <= 0f || !aspect.isFinite()) {
            return RectF(0f, 0f, viewW.toFloat(), viewH.toFloat())
        }
        val viewAspect = viewW.toFloat() / viewH
        return if (aspect > viewAspect) {
            // Video is wider than the window: full width, letterboxed top and bottom.
            val h = viewW / aspect
            val top = (viewH - h) / 2f
            RectF(0f, top, viewW.toFloat(), top + h)
        } else {
            // Video is taller than the window: full height, pillarboxed left and right.
            val w = viewH * aspect
            val left = (viewW - w) / 2f
            RectF(left, 0f, left + w, viewH.toFloat())
        }
    }
}
