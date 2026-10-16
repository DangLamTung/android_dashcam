package com.driving.hud.usbcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.view.Surface
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.detect.Detector
import com.driving.hud.usbcamera.detect.DetectorFactory
import com.driving.hud.usbcamera.record.MkvRecorder
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The JPEG-frame half of a camera source: decode -> draw to the HUD surface ->
 * convert to NV21 for the recorder and the detector, plus the once-a-second
 * telemetry readout.
 *
 * Shared by the sources that deliver whole JPEG frames ([WifiCameraController] for
 * HTTP MJPEG / RTSP and [DvrUsbCameraController] for the U2 DVR's BOT stream), so
 * both go through exactly the same rendering, detection and recording path.
 */
class MjpegFrameSink(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener,
    private val tag: String,
) {
    private companion object {
        const val DEFAULT_WIDTH = 1280
        const val DEFAULT_HEIGHT = 720
    }

    private var outputSurface: Surface? = null
    private var detector: Detector? = null
    private var rec: MkvRecorder? = null

    private var frameW = DEFAULT_WIDTH
    private var frameH = DEFAULT_HEIGHT

    private val inferExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "$tag-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }
    private val busy = AtomicBoolean(false)
    private var lastInferAt = 0L
    private var detectIntervalMs = 500L

    private var lastFpsCalcNs = 0L
    private var framesCounted = 0
    private var currentFps = 0
    private var frameIntervalMs = 0f
    private var lastFrameNs = 0L
    private var lastInferMs = -1f

    private var argbScratch = IntArray(0)
    private var nv21Scratch = ByteArray(0)

    /** Frames delivered since [resetFrameStats]; used for the HUD's frame counter. */
    var framesReceived = 0
        private set

    fun setOutputSurface(surface: Surface?) {
        outputSurface = surface
    }

    fun setFrameSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            frameW = width
            frameH = height
        }
    }

    /** Reads detection prefs and builds the detector for [width]x[height]. */
    fun prepare(width: Int = frameW, height: Int = frameH) {
        detectIntervalMs = HudPrefs.detectIntervalMs(context).toLong().coerceAtLeast(0L)
        setFrameSize(width, height)
        initDetector()
    }

    private fun initDetector() {
        if (detector == null) {
            try {
                detector = DetectorFactory.create(context, HudPrefs.modelAsset(context), frameW, frameH, null)
            } catch (t: Throwable) {
                HudLogger.w(tag, "Detector init failed: ${t.message}")
            }
        }
    }

    /** Decodes [jpeg] and pushes it through render -> record -> detect. */
    fun handleJpegFrame(jpeg: ByteArray) {
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        val w = bmp.width
        val h = bmp.height
        frameW = w
        frameH = h
        framesReceived++

        val nowNs = System.nanoTime()
        val prevNs = lastFrameNs
        lastFrameNs = nowNs
        if (prevNs != 0L) {
            val deltaMs = (nowNs - prevNs) / 1_000_000f
            frameIntervalMs = if (frameIntervalMs <= 0f) deltaMs else 0.8f * frameIntervalMs + 0.2f * deltaMs
        }
        framesCounted++

        if (lastFpsCalcNs == 0L) {
            lastFpsCalcNs = nowNs
        } else if (nowNs - lastFpsCalcNs >= 1_000_000_000L) {
            val elapsedSec = (nowNs - lastFpsCalcNs) / 1_000_000_000.0
            currentFps = (framesCounted / elapsedSec).toInt().coerceAtLeast(0)
            framesCounted = 0
            lastFpsCalcNs = nowNs
            listener.onTelemetry(
                Telemetry(
                    fps = currentFps,
                    frameIntervalMs = frameIntervalMs,
                    cpuPercent = 0,
                    tempC = -1,
                    inferMs = lastInferMs,
                    provider = HudPrefs.detectProvider(context).ifEmpty { "NCNN" },
                ),
            )
        }

        renderToSurface(bmp, w, h)

        // Only convert to NV21 when recording or a detection pass is due.
        val recorder = rec
        val isRecActive = recorder != null && recorder.isRecording
        val det = detector
        val now = System.currentTimeMillis()
        val isDetDue = det != null && (detectIntervalMs <= 0 || now - lastInferAt >= detectIntervalMs) && !busy.get()

        if (isRecActive || isDetDue) {
            val nv21 = getOrFillNv21(bmp, w, h)
            val need = w * h * 3 / 2

            if (isRecActive && recorder != null) {
                recorder.submit(ByteBuffer.wrap(nv21), need)
            }

            if (isDetDue && busy.compareAndSet(false, true)) {
                lastInferAt = now
                val copy = nv21.clone()
                val conf = HudPrefs.confidence(context)
                inferExecutor.execute {
                    try {
                        val t0 = System.nanoTime()
                        val dets = det?.detect(copy, w, h, conf).orEmpty()
                        lastInferMs = (System.nanoTime() - t0) / 1_000_000f
                        listener.onDetections(dets)
                    } catch (t: Throwable) {
                        HudLogger.w(tag, "Inference error: ${t.message}")
                    } finally {
                        busy.set(false)
                    }
                }
            }
        }

        bmp.recycle()
    }

    private fun renderToSurface(bmp: Bitmap, w: Int, h: Int) {
        val surface = outputSurface ?: return
        if (!surface.isValid) return
        try {
            val canvas = surface.lockCanvas(null) ?: return
            canvas.drawBitmap(bmp, Rect(0, 0, w, h), Rect(0, 0, canvas.width, canvas.height), null)
            surface.unlockCanvasAndPost(canvas)
        } catch (t: Throwable) {
            HudLogger.w(tag, "Canvas draw error: ${t.message}")
        }
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

                val y = (66 * r + 129 * g + 25 * b + 128 shr 8) + 16
                val u = (-38 * r - 74 * g + 112 * b + 128 shr 8) + 128
                val v = (112 * r - 94 * g - 18 * b + 128 shr 8) + 128

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
        // Frames come from a JPEG decode through [encodeYUV420SP], i.e. NV21: the recorder has to
        // swap the chroma planes or the recording comes out with red and blue exchanged.
        val file = r.start(frameW, frameH, HudPrefs.targetFps(context), sourceIsNv21 = true) ?: return null
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

    /** Stops any recording; the detector stays warm for the next source. */
    fun close() {
        try {
            rec?.stop()
        } catch (_: Throwable) {
        }
        rec = null
        outputSurface = null
    }

    fun destroy() {
        close()
        try {
            detector?.close()
        } catch (_: Throwable) {
        }
        detector = null
        inferExecutor.shutdown()
    }
}
