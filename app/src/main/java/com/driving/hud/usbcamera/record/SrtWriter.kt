package com.driving.hud.usbcamera.record

import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.util.HudLogger
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Generates an SRT (SubRip) subtitle track alongside the recorded MKV video.
 *
 * Players like VLC, MX Player, and desktop media players automatically display these
 * subtitles when the .srt file shares the same base name as the video file.
 */
class SrtWriter(file: File) {

    private companion object {
        private const val TAG = "SrtWriter"
    }

    val srtFile: File = File(file.parentFile, file.nameWithoutExtension + ".srt")
    private var writer: BufferedWriter? = null
    private var cueIndex = 1
    private var lastStartMs = -1L
    private var lastText = ""

    init {
        try {
            writer = BufferedWriter(FileWriter(srtFile))
        } catch (t: Throwable) {
            HudLogger.w(TAG, "Failed to create SRT file ${srtFile.absolutePath}: ${t.message}")
        }
    }

    /**
     * Updates detection subtitles at a given presentation timestamp in milliseconds.
     */
    @Synchronized
    fun onDetections(ptsMs: Long, detections: List<Detection>) {
        if (writer == null) return
        if (detections.isEmpty()) {
            flushPrevious(ptsMs)
            return
        }

        val text = detections.joinToString("\n") { d ->
            val labelClean = d.label.replace("\n", "  ")
            "${labelClean.uppercase()} [${(d.score * 100).toInt()}%]"
        }

        if (text == lastText) return

        flushPrevious(ptsMs)
        lastStartMs = ptsMs
        lastText = text
    }

    private fun flushPrevious(currentMs: Long) {
        val w = writer ?: return
        if (lastStartMs >= 0 && lastText.isNotEmpty()) {
            val endMs = (currentMs.coerceAtLeast(lastStartMs + 200)).coerceAtMost(lastStartMs + 2000)
            try {
                w.write(cueIndex.toString())
                w.newLine()
                w.write(formatTimestamp(lastStartMs) + " --> " + formatTimestamp(endMs))
                w.newLine()
                w.write(lastText)
                w.newLine()
                w.newLine()
                cueIndex++
            } catch (t: Throwable) {
                HudLogger.w(TAG, "Error writing subtitle cue: ${t.message}")
            }
            lastStartMs = -1L
            lastText = ""
        }
    }

    @Synchronized
    fun close(finalDurationMs: Long = -1L) {
        val w = writer ?: return
        try {
            if (finalDurationMs > 0 && lastStartMs >= 0) {
                flushPrevious(finalDurationMs)
            }
            w.flush()
            w.close()
        } catch (t: Throwable) {
            HudLogger.w(TAG, "Error closing SRT file: ${t.message}")
        } finally {
            writer = null
        }
    }

    private fun formatTimestamp(ms: Long): String {
        val totalSec = ms / 1000
        val millis = ms % 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }
}
