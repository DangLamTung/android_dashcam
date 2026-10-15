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
 */
class SystemCameraController(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener
) {

    companion object {
        private const val TAG = "SystemCameraCtrl"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720

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
    }

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as AndroidCameraManager

    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private val inferExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "system-cam-infer").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var characteristics: CameraCharacteristics? = null

    private var outputSurface: Surface? = null
    private var outputHolder: SurfaceHolder? = null

    var isFacingBack = true
        private set

    private var frameW = DEFAULT_WIDTH
    private var frameH = DEFAULT_HEIGHT

    private var nv21Buffer = ByteArray(0)
    private var detector: Detector? = null
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
        this.outputSurface = surface
        this.outputHolder = holder
        holder?.setFixedSize(frameW, frameH)
        cameraHandler?.post {
            if (isOpen && cameraDevice != null) {
                startPreviewSession()
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun open(facingBack: Boolean = true) {
        if (isOpen) close()
        isFacingBack = facingBack
        detectIntervalMs = HudPrefs.detectIntervalMs(context).toLong().coerceAtLeast(0L)
        initDetector()

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
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

            // Pick optimal preview dimensions from supported configurations
            val yuvSizes = map?.getOutputSizes(ImageFormat.YUV_420_888)?.map { it.width to it.height }
            val surfaceSizes = map?.getOutputSizes(SurfaceHolder::class.java)?.map { it.width to it.height }
            val targetW = HudPrefs.previewWidth(context)
            val targetH = HudPrefs.previewHeight(context)

            val (optW, optH) = chooseOptimalDimension(surfaceSizes, yuvSizes, targetW, targetH)
            frameW = optW
            frameH = optH
            HudLogger.i(TAG, "Selected system camera resolution: ${frameW}x$frameH (target ${targetW}x$targetH)")

            outputHolder?.setFixedSize(frameW, frameH)

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

        try {
            captureSession?.close()
            captureSession = null
        } catch (_: Throwable) {}

        outputHolder?.setFixedSize(frameW, frameH)

        val surfaces = mutableListOf<Surface>()
        if (reader.surface.isValid) {
            surfaces.add(reader.surface)
        }
        val out = outputSurface
        val hasPreview = (out != null && out.isValid)
        if (hasPreview && out != null) {
            surfaces.add(out)
        }

        if (surfaces.isEmpty()) {
            HudLogger.w(TAG, "No valid surfaces to create capture session")
            return
        }

        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
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
                        HudLogger.i(TAG, "Camera capture session repeating request started with ${surfaces.size} surface(s)")
                    } catch (t: Throwable) {
                        HudLogger.e(TAG, "Failed to start capture request: ${t.message}", t)
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    HudLogger.e(TAG, "Failed to configure camera capture session")
                    // If combined preview session failed, fallback to reader.surface so detection & recording stay active
                    if (surfaces.size > 1 && reader.surface.isValid) {
                        HudLogger.w(TAG, "Retrying capture session with reader.surface only")
                        try {
                            device.createCaptureSession(listOf(reader.surface), object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(fallbackSession: CameraCaptureSession) {
                                    captureSession = fallbackSession
                                    try {
                                        val req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                            addTarget(reader.surface)
                                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                                        }
                                        fallbackSession.setRepeatingRequest(req.build(), null, handler)
                                        HudLogger.i(TAG, "Fallback session started with reader.surface")
                                    } catch (t: Throwable) {
                                        HudLogger.e(TAG, "Fallback repeating request failed: ${t.message}")
                                    }
                                }

                                override fun onConfigureFailed(s: CameraCaptureSession) {
                                    HudLogger.e(TAG, "Fallback capture session also failed")
                                    listener.onError("Failed to configure camera session")
                                }
                            }, handler)
                        } catch (t: Throwable) {
                            HudLogger.e(TAG, "Fallback createCaptureSession error: ${t.message}")
                        }
                    } else {
                        listener.onError("Failed to configure camera session")
                    }
                }
            }, handler)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "createCaptureSession failed: ${t.message}", t)
        }
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

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

        try {
            val need = frameW * frameH * 3 / 2
            if (nv21Buffer.size != need) {
                nv21Buffer = ByteArray(need)
            }
            yuv420ToNv21(image, nv21Buffer, frameW, frameH)

            // Feed hardware recorder
            val recorder = rec
            if (recorder != null && recorder.isRecording) {
                val byteBuf = ByteBuffer.wrap(nv21Buffer)
                recorder.submit(byteBuf, need)
            }

            // Object detection
            val det = detector
            val now = System.currentTimeMillis()
            val isDue = (detectIntervalMs <= 0 || now - lastInferAt >= detectIntervalMs)
            if (det != null && isDue && busy.compareAndSet(false, true)) {
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
                HudLogger.w(TAG, "Could not initialize detector: ${t.message}")
            }
        }
    }

    fun close() {
        isOpen = false
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
        try {
            detector?.close()
        } catch (_: Throwable) {}
        detector = null
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        inferExecutor.shutdown()
    }
}
