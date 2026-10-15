package com.driving.hud.usbcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.detect.Detector
import com.driving.hud.usbcamera.detect.DetectorFactory
import com.driving.hud.usbcamera.record.MkvRecorder
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private var detector: Detector? = null
    private var rec: MkvRecorder? = null

    private var frameW = DEFAULT_WIDTH
    private var frameH = DEFAULT_HEIGHT

    private val inferExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "wifi-cam-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val busy = AtomicBoolean(false)
    private var lastInferAt = 0L
    private var detectIntervalMs = 500L

    @Volatile
    var isOpen = false
        private set

    fun setOutputSurface(surface: Surface?) {
        this.outputSurface = surface
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
        detectIntervalMs = HudPrefs.detectIntervalMs(context).toLong().coerceAtLeast(0L)
        initDetector()

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
                    val vw = player.videoWidth
                    val vh = player.videoHeight
                    if (vw > 0 && vh > 0) {
                        frameW = vw
                        frameH = vh
                    }
                    player.start()
                    isOpen = true
                    listener.onStatusChanged("WiFi Camera (RTSP): Streaming")
                }
                setOnVideoSizeChangedListener { _, width, height ->
                    if (width > 0 && height > 0) {
                        frameW = width
                        frameH = height
                    }
                }
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

    private var argbScratch = IntArray(0)
    private var nv21Scratch = ByteArray(0)

    private fun handleJpegFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val w = bmp.width
        val h = bmp.height
        frameW = w
        frameH = h

        // 1. Render to outputSurface via Canvas
        val surface = outputSurface
        if (surface != null && surface.isValid) {
            try {
                val canvas = surface.lockCanvas(null)
                if (canvas != null) {
                    val srcRect = Rect(0, 0, w, h)
                    val dstRect = Rect(0, 0, canvas.width, canvas.height)
                    canvas.drawBitmap(bmp, srcRect, dstRect, null)
                    surface.unlockCanvasAndPost(canvas)
                }
            } catch (t: Throwable) {
                HudLogger.w(TAG, "Canvas draw error: ${t.message}")
            }
        }

        // 2. Only convert to NV21 if recording or detection is active
        val recorder = rec
        val isRecActive = (recorder != null && recorder.isRecording)
        val det = detector
        val now = System.currentTimeMillis()
        val isDetDue = (det != null && (detectIntervalMs <= 0 || now - lastInferAt >= detectIntervalMs) && !busy.get())

        if (isRecActive || isDetDue) {
            val need = w * h * 3 / 2
            val nv21 = getOrFillNv21(bmp, w, h)

            if (isRecActive && recorder != null) {
                recorder.submit(ByteBuffer.wrap(nv21), need)
            }

            if (isDetDue && busy.compareAndSet(false, true)) {
                lastInferAt = now
                val copy = nv21.clone()
                val conf = HudPrefs.confidence(context)
                inferExecutor.execute {
                    try {
                        val dets = det?.detect(copy, w, h, conf).orEmpty()
                        listener.onDetections(dets)
                    } catch (t: Throwable) {
                        HudLogger.w(TAG, "Inference error: ${t.message}")
                    } finally {
                        busy.set(false)
                    }
                }
            }
        }

        bmp.recycle()
    }

    private fun getOrFillNv21(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val pixelCount = width * height
        if (argbScratch.size != pixelCount) {
            argbScratch = IntArray(pixelCount)
        }
        val need = pixelCount * 3 / 2
        if (nv21Scratch.size != need) {
            nv21Scratch = ByteArray(need)
        }
        bitmap.getPixels(argbScratch, 0, width, 0, 0, width, height)
        encodeYUV420SP(nv21Scratch, argbScratch, width, height)
        return nv21Scratch
    }

    private fun encodeYUV420SP(yuv420sp: ByteArray, argb: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var yIndex = 0
        var uvIndex = frameSize
        var index = 0

        for (j in 0 until height) {
            for (i in 0 until width) {
                val c = argb[index++]
                val r = (c shr 16) and 0xff
                val g = (c shr 8) and 0xff
                val b = c and 0xff

                var y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                var u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                var v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

                yuv420sp[yIndex++] = y.coerceIn(0, 255).toByte()
                if (j % 2 == 0 && i % 2 == 0) {
                    yuv420sp[uvIndex++] = v.coerceIn(0, 255).toByte()
                    yuv420sp[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }
        }
    }

    fun startRecording(): AusbcCameraController.RecordingStartInfo? {
        if (rec?.isRecording == true) return null
        val r = MkvRecorder(context)
        val file = r.start(frameW, frameH, HudPrefs.targetFps(context)) ?: return null
        rec = r
        return AusbcCameraController.RecordingStartInfo(file.absolutePath, r.isSdCard)
    }

    fun stopRecording(): RecordingStorageManager.RecordingResult? {
        val r = rec ?: return null
        val file = r.stop()
        val result = r.lastRecordingResult ?: file?.let {
            RecordingStorageManager.publishRecording(context, it, r.isSdCard)
        }
        rec = null
        return result
    }

    fun isRecording(): Boolean = rec?.isRecording == true

    fun updateRecordingDetections(detections: List<Detection>) {
        rec?.updateDetections(detections)
    }

    fun frameSize(): Pair<Int, Int> = frameW to frameH

    private fun initDetector() {
        if (detector == null) {
            try {
                detector = DetectorFactory.create(context, HudPrefs.modelAsset(context), frameW, frameH, null)
            } catch (t: Throwable) {
                HudLogger.w(TAG, "Detector init failed: ${t.message}")
            }
        }
    }

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

        try { rec?.stop() } catch (_: Throwable) {}
        rec = null

        HudLogger.i(TAG, "WiFi camera closed")
    }

    fun destroy() {
        close()
        try { detector?.close() } catch (_: Throwable) {}
        detector = null
        inferExecutor.shutdown()
    }
}
