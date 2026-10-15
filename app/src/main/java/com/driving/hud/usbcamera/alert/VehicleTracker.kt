package com.driving.hud.usbcamera.alert

import com.driving.hud.usbcamera.calib.GroundPlaneCalibration
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.util.HudLogger

/**
 * Turns per-frame detections into stable, temporally filtered "what is in front of me" tracks
 * and raises a time-to-collision alert.
 *
 * The point is not to detect better, it is to stop reacting to single frames:
 *
 * - **Association**: detections are matched to existing tracks by centre proximity, so a
 *   measurement history accumulates per object rather than per frame.
 * - **Average-change filter**: each track's closing rate comes from [ClosingRateFilter], not
 *   from differencing the last two frames.
 * - **Asymmetric hysteresis**: a track must be seen [acquireHits] times before it can alert,
 *   and survives [dropMisses] consecutive misses before it is forgotten. Quick to trust, slow
 *   to abandon — the same shape openpilot uses to "keep lead when uncertain".
 * - **In-path gate**: distance comes from the ground-plane calibration, which also gives the
 *   lateral offset in metres, so a parked car beside you is excluded by geometry rather than
 *   by hoping the detector behaves.
 *
 * Time-to-collision is `distance / closing rate`. It is only produced while the gap is actually
 * shrinking; a stationary object that never closes has no TTC.
 *
 * This is an advisory aid. It is monocular, so distance is only valid for objects on the road
 * plane, and it must not be relied on for safety.
 */
