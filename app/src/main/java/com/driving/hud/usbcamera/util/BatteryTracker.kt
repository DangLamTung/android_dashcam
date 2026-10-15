package com.driving.hud.usbcamera.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import java.util.Locale
import kotlin.math.abs

/**
 * High-precision battery monitor for the USB Camera HUD.
 *
 * Measures:
 * 1. Real-time battery percentage, voltage (V), temperature (°C), and charging status.
 * 2. Instantaneous current draw (mA) and total power consumption (mW / W) caused by
 *    the phone, OTG USB camera, display, and AI inference.
 * 3. Session drain tracking: elapsed duration, total % drained, measured drain rate (%/hour),
 *    and estimated battery runtime remaining.
 */
class BatteryTracker(private val context: Context) {

    companion object {
        private const val TAG = "BatteryTracker"
        private const val DEFAULT_BATTERY_MAH = 7000f // Modern fallback baseline (7,000 mAh)
    }

    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
    private val batteryCapacityMah: Float = detectBatteryCapacityMah()

    private var sessionStartTimeMs: Long = SystemClock.elapsedRealtime()
    private var sessionStartLevel: Int = -1
    private var lastLoggedTimeMs: Long = 0L

    // Exponential Moving Average (EMA) filter state for jitter-free Watt and mA display
    private var smoothedCurrentMa: Float = 0f
    private var smoothedPowerMw: Float = 0f
    private var hasSmoothedSample = false

