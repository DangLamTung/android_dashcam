package com.driving.hud.usbcamera.camera

import android.view.Surface

object CameraNativeBridge {

    init {
        System.loadLibrary("native-camera-hud")
    }

    /**
     * Set by UsbCameraManager; receives each complete MJPEG frame decoded from
     * the native libusb isochronous capture thread.
     */
    @JvmStatic
    var frameListener: ((ByteArray) -> Unit)? = null

    /** Called from native (libusb capture thread) for each complete frame. */
    fun onNativeFrame(frame: ByteArray) {
        frameListener?.invoke(frame)
    }

    /**
     * Initializes the native decoder engine.
     * @param format 0 = MJPEG, 1 = YUYV, 2 = H264
     * @param width target width (e.g., 1280 or 1920)
     * @param height target height (e.g., 720 or 1080)
     */
    external fun nativeInit(format: Int, width: Int, height: Int): Boolean

    /**
     * Sets the display Surface for direct ANativeWindow rendering.
     */
    external fun nativeSetSurface(surface: Surface?): Unit

    /**
     * Sets whether the HUD display is horizontally mirrored (for windshield reflection).
     */
    external fun nativeSetMirror(mirror: Boolean): Unit

    /**
     * Registers (or clears, with null) the Kotlin logger object so native C++
     * logs are forwarded into the on-device log file.
     */
    external fun nativeSetLogSink(sink: Any?): Unit

    /**
     * Feeds incoming raw USB chunk from the USB camera endpoint.
     */
    external fun nativeFeedChunk(chunk: ByteArray, length: Int): Unit

    /**
     * Stops the active stream reader.
     */
    external fun nativeStop(): Unit

    /**
     * Releases decoder and native window resources.
     */
    external fun nativeRelease(): Unit

    /**
     * Startup diagnostic: returns libusb_init()'s code (0 = OK). No camera needed.
     */
    external fun nativeLibusbTestInit(): Int

    /**
     * Starts native libusb isochronous capture on the given device fd.
     * libusb claims the interface, sets the alternate setting and delivers
     * complete MJPEG frames via [onNativeFrame]. Returns 0 on success, or a
     * negative error code on failure.
     */
    external fun nativeLibusbStart(fd: Int, ifaceId: Int, altSetting: Int, endpointAddress: Int, maxPacketSize: Int): Int

    /**
     * Stops the native libusb capture and releases its resources.
     */
    external fun nativeLibusbStop(): Unit
}
