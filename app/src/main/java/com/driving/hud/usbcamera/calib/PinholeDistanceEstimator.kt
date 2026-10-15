package com.driving.hud.usbcamera.calib

import com.driving.hud.usbcamera.detect.Detection
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

/**
 * Pinhole Camera Model & True Distance Estimator for Monocular Automotive HUD.
 *
 * This combines:
 * 1. Pinhole Projection Geometry (intrinsics: fx, fy, cx, cy)
 * 2. Real-world 3D Object Size Priors (known physical width & height of cars, trucks, pedestrians)
 * 3. Ground Plane Raycasting (extrinsics: camera mount height H, pitch angle theta_p)
 * 4. Adaptive Distance Fusion:
 *    - At close range (< 15m), ground contact provides high angular resolution.
 *    - At long range (> 25m), the horizon singularity causes ground distance variance,
 *      whereas bounding box width (fx * W_real / w_px) remains stable and well-conditioned.
 *    - Combining both with depth-dependent confidence and outlier rejection yields
 *      the "True Distance" (Z_true) along with 3D camera coordinates (X, Y, Z).
 */
class PinholeDistanceEstimator(
    val frameW: Int,
    val frameH: Int,
    val cameraHeightM: Float = 1.20f,
    val pitchDeg: Float = 8.0f,
    val horizontalFovDeg: Float = 65.0f,
    val principalPointX: Float = frameW / 2.0f,
    val principalPointY: Float = frameH / 2.0f
) {

    /**
     * Focal length in pixels derived from horizontal FOV:
     * fx = (frameW / 2) / tan(hFOV / 2)
     */
    val focalPx: Float =
        (frameW / 2.0f) / tan(Math.toRadians(horizontalFovDeg / 2.0)).toFloat()

    val fx: Float get() = focalPx
    val fy: Float get() = focalPx // Square pixels assumed for typical UVC CMOS sensors

    val cx: Float get() = principalPointX
    val cy: Float get() = principalPointY

    val pitchRad: Float = Math.toRadians(pitchDeg.toDouble()).toFloat()

    /**
     * Row in source pixels where optical ground ray meets the horizon:
     * y_horizon = cy - fy * tan(pitch)
     */
    val horizonRow: Float = cy - fy * tan(pitchRad)

    /**
     * Real-world physical dimensions (meters) for known YOLO classes.
     */
    data class ObjectPrior(
        val realWidthM: Float,
        val realHeightM: Float,
        val widthWeight: Float = 0.80f // Automotive width is much more uniform than height
    )

    companion object {
        val PRIORS = mapOf(
            "car" to ObjectPrior(realWidthM = 1.82f, realHeightM = 1.48f, widthWeight = 0.80f),
            "suv" to ObjectPrior(realWidthM = 1.90f, realHeightM = 1.70f, widthWeight = 0.75f),
            "van" to ObjectPrior(realWidthM = 2.00f, realHeightM = 2.05f, widthWeight = 0.75f),
            "truck" to ObjectPrior(realWidthM = 2.50f, realHeightM = 3.20f, widthWeight = 0.75f),
            "bus" to ObjectPrior(realWidthM = 2.55f, realHeightM = 3.20f, widthWeight = 0.75f),
            "train" to ObjectPrior(realWidthM = 3.00f, realHeightM = 3.80f, widthWeight = 0.70f),
            "motorcycle" to ObjectPrior(realWidthM = 0.80f, realHeightM = 1.30f, widthWeight = 0.65f),
            "motorbike" to ObjectPrior(realWidthM = 0.80f, realHeightM = 1.30f, widthWeight = 0.65f),
            "bicycle" to ObjectPrior(realWidthM = 0.60f, realHeightM = 1.15f, widthWeight = 0.65f),
            "person" to ObjectPrior(realWidthM = 0.55f, realHeightM = 1.72f, widthWeight = 0.35f), // Person height is more reliable
            "pedestrian" to ObjectPrior(realWidthM = 0.55f, realHeightM = 1.72f, widthWeight = 0.35f)
        )

        val DEFAULT_PRIOR = ObjectPrior(realWidthM = 1.80f, realHeightM = 1.50f, widthWeight = 0.75f)

        fun priorForLabel(label: String): ObjectPrior {
            val key = label.lowercase().trim()
            return PRIORS[key] ?: DEFAULT_PRIOR
        }
    }

    /**
     * Complete true distance estimate and 3D positioning for a detected object.
     */
    data class DistanceEstimate(
        val trueDistanceM: Float,
        val pinholeDistanceM: Float,
        val groundDistanceM: Float?,
        val lateralOffsetM: Float,
        val verticalOffsetM: Float,
        val boxWidthPx: Float,
        val boxHeightPx: Float,
        val isGroundValid: Boolean,
        val confidenceScore: Float
    )

    /**
     * Compute pinhole model distance from bounding box size:
     * Zw = (fx * W_real) / w_px
     * Zh = (fy * H_real) / h_px
     */
    fun pinholeDistance(
        boxWidthPx: Float,
        boxHeightPx: Float,
        prior: ObjectPrior
    ): Float {
        if (boxWidthPx <= 1.0f || boxHeightPx <= 1.0f) return Float.NaN
        val zw = (fx * prior.realWidthM) / boxWidthPx
        val zh = (fy * prior.realHeightM) / boxHeightPx
        val w = prior.widthWeight
        return w * zw + (1.0f - w) * zh
    }

    /**
     * Ground plane distance from pixel row contact with road:
     * angle = atan((y - cy) / fy) + pitchRad
     * d = H_cam / tan(angle)
     */
    fun groundDistanceForRow(y: Float): Float? {
        val angle = atan((y - cy) / fy) + pitchRad
        // Horizon or above check
        if (angle <= 0.002f) return null
        return cameraHeightM / tan(angle)
    }

    /**
     * Comprehensive True Distance estimation for a detection.
     */
    fun estimate(detection: Detection): DistanceEstimate {
        val boxW = (detection.right - detection.left) * frameW
        val boxH = (detection.bottom - detection.top) * frameH
        val bottomRow = detection.bottom * frameH
        val centerCol = (detection.left + detection.right) / 2.0f * frameW
        val centerRow = (detection.top + detection.bottom) / 2.0f * frameH

        val prior = priorForLabel(detection.label)
        val zPinhole = pinholeDistance(boxW, boxH, prior)
        val zGround = groundDistanceForRow(bottomRow)

        val isGroundValid = zGround != null && zGround in 0.5f..150.0f
        val isPinholeValid = !zPinhole.isNaN() && zPinhole in 0.5f..200.0f

        val zTrue: Float
        val conf: Float

        when {
            isGroundValid && isPinholeValid -> {
                val zg = zGround!!
                val zp = zPinhole
                val diff = abs(zg - zp)
                val maxDiffAllowed = max(12.0f, zp * 0.45f)

                if (diff > maxDiffAllowed) {
                    // One model is experiencing an outlier (e.g., hill, occluded bumper, non-standard car)
                    // At close range (< 12m), ground is geometric ground truth.
                    // At far range (> 25m), pinhole size is much more robust against pitch flutter.
                    zTrue = if (zg < 12.0f) zg else zp
                    conf = 0.60f
                } else {
                    // Blend based on range:
                    // Under 12m: ground 70%, pinhole 30%
                    // 12m - 30m: ground 50%, pinhole 50%
                    // Over 30m: ground 25%, pinhole 75%
                    val groundWeight = when {
                        zg < 12.0f -> 0.70f
                        zg > 35.0f -> 0.25f
                        else -> 0.70f - (zg - 12.0f) / (35.0f - 12.0f) * 0.45f
                    }
                    zTrue = groundWeight * zg + (1.0f - groundWeight) * zp
                    conf = 0.90f
                }
            }
            isPinholeValid -> {
                // Object is elevated, above horizon, or bumper cut off
                zTrue = zPinhole
                conf = 0.70f
            }
            isGroundValid -> {
                zTrue = zGround!!
                conf = 0.70f
            }
            else -> {
                zTrue = Float.NaN
                conf = 0.0f
            }
        }

        // 3D camera coordinates: X (right), Y (down)
        val lateralX = if (!zTrue.isNaN()) ((centerCol - cx) * zTrue) / fx else 0.0f
        val verticalY = if (!zTrue.isNaN()) ((centerRow - cy) * zTrue) / fy else 0.0f

        return DistanceEstimate(
            trueDistanceM = zTrue,
            pinholeDistanceM = zPinhole,
            groundDistanceM = zGround,
            lateralOffsetM = lateralX,
            verticalOffsetM = verticalY,
            boxWidthPx = boxW,
            boxHeightPx = boxH,
            isGroundValid = isGroundValid,
            confidenceScore = conf
        )
    }

    /**
     * Inverse projection: maps road distance in meters (e.g. 5m, 10m, 20m, 50m) to pixel row.
     * Returns null if the distance cannot project onto the frame (behind or above horizon).
     */
    fun rowForDistance(distanceM: Float): Float? {
        if (distanceM <= 0.1f) return null
        val theta = atan(cameraHeightM / distanceM) // Angle below horizon
        val alpha = theta - pitchRad // Ray departure angle from optical axis
        val y = cy + fy * tan(alpha)
        if (y < 0 || y > frameH * 2f) return null
        return y
    }

    /**
     * Auto-pitch calibration solver:
     * When pointing at a target known to be at [targetDistanceM] (e.g., 5m or 10m),
     * and its road contact is at [contactRowPx]:
     * theta_p = atan(H_cam / targetDistance) - atan((y - cy) / fy)
     */
    fun solvePitchForTarget(targetDistanceM: Float, contactRowPx: Float): Float {
        val theta = atan(cameraHeightM / targetDistanceM)
        val alpha = atan((contactRowPx - cy) / fy)
        val solvedPitchRad = theta - alpha
        return Math.toDegrees(solvedPitchRad.toDouble()).toFloat()
    }

    /**
     * Auto-FOV calibration solver:
     * When looking at an object of known width [targetRealWidthM] (e.g. 1.82m for a standard car)
     * at known distance [targetDistanceM], with bounding box pixel width [boxWidthPx]:
     * f = (boxWidthPx * targetDistance) / targetRealWidth
     * hFOV = 2 * atan((frameW / 2) / f)
     */
    fun solveFovForTarget(targetDistanceM: Float, targetRealWidthM: Float, boxWidthPx: Float): Float {
        if (boxWidthPx <= 1.0f || targetDistanceM <= 0.1f || targetRealWidthM <= 0.1f) return horizontalFovDeg
        val solvedFocalPx = (boxWidthPx * targetDistanceM) / targetRealWidthM
        val hFovRad = 2.0 * atan((frameW / 2.0) / solvedFocalPx)
        return Math.toDegrees(hFovRad).toFloat().coerceIn(20.0f, 120.0f)
    }

    /** Width of field of view in meters at distance Z. */
    fun fieldWidthAt(z: Float): Float = (z * frameW) / fx

    fun describe(): String =
        "PinholeModel ${frameW}x$frameH H=${cameraHeightM}m pitch=${pitchDeg}° hfov=${horizontalFovDeg}° fx=${"%.1f".format(fx)}px horizon=${"%.1f".format(horizonRow)}px"
}
