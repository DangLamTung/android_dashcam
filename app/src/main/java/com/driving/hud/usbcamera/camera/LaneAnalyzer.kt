package com.driving.hud.usbcamera.camera

import android.content.Context
import com.driving.hud.usbcamera.detect.LaneDetector
import com.driving.hud.usbcamera.detect.LaneResult
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Throttled, off-thread bridge between a camera source's NV21 frames and [LaneDetector].
 *
 * Lane detection is *not* free but it is cheap, and it must never compete with the object
 * detector for the frame thread: a pass is copied out (luma only), analysed on its own
 * low-priority thread, and the result is published through
 * [AusbcCameraController.CameraEventListener.onLanes]. Passing [isDue] before building NV21
 * lets each source skip the conversion entirely while lanes are switched off.
 */
class LaneAnalyzer(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener,
    private val tag: String,
) {

    private val detector = LaneDetector()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "$tag-lane").apply { priority = Thread.NORM_PRIORITY - 2; isDaemon = true }
    }

    private companion object {
        /** Passes between one-line diagnostics; at 2.5 Hz this is roughly every 5 s. */
        const val LOG_EVERY = 12
    }

    private val busy = AtomicBoolean(false)

    @Volatile
    private var lastRunAt = 0L

    private var luma = ByteArray(0)

    /** Passes since the last diagnostic line. */
    private var passes = 0

    /** True when lanes are on and the throttle allows another pass right now. */
    fun isDue(): Boolean {
        if (!HudPrefs.laneDetect(context)) return false
        val interval = HudPrefs.laneIntervalMs(context).toLong()
        if (interval > 0 && System.currentTimeMillis() - lastRunAt < interval) return false
        return !busy.get()
    }

    /**
     * Copies the luma plane and analyses it asynchronously. No-op unless [isDue]; the caller can
     * therefore call it unconditionally on every frame.
     */
    fun submit(nv21: ByteArray, width: Int, height: Int) {
        val plane = width * height
        if (plane <= 0 || nv21.size < plane) return
        if (luma.size != plane) luma = ByteArray(plane)
        System.arraycopy(nv21, 0, luma, 0, plane)
        analyse(luma, width, height)
    }

    /**
     * Same as [submit] without the copy, for a caller that already owns a luma-only buffer and
     * promises not to touch it until the pass finishes (the UVC lane-only path allocates its own).
     *
     * @param lumaPlane at least `width * height` bytes of Y samples; ownership stays with the
     *        caller, which must not rewrite it while [isDue] would answer false
     */
    fun submitOwned(lumaPlane: ByteArray, width: Int, height: Int) {
        val plane = width * height
        if (plane <= 0 || lumaPlane.size < plane) return
        analyse(lumaPlane, width, height)
    }

    private fun analyse(buffer: ByteArray, width: Int, height: Int) {
        if (!busy.compareAndSet(false, true)) return
        lastRunAt = System.currentTimeMillis()
        try {
            executor.execute(task(buffer, width, height))
        } catch (t: Throwable) {
            // Only reachable after destroy() shut the executor down: a camera callback can still
            // be in flight. Clearing `busy` matters, otherwise a later session would see lanes
            // as permanently busy and never analyse a frame again.
            busy.set(false)
            HudLogger.w(tag, "lane task rejected: ${t.message}")
        }
    }

    private fun task(buffer: ByteArray, width: Int, height: Int) = Runnable {
        val t0 = System.nanoTime()
        try {
            // Coordinates are published in frame space, exactly like detection boxes: the HUD
            // overlay is flipped together with the video when the mirror is on, so flipping the
            // result here as well would cancel it out.
            val result = detector.detect(buffer, width, height)
            val tookMs = (System.nanoTime() - t0) / 1_000_000f
            logDiagnostics(result, tookMs)
            listener.onLanes(result.copy(tookMs = tookMs))
        } catch (t: Throwable) {
            HudLogger.w(tag, "lane pass failed: ${t.message}")
        } finally {
            busy.set(false)
        }
    }

    /**
     * One line every [LOG_EVERY] passes with exactly the numbers needed to tune the detector on
     * a real road: how long a pass takes, the adaptive edge threshold, how many windows each side
     * found, and the resulting offset. Turning "lanes are bad in the rain" into a knob.
     */
    private fun logDiagnostics(result: LaneResult, tookMs: Float) {
        if (++passes % LOG_EVERY != 0) return
        val off = if (result.offsetLanes.isNaN()) {
            "n/a"
        } else {
            String.format(java.util.Locale.US, "%+.2f", result.offsetLanes)
        }
        HudLogger.i(
            tag,
            "lane pass %.1f ms · thr %d · L %d R %d · off %s · conf %.2f%s".format(
                java.util.Locale.US,
                tookMs,
                detector.lastThreshold,
                result.left.size,
                result.right.size,
                off,
                result.confidence,
                if (result.departure) " · DEPARTURE" else "",
            )
        )
    }

    /** Forgets the smoothed lanes; called when the source is re-opened. */
    fun reset() {
        detector.reset()
        listener.onLanes(LaneResult.NONE)
    }

    fun destroy() {
        detector.reset()
        executor.shutdown()
    }
}
