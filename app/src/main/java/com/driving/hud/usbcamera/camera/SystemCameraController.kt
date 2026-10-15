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
 * Camera controller for the built-in system camera (rear/front) using Android Camera2 API.
 * Provides live preview, NV21 conversion for ONNX detection, and dashcam recording.
 */
class SystemCameraController(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener
) {

    companion object {
        private const val TAG = "SystemCameraCtrl"
        private const val DEFAULT_WIDTH = 1280
        private const val DEFAULT_HEIGHT = 720
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
    private var outputSurface: Surface? = null

    var isFacingBack = true
        private set

    private var frameW = DEFAULT_WIDTH
    private var frameH = DEFAULT_HEIGHT

    private var nv21Buffer = ByteArray(0)
    private var detector: Detector? = null
    private var rec: MkvRecorder? = null

    private val busy = AtomicBoolean(false)
    private var lastInferAt = 0L
    private val detectIntervalMs = 500L
    private val framesInWindow = AtomicInteger(0)

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

    fun setOutputSurface(surface: Surface?) {
        this.outputSurface = surface
        if (isOpen && cameraDevice != null) {
            startPreviewSession()
        }
    }

    @SuppressLint("MissingPermission")
    fun open(facingBack: Boolean = true) {
        if (isOpen) close()
        isFacingBack = facingBack
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

            // Pick preview dimensions from preferences
            frameW = HudPrefs.previewWidth(context)
            frameH = HudPrefs.previewHeight(context)

            imageReader = ImageReader.newInstance(frameW, frameH, ImageFormat.YUV_420_888, 2).apply {
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
                    HudLogger.e(TAG, "System camera error: $error")
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

        val surfaces = mutableListOf<Surface>(reader.surface)
        outputSurface?.let { if (it.isValid) surfaces.add(it) }

        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(reader.surface)
                            outputSurface?.let { if (it.isValid) addTarget(it) }
                            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        }
                        session.setRepeatingRequest(requestBuilder.build(), null, handler)
                        HudLogger.i(TAG, "Camera capture session repeating request started")
                    } catch (t: Throwable) {
                        HudLogger.e(TAG, "Failed to start capture request: ${t.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    HudLogger.e(TAG, "Failed to configure camera capture session")
                }
            }, handler)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "createCaptureSession failed: ${t.message}")
        }
    }

    private fun onFrameAvailable(reader: ImageReader) {
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        } ?: return

        framesInWindow.incrementAndGet()

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
            if (det != null && now - lastInferAt >= detectIntervalMs && busy.compareAndSet(false, true)) {
                lastInferAt = now
                val copy = nv21Buffer.clone()
                val w = frameW
                val h = frameH
                val conf = HudPrefs.confidence(context)
                inferExecutor.execute {
                    try {
                        val dets = det.detect(copy, w, h, conf)
                        listener.onDetections(dets)
                    } catch (t: Throwable) {
                        HudLogger.w(TAG, "Detection error: ${t.message}")
                    } finally {
                        busy.set(false)
                    }
                }
            }
        } finally {
            image.close()
        }
    }

    /** Converts android.media.Image (YUV_420_888) to NV21 format. */
    private fun yuv420ToNv21(image: Image, outNv21: ByteArray, width: Int, height: Int) {
        val planes = image.planes
        val yPlane = planes[0].buffer
        val uPlane = planes[1].buffer
        val vPlane = planes[2].buffer

        val ySize = width * height
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride

        var pos = 0
        if (yRowStride == width && yPixelStride == 1) {
            yPlane.get(outNv21, 0, ySize)
            pos = ySize
        } else {
            for (row in 0 until height) {
                yPlane.position(row * yRowStride)
                yPlane.get(outNv21, pos, width)
                pos += width
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

        for (row in 0 until halfH) {
            for (col in 0 until halfW) {
                val vIdx = row * vRowStride + col * vPixelStride
                val uIdx = row * uRowStride + col * uPixelStride
                outNv21[pos++] = if (vIdx < vLimit) vPlane.get(vIdx) else 128.toByte()
                outNv21[pos++] = if (uIdx < uLimit) uPlane.get(uIdx) else 128.toByte()
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
        try {
            captureSession?.close()
        } catch (_: Throwable) {}
        captureSession = null

        try {
            cameraDevice?.close()
        } catch (_: Throwable) {}
        cameraDevice = null

        try {
            imageReader?.close()
        } catch (_: Throwable) {}
        imageReader = null

        try {
            rec?.stop()
        } catch (_: Throwable) {}
        rec = null

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
