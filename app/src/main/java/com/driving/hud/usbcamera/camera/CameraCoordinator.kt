package com.driving.hud.usbcamera.camera

import android.content.Context
import android.view.Surface
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs

/**
 * Unified camera coordinator that switches between:
 * 1. USB Camera (UVC via AusbcCameraController)
 * 2. System Camera (Built-in Rear / Front via SystemCameraController)
 * 3. WiFi Camera (RTSP / HTTP MJPEG via WifiCameraController)
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

    var currentMode: String = HudPrefs.cameraMode(context)
        private set

    private var currentSurface: Surface? = null

    fun registerReceiver() {
        usbController.registerReceiver()
    }

    fun unregister() {
        usbController.unregister()
    }

    fun setOutputSurface(surface: Surface?) {
        currentSurface = surface
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.setOutputSurface(surface)
            HudPrefs.MODE_SYSTEM -> systemController.setOutputSurface(surface)
            HudPrefs.MODE_WIFI -> wifiController.setOutputSurface(surface)
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
        }
    }

    /**
     * Switches the active camera mode (USB, SYSTEM, or WIFI).
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
        }

        currentMode = newMode
        HudPrefs.setCameraMode(context, newMode)

        // Start new
        setOutputSurface(currentSurface)
        scanAndConnect(forcePermission = true)
    }

    /** Cycles to the next camera source: USB -> SYSTEM -> WIFI -> USB */
    fun cycleNextMode(): String {
        val next = when (currentMode) {
            HudPrefs.MODE_USB -> HudPrefs.MODE_SYSTEM
            HudPrefs.MODE_SYSTEM -> HudPrefs.MODE_WIFI
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
            else -> false
        }
    }

    fun startRecording(): AusbcCameraController.RecordingStartInfo? {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.startRecording()
            HudPrefs.MODE_SYSTEM -> systemController.startRecording()
            HudPrefs.MODE_WIFI -> wifiController.startRecording()
            else -> null
        }
    }

    fun stopRecording(): RecordingStorageManager.RecordingResult? {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.stopRecording()
            HudPrefs.MODE_SYSTEM -> systemController.stopRecording()
            HudPrefs.MODE_WIFI -> wifiController.stopRecording()
            else -> null
        }
    }

    fun isRecording(): Boolean {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.isRecording()
            HudPrefs.MODE_SYSTEM -> systemController.isRecording()
            HudPrefs.MODE_WIFI -> wifiController.isRecording()
            else -> false
        }
    }

    fun frameSize(): Pair<Int, Int> {
        return when (currentMode) {
            HudPrefs.MODE_USB -> usbController.frameSize()
            HudPrefs.MODE_SYSTEM -> systemController.frameSize()
            HudPrefs.MODE_WIFI -> wifiController.frameSize()
            else -> 0 to 0
        }
    }

    fun updateRecordingDetections(detections: List<Detection>) {
        when (currentMode) {
            HudPrefs.MODE_USB -> usbController.updateRecordingDetections(detections)
            HudPrefs.MODE_SYSTEM -> systemController.updateRecordingDetections(detections)
            HudPrefs.MODE_WIFI -> wifiController.updateRecordingDetections(detections)
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

    fun describeUsbDevices(): String {
        return usbController.describeUsbDevices()
    }

    fun destroy() {
        onAppBackgrounded()
        systemController.destroy()
        wifiController.destroy()
    }
}