internal class VehicleTracker(
    private val acquireHits: Int = 2,
    private val dropMisses: Int = 3,
    private val lateralLimitM: Float = 3.0f,
    private val warnTtcS: Float = 2.5f,
    private val cautionTtcS: Float = 4.0f,
    windowSamples: Int = 6
) {

    /** Per-detection annotation, aligned by index with the detections handed to [update]. */
    data class State(
        val label: String,
        val distanceM: Float,
        val lateralM: Float,
        val closingMps: Float,
        val ttcS: Float,
        val hits: Int,
        val inPath: Boolean,
        val alerting: Boolean,
        val pinholeDistanceM: Float = Float.NaN,
        val groundDistanceM: Float = Float.NaN
    )

    data class Alert(val warn: Boolean, val label: String, val distanceM: Float, val closingMps: Float, val ttcS: Float)

    data class Update(val states: List<State>, val alert: Alert?)

    private class Track(val id: Int, private val windowSamples: Int) {
        var label = ""
        var cx = 0f
        var cy = 0f
        var lastSeenMs = 0L
        var hits = 0
        var misses = 0
        var distanceM = 0f
        var pinholeM = Float.NaN
        var groundM = Float.NaN
        var lateralM = 0f
        var inPath = false
        var closingMps = 0f
        var ttcS = Float.NaN
        var settled = false
        val filter = ClosingRateFilter(capacity = windowSamples)

        fun reset() {
            filter.reset()
            closingMps = 0f
            ttcS = Float.NaN
            pinholeM = Float.NaN
            groundM = Float.NaN
        }
    }

    private val tracks = ArrayList<Track>()
    private var nextId = 1
    private val windowSamples = windowSamples

    /** Detectable vehicles only — pedestrians and street furniture are not what this alerts on. */
    private val alertLabels = setOf(
        "car", "motorcycle", "motorbike", "truck", "bus", "bicycle", "train"
    )

    private var lastLogMs = 0L

    fun reset() {
        tracks.clear()
    }

    fun update(
        detections: List<Detection>,
        calib: GroundPlaneCalibration?,
        frameW: Int,
        frameH: Int,
        nowMs: Long
    ): Update {
        val states = ArrayList<State>(detections.size)
        if (calib == null || frameW <= 0 || frameH <= 0) {
            return Update(List(detections.size) { emptyState(it, detections) }, null)
        }

        // ---- observations -------------------------------------------------
        class Obs(
            val d: Detection,
            val cx: Float,
            val cy: Float,
            val distance: Float,
            val lateral: Float,
            val inPath: Boolean,
            val pinholeM: Float,
            val groundM: Float
        )
        val obs = ArrayList<Obs>(detections.size)
        for (d in detections) {
            val cx = (d.left + d.right) / 2f
            val cy = (d.top + d.bottom) / 2f
            // Combine pinhole bounding box prior with ground plane raycast
            val est = calib.estimator.estimate(d)
            val distance = est.trueDistanceM
            if (distance.isNaN() || distance <= 0.1f || distance > 150f) {
                obs.add(
                    Obs(
                        d, cx, cy, Float.NaN, 0f, false,
                        est.pinholeDistanceM, est.groundDistanceM ?: Float.NaN
                    )
                )
                continue
            }
            val lateral = est.lateralOffsetM
            obs.add(
                Obs(
                    d, cx, cy, distance, lateral,
                    Math.abs(lateral) <= lateralLimitM,
                    est.pinholeDistanceM,
                    est.groundDistanceM ?: Float.NaN
                )
            )
        }

        // ---- associate to existing tracks (greedy nearest centre) ---------
        val taken = BooleanArray(tracks.size)
        val matched = arrayOfNulls<Track>(detections.size)
        for (i in obs.indices) {
            val o = obs[i]
            if (o.distance.isNaN()) continue
            var best = -1
            var bestCost = 0.12f // normalised centre distance
            for (t in tracks.indices) {
                if (taken[t]) continue
                val tr = tracks[t]
                if (tr.label != o.d.label) continue
                val cost = Math.hypot((tr.cx - o.cx).toDouble(), (tr.cy - o.cy).toDouble()).toFloat()
                if (cost < bestCost) {
                    bestCost = cost
                    best = t
                }
            }
            if (best >= 0) {
                taken[best] = true
                matched[i] = tracks[best]
            }
        }

        // ---- update matched tracks, create new ones, age the rest ---------
        for (i in obs.indices) {
            val o = obs[i]
            if (o.distance.isNaN()) continue
            var t = matched[i]
            if (t == null) {
                t = Track(nextId++, windowSamples)
                tracks.add(t)
            }
            t.label = o.d.label
            t.cx = o.cx
            t.cy = o.cy
            t.distanceM = o.distance
            t.pinholeM = o.pinholeM
            t.groundM = o.groundM
            t.lateralM = o.lateral
            t.inPath = o.inPath
            t.hits++
            t.misses = 0
            t.lastSeenMs = nowMs
            t.filter.add(nowMs, o.distance)
            val rate = t.filter.rate()
            if (rate != null) {
                // Negative rate = distance shrinking = closing.
                t.closingMps = -rate
                t.ttcS = if (t.closingMps > 0.2f) t.distanceM / t.closingMps else Float.NaN
                t.settled = true
            }
        }

        // Tracks not matched this frame: age them, and forget the ones that stay missing.
        val it = tracks.iterator()
        while (it.hasNext()) {
            val t = it.next()
            if (t.lastSeenMs != nowMs) {
                t.misses++
                if (t.misses > dropMisses) it.remove()
            }
        }

        // ---- per-detection annotation + best alert -----------------------
        var bestAlert: Alert? = null
        for (i in obs.indices) {
            val o = obs[i]
            val t = matched[i]
            if (o.distance.isNaN() || t == null) {
                states.add(
                    State(
                        label = o.d.label,
                        distanceM = Float.NaN,
                        lateralM = 0f,
                        closingMps = 0f,
                        ttcS = Float.NaN,
                        hits = 0,
                        inPath = false,
                        alerting = false,
                        pinholeDistanceM = o.pinholeM,
                        groundDistanceM = o.groundM
                    )
                )
                continue
            }
            val alerting = t.inPath && t.hits >= acquireHits && alertLabels.contains(t.label.lowercase()) &&
                !t.ttcS.isNaN() && t.ttcS <= cautionTtcS
            states.add(
                State(
                    label = t.label,
                    distanceM = t.distanceM,
                    lateralM = t.lateralM,
                    closingMps = t.closingMps,
                    ttcS = t.ttcS,
                    hits = t.hits,
                    inPath = t.inPath,
                    alerting = alerting,
                    pinholeDistanceM = t.pinholeM,
                    groundDistanceM = t.groundM
                )
            )
            if (alerting) {
                val warn = t.ttcS <= warnTtcS
                if (bestAlert == null || t.ttcS < bestAlert.ttcS) {
                    bestAlert = Alert(warn, t.label, t.distanceM, t.closingMps, t.ttcS)
                }
            }
        }

        val alert = bestAlert
        if (alert != null && alert.warn && nowMs - lastLogMs > 900L) {
            lastLogMs = nowMs
            HudLogger.i(
                TAG,
                String.format(
                    java.util.Locale.US,
                    "TTC ALERT %s d=%.1fm closing=%.1fm/s ttc=%.1fs",
                    alert.label, alert.distanceM, alert.closingMps, alert.ttcS
                )
            )
        }
        return Update(states, alert)
    }

    private fun emptyState(index: Int, detections: List<Detection>) = State(
        detections.getOrNull(index)?.label ?: "", Float.NaN, 0f, 0f, Float.NaN, 0, false, false
    )

    /** One-line snapshot for the 1 Hz telemetry log. */
    fun describe(): String {
        if (tracks.isEmpty()) return "tracks=0"
        return tracks.joinToString(" ") { t ->
            String.format(
                java.util.Locale.US,
                "[%s d=%.1f lat=%.1f v=%+.1f ttc=%s hits=%d%s]",
                t.label, t.distanceM, t.lateralM, -t.closingMps,
                if (t.ttcS.isNaN()) "--" else String.format(java.util.Locale.US, "%.1f", t.ttcS),
                t.hits, if (t.inPath) "" else " off-path"
            )
        }
    }

    private companion object {
        const val TAG = "VehicleTracker"
    }
}
