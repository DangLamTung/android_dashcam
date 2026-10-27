package com.driving.hud.usbcamera.detect

/** One point of a fitted lane edge, normalised to the camera frame (0..1, y grows downwards). */
data class LanePoint(val x: Float, val y: Float)

/**
 * Result of one lane pass.
 *
 * [offsetLanes] says where the vehicle sits inside its own lane, measured in lane widths:
 * `0` is centred, `+0.5` means the lane centre is half a lane width to the right of the camera
 * axis (so the car is drifting to the left of the lane). `NaN` when only one edge was found and
 * the lane width is therefore unknown.
 */
data class LaneResult(
    val left: List<LanePoint>,
    val right: List<LanePoint>,
    val offsetLanes: Float,
    val departure: Boolean,
    val confidence: Float,
    val laneWidth: Float,
    /** How long the pass took, filled in by the caller that timed it (0 when unknown). */
    val tookMs: Float = 0f,
) {
    val isEmpty: Boolean get() = left.isEmpty() && right.isEmpty()

    companion object {
        val NONE = LaneResult(emptyList(), emptyList(), Float.NaN, false, 0f, Float.NaN)
    }
}

/**
 * Lane detection from a single NV21 luma plane — classic computer vision, no model.
 *
 * A lane network would mean a second runtime, a second set of assets and another 8–40 ms per
 * pass. The pipeline below runs in a few milliseconds at 320 px wide, which is what makes it
 * affordable to run *alongside* the object detector on the same device.
 *
 * Per pass:
 *  1. downsample luma to ~320 px wide (4 samples per output pixel),
 *  2. Sobel-X magnitude — lane markings are near-vertical edges,
 *  3. threshold at the 90th percentile of the ROI, so dusk, tunnels and bright paint all work
 *     without a per-camera exposure setting,
 *  4. trapezoid ROI plus a sliding-window centroid walk from the bottom of the picture upwards,
 *  5. least-squares fit `x = f(y)` per side (quadratic with enough points, linear otherwise),
 *  6. exponential smoothing and a short hold, so one missed pass does not make lanes flicker,
 *  7. lane offset in lane widths plus a hysteresis-gated departure flag.
 *
 * Pure Kotlin on purpose: no Android dependency, so the whole algorithm is unit tested against
 * synthetic frames in `HudUnitTests`.
 */
class LaneDetector {

    private companion object {
        /** Analysis width. At 720p a lane marking is 6–10 px wide after this downsample. */
        const val ANALYSIS_WIDTH = 320

        /** Top of the region of interest, as a fraction of the frame height. */
        const val ROI_TOP = 0.58f

        /** Trapezoid half-width at the ROI top / bottom, as fractions of the frame width. */
        const val TRAPEZOID_TOP = 0.30f
        const val TRAPEZOID_BOTTOM = 0.98f

        /** Windows walked from the bottom of the ROI upwards. */
        const val WINDOWS = 8

        /** Half-width of the search window around the previous centre. */
        const val WINDOW_HALF = 0.10f

        /** Edges kept after thresholding, as a fraction of the ROI. */
        const val EDGE_PERCENTILE = 0.90f

        /** Below this Sobel magnitude the frame is flat or too dark to hold any marking. */
        const val MIN_EDGE_MAGNITUDE = 24

        /** Minimum edge mass in a window before its centroid is trusted. */
        const val MIN_WINDOW_PIXELS = 10

        /** Departure threshold, in lane widths from the lane centre. */
        const val DEPARTURE_LANES = 0.45f

        /** Consecutive passes past the departure threshold before the warning is raised. */
        const val DEPARTURE_HOLD = 2

        /** Passes without a fit before a smoothed lane is dropped. */
        const val MAX_HOLD_PASSES = 3

        /** Weight of a new fit in the exponential smoothing. */
        const val SMOOTHING = 0.5f

        /** Plausible lane width at the bottom of the frame, as a fraction of the width. */
        const val MIN_LANE_WIDTH = 0.30f
        const val MAX_LANE_WIDTH = 1.05f

        /** Points emitted per lane for the overlay. */
        const val DRAW_POINTS = 12
    }

