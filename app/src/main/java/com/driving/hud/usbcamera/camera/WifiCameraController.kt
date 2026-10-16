package com.driving.hud.usbcamera.camera

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Camera controller for WiFi / IP network cameras.
 * Supports:
 * - RTSP streams (e.g. rtsp://192.168.1.100:554/live) via hardware MediaPlayer.
 * - HTTP MJPEG streams (e.g. http://192.168.1.100:8080/video) via network reader + canvas renderer.
 */
class WifiCameraController(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener
) {

    companion object {
        private const val TAG = "WifiCameraCtrl"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
    }

    private var mediaPlayer: MediaPlayer? = null
    private var streamThread: Thread? = null
    @Volatile
    private var streamRunning = false

    private var outputSurface: Surface? = null

    /**
     * Decode -> draw -> detect/record/telemetry, shared with [DvrUsbCameraController]
     * so both JPEG sources behave identically.
     */
    private val sink = MjpegFrameSink(context, listener, TAG)

    @Volatile
    var isOpen = false
        private set

    fun setOutputSurface(surface: Surface?) {
        this.outputSurface = surface
        sink.setOutputSurface(surface)
        val mp = mediaPlayer
        if (mp != null) {
            try {
                mp.setSurface(surface)
            } catch (t: Throwable) {
                HudLogger.w(TAG, "Failed to set surface on MediaPlayer: ${t.message}")
            }
        }
    }

    fun open(url: String) {
        if (isOpen) close()
        sink.prepare()

        HudLogger.i(TAG, "Opening WiFi camera: $url")
        val cleanUrl = url.trim()

        if (cleanUrl.startsWith("rtsp://", ignoreCase = true)) {
            startRtsp(cleanUrl)
        } else {
            startHttpMjpeg(cleanUrl)
        }
    }

    private fun startRtsp(url: String) {
        try {
            val mp = MediaPlayer().apply {
                setDataSource(context, Uri.parse(url))
                outputSurface?.let { if (it.isValid) setSurface(it) }
                setOnPreparedListener { player ->
                    HudLogger.i(TAG, "RTSP stream prepared, starting playback")
                    sink.setFrameSize(player.videoWidth, player.videoHeight)
                    player.start()
                    isOpen = true
                    listener.onStatusChanged("WiFi Camera (RTSP): Streaming")
                }
                setOnVideoSizeChangedListener { _, width, height -> sink.setFrameSize(width, height) }
                setOnErrorListener { _, what, extra ->
                    HudLogger.e(TAG, "RTSP MediaPlayer error: what=$what extra=$extra")
                    listener.onError("RTSP stream error ($what, $extra)")
                    close()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = mp
        } catch (t: Throwable) {
            HudLogger.e(TAG, "Failed to open RTSP stream: ${t.message}", t)
            listener.onError("Cannot open RTSP: ${t.message}")
        }
    }

    private fun startHttpMjpeg(url: String) {
        streamRunning = true
        streamThread = Thread({
            var conn: HttpURLConnection? = null
            var stream: BufferedInputStream? = null
            try {
                val httpUrl = URL(url)
                conn = (httpUrl.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 5000
                    readTimeout = 8000
                    requestMethod = "GET"
                    setRequestProperty("Connection", "Keep-Alive")
                    connect()
                }

                stream = BufferedInputStream(conn.inputStream)
                isOpen = true
                listener.onStatusChanged("WiFi Camera (MJPEG): Streaming")
                HudLogger.i(TAG, "HTTP MJPEG stream connected")

                val buffer = ByteArray(1024 * 64)
                val frameStream = ByteArrayOutputStream()

                var inFrame = false
                var prev = 0

                while (streamRunning) {
                    val count = stream.read(buffer)
                    if (count < 0) break

                    for (i in 0 until count) {
                        val b = buffer[i].toInt() and 0xFF
                        if (!inFrame) {
                            if (prev == 0xFF && b == 0xD8) {
                                inFrame = true
                                frameStream.reset()
                                frameStream.write(0xFF)
                                frameStream.write(0xD8)
                            }
                        } else {
                            frameStream.write(b)
                            if (prev == 0xFF && b == 0xD9) {
                                inFrame = false
                                val jpegBytes = frameStream.toByteArray()
                                handleJpegFrame(jpegBytes)
                            }
                        }
                        prev = b
                    }
                }
            } catch (t: Throwable) {
                if (streamRunning) {
                    HudLogger.e(TAG, "MJPEG stream error: ${t.message}")
                    listener.onError("Stream error: ${t.message}")
                }
            } finally {
                try { stream?.close() } catch (_: Throwable) {}
                try { conn?.disconnect() } catch (_: Throwable) {}
                close()
            }
        }, "wifi-mjpeg-stream").apply {
            isDaemon = true
            start()
        }
    }

    /** Frame handling below is shared with the DVR source via [MjpegFrameSink]. */
    private fun handleJpegFrame(jpeg: ByteArray) = sink.handleJpegFrame(jpeg)

    fun startRecording(): AusbcCameraController.RecordingStartInfo? = sink.startRecording()

    fun stopRecording(): RecordingStorageManager.RecordingResult? = sink.stopRecording()

    fun isRecording(): Boolean = sink.isRecording()

    fun updateRecordingDetections(detections: List<Detection>) = sink.updateRecordingDetections(detections)

    fun frameSize(): Pair<Int, Int> = sink.frameSize()

    fun close() {
        isOpen = false
        streamRunning = false
        try { streamThread?.interrupt() } catch (_: Throwable) {}
        streamThread = null

        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Throwable) {}
        mediaPlayer = null

        sink.close()

        HudLogger.i(TAG, "WiFi camera closed")
    }

    fun destroy() {
        close()
        sink.destroy()
    }
}
