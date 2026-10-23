package com.driving.hud.usbcamera.camera

import android.content.Context
import android.view.Surface
import android.view.SurfaceHolder
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs

/**
 * Unified camera coordinator that switches between:
 * 1. USB Camera (UVC via AusbcCameraController)
 * 2. System Camera (Built-in Rear / Front via SystemCameraController)
 * 3. WiFi Camera (RTSP / HTTP MJPEG via WifiCameraController)
 * 4. U2 DVR dashcam (mass-storage + SCSI Bulk-Only Transport via DvrUsbCameraController)
 */
class CameraCoordinator(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener
) {

    companion object {
        private const val TAG = "CameraCoordinator"
    }

    val usbController = AusbcCameraController(context, listener)
    val systemController = SystemCameraController(context, listener)
    val wifiController = WifiCameraController(context, listener)
    val dvrController = DvrUsbCameraController(context, listener)

    var currentMode: String = HudPrefs.cameraMode(context)
        private set

    private var currentSurface: Surface? = null
    private var currentHolder: SurfaceHolder? = null

    init {
        // Only the UVC source wants the USB engine to react to a device arriving.
        usbController.autoScanOnAttach = currentMode == HudPrefs.MODE_USB
    }

    fun registerReceiver() {
        usbController.registerReceiver()
        // The DVR source needs its own detach signal: the UVC engine ignores devices that are not
        // UVC cameras, so unplugging the DVR would otherwise go unnoticed.
        dvrController.registerReceiver()
    }

    fun unregister() {
        usbController.unregister()
        dvrController.unregister()
    }

    fun setOutputSurface(surface: Surface?, holder: SurfaceHolder? = null) {
        currentSurface = surface
        currentHolder = holder
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.setOutputSurface(surface)
            HudPrefs.MODE_SYSTEM -> systemController.setOutputSurface(surface, holder)
            HudPrefs.MODE_WIFI -> wifiController.setOutputSurface(surface)
            HudPrefs.MODE_DVR -> dvrController.setOutputSurface(surface)
        }
    }

    fun scanAndConnect(forcePermission: Boolean = false) {
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.scanAndConnect(forcePermission)
            HudPrefs.MODE_SYSTEM -> {
                val isBack = HudPrefs.systemFacing(context) == HudPrefs.FACING_BACK
                systemController.open(isBack)
            }
            HudPrefs.MODE_WIFI -> {
                val url = HudPrefs.wifiUrl(context)
                wifiController.open(url)
            }
            HudPrefs.MODE_DVR -> dvrController.open(forcePermission)
        }
    }

    /**
     * Switches the active camera mode (USB, SYSTEM, WIFI or DVR).
     * Stops the previous camera, saves the setting, and starts the new camera.
     */
    fun switchMode(newMode: String, force: Boolean = false) {
        val facingChanged = (newMode == HudPrefs.MODE_SYSTEM &&
            systemController.isFacingBack != (HudPrefs.systemFacing(context) == HudPrefs.FACING_BACK))

        if (!force && newMode == currentMode && !facingChanged && isStreaming()) return
        // Stop active recording if in progress
        if (isRecording()) {
            try {
                stopRecording()
            } catch (t: Throwable) {
                HudLogger.w(TAG, "Error stopping recording during mode switch: ${t.message}")
            }
        }

        // Stop current
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.onAppBackgrounded()
            HudPrefs.MODE_SYSTEM -> systemController.close()
            HudPrefs.MODE_WIFI -> wifiController.close()
            HudPrefs.MODE_DVR -> dvrController.close()
        }

        currentMode = newMode
        HudPrefs.setCameraMode(context, newMode)
        usbController.autoScanOnAttach = newMode == HudPrefs.MODE_USB

        // Start new
        setOutputSurface(currentSurface, currentHolder)
        scanAndConnect(forcePermission = true)
    }

    /** Cycles to the next camera source: USB -> SYSTEM -> WIFI -> DVR -> USB */
    fun cycleNextMode(): String {
        val next = when (currentMode) {
            HudPrefs.MODE_USB -> HudPrefs.MODE_SYSTEM
            HudPrefs.MODE_SYSTEM -> HudPrefs.MODE_WIFI
            HudPrefs.MODE_WIFI -> HudPrefs.MODE_DVR
            else -> HudPrefs.MODE_USB
        }
        switchMode(next)
        return next
    }

    fun toggleSystemFacing(): Boolean {
        return systemController.toggleFacing()
    }

    fun isStreaming(): Boolean {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.isRecording() || usbController.frameSize().first > 0
            HudPrefs.MODE_SYSTEM -> systemController.isOpen
            HudPrefs.MODE_WIFI -> wifiController.isOpen
            HudPrefs.MODE_DVR -> dvrController.isOpen
            else -> false
        }
    }

    fun startRecording(): AusbcCameraController.RecordingStartInfo? {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.startRecording()
            HudPrefs.MODE_SYSTEM -> systemController.startRecording()
            HudPrefs.MODE_WIFI -> wifiController.startRecording()
            HudPrefs.MODE_DVR -> dvrController.startRecording()
            else -> null
        }
    }

    fun stopRecording(): RecordingStorageManager.RecordingResult? {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.stopRecording()
            HudPrefs.MODE_SYSTEM -> systemController.stopRecording()
            HudPrefs.MODE_WIFI -> wifiController.stopRecording()
            HudPrefs.MODE_DVR -> dvrController.stopRecording()
            else -> null
        }
    }

    fun isRecording(): Boolean {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.isRecording()
            HudPrefs.MODE_SYSTEM -> systemController.isRecording()
            HudPrefs.MODE_WIFI -> wifiController.isRecording()
            HudPrefs.MODE_DVR -> dvrController.isRecording()
            else -> false
        }
    }

    fun frameSize(): Pair<Int, Int> {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.frameSize()
            HudPrefs.MODE_SYSTEM -> systemController.frameSize()
            HudPrefs.MODE_WIFI -> wifiController.frameSize()
            HudPrefs.MODE_DVR -> dvrController.frameSize()
            else -> 0 to 0
        }
    }

    fun updateRecordingDetections(detections: List<Detection>) {
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.updateRecordingDetections(detections)
            HudPrefs.MODE_SYSTEM -> systemController.updateRecordingDetections(detections)
            HudPrefs.MODE_WIFI -> wifiController.updateRecordingDetections(detections)
            HudPrefs.MODE_DVR -> dvrController.updateRecordingDetections(detections)
        }
    }

    fun setMirror(mirror: Boolean) {
        usbController.setMirror(mirror)
    }

    fun startWatchdog() {
        if (currentMode == HudPrefs.MODE_USB) {
            usbController.startWatchdog()
        }
    }

    fun onAppBackgrounded() {
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.onAppBackgrounded()
            HudPrefs.MODE_SYSTEM -> systemController.close()
            HudPrefs.MODE_WIFI -> wifiController.close()
            HudPrefs.MODE_DVR -> dvrController.close()
        }
    }

    fun nudgeFocus(deltaPercent: Int) {
        if (currentMode == HudPrefs.MODE_USB) {
            usbController.nudgeFocus(deltaPercent)
        }
    }

    fun toggleAutoFocus() {
        if (currentMode == HudPrefs.MODE_USB) {
            usbController.toggleAutoFocus()
        }
    }

    fun hasAutoFocus(): Boolean {
        return if (currentMode == HudPrefs.MODE_USB) usbController.hasAutoFocus() else true
    }

    /**
     * Starts building the detector for the active mode before the camera is even open. The
     * session costs seconds of native initialisation, and paying it up front is the difference
     * between boxes appearing with the first frame and boxes appearing several seconds later.
     */
    fun prewarmDetector() {
        when (currentMode) {
            HudPrefs.MODE_SYSTEM -> systemController.prewarmDetector()
            // WiFi and the DVR share the JPEG sink, whose frame size is known up front, so their
            // session can be built from onCreate. The remembered backend still costs seconds of
            // native init, and starting it before the camera opens is the difference between
            // boxes on the first frame and boxes several seconds later.
            HudPrefs.MODE_WIFI -> wifiController.prewarmDetector()
            HudPrefs.MODE_DVR -> dvrController.prewarmDetector()
            // The UVC path builds its detector when the camera opens, where the negotiated frame
            // size is known.
        }
    }

    fun describeUsbDevices(): String {
        return usbController.describeUsbDevices()
    }

    fun destroy() {
        onAppBackgrounded()
        systemController.destroy()
        wifiController.destroy()
        dvrController.destroy()
    }
}