    private var w = 0
    private var h = 0
    private var roiTop = 0
    private var gray = ByteArray(0)
    private var edges = IntArray(0)
    private var maskLeft = IntArray(0)
    private var maskRight = IntArray(0)
    private var threshold = MIN_EDGE_MAGNITUDE + 1

    /** Smoothed coefficients (a, b, c) of `x(t) = c + b·t + a·t²`, t = 0 at the frame bottom. */
    private var leftFit: FloatArray? = null
    private var rightFit: FloatArray? = null
    private var leftHold = 0
    private var rightHold = 0
    private var departureStreak = 0

    /** Analyses one NV21 frame. [frameW] × [frameH] is the full size; only the Y plane is read. */
    fun detect(nv21: ByteArray, frameW: Int, frameH: Int): LaneResult {
        if (frameW < 64 || frameH < 64) return LaneResult.NONE
        if (nv21.size < frameW * frameH) return LaneResult.NONE

        val step = (frameW / ANALYSIS_WIDTH).coerceAtLeast(1)
        downsample(nv21, frameW, frameH, step)
        if (w < 64) return LaneResult.NONE
        if (!computeEdges()) return compose(FloatArray(0), FloatArray(0), 0f)

        val winH = ((h - roiTop) / WINDOWS).coerceAtLeast(3)
        val halfWin = (w * WINDOW_HALF).toInt().coerceAtLeast(6)

        val leftPoints = walk(bottomCentre(leftSide = true, winH = winH), winH, halfWin)
        val rightPoints = walk(bottomCentre(leftSide = false, winH = winH), winH, halfWin)

        val leftRaw = fit(leftPoints, leftSide = true)
        val rightRaw = fit(rightPoints, leftSide = false)
        val confidence = (leftPoints.size + rightPoints.size).toFloat() / (2 * WINDOWS)
        return compose(leftRaw, rightRaw, confidence)
    }

    /**
     * The edge threshold the last pass settled on (a Sobel magnitude). Exposed for the
     * diagnostics line so the value can be compared against a real road instead of guessed.
     */
    val lastThreshold: Int get() = threshold

    /** Forgets the smoothed lanes, so the next pass starts from scratch. */
    fun reset() {
        leftFit = null
        rightFit = null
        leftHold = 0
        rightHold = 0
        departureStreak = 0
    }

    // ---------------------------------------------------------------- pipeline

    private fun downsample(nv21: ByteArray, frameW: Int, frameH: Int, step: Int) {
        val ow = (frameW / step).coerceAtLeast(1)
        val oh = (frameH / step).coerceAtLeast(1)
        if (ow != w || oh != h) {
            w = ow
            h = oh
            roiTop = (h * ROI_TOP).toInt().coerceIn(1, (h - 8).coerceAtLeast(1))
            gray = ByteArray(ow * oh)
            edges = IntArray(ow * oh)
            buildMask()
        }
        val half = step / 2
        var o = 0
        for (sy in 0 until oh) {
            val rowA = sy * step
            val rowB = (rowA + half).coerceAtMost(frameH - 1)
            val baseA = rowA * frameW
            val baseB = rowB * frameW
            for (sx in 0 until ow) {
                val xA = sx * step
                val xB = (xA + half).coerceAtMost(frameW - 1)
                val sum = (nv21[baseA + xA].toInt() and 0xFF) +
                    (nv21[baseA + xB].toInt() and 0xFF) +
                    (nv21[baseB + xA].toInt() and 0xFF) +
                    (nv21[baseB + xB].toInt() and 0xFF)
                gray[o++] = (sum / 4).toByte()
            }
        }
    }

