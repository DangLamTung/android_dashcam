package com.driving.hud.usbcamera.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.driving.hud.usbcamera.R
import com.driving.hud.usbcamera.util.HudLogger
import java.util.concurrent.atomic.AtomicInteger

/**
 * Short audible alert for the collision warning.
 *
 * The tone is played through [SoundPool] with navigation-guidance attributes and **no audio
 * focus request**, so it mixes with whatever else is speaking (Maps/Waze directions, music, a
 * phone call) instead of pausing it. The previous ToneGenerator on STREAM_ALARM was audibly
 * cutting other audio on this phone, because the alarm stream is treated as exclusive by the
 * OEM audio policy.
 *
 * [SoundPool.play] is thread-safe, and every call is guarded: this runs on the inference thread
 * and an audio failure must never take down detection.
 */
internal class AlertTone(context: Context) {

    private companion object {
        const val TAG = "AlertTone"
        const val VOLUME = 0.9f

        /** One delayed retry when the samples have not finished decoding yet. */
        const val LOAD_RETRY_MS = 180L
    }

    private val appContext = context.applicationContext

    /**
     * Touched from the main thread ([warmUp], [release]) and from the inference thread ([beep],
     * called on the frame pipeline), so every field here is either volatile or guarded by
     * [lock]. A torn read would silently drop a warning.
     */
    @Volatile
    private var pool: SoundPool? = null

    @Volatile
    private var beepId = 0

    @Volatile
    private var warnId = 0

    /** Samples that have finished decoding; playing an unloaded id returns 0 and is silent. */
    private val loaded = AtomicInteger(0)

    private val lock = Any()

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun ensurePool(): SoundPool? {
        pool?.let { return it }
        synchronized(lock) {
            pool?.let { return it }
            return build()
        }
    }

    private fun build(): SoundPool? {
        return try {
            val attributes = AudioAttributes.Builder()
                // Guidance, not alarm: it shares the media path (so it is not muted in silent
                // mode) but takes no focus, so nothing else is paused or ducked by us.
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val created = SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(attributes)
                .build()
            created.setOnLoadCompleteListener { _, _, status ->
                if (status == 0) loaded.incrementAndGet()
            }
            val beep = created.load(appContext, R.raw.alert_beep, 1)
            val warn = created.load(appContext, R.raw.alert_warn, 1)
            if (beep == 0 || warn == 0) {
                HudLogger.w(TAG, "alert samples could not be queued (beep=$beep warn=$warn)")
                created.release()
                return null
            }
            beepId = beep
            warnId = warn
            pool = created
            HudLogger.i(TAG, "SoundPool ready (mix-only, no audio focus)")
            created
        } catch (t: Throwable) {
            HudLogger.w(TAG, "SoundPool unavailable: ${t.message}")
            null
        }
    }

    /** Loads the samples up front so the first warning is not swallowed while they decode. */
    fun warmUp() {
        ensurePool()
    }

    fun beep(warn: Boolean) {
        val soundPool = ensurePool() ?: return
        val id = if (warn) warnId else beepId
        if (id == 0) return
        val priority = if (warn) 1 else 0
        try {
            val played = soundPool.play(id, VOLUME, VOLUME, priority, 0, 1f)
            if (played == 0 && loaded.get() < 2) {
                // The sample is still decoding (only possible in the first moments after start).
                // A collision warning is worth exactly one retry rather than silence.
                handler.postDelayed({
                    if (pool === soundPool && loaded.get() >= 2) {
                        try {
                            soundPool.play(id, VOLUME, VOLUME, priority, 0, 1f)
                        } catch (_: Throwable) {
                        }
                    }
                }, LOAD_RETRY_MS)
            }
        } catch (t: Throwable) {
            HudLogger.w(TAG, "beep failed: ${t.message}")
            release()
        }
    }

    fun release() {
        synchronized(lock) {
            try {
                pool?.release()
            } catch (_: Throwable) {
            }
            pool = null
            beepId = 0
            warnId = 0
            loaded.set(0)
        }
    }
}