    private fun detectBatteryCapacityMah(): Float {
        try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val powerProfile = powerProfileClass.getConstructor(Context::class.java).newInstance(context)
            val cap = powerProfileClass.getMethod("getBatteryCapacity").invoke(powerProfile) as? Double
            if (cap != null && cap > 1000.0) {
                HudLogger.i(TAG, "Detected hardware battery capacity: ${cap.toFloat()} mAh")
                return cap.toFloat()
            }
        } catch (t: Throwable) {
            HudLogger.w(TAG, "PowerProfile lookup failed: ${t.message}; using ${DEFAULT_BATTERY_MAH.toInt()} mAh")
        }
        return DEFAULT_BATTERY_MAH
    }

    data class BatterySnapshot(
        val levelPercent: Int,          // 0..100
        val isCharging: Boolean,
        val pluggedSource: String,      // "BATTERY", "AC", "USB", "WIRELESS"
        val voltageMv: Int,             // in millivolts, e.g. 3950 mV
        val currentNowMa: Int,          // in milliamperes (negative = discharge, positive = charge)
        val powerMw: Int,               // power in mW (abs(currentNowMa) * voltageMv / 1000)
        val tempC: Float,               // in °C, e.g. 34.2°C
        val sessionElapsedSec: Long,    // duration of active session in seconds
        val sessionStartLevel: Int,     // starting battery percentage
        val sessionDrainPercent: Int,   // startLevel - currentLevel (positive = drained)
        val drainPerHour: Float,        // %/hour drain rate
        val estHoursRemaining: Float    // estimated hours remaining (-1 if charging/unknown)
    ) {
        /**
         * Compact one-line telemetry readout for the HUD overlay.
         * Example: "BAT 82% · -420mA 1.7W · 8.5%/h (~9.6h)"
         */
        fun toHudString(): String {
            if (levelPercent < 0) return "BAT --"

            val currStr = if (currentNowMa != 0) {
                val sign = if (currentNowMa > 0) "+" else ""
                "$sign${currentNowMa}mA"
            } else ""

            val powerStr = if (powerMw > 0) {
                "${String.format(Locale.US, "%.1f", powerMw / 1000f)}W"
            } else ""

            val rateStr = if (isCharging) {
                "CHG ($pluggedSource)"
            } else if (drainPerHour > 0.1f) {
                "${String.format(Locale.US, "%.1f", drainPerHour)}%/h"
            } else ""

            val remStr = if (estHoursRemaining > 0.05f && !isCharging) {
                val h = estHoursRemaining.toInt()
                val m = ((estHoursRemaining - h) * 60).toInt()
                if (h > 0) "(~${h}h${m}m)" else "(~${m}m)"
            } else ""

            val parts = ArrayList<String>(4)
            parts.add("BAT $levelPercent%")
            if (currStr.isNotEmpty() || powerStr.isNotEmpty()) {
                val p = listOf(currStr, powerStr).filter { it.isNotEmpty() }.joinToString(" ")
                parts.add(p)
            }
            if (rateStr.isNotEmpty()) {
                parts.add(if (remStr.isNotEmpty()) "$rateStr $remStr" else rateStr)
            }
            return parts.joinToString(" · ")
        }

        /**
         * Multi-line detailed report for the home panel.
         */
        fun toDetailedSummary(): String {
            if (levelPercent < 0) return "Battery info unavailable"

            val voltStr = if (voltageMv > 0) "${String.format(Locale.US, "%.2f", voltageMv / 1000f)} V (${voltageMv} mV)" else "n/a"
            val tempStr = if (tempC > 0) "${String.format(Locale.US, "%.1f", tempC)} °C" else "n/a"
            val powerStr = if (powerMw > 0) "${String.format(Locale.US, "%.2f", powerMw / 1000f)} W (${powerMw} mW)" else "n/a"
            val currStr = if (currentNowMa != 0) {
                "${if (currentNowMa > 0) "+" else ""}$currentNowMa mA"
            } else "n/a"

            val sessionDuration = formatDuration(sessionElapsedSec)
            val sessionStats = if (isCharging) {
                "Charging via $pluggedSource (session duration: $sessionDuration)"
            } else if (sessionElapsedSec >= 30) {
                val drainSign = if (sessionDrainPercent > 0) "-$sessionDrainPercent%" else "$sessionDrainPercent%"
                "Session drain: $drainSign in $sessionDuration (${String.format(Locale.US, "%.1f", drainPerHour)} %/hr)"
            } else {
                "Session started ($sessionDuration elapsed)"
            }

            val remStr = if (estHoursRemaining > 0.05f && !isCharging) {
                val h = estHoursRemaining.toInt()
                val m = ((estHoursRemaining - h) * 60).toInt()
                "Estimated remaining runtime: ~${h}h ${m}m"
            } else ""

            return buildString {
                appendLine("Battery: $levelPercent%  (${if (isCharging) "Charging - $pluggedSource" else "Discharging"})")
                appendLine("Current: $currStr | Voltage: $voltStr")
                appendLine("Power Draw: $powerStr | Temp: $tempStr")
                appendLine(sessionStats)
                if (remStr.isNotEmpty()) appendLine(remStr)
            }.trimEnd()
        }

        fun toLogLine(): String {
            return "Battery: $levelPercent%, isCharging=$isCharging ($pluggedSource), " +
                "current=${currentNowMa}mA, voltage=${voltageMv}mV, power=${powerMw}mW, " +
                "temp=${tempC}°C, sessionDrain=${sessionDrainPercent}% in ${sessionElapsedSec}s " +
                "(rate=${String.format(Locale.US, "%.1f", drainPerHour)}%/h, estLeft=${String.format(Locale.US, "%.1f", estHoursRemaining)}h)"
        }
    }

    /** Reset session timer and start level (e.g. when entering HUD mode or user clicks reset). */
    fun resetSession() {
        sessionStartTimeMs = SystemClock.elapsedRealtime()
        hasSmoothedSample = false
        val snap = sample()
        sessionStartLevel = snap.levelPercent
        HudLogger.i(TAG, "Battery session reset at level ${sessionStartLevel}% (capacity: ${batteryCapacityMah.toInt()}mAh)")
    }

    /**
     * Samples the latest battery data and calculates instantaneous power and session drain rates.
     */
    fun sample(): BatterySnapshot {
        // Sticky broadcast for battery metrics (instant, non-blocking)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter)

        val rawLevel = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (rawLevel >= 0 && scale > 0) (rawLevel * 100) / scale else rawLevel
        val voltageMv = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1) ?: -1

        val tempRaw = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        val tempC = if (tempRaw > 0) tempRaw / 10f else -1f

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
        val pluggedSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
            else -> "BATTERY"
        }

        // Current draw (microamps or milliamps depending on OEM)
        var currentMa = 0
        batteryManager?.let { bm ->
            val rawCur = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (rawCur != Int.MIN_VALUE && rawCur != 0) {
                // If > 10,000, value is in microamperes (µA). Otherwise, device driver gives mA directly.
                val inMa = if (abs(rawCur) > 10000) rawCur / 1000 else rawCur
                // Normalize sign: negative = discharge, positive = charging
                currentMa = if (isCharging) {
                    abs(inMa)
                } else {
                    -abs(inMa)
                }
            }
        }

        // Calculate power consumption in mW (P = V * I)
        val powerMw = if (voltageMv > 0 && currentMa != 0) {
            (abs(currentMa) * voltageMv) / 1000
        } else 0

        // Apply EMA filter (alpha = 0.2 gives a smooth ~5-second low-pass response across 1 Hz ticks)
        if (!hasSmoothedSample) {
            smoothedCurrentMa = currentMa.toFloat()
            smoothedPowerMw = powerMw.toFloat()
            hasSmoothedSample = true
        } else {
            val alpha = 0.2f
            smoothedCurrentMa = alpha * currentMa + (1f - alpha) * smoothedCurrentMa
            smoothedPowerMw = alpha * powerMw + (1f - alpha) * smoothedPowerMw
        }
        val displayCurrentMa = smoothedCurrentMa.toInt()
        val displayPowerMw = smoothedPowerMw.toInt()

        // Session metrics
        val nowMs = SystemClock.elapsedRealtime()
        val sessionElapsedSec = (nowMs - sessionStartTimeMs) / 1000

        if (sessionStartLevel < 0 && levelPercent >= 0) {
            sessionStartLevel = levelPercent
        }
        val startLevel = if (sessionStartLevel >= 0) sessionStartLevel else levelPercent
        val sessionDrainPercent = startLevel - levelPercent

        // Calculate drain rate (% per hour)
        var drainPerHour = 0f
        var estHoursRemaining = -1f

        if (!isCharging && levelPercent > 0) {
            if (sessionElapsedSec >= 45 && sessionDrainPercent > 0) {
                // Measured session drain rate
                drainPerHour = (sessionDrainPercent.toFloat() / sessionElapsedSec) * 3600f
            } else if (abs(smoothedCurrentMa) > 50f) {
                // Smoothed estimated drain rate based on mA draw and battery capacity
                drainPerHour = (abs(smoothedCurrentMa) / batteryCapacityMah) * 100f
            }

            if (drainPerHour > 0.2f) {
                estHoursRemaining = levelPercent / drainPerHour
            }
        }

        return BatterySnapshot(
            levelPercent = levelPercent,
            isCharging = isCharging,
            pluggedSource = pluggedSource,
            voltageMv = voltageMv,
            currentNowMa = displayCurrentMa,
            powerMw = displayPowerMw,
            tempC = tempC,
            sessionElapsedSec = sessionElapsedSec,
            sessionStartLevel = startLevel,
            sessionDrainPercent = sessionDrainPercent,
            drainPerHour = drainPerHour,
            estHoursRemaining = estHoursRemaining
        )
    }

    /**
     * Logs the current battery snapshot to [HudLogger] if [minIntervalMs] has elapsed.
     */
    fun logIfNeeded(minIntervalMs: Long = 30_000) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLoggedTimeMs >= minIntervalMs) {
            lastLoggedTimeMs = now
            val snap = sample()
            HudLogger.i(TAG, snap.toLogLine())
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m >= 60) {
        val h = m / 60
        val remM = m % 60
        "${h}h ${remM}m"
    } else {
        "${m}m ${s}s"
    }
}