    /** Per-row bounds of the road trapezoid, so nothing outside the road is searched. */
    private fun buildMask() {
        maskLeft = IntArray(h)
        maskRight = IntArray(h)
        val span = (h - 1 - roiTop).coerceAtLeast(1)
        val cx = w / 2f
        for (y in 0 until h) {
            if (y < roiTop) {
                maskLeft[y] = 1
                maskRight[y] = 0
                continue
            }
            val f = (y - roiTop).toFloat() / span
            val half = TRAPEZOID_TOP * w + (TRAPEZOID_BOTTOM - TRAPEZOID_TOP) * w * f
            maskLeft[y] = (cx - half).toInt().coerceAtLeast(1)
            maskRight[y] = (cx + half).toInt().coerceAtMost(w - 2)
        }
    }

    /** Sobel-X over the ROI, thresholded at [EDGE_PERCENTILE]. False when the frame is flat. */
    private fun computeEdges(): Boolean {
        java.util.Arrays.fill(edges, 0)
        var maxMag = 0
        for (y in roiTop until h - 1) {
            val up = (y - 1) * w
            val mid = y * w
            val down = (y + 1) * w
            for (x in maskLeft[y]..maskRight[y]) {
                val tl = gray[up + x - 1].toInt() and 0xFF
                val ml = gray[mid + x - 1].toInt() and 0xFF
                val bl = gray[down + x - 1].toInt() and 0xFF
                val tr = gray[up + x + 1].toInt() and 0xFF
                val mr = gray[mid + x + 1].toInt() and 0xFF
                val br = gray[down + x + 1].toInt() and 0xFF
                val gx = -tl - 2 * ml - bl + tr + 2 * mr + br
                val mag = if (gx < 0) -gx else gx
                edges[mid + x] = mag
                if (mag > maxMag) maxMag = mag
            }
        }
        if (maxMag < MIN_EDGE_MAGNITUDE) return false

        // 128 bins of 8 units each covers the full Sobel range (0..1020).
        val hist = IntArray(128)
        var total = 0
        for (y in roiTop until h - 1) {
            val row = y * w
            for (x in maskLeft[y]..maskRight[y]) {
                hist[(edges[row + x] shr 3).coerceAtMost(127)]++
                total++
            }
        }
        if (total == 0) return false
        val target = (total * EDGE_PERCENTILE).toInt()
        var acc = 0
        var bin = 127
        for (i in 0 until 128) {
            acc += hist[i]
            if (acc >= target) {
                bin = i
                break
            }
        }
        threshold = (bin shl 3).coerceAtLeast(MIN_EDGE_MAGNITUDE + 1)
        return true
    }

    /**
     * Column with the most edge mass in the bottom [winH] rows of one side. This seeds the
     * sliding walk; a lane that is not visible at the very bottom simply yields no lane.
     */
    private fun bottomCentre(leftSide: Boolean, winH: Int): Int {
        val yStart = (h - winH).coerceAtLeast(roiTop)
        var bestX = -1
        var bestSum = 0
        val from = if (leftSide) 0 else w / 2
        val to = if (leftSide) w / 2 else w
        for (x in from until to) {
            var sum = 0
            for (y in yStart until h) {
                if (x < maskLeft[y] || x > maskRight[y]) continue
                val m = edges[y * w + x]
                if (m >= threshold && m > MIN_EDGE_MAGNITUDE) sum += m
            }
            if (sum > bestSum) {
                bestSum = sum
                bestX = x
            }
        }
        if (bestX < 0) return -1
        // A seed that carries no real mass is the noise floor, not a marking.
        return if (bestSum >= MIN_WINDOW_PIXELS * MIN_EDGE_MAGNITUDE) bestX else -1
    }

