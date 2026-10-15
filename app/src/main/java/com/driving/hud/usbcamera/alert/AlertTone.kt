package com.driving.hud.usbcamera.alert

import android.media.AudioManager
import android.media.ToneGenerator

/**
 * Short audible alert for the collision warning.
 *
 * A visual banner alone is easy to miss while driving, so a warning gets a distinctly harsher
 * tone than a caution. ToneGenerator needs no permission. Every call is guarded: this runs on
 * the inference thread and an audio failure must never take down detection.
 */
internal class AlertTone {

    private var generator: ToneGenerator? = null

    fun beep(warn: Boolean) {
        try {
            val g = generator ?: ToneGenerator(AudioManager.STREAM_ALARM, 80).also { generator = it }
            if (warn) {
                g.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 250)
            } else {
                g.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
            }
        } catch (t: Throwable) {
            // Audio is best-effort; the banner is the primary channel.
            generator = null
        }
    }

    fun release() {
        try {
            generator?.release()
        } catch (_: Throwable) {
        }
        generator = null
    }
}
