package com.driving.hud.usbcamera.calib

import kotlin.math.atan
import kotlin.math.tan

/**
 * Flat-ground distance model for a forward-facing camera.
 *
 * This trades the object detector for geometry: instead of asking "what is that?", it answers
 * "how far away is that pixel?", which is the number that actually matters while driving.
 *
 * Model: a pinhole camera [cameraHeightM] above a flat road, optical axis tilted
 * [pitchDeg] downwards, focal length derived from the horizontal field of view. For a pixel
 * row `y` the ray leaves the optical axis at `atan((y - cy) / f)`; adding the mounting tilt
 * gives the angle below the horizon, and the ground distance is `height / tan(angle)`. Rows at
 * or above the horizon have no finite distance.
 *
 * Accuracy is dominated by [cameraHeightM] and [pitchDeg], not by the optics: distance error is
 * roughly proportional to the error in height, and pitch error grows with distance. Both are
 * meant to be calibrated against a real object at a known range (see the Settings hint).
 *
 * The results are identical whichever way the camera is scaled on screen, because everything is
 * expressed in source-frame rows and mapped to the view the same way detections are.
 *
 * Only the forward geometry is implemented: the tracker needs a range for each detection, which
 * is what [distanceForRow] provides. The inverse map (row for a given range) and the lateral
 * helpers were only ever used by the distance-ladder overlay, which has been removed.
 */
internal class GroundPlaneCalibration(
    val frameW: Int,
    val frameH: Int,
    val cameraHeightM: Float,
    val pitchDeg: Float,
    val horizontalFovDeg: Float
) {

    /** Focal length in pixels, from the horizontal field of view. */
    val focalPx: Float =
        (frameW / 2f) / tan(Math.toRadians(horizontalFovDeg / 2.0)).toFloat()

    /** Pinhole camera model and true distance estimator. */
    val estimator = PinholeDistanceEstimator(
        frameW = frameW,
        frameH = frameH,
        cameraHeightM = cameraHeightM,
        pitchDeg = pitchDeg,
        horizontalFovDeg = horizontalFovDeg
    )

    private val cx = frameW / 2f
    private val cy = frameH / 2f
    private val pitchRad = Math.toRadians(pitchDeg.toDouble()).toFloat()

    /** Row at which the ground would be infinitely far away (the horizon). */
    val horizonRow: Float = cy - focalPx * tan(pitchRad)

    /** Angle of the ray through pixel row [y], measured downwards from the horizon. */
    private fun angleBelowHorizon(y: Float): Float = atan((y - cy) / focalPx) + pitchRad

    /** Ground distance in metres for a pixel row, or null when that row is at/above the horizon. */
    fun distanceForRow(y: Float): Float? {
        val a = angleBelowHorizon(y)
        // Guard the singularity: a hair above the horizon the distance is unbounded.
        if (a <= 0.002f) return null
        return cameraHeightM / tan(a)
    }

    /** Width of the whole frame, in metres, at distance [z]. */
    fun widthAt(z: Float): Float = z * frameW / focalPx

    /** One-line summary for the log, so the numbers can be checked without a screenshot. */
    fun describe(): String {
        val fovCheck = 2.0 * Math.toDegrees(atan((frameW / 2f) / focalPx.toDouble()))
        return "calib frame=${frameW}x$frameH height=${cameraHeightM}m pitch=${pitchDeg}deg " +
            "hfov=${horizontalFovDeg}deg (focal=${"%.1f".format(focalPx)}px, " +
            "check=${"%.1f".format(fovCheck)}deg) horizonRow=${"%.1f".format(horizonRow)}"
    }

    /** Readout for the image centre, which is where the reticle sits. */
    fun centreDistance(): Float? = distanceForRow(cy)
}