    /** Walks [WINDOWS] bands upwards from [startX], re-centring on the brightest edge each time. */
    private fun walk(startX: Int, winH: Int, halfWin: Int): List<IntArray> {
        if (startX < 0) return emptyList()
        val out = ArrayList<IntArray>(WINDOWS)
        var cx = startX
        var yEnd = h - 1
        while (yEnd > roiTop && out.size < WINDOWS) {
            val yStart = (yEnd - winH + 1).coerceAtLeast(roiTop)
            val x0 = (cx - halfWin).coerceAtLeast(0)
            val x1 = (cx + halfWin).coerceAtMost(w - 1)
            var weighted = 0L
            var mass = 0
            for (y in yStart..yEnd) {
                val l = maskLeft[y].coerceAtLeast(x0)
                val r = maskRight[y].coerceAtMost(x1)
                if (l > r) continue
                val row = y * w
                for (x in l..r) {
                    val m = edges[row + x]
                    if (m >= threshold && m > MIN_EDGE_MAGNITUDE) {
                        weighted += m.toLong() * x
                        mass += m
                    }
                }
            }
            // A band with too little edge mass is occluded (shadow, car ahead): keep walking
            // from the same x rather than snapping the lane onto noise.
            if (mass >= MIN_WINDOW_PIXELS * MIN_EDGE_MAGNITUDE) {
                cx = (weighted / mass).toInt()
                out.add(intArrayOf(cx, (yStart + yEnd) / 2))
            }
            yEnd = yStart - 1
        }
        return out
    }

    /**
     * Least-squares fit of `x(t)`, where t runs 0 at the frame bottom to 1 at the ROI top.
     * Returns (a, b, c) or an empty array when there is not enough evidence.
     */
    private fun fit(points: List<IntArray>, leftSide: Boolean): FloatArray {
        if (points.size < 3) return FloatArray(0)
        val span = (h - 1 - roiTop).coerceAtLeast(1).toFloat()

        var n = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var b0 = 0.0
        var b1 = 0.0
        var b2 = 0.0
        for (p in points) {
            val t = ((h - 1 - p[1]) / span).toDouble()
            val x = p[0].toDouble()
            val t2 = t * t
            n += 1.0
            s1 += t
            s2 += t2
            s3 += t2 * t
            s4 += t2 * t2
            b0 += x
            b1 += x * t
            b2 += x * t2
        }

        val coeffs = if (points.size >= 5) {
            solve3(n, s1, s2, s3, s4, b0, b1, b2)
        } else {
            solve2(n, s1, s2, b0, b1)
        } ?: return FloatArray(0)

        val a = coeffs[0].toFloat()
        val b = coeffs[1].toFloat()
        val c = coeffs[2].toFloat()
        // Sanity: the lane must start on screen, stay inside a plausible envelope, and lean the
        // way its own side of the road does. The two markings converge towards the vanishing
        // point, so going up the frame the LEFT edge moves right and the RIGHT edge moves left.
        if (c < -0.1f * w || c > 1.1f * w) return FloatArray(0)
        val top = c + b + a
        if (top < -0.15f * w || top > 1.15f * w) return FloatArray(0)
        if (kotlin.math.abs(a) > 0.6f * w) return FloatArray(0)
        if (leftSide && b < -0.10f * w) return FloatArray(0)
        if (!leftSide && b > 0.10f * w) return FloatArray(0)
        return floatArrayOf(a, b, c)
    }

    private fun solve2(n: Double, s1: Double, s2: Double, b0: Double, b1: Double): DoubleArray? {
        val det = n * s2 - s1 * s1
        if (kotlin.math.abs(det) < 1e-6) return null
        val c = (b0 * s2 - b1 * s1) / det
        val b = (n * b1 - s1 * b0) / det
        return doubleArrayOf(0.0, b, c)
    }

