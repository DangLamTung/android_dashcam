package com.driving.hud.usbcamera.detect

import java.io.File

/**
 * Total CPU time this process has burned, summed over every thread.
 *
 * Wall-clock time is not a cost measurement: a backend can finish sooner purely because it
 * spreads the *same* work over more threads, which does nothing for heat or battery. This is
 * what tells those two cases apart, and it is why the backend shoot-out picks its winner on
 * CPU-ms rather than on latency.
 */
internal object SelfCpu {

    /**
     * `/proc/self/stat` reports utime/stime in clock ticks (100 Hz on Android). The comm field
     * is wrapped in parentheses and may itself contain spaces, so parsing starts after the
     * last `)`, which puts state at index 0 and therefore utime at 11 and stime at 12.
     *
     * Returns -1 when the file cannot be read, so a caller can fall back to wall time.
     */
    fun millis(): Long {
        return try {
            val stat = File("/proc/self/stat").readText()
            val tail = stat.substring(stat.lastIndexOf(')') + 2).split(' ')
            val utime = tail.getOrNull(11)?.toLongOrNull()
            val stime = tail.getOrNull(12)?.toLongOrNull()
            if (utime == null || stime == null) -1L else (utime + stime) * 10L
        } catch (t: Throwable) {
            -1L
        }
    }
}
