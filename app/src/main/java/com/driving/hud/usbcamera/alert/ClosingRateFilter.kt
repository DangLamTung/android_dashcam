package com.driving.hud.usbcamera.alert

/**
 * Average-change filter: estimates a signal's rate of change by averaging the frame-to-frame
 * differences over a short window, instead of differencing two consecutive samples.
 *
 * Why: a single-frame difference of a noisy measurement is dominated by measurement noise. If
 * distance jitters by ±0.5 m between frames while genuinely closing at 1 m/s, one-frame
 * differencing reports anything from -2 to +4 m/s. Averaging the changes over a window divides
 * the noise by roughly the window length while keeping the trend, which is what a time-to-
 * collision estimate needs to be stable enough to act on.
 *
 * The rate is computed as `sum(Δvalue) / sum(Δt)` over the window rather than a mean of
 * per-frame ratios, so irregular frame intervals (the detection rate is configurable, and the
 * scheduler is not perfectly punctual) do not bias the result.
 *
 * Gaps longer than [maxGapMs] are treated as a break: the history is dropped rather than
 * averaged across it, because samples from before an occluded stretch describe a different
 * motion than the one in progress.
 *
 * Units are whatever the caller uses: pass milliseconds and metres, read back metres/second.
 */
internal class ClosingRateFilter(
    private val capacity: Int = 6,
    private val maxGapMs: Long = 1_200L
) {

    private val times = LongArray(capacity)
    private val values = FloatArray(capacity)

    /** Where the next sample is written. */
    private var next = 0

    /** Number of valid samples, never more than [capacity]. */
    private var count = 0

    /** Physical slot holding the sample `age` steps back (0 = newest). */
    private fun indexOf(age: Int): Int {
        var i = (next - 1 - age) % capacity
        if (i < 0) i += capacity
        return i
    }

    fun reset() {
        next = 0
        count = 0
    }

    fun add(timeMs: Long, value: Float) {
        // A gap means the object was not tracked continuously: start a fresh window.
        if (count > 0 && timeMs - times[indexOf(0)] > maxGapMs) {
            next = 0
            count = 0
        }
        times[next] = timeMs
        values[next] = value
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    /** Samples currently in the window. */
    val samples: Int
        get() = count

    /** Time spanned by the window, in the units passed to [add]. */
    fun spanMs(): Long = if (count < 2) 0L else times[indexOf(0)] - times[indexOf(count - 1)]

    /**
     * Average rate of change over the window, in value units per second, or null when there is
     * not enough history (fewer than two samples, or all samples at the same instant).
     */
    fun rate(): Float? {
        if (count < 2) return null
        var sumDelta = 0f
        var sumDt = 0L
        var hasPrev = false
        var prevT = 0L
        var prevV = 0f
        for (age in count - 1 downTo 0) {
            val i = indexOf(age)
            val t = times[i]
            val v = values[i]
            if (hasPrev) {
                val dt = t - prevT
                // Belt and braces: `add` already broke the window on a gap, but a single
                // straddling pair should not be used either.
                if (dt > 0L && dt <= maxGapMs) {
                    sumDelta += v - prevV
                    sumDt += dt
                }
            }
            hasPrev = true
            prevT = t
            prevV = v
        }
        if (sumDt <= 0L) return null
        return sumDelta / (sumDt / 1000f)
    }
}