    /** Solves the 3×3 normal equations for `x = c + b·t + a·t²` by Gauss-Jordan elimination. */
    private fun solve3(
        s0: Double,
        s1: Double,
        s2: Double,
        s3: Double,
        s4: Double,
        b0: Double,
        b1: Double,
        b2: Double,
    ): DoubleArray? {
        val m = arrayOf(
            doubleArrayOf(s0, s1, s2, b0),
            doubleArrayOf(s1, s2, s3, b1),
            doubleArrayOf(s2, s3, s4, b2),
        )
        for (col in 0 until 3) {
            var pivot = col
            for (row in col + 1 until 3) {
                if (kotlin.math.abs(m[row][col]) > kotlin.math.abs(m[pivot][col])) pivot = row
            }
            if (kotlin.math.abs(m[pivot][col]) < 1e-9) return null
            if (pivot != col) {
                val tmp = m[col]
                m[col] = m[pivot]
                m[pivot] = tmp
            }
            for (row in 0 until 3) {
                if (row == col) continue
                val f = m[row][col] / m[col][col]
                for (k in col until 4) m[row][k] -= f * m[col][k]
            }
        }
        return doubleArrayOf(m[2][3] / m[2][2], m[1][3] / m[1][1], m[0][3] / m[0][0])
    }

    // ------------------------------------------------------------- composition

    private fun compose(leftRaw: FloatArray, rightRaw: FloatArray, confidence: Float): LaneResult {
        val leftSmooth = smooth(leftFit, leftRaw, leftHold)
        val rightSmooth = smooth(rightFit, rightRaw, rightHold)
        leftFit = leftSmooth.first
        leftHold = leftSmooth.second
        rightFit = rightSmooth.first
        rightHold = rightSmooth.second

        val left = leftFit
        val right = rightFit
        if (left == null && right == null) {
            departureStreak = 0
            return LaneResult.NONE
        }

        val leftBottom = left?.get(2)
        val rightBottom = right?.get(2)

        var laneWidth = Float.NaN
        var offset = Float.NaN
        if (leftBottom != null && rightBottom != null) {
            val px = rightBottom - leftBottom
            val norm = px / w
            if (norm in MIN_LANE_WIDTH..MAX_LANE_WIDTH) {
                laneWidth = norm
                offset = ((leftBottom + rightBottom) / 2f - w / 2f) / px
            }
        }

        val pastThreshold = !offset.isNaN() && kotlin.math.abs(offset) >= DEPARTURE_LANES
        departureStreak = if (pastThreshold) departureStreak + 1 else 0

        return LaneResult(
            left = sample(left),
            right = sample(right),
            offsetLanes = offset,
            departure = departureStreak >= DEPARTURE_HOLD,
            confidence = confidence,
            laneWidth = laneWidth,
        )
    }

    /**
     * Applies a new fit, or holds the previous one for a few passes when this pass found nothing
     * (a shadow, a car ahead, a painted junction). Returns the coefficients and the new hold.
     */
    private fun smooth(prev: FloatArray?, raw: FloatArray, hold: Int): Pair<FloatArray?, Int> {
        if (raw.isNotEmpty()) {
            val next = if (prev == null || prev.isEmpty()) {
                raw
            } else {
                FloatArray(3) { prev[it] + (raw[it] - prev[it]) * SMOOTHING }
            }
            return next to 0
        }
        val missed = hold + 1
        if (prev == null || prev.isEmpty() || missed > MAX_HOLD_PASSES) return null to 0
        return prev to missed
    }

    private fun sample(fit: FloatArray?): List<LanePoint> {
        if (fit == null || fit.isEmpty()) return emptyList()
        val span = (h - 1 - roiTop).coerceAtLeast(1).toFloat()
        val a = fit[0]
        val b = fit[1]
        val c = fit[2]
        val out = ArrayList<LanePoint>(DRAW_POINTS)
        for (i in 0 until DRAW_POINTS) {
            val t = i.toFloat() / (DRAW_POINTS - 1)
            val x = c + b * t + a * t * t
            val y = (h - 1) - t * span
            out.add(LanePoint((x / w).coerceIn(-0.05f, 1.05f), (y / h).coerceIn(0f, 1f)))
        }
        return out
    }
}
