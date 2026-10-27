package com.driving.hud.usbcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import java.util.concurrent.atomic.AtomicInteger

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

    /** Lane passes run on their own thread; see [LaneAnalyzer]. */
    private val lanes = LaneAnalyzer(context, listener, tag)

    /**
     * Mirror every frame before it is drawn, detected or recorded.
     *
     * The U2 DVR's camera is mounted the other way round in the car, so its JPEGs arrive
     * left-to-right reversed. Flipping here instead of on the SurfaceView keeps the HUD, the
     * recording and the detection boxes showing the same, corrected picture.
     */
    @Volatile
    private var mirror = false

    /** Reused destination for [flipHorizontally]: one 720p allocation instead of one per frame. */
    private var mirrorScratch: Bitmap? = null
    private var mirrorCanvas: Canvas? = null

    /** Built on the inference thread and read from the frame thread: must stay volatile. */
    @Volatile
    private var detector: Detector? = null

    private var rec: MkvRecorder? = null

    /**
     * Bumped when the sink is torn down. Building a detector means measuring every backend,
     * which takes seconds; without this a build that finishes after the source was closed would
     * install a session for a camera that is already gone.
     */
    private val detectorGen = AtomicInteger(0)

    /** True while a detector build is in flight, so it is never started twice. */
    @Volatile
    private var detectorBuilding = false

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

    /** See [mirror]: applied to every decoded frame, so the recording flips with the HUD. */
    fun setMirror(on: Boolean) {
        mirror = on
        HudLogger.i(tag, "setMirror: $on")
    }

    fun setFrameSize(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            frameW = width
            frameH = height
        }
    }

    /** Reads detection prefs and starts building the detector for [width]x[height]. */
    fun prepare(width: Int = frameW, height: Int = frameH) {
        detectIntervalMs = HudPrefs.detectIntervalMs(context).toLong().coerceAtLeast(0L)
        setFrameSize(width, height)
        // A new session must not inherit lanes smoothed from the previous camera.
        lanes.reset()
        initDetector()
    }

    /**
     * Builds the detector on the inference thread, never inline.
     *
     * This is called from `open()`, which MainActivity runs on the UI thread, and a first build
     * is a full backend shoot-out: it loads the ncnn graph (4.5 MB) **and** the ONNX model
     * (9 MB), times every candidate and then rebuilds the winner. Measured on the tablet that is
     * ~14 s of main-thread work with several native threads saturating every core, i.e. an ANR
     * waiting for the first tap. The frame path treats a null detector as "no boxes yet".
     */
    private fun initDetector() {
        // Same rule as the other sources: with detection switched off in Settings there is no
        // point paying for the shoot-out or for a native session at all.
        if (!HudPrefs.detect(context)) {
            HudLogger.i(tag, "detection disabled in settings — running preview only")
            return
        }
        if (detector != null || detectorBuilding) return

        detectorBuilding = true
        val gen = detectorGen.incrementAndGet()
        val w = frameW
        val h = frameH
        val asset = HudPrefs.modelAsset(context)
        // Reuse the backend measured for this model + frame size. Passing null here (as this
        // path used to) re-ran the whole shoot-out on every single connect, so the model was
        // loaded again and again for a result that never changes.
        val cacheKey = "$asset@${w}x$h"
        val cached = HudPrefs.detectProviderFor(context, cacheKey).takeIf { it.isNotBlank() }
        if (cached != null) {
            HudLogger.i(tag, "reusing detector backend '$cached' for $cacheKey (skipping benchmark)")
        }
        inferExecutor.execute {
            try {
                val built = try {
                    DetectorFactory.create(context, asset, w, h, cached)
                } catch (t: Throwable) {
                    HudLogger.w(tag, "Detector init failed: ${t.message}")
                    null
                }
                if (gen != detectorGen.get()) {
                    HudLogger.i(tag, "discarding detector - source was closed during setup")
                    try {
                        built?.close()
                    } catch (_: Throwable) {}
                    return@execute
                }
                detector = built
                if (built != null && cached == null) {
                    val winner = DetectorFactory.activeBackend
                    HudPrefs.setDetectProviderFor(context, cacheKey, winner)
                    HudLogger.i(tag, "remembered detector backend '$winner' for $cacheKey")
                }
            } finally {
                detectorBuilding = false
            }
        }
    }

    /** Decodes [jpeg] and pushes it through render -> record -> detect. */
    fun handleJpegFrame(jpeg: ByteArray) {
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return
        // A mirror copy lives in [mirrorScratch] and outlives this frame; only the decode is ours.
        val bmp = if (mirror) flipHorizontally(decoded) ?: decoded else decoded
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

        // Only convert to NV21 when recording, a detection pass is due, or a lane pass is due.
        val recorder = rec
        val isRecActive = recorder != null && recorder.isRecording
        val det = detector
        val now = System.currentTimeMillis()
        // Re-read the switch per frame so turning detection off in Settings actually stops the
        // inference instead of leaving it running at full rate on every JPEG source.
        val isDetDue = det != null && HudPrefs.detect(context) &&
            (detectIntervalMs <= 0 || now - lastInferAt >= detectIntervalMs) && !busy.get()
        val isLaneDue = lanes.isDue()

        if (isRecActive || isDetDue || isLaneDue) {
            val nv21 = getOrFillNv21(bmp, w, h)
            val need = w * h * 3 / 2

            if (isRecActive && recorder != null) {
                recorder.submit(ByteBuffer.wrap(nv21), need)
            }

            // The frame has already been mirrored above when the driver has the flip on, so the
            // lane result needs no extra flip to match the picture.
            if (isLaneDue) lanes.submit(nv21, w, h)
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

        decoded.recycle()
    }

    /** Returns a horizontally flipped copy of [src], or null when the copy failed. */
    private fun flipHorizontally(src: Bitmap): Bitmap? = try {
        val existing = mirrorScratch
        val dst = if (existing != null && existing.width == src.width && existing.height == src.height) {
            existing
        } else {
            existing?.recycle()
            Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888).also {
                mirrorScratch = it
                mirrorCanvas = Canvas(it)
            }
        }
        val canvas = mirrorCanvas ?: Canvas(dst).also { mirrorCanvas = it }
        canvas.save()
        canvas.scale(-1f, 1f, src.width / 2f, src.height / 2f)
        canvas.drawBitmap(src, 0f, 0f, null)
        canvas.restore()
        dst
    } catch (t: Throwable) {
        HudLogger.w(tag, "Mirror failed: ${t.message}")
        null
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
        lanes.destroy()
        // Same rule as reloadDetector: the session is released behind the last queued pass, and
        // `shutdown()` still runs it because the executor drains its queue first.
        closeDetectorLater()
        mirrorScratch?.recycle()
        mirrorScratch = null
        mirrorCanvas = null
        inferExecutor.shutdown()
    }

    /**
     * Swaps in a different model without reopening the source. See
     * [SystemCameraController.reloadDetector] for why the close is queued rather than inline.
     */
    fun reloadDetector() {
        closeDetectorLater()
        initDetector()
    }

    /** Drops the current session, releasing it on the inference thread (see [reloadDetector]). */
    private fun closeDetectorLater() {
        val old = detector
        detector = null
        detectorGen.incrementAndGet()
        if (old == null) return
        try {
            inferExecutor.execute { runCatching { old.close() } }
        } catch (t: Throwable) {
            HudLogger.w(tag, "could not queue detector close: ${t.message}")
        }
    }
}
