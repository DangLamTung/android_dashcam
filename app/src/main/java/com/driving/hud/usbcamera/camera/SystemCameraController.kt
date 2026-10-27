package com.driving.hud.usbcamera.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager as AndroidCameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
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
 * Camera controller for the built-in system camera (rear/front) using Android Camera2 API.
 * Provides live preview, resolution negotiation from device characteristics, NV21 conversion
 * for ONNX/ncnn detection, FPS telemetry, and dashcam recording.
 *
 * **Orientation.** The preview is a `SurfaceView` fed the *same* raw sensor buffer as the
 * `ImageReader` — no `TextureView` transform, no `setDisplayOrientation`. That is deliberate:
 * preview and analysis therefore share one orientation, so boxes and lane lines cannot drift from
 * the picture. The trade-off is that the picture is not rotated for the display, so in portrait it
 * appears sideways. The HUD is used in landscape, which is why that is acceptable; real portrait
 * support would have to rotate both streams, not just the analysis one.
 */
class SystemCameraController(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener
) {

    companion object {
        private const val TAG = "SystemCameraCtrl"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720

        /** Detection passes to wait before writing the diagnostic dump. */
        private const val DIAG_DUMP_AFTER_PASSES = 3

        /**
         * Selects the closest supported size from the camera's available sizes.
         */
        internal fun chooseOptimalDimension(
            primaryChoices: List<Pair<Int, Int>>?,
            fallbackChoices: List<Pair<Int, Int>>?,
            targetW: Int,
            targetH: Int
        ): Pair<Int, Int> {
            val list = when {
                !primaryChoices.isNullOrEmpty() -> primaryChoices
                !fallbackChoices.isNullOrEmpty() -> fallbackChoices
                else -> return targetW to targetH
            }
            // 1. Exact match
            list.firstOrNull { it.first == targetW && it.second == targetH }?.let { return it }

            // 2. Same aspect ratio (within 5%), closest area
            val targetAspect = targetW.toDouble() / targetH
            val sameAspect = list.filter {
                Math.abs((it.first.toDouble() / it.second) - targetAspect) < 0.05
            }
            if (sameAspect.isNotEmpty()) {
                return sameAspect.minByOrNull {
                    Math.abs(it.first * it.second - targetW * targetH)
                }!!
            }

            // 3. Closest area
            return list.minByOrNull {
                Math.abs(it.first * it.second - targetW * targetH)
            }!!
        }

        /**
         * Sizes that BOTH the preview (SurfaceHolder) and the analysis (YUV_420_888) streams can
         * produce, or null when either capability list is unavailable.
         *
         * The two lists are independent, and the preview list is normally the longer one: a
         * device can preview 1920x1080 while its only YUV_420_888 sizes stop at 1280x720. Sizing
         * the ImageReader from the preview list alone asks for an analysis surface the device
         * cannot produce, the combined session fails to configure, and the video never appears
         * even though the UI reports "Streaming".
         */
        internal fun commonOutputSizes(
            previewSizes: List<Pair<Int, Int>>?,
            analysisSizes: List<Pair<Int, Int>>?
        ): List<Pair<Int, Int>>? {
            if (previewSizes.isNullOrEmpty() || analysisSizes.isNullOrEmpty()) return null
            return previewSizes.filter { it in analysisSizes }
        }
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val inferExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "system-cam-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    /**
     * Bumped every time a session is requested. Creating a session invalidates the previous one,
     * but the previous session's `onConfigured` can still be delivered afterwards — and calling
     * `setRepeatingRequest` on it then throws
     * "Session has been closed; further changes are illegal". Confirmed on the tablet: entering
     * the HUD produced two such exceptions per run. Callbacks from a superseded generation are
     * discarded instead.
     */
    private var sessionGen = 0

    private var imageReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null

    private var outputSurface: Surface? = null
    private var outputHolder: SurfaceHolder? = null

    /** Buffer size last handed to [SurfaceHolder.setFixedSize]; 0 means "not applied yet". */
    private var fixedBufferW = 0
    private var fixedBufferH = 0

    var isFacingBack = true
        private set

    private var frameW = DEFAULT_WIDTH
    private var frameH = DEFAULT_HEIGHT

    private var nv21Buffer = ByteArray(0)

    /** One-shot geometry dump per session; see [logFrameGeometry]. */
    private var frameGeometryLogged = false

    /** The line [logGeometry] produced, embedded in the on-device diagnostic dump. */
    private var geometryLine: String = ""

    /**
     * Diagnostic: dump the analysis frame and its boxes to `files/diag/` so a mismatch can be
     * *seen* instead of inferred. Armed for the third detection pass of a session, which is late
     * enough for the detector to be built and early enough to be during a normal drive.
     */
    @Volatile
    var diagDumpArmed: Boolean = true

    private var diagDumps = 0

    /** Lane passes run on their own thread; see [LaneAnalyzer]. */
    private val lanes = LaneAnalyzer(context, listener, TAG)

    /** Written on the inference thread and read on the camera handler: must stay volatile. */
    @Volatile
    private var detector: Detector? = null

    /**
     * Bumped when the detector is torn down. Picking a backend means timing every candidate on a
     * real frame, which takes seconds; without this a build that finishes after the camera is
     * closed would resurrect a session for a camera that is already gone.
     */
    private val detectorGen = AtomicInteger(0)

    /** True while a detector build is in flight, so it is never started twice. */
    @Volatile
    private var detectorBuilding = false

    private var rec: MkvRecorder? = null

    private val busy = AtomicBoolean(false)
    private var lastInferAt = 0L
    private var detectIntervalMs = 500L
    private val framesInWindow = AtomicInteger(0)

    // FPS calculation & telemetry
    private var lastFpsCalcNs = 0L
    private var framesCounted = 0
    private var currentFps = 0
    private var frameIntervalMs = 0f
    private var lastFrameNs = 0L
    private var lastInferMs = -1f

    @Volatile
    var isOpen = false
        private set

    init {
        startBackgroundThread()
    }

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("SystemCameraBg").apply {
            start()
            cameraHandler = Handler(looper)
        }
    }

    fun setOutputSurface(surface: Surface?, holder: SurfaceHolder? = null) {
        if (holder !== outputHolder) {
            // A different SurfaceHolder has no fixed buffer size yet.
            fixedBufferW = 0
            fixedBufferH = 0
        }
        this.outputSurface = surface
        this.outputHolder = holder
        applyFixedBufferSize(holder)
        cameraHandler?.post {
            if (isOpen && cameraDevice != null) {
                startPreviewSession()
            }
        }
    }

    /**
     * Pins the SurfaceView buffer to the stream size so the preview is not resampled.
     *
     * [SurfaceHolder.setFixedSize] destroys and re-creates the Surface whenever the size really
     * changes, which re-enters the whole surface callback chain (destroy -> create -> changed),
     * and each of those re-runs this class' session setup. Calling it unconditionally on every
     * surface event therefore makes the preview tear itself down and rebuild, so it is only
     * issued when the size differs from what was last applied.
     */
    private fun applyFixedBufferSize(holder: SurfaceHolder?) {
        if (holder == null || frameW <= 0 || frameH <= 0) return
        if (fixedBufferW == frameW && fixedBufferH == frameH) return
        try {
            holder.setFixedSize(frameW, frameH)
            fixedBufferW = frameW
            fixedBufferH = frameH
            HudLogger.i(TAG, "preview buffer fixed at ${frameW}x$frameH")
        } catch (t: Throwable) {
            HudLogger.w(TAG, "setFixedSize(${frameW}x$frameH) failed: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun open(facingBack: Boolean = true) {
        if (isOpen) close()
        isFacingBack = facingBack
        detectIntervalMs = HudPrefs.detectIntervalMs(context).toLong().coerceAtLeast(0L)
        // NOTE: the detector is built below, once the stream size is negotiated — a detector is
        // benchmarked against the frame size it will actually be fed.

        try {
            val targetFacing = if (facingBack) {
                CameraCharacteristics.LENS_FACING_BACK
            } else {
                CameraCharacteristics.LENS_FACING_FRONT
            }

            var cameraIdToOpen: String? = null
            for (id in cameraManager.cameraIdList) {
                val chars = cameraManager.getCameraCharacteristics(id)
                val facing = chars.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    cameraIdToOpen = id
                    break
                }
            }

            if (cameraIdToOpen == null) {
                cameraIdToOpen = cameraManager.cameraIdList.firstOrNull()
            }

            if (cameraIdToOpen == null) {
                listener.onError("No system camera found")
                return
            }

            val chars = cameraManager.getCameraCharacteristics(cameraIdToOpen)
            characteristics = chars
            logGeometry(chars)
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Pick a size that BOTH streams can produce. The preview (SurfaceHolder) and the
            // analysis (YUV_420_888) streams have separate capability lists and the preview list
            // is usually the longer one — a device can preview 1920x1080 while its only
            // YUV_420_888 sizes stop at 1280x720. Choosing from the preview list alone asks the
            // ImageReader for a size the device cannot analyse, the combined session then fails
            // to configure, and the video never appears even though the UI says "Streaming".
            val previewSizes = map?.getOutputSizes(SurfaceHolder::class.java)?.map { it.width to it.height }
            val analysisSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }
            val targetW = HudPrefs.previewWidth(context)
            val targetH = HudPrefs.previewHeight(context)

            val common = commonOutputSizes(previewSizes, analysisSizes)
            if (common != null) {
                HudLogger.i(
                    TAG,
                    "camera output sizes: ${previewSizes?.size ?: 0} preview, " +
                        "${analysisSizes?.size ?: 0} analysis, ${common.size} usable by both"
                )
            }
            val (optW, optH) = if (!common.isNullOrEmpty()) {
                chooseOptimalDimension(common, null, targetW, targetH)
            } else {
                HudLogger.w(
                    TAG,
                    "no size is supported by both the preview and the analysis stream — " +
                        "falling back to the preview list"
                )
                chooseOptimalDimension(previewSizes, analysisSizes, targetW, targetH)
            }
            frameW = optW
            frameH = optH
            HudLogger.i(TAG, "Selected system camera resolution: ${frameW}x$frameH (target ${targetW}x$targetH)")

            applyFixedBufferSize(outputHolder)

            initDetector()

            imageReader = ImageReader.newInstance(frameW, frameH, ImageFormat.YUV_420_888, 3).apply {
                setOnImageAvailableListener({ reader ->
                    onFrameAvailable(reader)
                }, cameraHandler)
            }

            cameraManager.openCamera(cameraIdToOpen, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    isOpen = true
                    HudLogger.i(TAG, "System camera opened: $cameraIdToOpen (${frameW}x$frameH)")
                    val facingName = if (facingBack) "Rear" else "Front"
                    listener.onStatusChanged("System Camera ($facingName): Streaming")
                    startPreviewSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    HudLogger.w(TAG, "System camera disconnected")
                    listener.onDisconnected()
                    close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    HudLogger.e(TAG, "System camera error code: $error")
                    listener.onError("Camera error code: $error")
                    close()
                }
            }, cameraHandler)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "Failed to open system camera: ${t.message}", t)
            listener.onError("Failed to open camera: ${t.message}")
        }
    }

    private fun startPreviewSession() {
        val device = cameraDevice ?: return
        val reader = imageReader ?: return
        val handler = cameraHandler ?: return

        // Supersede whatever is in flight. The new session invalidates the old one, so the old
        // callback must not be allowed to touch it when it eventually arrives.
        val gen = ++sessionGen
        try {
            captureSession?.close()
        } catch (_: Throwable) {}
        captureSession = null

        applyFixedBufferSize(outputHolder)

        val surfaces = mutableListOf<Surface>()
        if (reader.surface.isValid) {
            surfaces.add(reader.surface)
        }
        val out = outputSurface
        if (out != null && out.isValid && out !== reader.surface) {
            surfaces.add(out)
        }

        if (surfaces.isEmpty()) {
            HudLogger.w(TAG, "No valid surfaces to create capture session")
            return
        }

        try {
            createSession(device, surfaces, handler, gen)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "createCaptureSession failed: ${t.message}", t)
        }
    }

    /**
     * Creates a session for exactly [surfaces] and starts the repeating preview request on it.
     * [gen] is the generation this attempt belongs to; a callback from an older generation is
     * ignored (its session has already been invalidated by a newer one).
     */
    private fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        handler: Handler,
        gen: Int
    ) {
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (gen != sessionGen) {
                    HudLogger.i(TAG, "discarding superseded session (gen $gen, current $sessionGen)")
                    try {
                        session.close()
                    } catch (_: Throwable) {}
                    return
                }
                if (cameraDevice == null) return
                captureSession = session
                try {
                    val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        surfaces.forEach { addTarget(it) }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        val afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                        if (afModes?.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) == true) {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                    }
                    session.setRepeatingRequest(requestBuilder.build(), null, handler)
                    HudLogger.i(
                        TAG,
                        "capture session started with ${surfaces.size} surface(s): ${describe(surfaces)}"
                    )
                } catch (t: Throwable) {
                    HudLogger.e(TAG, "Failed to start capture request: ${t.message}", t)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                HudLogger.e(
                    TAG,
                    "capture session rejected for ${surfaces.size} surface(s): ${describe(surfaces)}"
                )
                if (gen != sessionGen) {
                    HudLogger.i(TAG, "ignoring failure of superseded session (gen $gen)")
                    return
                }
                onSessionFailed(surfaces, handler, gen)
            }
        }, handler)
    }

    /**
     * The requested combination of streams was refused.
     *
     * The preview is what the driver is actually looking at, so it is retried on its own: losing
     * detection and recording for this session is far better than a black screen (the previous
     * behaviour retried with the *analysis* surface only, which produced exactly that — no video
     * at all, while the status badge still read "Streaming"). Only when even the reduced session
     * fails is the failure surfaced to the user.
     */
    private fun onSessionFailed(attempted: List<Surface>, handler: Handler, gen: Int) {
        val device = cameraDevice ?: return
        val reader = imageReader
        val preview = outputSurface
        val hasPreview = preview != null && preview.isValid
        val analysisWasAttempted = reader != null && attempted.contains(reader.surface)

        val retry: List<Surface> = when {
            // Drop the analysis stream and keep the picture.
            hasPreview && analysisWasAttempted -> listOf(preview!!)
            // No preview surface yet (HUD hidden): the analysis stream is all there is.
            !hasPreview && reader != null && reader.surface.isValid -> listOf(reader.surface)
            else -> emptyList()
        }

        if (retry.isEmpty()) {
            listener.onError("Camera cannot be configured for this preview size")
            return
        }
        HudLogger.w(
            TAG,
            "retrying with ${describe(retry)} — " +
                if (retry.size == 1 && retry[0] === preview) {
                    "detection and recording are unavailable at this resolution"
                } else {
                    "there is no preview surface yet"
                }
        )
        try {
            createSession(device, retry, handler, gen)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "retry createCaptureSession failed: ${t.message}", t)
            listener.onError("Camera cannot be configured for this preview size")
        }
    }

    /** Names a surface for the log, so a failed session can be read without guessing. */
    private fun describe(surfaces: List<Surface>): String = surfaces.joinToString("+") { s ->
        when {
            s === outputSurface -> "preview"
            imageReader?.surface === s -> "analysis"
            else -> "surface"
        }
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        if (!frameGeometryLogged) {
            frameGeometryLogged = true
            logFrameGeometry(image)
        }

        val nowNs = System.nanoTime()
        val prevNs = lastFrameNs
        lastFrameNs = nowNs
        if (prevNs != 0L) {
            val deltaMs = (nowNs - prevNs) / 1_000_000f
            frameIntervalMs = if (frameIntervalMs <= 0f) deltaMs else 0.8f * frameIntervalMs + 0.2f * deltaMs
        }
        framesCounted++
        framesInWindow.incrementAndGet()

        // Publish live telemetry every second
        if (lastFpsCalcNs == 0L) {
            lastFpsCalcNs = nowNs
        } else if (nowNs - lastFpsCalcNs >= 1_000_000_000L) {
            val elapsedSec = (nowNs - lastFpsCalcNs) / 1_000_000_000.0
            currentFps = (framesCounted / elapsedSec).toInt().coerceAtLeast(0)
            framesCounted = 0
            lastFpsCalcNs = nowNs
            val t = Telemetry(
                fps = currentFps,
                frameIntervalMs = frameIntervalMs,
                cpuPercent = 0,
                tempC = -1,
                inferMs = lastInferMs,
                provider = HudPrefs.detectProvider(context).ifEmpty { "NCNN" }
            )
            listener.onTelemetry(t)
        }

        // The YUV_420_888 -> NV21 copy is the most expensive thing in this callback (a full
        // frame of per-pixel work) and it happens on a camera handler thread, so doing it for
        // every frame starves the ImageReader queue and makes the preview judder. Only the
        // consumers of pixels can justify it: the recorder, a detection pass that is due, and a
        // lane pass that is due.
        val recorder = rec
        val isRecActive = recorder?.isRecording == true
        val det = detector
        val now = System.currentTimeMillis()
        val isDetDue = det != null && HudPrefs.detect(context) &&
            (detectIntervalMs <= 0 || now - lastInferAt >= detectIntervalMs) &&
            !busy.get()
        val lanesDue = lanes.isDue()

        try {
            if (!isRecActive && !isDetDue && !lanesDue) return

            val need = frameW * frameH * 3 / 2
            if (nv21Buffer.size != need) {
                nv21Buffer = ByteArray(need)
            }
            yuv420ToNv21(image, nv21Buffer, frameW, frameH)

            // Feed hardware recorder
            if (isRecActive && recorder != null) {
                val byteBuf = ByteBuffer.wrap(nv21Buffer)
                recorder.submit(byteBuf, need)
            }

            // Lane detection copies the luma out synchronously, so sharing the buffer with the
            // inference pass below is safe.
            if (lanesDue) lanes.submit(nv21Buffer, frameW, frameH)

            // Object detection
            if (isDetDue && det != null && busy.compareAndSet(false, true)) {
                lastInferAt = now
                val copy = nv21Buffer.clone()
                val w = frameW
                val h = frameH
                val conf = HudPrefs.confidence(context)
                inferExecutor.execute {
                    try {
                        val t0 = System.nanoTime()
                        val dets = det.detect(copy, w, h, conf)
                        lastInferMs = (System.nanoTime() - t0) / 1_000_000f
                        maybeDump(copy, w, h, dets)
                        listener.onDetections(dets)
                    } catch (t: Throwable) {
                        HudLogger.w(TAG, "Inference error: ${t.message}")
                    } finally {
                        busy.set(false)
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    /**
     * One-shot dump of the numbers that decide whether a bounding box can land in the right place:
     * what the sensor says its orientation is, what the display is rotated to, the size handed to
     * the preview surface, and the size the analysis buffer really has. A mismatch between any two
     * of them is the difference between "boxes are wrong" and "boxes are fine".
     */
    private fun logGeometry(chars: CameraCharacteristics) {
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION)
        val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT -> "front"
            CameraCharacteristics.LENS_FACING_BACK -> "back"
            else -> "external"
        }
        val rotation = displayRotationDegrees()
        geometryLine = "facing=$facing sensorOrientation=$sensor displayRotation=$rotation " +
            "=> analysis needs ${((sensor ?: 0) - rotation + 360) % 360}deg " +
            "| requested ${frameW}x$frameH | preview surface ${outputSurfaceSize()}"
        HudLogger.i(TAG, "camera geometry: $geometryLine")
    }

    private fun outputSurfaceSize(): String {
        val holder = outputHolder ?: return "n/a"
        return try {
            val f = holder.surfaceFrame
            "${f.width()}x${f.height()}"
        } catch (t: Throwable) {
            "n/a"
        }
    }

    private fun displayRotationDegrees(): Int = try {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        when (wm?.defaultDisplay?.rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
    } catch (t: Throwable) {
        0
    }

    /** Writes one analysis frame plus its boxes, once per session, when [diagDumpArmed]. */
    private fun maybeDump(nv21: ByteArray, w: Int, h: Int, dets: List<Detection>) {
        if (!diagDumpArmed) return
        diagDumps++
        if (diagDumps < DIAG_DUMP_AFTER_PASSES) return
        diagDumpArmed = false
        Camera2Diag.dump(context, nv21, w, h, dets, geometryLine)
    }

    private fun logFrameGeometry(image: Image) {
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        HudLogger.i(
            TAG,
            "analysis image: ${image.width}x${image.height} crop=${image.cropRect} " +
                "Y[rowStride=${y.rowStride} pixelStride=${y.pixelStride} limit=${y.buffer.limit()}] " +
                "U[rowStride=${u.rowStride} pixelStride=${u.pixelStride}] " +
                "V[rowStride=${v.rowStride} pixelStride=${v.pixelStride}] " +
                "| decoder expects ${frameW}x$frameH"
        )
    }

    /** Converts android.media.Image (YUV_420_888) to NV21 format safely with bounds validation. */
    private fun yuv420ToNv21(image: Image, outNv21: ByteArray, width: Int, height: Int) {
        val w = image.width.coerceAtMost(width)
        val h = image.height.coerceAtMost(height)
        val planes = image.planes
        val yPlane = planes[0].buffer
        val uPlane = planes[1].buffer
        val vPlane = planes[2].buffer

        val ySize = width * height
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        if (yRowStride == width && yPixelStride == 1 && yPlane.remaining() >= ySize) {
            yPlane.position(0)
            yPlane.get(outNv21, 0, ySize)
        } else {
            var yPos = 0
            for (row in 0 until h) {
                val rowOffset = (row * yRowStride).coerceAtMost((yPlane.limit() - w).coerceAtLeast(0))
                yPlane.position(rowOffset)
                yPlane.get(outNv21, yPos, w)
                yPos += width
            }
        }

        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val halfW = width / 2
        val halfH = height / 2

        val vLimit = vPlane.limit()
        val uLimit = uPlane.limit()

        var pos = ySize
        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                val vIdx = row * vRowStride + col * vPixelStride
                val uIdx = row * uRowStride + col * uPixelStride
                outNv21[pos++] = if (vIdx in 0 until vLimit) vPlane.get(vIdx) else 128.toByte()
                outNv21[pos++] = if (uIdx in 0 until uLimit) uPlane.get(uIdx) else 128.toByte()
            }
        }
    }

    fun toggleFacing(): Boolean {
        isFacingBack = !isFacingBack
        HudPrefs.setSystemFacing(context, if (isFacingBack) HudPrefs.FACING_BACK else HudPrefs.FACING_FRONT)
        if (isOpen) {
            open(isFacingBack)
        }
        return isFacingBack
    }

    fun startRecording(): AusbcCameraController.RecordingStartInfo? {
        if (rec?.isRecording == true) return null
        val r = MkvRecorder(context)
        // Frames are converted with yuv420ToNv21 below, so the recorder must swap the chroma
        // planes; otherwise red and blue come out exchanged in the recording.
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

    private fun initDetector() {
        // Honour the Settings switch the same way the USB path does: with detection off there is
        // no point paying for a multi-second backend shoot-out or for a detector session.
        if (!HudPrefs.detect(context)) {
            HudLogger.i(TAG, "detection disabled in settings — system camera runs preview only")
            return
        }
        // Already built, or being built: prewarmDetector() may have beaten open() to it, and a
        // second build would bump the generation and make the first one discard itself.
        if (detector != null || detectorBuilding) return

        // Built on the inference thread: the backend shoot-out is a measurement that takes
        // seconds, and this method is called from open(), which MainActivity runs on the UI
        // thread. Measured on the tablet: doing it inline blocked for ~14 s before the camera
        // even opened. The frame path treats a null detector as "not ready yet".
        detectorBuilding = true
        val gen = detectorGen.incrementAndGet()
        val w = frameW
        val h = frameH
        val asset = HudPrefs.modelAsset(context)
        // Picking a backend means timing every candidate on a real frame: measured at ~15 s on
        // this tablet. The USB path remembers the winner per model + frame size; this path used
        // to pass null every time, which re-ran the whole shoot-out on every connect and left
        // the HUD with no boxes at all for those 15 s - the single biggest reason the detector
        // felt laggy. Reuse the remembered backend, and remember the winner when we do pay.
        val cacheKey = "$asset@${w}x$h"
        val cached = HudPrefs.detectProviderFor(context, cacheKey).takeIf { it.isNotBlank() }
        if (cached != null) {
            HudLogger.i(TAG, "reusing detector backend '$cached' for $cacheKey (skipping benchmark)")
        }
        inferExecutor.execute {
            try {
                val built = try {
                    DetectorFactory.create(context, asset, w, h, cached)
                } catch (t: Throwable) {
                    HudLogger.w(TAG, "Could not initialize detector: ${t.message}")
                    null
                }
                if (gen != detectorGen.get()) {
                    HudLogger.i(TAG, "discarding detector - session was closed during setup")
                    try {
                        built?.close()
                    } catch (_: Throwable) {}
                    return@execute
                }
                detector = built
                if (built != null && cached == null) {
                    val winner = DetectorFactory.activeBackend
                    HudPrefs.setDetectProviderFor(context, cacheKey, winner)
                    HudLogger.i(TAG, "remembered detector backend '$winner' for $cacheKey")
                }
            } finally {
                detectorBuilding = false
            }
        }
    }

    /**
     * Builds the detector now instead of at the first connect.
     *
     * Even with the backend remembered, creating the session costs ~7 s of native
     * initialisation (model load plus Vulkan pipeline creation), and until it finishes the HUD
     * shows live video with no boxes at all. Starting it from MainActivity.onCreate overlaps
     * that with the user reading the home screen. Idempotent, and a no-op unless detection is
     * enabled in Settings.
     */
    fun prewarmDetector() {
        if (frameW <= 0 || frameH <= 0) {
            frameW = HudPrefs.previewWidth(context)
            frameH = HudPrefs.previewHeight(context)
        }
        initDetector()
    }

    fun close() {
        isOpen = false
        // Log the geometry again for the next session: the lens or the device rotation may differ.
        frameGeometryLogged = false
        diagDumps = 0
        diagDumpArmed = true
        // Invalidate any session still being configured, so its callback is discarded.
        sessionGen++
        val session = captureSession
        captureSession = null
        try {
            session?.close()
        } catch (_: Throwable) {}

        val device = cameraDevice
        cameraDevice = null
        try {
            device?.close()
        } catch (_: Throwable) {}

        val reader = imageReader
        imageReader = null
        try {
            reader?.close()
        } catch (_: Throwable) {}

        val r = rec
        rec = null
        try {
            r?.stop()
        } catch (_: Throwable) {}

        HudLogger.i(TAG, "System camera closed")
    }

    fun destroy() {
        close()
        // Queued behind the last pass; `shutdown()` below still drains the queue.
        closeDetectorLater()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        inferExecutor.shutdown()
        lanes.destroy()
    }

    /**
     * Swaps in a different model without reopening the camera.
     *
     * The old session is closed **on the inference thread**, behind whatever pass is already
     * running: freeing an ncnn session while `detect()` reads it faults inside native code, and
     * this is reachable from the Settings screen on every model change.
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
            HudLogger.w(TAG, "could not queue detector close: ${t.message}")
        }
    }
}
