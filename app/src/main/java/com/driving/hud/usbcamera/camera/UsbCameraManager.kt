package com.driving.hud.usbcamera.camera

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.driving.hud.usbcamera.R
import com.driving.hud.usbcamera.camera.usbiso.UsbDeviceFsUtil
import com.driving.hud.usbcamera.camera.usbiso.UsbIso
import com.driving.hud.usbcamera.util.HudLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class UsbCameraManager(
    private val context: Context,
    private val listener: CameraEventListener
) {
    companion object {
        private const val TAG = "UsbCameraManager"
        private const val ACTION_USB_PERMISSION = "com.driving.hud.usbcamera.USB_PERMISSION"
        private const val CHUNK_BUFFER_SIZE = 64 * 1024 // 64KB chunks
        // UVC VideoStreaming interface control selectors (UVC spec A.9.7 / libuvc).
        // NOTE: commit is 0x02; 0x03 is VS_STILL_PROBE_CONTROL.
        private const val VS_PROBE_CONTROL = 0x01
        private const val VS_COMMIT_CONTROL = 0x02
    }

    interface CameraEventListener {
        fun onStatusChanged(status: String)
        fun onConnected(device: UsbDevice)
        fun onDisconnected()
        fun onError(message: String)
        fun onFpsUpdated(fps: Int, bytesPerSec: Long)
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbConnection: UsbDeviceConnection? = null
    private var activeDevice: UsbDevice? = null
    private var videoInterface: UsbInterface? = null
    private var videoEndpoint: UsbEndpoint? = null

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var streamingJob: Job? = null
    private var watchdogJob: Job? = null
    private val isStreaming = AtomicBoolean(false)
    private val permissionRequestPending = AtomicBoolean(false)

    // True when the last UVC commit control transfer was accepted (camera started).
    private var lastUvcCommitOk = false
    // Last probe buffer the camera accepted for SET_CUR+GET_CUR (used to retry
    // VS_COMMIT_CONTROL after the streaming alternate is active).
    private var lastProbeBuf: ByteArray? = null
    private var lastProbeIface = -1
    // Probe/commit structure length, from the device's bcdUVC (26 / 34 / 48).
    private var uvcProbeLen = 26

    // Raw-usbfs isochronous capture state (UsbIso ioctls) — the proven Android
    // path from Peter-St/Android-UVC-Camera. Works where libusb wrap_sys_device
    // and Android UsbRequest both fail to deliver iso data (incl. MediaTek).
    private var isoThread: Thread? = null
    private val isoStreaming = AtomicBoolean(false)
    private var isoUsbIso: UsbIso? = null
    private var isoStatsJob: Job? = null
    private var isoDecodeJob: Job? = null
    private var isoFrameChannel: Channel<ByteArray>? = null
    private var isoFrameCount = 0L
    private var isoBytes = 0L

    // Real MJPEG pipeline: USB chunks -> frame assembler -> MediaCodec decoder.
    private var outputSurface: Surface? = null
    private var mjpegDecoder: MjpegDecoder? = null
    private var frameAssembler: UvcFrameAssembler? = null

    private val permissionIntent: PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(ACTION_USB_PERMISSION),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    )

    private val usbReceiver = object : BroadcastReceiver() {
        // Named `ctx`, not `context`, so it does not shadow the non-null field of the same
        // name that the status messages below resolve their strings from.
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    permissionRequestPending.set(false)
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    HudLogger.i(TAG, "USB permission result received: granted=$granted device=${device?.deviceName}")
                    if (granted && device != null) {
                        HudLogger.i(TAG, "USB Permission granted for: ${device.deviceName}")
                        openAndStartCamera(device)
                    } else {
                        HudLogger.w(TAG, "USB Permission denied (granted=$granted, device=$device)")
                        listener.onStatusChanged(context.getString(R.string.status_permission_denied))
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    HudLogger.i(TAG, "USB Device attached: ${device?.deviceName}")
                    listener.onStatusChanged(context.getString(R.string.status_attached_connecting))
                    scanAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    HudLogger.w(TAG, "USB Device detached: ${device?.deviceName}")
                    if (device == activeDevice) {
                        disconnectCamera("Device Disconnected (Janky OTG/Cable)")
                    }
                }
            }
        }
    }

    /**
     * Registers the USB broadcast receiver. Idempotent. This stays registered
     * for the whole activity life — it is NOT unregistered on onStop, so the
     * USB permission result is never lost when the system permission dialog
     * (or any overlay) temporarily covers the app.
     *
     * Lint flags the else-branch below as an unflagged registration, but that branch is
     * unreachable on any device that enforces the flag: the requirement only exists from
     * API 34, and the constant is available from API 33, so the guarded call always wins.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun registerReceiver() {
        HudLogger.i(TAG, "registerReceiver() - USB receiver")
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            // not yet registered — fine
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        HudLogger.i(TAG, "USB receiver registered")
    }

    /**
     * Called when the app goes to background: stops the watchdog and stream but
     * KEEPS the receiver registered so a pending permission result is received.
     */
    fun onAppBackgrounded() {
        HudLogger.i(TAG, "onAppBackgrounded - stop watchdog + disconnect (receiver stays registered)")
        stopWatchdog()
        disconnectCamera("App Backgrounded")
    }

    fun unregister() {
        HudLogger.i(TAG, "unregister() - full cleanup")
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            HudLogger.e(TAG, "Failed to unregister receiver", e)
        }
        stopWatchdog()
        disconnectCamera("Service Stopped")
    }

    /**
     * Sets the Surface that decoded frames are rendered to (the HUD SurfaceView).
     */
    fun setOutputSurface(surface: Surface?) {
        HudLogger.i(TAG, "setOutputSurface: $surface")
        outputSurface = surface
        mjpegDecoder?.attachSurface(surface)
    }

    /**
     * The HUD mirrors the picture by scaling [HudSurfaceView][com.driving.hud.usbcamera.ui.HudSurfaceView],
     * which keeps the detection overlay aligned with it. Flipping the software decoder as well
     * cancelled that out (two display-only flips are the same as none), and it was also lost when
     * the decoder was created after the toggle, so the decoder is deliberately left alone.
     */
    fun setMirror(mirror: Boolean) {
        HudLogger.i(TAG, "setMirror: $mirror (applied by the HUD SurfaceView)")
    }

    fun scanAndConnect(forcePermission: Boolean = false) {
        if (isStreaming.get()) return

        val deviceList = usbManager.deviceList
        HudLogger.i(TAG, "Scanning USB devices. Count: ${deviceList.size}")
        for (device in deviceList.values) {
            val cls = device.deviceClass
            val vendor = String.format("0x%04X", device.vendorId)
            val product = String.format("0x%04X", device.productId)
            HudLogger.d(TAG, "  USB: ${device.deviceName} name=${device.productName} class=$cls vid=$vendor pid=$product")
        }

        var targetDevice: UsbDevice? = null
        for (device in deviceList.values) {
            if (isUvcDevice(device)) {
                targetDevice = device
                HudLogger.i(TAG, "  -> UVC device found: ${device.deviceName}")
                break
            }
        }

        if (targetDevice != null) {
            if (usbManager.hasPermission(targetDevice)) {
                HudLogger.i(TAG, "  -> USB permission already granted, opening camera")
                openAndStartCamera(targetDevice)
            } else {
                requestUsbPermission(targetDevice, forcePermission)
            }
        } else {
            HudLogger.d(TAG, "  -> No UVC device found, waiting...")
            listener.onStatusChanged(context.getString(R.string.status_waiting_camera))
        }
    }

    /**
     * Requests USB permission for the given device on the main thread and
     * debounces repeated calls (the watchdog fires every 2s). Spamming
     * [UsbManager.requestPermission] is what leaves the system dialog "stuck"
     * (never shown / never resolved), so we only request while not already
     * pending. Pass force=true (Connect button) to issue a fresh request.
     */
    private fun requestUsbPermission(device: UsbDevice, force: Boolean) {
        if (force) {
            permissionRequestPending.set(false)
        }
        if (permissionRequestPending.get()) {
            HudLogger.w(TAG, "USB permission request already pending — debounced. Tap Connect to force a fresh request.")
            return
        }
        permissionRequestPending.set(true)
        HudLogger.i(TAG, "  -> Requesting USB permission for ${device.deviceName}...")
        listener.onStatusChanged(context.getString(R.string.status_requesting_permission))
        mainHandler.post {
            HudLogger.i(TAG, "requestPermission() called (main thread=${Looper.myLooper() == Looper.getMainLooper()})")
            try {
                usbManager.requestPermission(device, permissionIntent)
            } catch (t: Throwable) {
                HudLogger.e(TAG, "requestPermission() threw", t)
                permissionRequestPending.set(false)
            }
            // If no result arrives, tell the user what to do instead of hanging.
            mainHandler.postDelayed({
                if (permissionRequestPending.get()) {
                    HudLogger.w(TAG, "USB permission request still pending after 3s (no dialog result)")
                    listener.onStatusChanged(context.getString(R.string.status_no_prompt))
                }
            }, 3000)
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        // Video Class 0x0E (14) or Misc Class 0xEF (239)
        if (device.deviceClass == 14 || device.deviceClass == 239) return true
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == 14 || iface.interfaceClass == 239) {
                return true
            }
        }
        return false
    }

    /**
     * Scans USB and returns a human-readable list of attached devices, also
     * logging each line to the on-device log file. Use this to check whether
     * the phone even enumerates the camera (OTG power/cable) before worrying
     * about permission.
     */
    fun describeUsbDevices(): String {
        val devices = usbManager.deviceList
        val sb = StringBuilder()
        sb.append("USB scan: ${devices.size} device(s) attached")
        if (devices.isEmpty()) {
            sb.append("\n  No USB devices enumerated.")
            sb.append("\n  If a camera is plugged in, check the OTG cable / power.")
            HudLogger.w(TAG, "USB scan: 0 devices enumerated (check OTG cable/power)")
        } else {
            for (device in devices.values) {
                val line = formatDevice(device)
                sb.append('\n').append("  ").append(line)
                HudLogger.i(TAG, "USB device: $line")
            }
            val uvc = devices.values.any { isUvcDevice(it) }
            sb.append('\n').append("  UVC camera found: $uvc")
            HudLogger.i(TAG, "UVC camera found: $uvc")
        }
        return sb.toString()
    }

    private fun formatDevice(device: UsbDevice): String {
        val vendor = String.format("0x%04X", device.vendorId)
        val product = String.format("0x%04X", device.productId)
        return "${device.productName ?: device.deviceName} | VID:$vendor PID:$product class=${device.deviceClass}"
    }

    private fun openAndStartCamera(device: UsbDevice) {
        permissionRequestPending.set(false)
        try {
            HudLogger.i(TAG, "openAndStartCamera: ${device.deviceName} (${device.productName})")
            val connection = usbManager.openDevice(device)
            if (connection == null) {
                val stillAttached = usbManager.deviceList.values.any { it.deviceName == device.deviceName }
                val perm = usbManager.hasPermission(device)
                HudLogger.e(TAG, "openDevice returned null. hasPermission=$perm stillAttached=$stillAttached. The OS refused to open this UVC+audio composite device (common on Unisoc/itel when the USB-audio subsystem claims it).")
                listener.onError("Cannot open USB camera (OS blocked it).\nTry rebooting the phone or a UVC-only camera.")
                return
            }
            HudLogger.i(TAG, "openDevice OK, fd=${connection.fileDescriptor}")

            // Find the Video Streaming interface (class 14) with an IN endpoint.
            // Prefer isochronous (UVC) endpoints over bulk. NOTE: the video
            // streaming interface's default alternate (0) has NO endpoint — the
            // isochronous endpoints live on alternate settings 1..n, which Android
            // exposes as separate entries in device.getInterface().
            var selectedInterface: UsbInterface?
            var selectedEndpoint: UsbEndpoint?

            // Prefer an isochronous IN endpoint whose maxPacketSize is valid for
            // USB 2.0 high-speed (<=3072), picking the largest (best resolution);
            // fall back to any isochronous, then to bulk.
            var bestIsoc: UsbInterface? = null
            var bestIsocEp: UsbEndpoint? = null
            var anyIsoc: UsbInterface? = null
            var anyIsocEp: UsbEndpoint? = null
            var bulkIface: UsbInterface? = null
            var bulkEp: UsbEndpoint? = null

            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass != 14) continue
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.direction != UsbConstants.USB_DIR_IN) continue
                    when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> {
                            if (ep.maxPacketSize in 512..3072) {
                                if (bestIsocEp == null || ep.maxPacketSize > bestIsocEp.maxPacketSize) {
                                    bestIsoc = iface
                                    bestIsocEp = ep
                                }
                            } else if (anyIsocEp == null) {
                                anyIsoc = iface
                                anyIsocEp = ep
                            }
                        }
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                            if (bulkEp == null) {
                                bulkIface = iface
                                bulkEp = ep
                            }
                        }
                    }
                }
            }
            selectedInterface = bestIsoc ?: anyIsoc ?: bulkIface
            selectedEndpoint = bestIsocEp ?: anyIsocEp ?: bulkEp

            if (selectedInterface == null || selectedEndpoint == null) {
                // Fallback to any IN endpoint
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        if (ep.direction == UsbConstants.USB_DIR_IN) {
                            selectedInterface = iface
                            selectedEndpoint = ep
                            break
                        }
                    }
                    if (selectedEndpoint != null) break
                }
            }

            HudLogger.i(
                TAG,
                "Interface=${selectedInterface?.id} alt=${selectedInterface?.alternateSetting} Endpoint=${selectedEndpoint?.address} type=${selectedEndpoint?.type} maxPkt=${selectedEndpoint?.maxPacketSize}"
            )

            this.usbConnection = connection
            this.activeDevice = device
            this.videoInterface = selectedInterface
            this.videoEndpoint = selectedEndpoint

            val isIsochronousEndpoint = selectedEndpoint?.type == UsbConstants.USB_ENDPOINT_XFER_ISOC

            // Real MJPEG pipeline: MediaCodec/software decoder -> HUD surface.
            frameAssembler = UvcFrameAssembler(TAG)
            mjpegDecoder = MjpegDecoder(TAG)
            mjpegDecoder?.start(outputSurface)

            // For isochronous UVC endpoints, use raw-usbfs isochronous capture
            // (USBDEVFS_SUBMITURB ioctls) — the proven Android path — with a
            // fallback to the UsbManager path if it can't start.
            val isoStarted = if (isIsochronousEndpoint) {
                startIsoRawCapture(connection, device, selectedInterface, selectedEndpoint)
            } else {
                HudLogger.i(TAG, "Bulk endpoint — using UsbManager streaming")
                false
            }

            if (!isoStarted) {
                // UsbManager path: claim at alt 0, UVC handshake, activate alt.
                val baseInterface: UsbInterface? = if (selectedInterface != null) {
                    (0 until device.interfaceCount)
                        .map { device.getInterface(it) }
                        .firstOrNull { it.id == selectedInterface.id && it.alternateSetting == 0 }
                        ?: selectedInterface
                } else null
                if (baseInterface != null) {
                    connection.claimInterface(baseInterface, true)
                    HudLogger.i(TAG, "Interface ${baseInterface.id} (alt ${baseInterface.alternateSetting}) claimed")
                }
                if (selectedInterface != null) {
                    startUvcStreaming(connection, selectedInterface, selectedEndpoint?.maxPacketSize ?: 3072, emptyList(), uvcProbeLen)
                }
                if (selectedInterface != null) {
                    try {
                        connection.setInterface(selectedInterface)
                        HudLogger.i(TAG, "setInterface(alt ${selectedInterface.alternateSetting}) ok")
                    } catch (t: Throwable) {
                        HudLogger.w(TAG, "setInterface failed: ${t.message}")
                    }
                }
            }

            isStreaming.set(true)
            listener.onConnected(device)
            listener.onStatusChanged(
                context.getString(
                    R.string.status_connected_named,
                    device.productName ?: context.getString(R.string.status_usb_camera_fallback)
                )
            )

            if (isoStarted) {
                HudLogger.i(TAG, "Using raw-usbfs isochronous capture (UsbManager loop skipped)")
            } else {
                startStreamingLoop()
            }
        } catch (e: Exception) {
            HudLogger.e(TAG, "Error starting camera", e)
            listener.onError("Failed to start camera: ${e.localizedMessage}")
        }
    }

    /**
     * Starts isochronous UVC capture using raw usbfs ioctls (USBDEVFS_SUBMITURB
     * / REAPURB) — the approach proven by Peter-St/Android-UVC-Camera to deliver
     * iso data on Android devices (incl. MediaTek) where libusb's
     * wrap_sys_device and Android's UsbRequest both fail.
     *
     * Flow (mirrors the reference app):
     *   1. claim the Video Control + Video Streaming interfaces (force)
     *   2. UVC Video Probe/Commit handshake (controlTransfer)
     *   3. raw USBDEVFS_SETINTERFACE to the iso alternate setting
     *   4. submit N isochronous URBs, loop reap() -> UvcFrameAssembler -> decoder
     *
     * Returns true if the iso stream thread started; otherwise the UsbManager
     * path is used as a fallback.
     */
    private fun startIsoRawCapture(
        connection: UsbDeviceConnection,
        device: UsbDevice,
        iface: UsbInterface?,
        ep: UsbEndpoint?
    ): Boolean {
        if (iface == null || ep == null) return false
        return try {
            val fd = connection.fileDescriptor
            val ifaceId = iface.id
            val altSetting = iface.alternateSetting
            val maxPacketSize = ep.maxPacketSize
            // A single isochronous URB may span at most ~1 ms of high-speed bus
            // time = 8 microframes. Submitting more (e.g. 21 x 3072 = 61 KB)
            // makes usbfs reject it with EMSGSIZE ("Message too long"), which
            // silently left every URB unqueued and the stream dead. Cap at 8.
            val packetsPerRequest = maxOf(1, minOf(8, 65536 / maxPacketSize))
            val activeUrbs = 16
            lastProbeBuf = null
            lastProbeIface = -1

            // 1) Claim the VideoControl interface (class 14, subclass 1), then the
            //    VideoStreaming interface AT ALT 0 (idle). Many UVC cameras STALL the
            //    VS_COMMIT_CONTROL request if the streaming interface is already at a
            //    streaming alternate, so probe/commit must run at alt 0; only then do
            //    we switch to the streaming alternate setting.
            val controlIface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.interfaceClass == 14 && it.interfaceSubclass == 1 }
            if (controlIface != null) {
                connection.claimInterface(controlIface, true)
                HudLogger.i(TAG, "Claimed VideoControl interface ${controlIface.id} fd=$fd")
            }
            val idleIface = (0 until device.interfaceCount)
                .map { device.getInterface(it) }
                .firstOrNull { it.id == ifaceId && it.alternateSetting == 0 }
            if (!connection.claimInterface(idleIface ?: iface, true)) {
                HudLogger.w(TAG, "Failed to claim streaming interface $ifaceId (alt 0)")
                return false
            }
            HudLogger.i(TAG, "Claimed streaming interface $ifaceId at alt 0 fd=$fd")
            if (idleIface != null) {
                try {
                    UsbDeviceFsUtil.setInterface(fd, ifaceId, 0)
                    HudLogger.i(TAG, "raw setInterface(fd=$fd iface=$ifaceId alt=0) OK")
                } catch (t: Throwable) {
                    HudLogger.w(TAG, "raw setInterface alt 0 failed: ${t.message}")
                }
            }

            // 1b) Read the UVC class-specific descriptors so we can probe/commit with
            //     the camera's REAL format/frame indices (MJPEG preferred) and the
            //     correct bcdUVC-sized control structure (26 / 34 / 48 bytes).
            val cfgDesc = readConfigDescriptor(connection)
            val uvcFormats = cfgDesc?.let { parseUvcFormats(it) } ?: emptyList()
            val bcdUvc = cfgDesc?.let { parseBcdUvc(it) } ?: 0
            val probeLen = when {
                bcdUvc >= 0x0150 -> 48   // UVC 1.5
                bcdUvc >= 0x0110 -> 34   // UVC 1.1
                else -> 26               // UVC 1.0
            }
            uvcProbeLen = probeLen
            HudLogger.i(TAG, "UVC bcdUVC=0x${String.format("%04X", bcdUvc)} -> probe/commit length=$probeLen")
            if (uvcFormats.isNotEmpty()) {
                HudLogger.i(TAG, "UVC formats: " + uvcFormats.joinToString(", ") { "fmt=${it.formatIndex}${if (it.isMjpeg) "[MJPG]" else "[raw]"} frames=" + it.frames.joinToString("|") { "${it.width}x${it.height}" } })
            } else {
                HudLogger.w(TAG, "UVC class descriptors unavailable — falling back to guessed indices")
            }

            // 1c) Discover the largest iso URB the kernel accepts BEFORE the handshake:
            //     the commit's dwMaxPayloadTransferSize MUST equal the iso packet size,
            //     otherwise the camera overflows every packet (EPROTO / -71, zero data).
            //     The endpoint only exists while the streaming alt is active, so briefly
            //     activate it, probe, then drop back to alt 0 for probe/commit.
            var chosenPacketSize = maxPacketSize
            var chosenPackets = packetsPerRequest
            if (idleIface != null) {
                try {
                    UsbDeviceFsUtil.setInterface(fd, ifaceId, altSetting)
                    val psCandidates = intArrayOf(maxPacketSize, 3072, 2048, 1024, 512, 256)
                    val pkCandidates = intArrayOf(8, 4, 2, 1)
                    loop@ for (ps in psCandidates) {
                        if (ps <= 0 || ps > maxPacketSize) continue
                        for (pk in pkCandidates) {
                            var probe: UsbIso? = null
                            try {
                                probe = UsbIso(fd, pk, ps, ep.address.toByte())
                                probe.preallocateRequests(1)
                                probe.submitUrbs()
                                HudLogger.i(TAG, "iso URB probe OK: packets=$pk packetSize=$ps")
                                chosenPacketSize = ps
                                chosenPackets = pk
                                break@loop
                            } catch (t: Throwable) {
                                HudLogger.w(TAG, "iso URB probe failed: packets=$pk packetSize=$ps -> ${t.message}")
                            } finally {
                                try {
                                    probe?.dispose()
                                } catch (_: Throwable) {
                                }
                            }
                        }
                    }
                    UsbDeviceFsUtil.setInterface(fd, ifaceId, 0)
                } catch (t: Throwable) {
                    HudLogger.w(TAG, "iso URB shape discovery failed: ${t.message}")
                }
            }
            val commitPayload = minOf(chosenPacketSize, maxPacketSize)

            // 2) UVC probe/commit handshake (at alt 0) with the payload size our
            //    isochronous URBs can actually carry.
            val commitOk = startUvcStreaming(connection, iface, commitPayload, uvcFormats, probeLen)
            HudLogger.i(TAG, "UVC probe/commit ok=$commitOk")

            // 3) Raw set-interface to the iso alternate setting.
            try {
                UsbDeviceFsUtil.setInterface(fd, ifaceId, altSetting)
                HudLogger.i(TAG, "raw setInterface(fd=$fd iface=$ifaceId alt=$altSetting) OK")
            } catch (t: Throwable) {
                HudLogger.w(TAG, "raw setInterface failed: ${t.message}")
                return false
            }

            // 3b) Some cameras only accept VS_COMMIT_CONTROL once the streaming
            //     alternate is already active. Retry the commit here.
            if (!commitOk) {
                val buf = lastProbeBuf
                if (buf != null) {
                    val cB = connection.controlTransfer(0x21, 0x01, VS_COMMIT_CONTROL shl 8, lastProbeIface, buf, buf.size, 300)
                    HudLogger.i(TAG, "post-alt commit (variant B) -> $cB")
                    if (cB == buf.size) {
                        lastUvcCommitOk = true
                    } else {
                        val okC = startUvcStreaming(connection, iface, commitPayload, uvcFormats, probeLen)
                        HudLogger.i(TAG, "post-alt probe/commit (variant C) ok=$okC")
                        if (okC) lastUvcCommitOk = true
                    }
                }
            }
            HudLogger.i(TAG, "UVC final commit state: ${if (lastUvcCommitOk) "accepted" else "not accepted (camera may auto-stream)"} (packet=$chosenPacketSize payload=$commitPayload)")

            val usbIso = UsbIso(fd, chosenPackets, chosenPacketSize, ep.address.toByte())
            isoUsbIso = usbIso
            usbIso.preallocateRequests(activeUrbs)
            isoStreaming.set(true)
            isoFrameCount = 0
            isoBytes = 0

            // Decode on a separate thread: software JPEG decode can take tens of ms,
            // and doing it inline would stall URB reaping and drop frames. CONFLATED
            // keeps only the freshest frame (live view — never lag behind).
            val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
            isoFrameChannel = frameChannel
            isoDecodeJob?.cancel()
            isoDecodeJob = scope.launch(Dispatchers.Default) {
                for (f in frameChannel) {
                    if (!isoStreaming.get()) break
                    mjpegDecoder?.feedFrame(f)
                }
            }

            isoThread = Thread({
                val data = ByteArray(chosenPacketSize)
                var byteCount = 0L
                var framesThisSecond = 0
                var reaps = 0L
                var packetsTotal = 0L
                var packetsErr = 0L
                var firstErrStatus = Int.MIN_VALUE
                var idDumps = 0
                var lastStats = System.currentTimeMillis()
                try {
                    usbIso.submitUrbs()
                    HudLogger.i(TAG, "USBIso URBs submitted ($activeUrbs x $chosenPackets packets of $chosenPacketSize B)")
                    while (isoStreaming.get() && !Thread.currentThread().isInterrupted) {
                        val req = usbIso.reapRequest(true)
                        reaps++
                        for (p in 0 until req.numberOfPackets) {
                            packetsTotal++
                            val packetStatus = req.getPacketStatus(p)
                            if (packetStatus != 0) {
                                packetsErr++
                                if (firstErrStatus == Int.MIN_VALUE) firstErrStatus = packetStatus
                                continue
                            }
                            val packetLen = req.getPacketActualLength(p)
                            if (packetLen <= 0) continue
                            req.getPacketData(p, data, packetLen)
                            if (idDumps < 5) {
                                idDumps++
                                val n = minOf(48, packetLen)
                                val sb = StringBuilder()
                                for (k in 0 until n) sb.append(String.format("%02X ", data[k]))
                                HudLogger.i(TAG, "iso pkt#$idDumps len=$packetLen hdr=${data[0].toInt() and 0xFF} b1=0x${String.format("%02X", data[1].toInt() and 0xFF)} data: $sb")
                            }
                            byteCount += packetLen
                            // UVC payload header: byte0=headerLen, then payload.
                            val headerLen = data[0].toInt() and 0xFF
                            if (headerLen < 2 || headerLen > packetLen) continue
                            val frames = frameAssembler?.feed(data, packetLen) ?: emptyList()
                            for (f in frames) {
                                isoFrameCount++
                                frameChannel.trySend(f)
                            }
                            framesThisSecond += frames.size
                        }
                        // Re-queue the request to keep activeUrbs URBs in flight.
                        try {
                            req.initialize()
                            req.submit()
                        } catch (t: Throwable) {
                            HudLogger.w(TAG, "req resubmit failed: ${t.message}")
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastStats >= 1000) {
                            val fps = framesThisSecond
                            HudLogger.i(TAG, "iso stream: frames/s=$fps bytes=$byteCount reaps=$reaps packets=${packetsTotal} errPkts=$packetsErr firstErr=$firstErrStatus (packets/urb=$packetsPerRequest urbs=$activeUrbs)")
                            listener.onFpsUpdated(fps, byteCount)
                            framesThisSecond = 0
                            byteCount = 0
                            reaps = 0
                            packetsTotal = 0
                            packetsErr = 0
                            lastStats = now
                        }
                    }
                } catch (t: Throwable) {
                    if (isoStreaming.get()) {
                        HudLogger.e(TAG, "USBIso stream thread error", t)
                    } else {
                        HudLogger.i(TAG, "USBIso stream thread stopped")
                    }
                } finally {
                    try {
                        usbIso.dispose()
                    } catch (_: Throwable) {
                    }
                }
                HudLogger.w(TAG, "USBIso stream thread exited")
            }, "hud-iso-stream")
            isoThread?.isDaemon = true
            isoThread?.start()

            isoStatsJob = scope.launch {
                while (isActive && isoStreaming.get()) {
                    delay(5000)
                    HudLogger.d(TAG, "iso alive: frames=${isoFrameCount} bytes=${isoBytes}")
                    isoFrameCount = 0
                    isoBytes = 0
                }
            }
            true
        } catch (t: Throwable) {
            HudLogger.e(TAG, "startIsoRawCapture threw", t)
            isoStreaming.set(false)
            false
        }
    }

    /**
     * Best-effort UVC control handshake (Video Probe/Commit) to ask the camera
     * to start streaming MJPEG. Many UVC cams auto-stream, but some require
     * this. Returns true if a probe+commit combination was accepted.
     *
     * The probe buffer is a UVC 1.1 VS_PROBE_CONTROL (26 bytes):
     *   [0]=bmHint low (bit0 = dwFrameInterval present)
     *   [1]=bmHint high
     *   [2]=bFormatIndex  [3]=bFrameIndex  [4..7]=dwFrameInterval
     * NOTE: bmHint must NOT be the bLength — that mis-set is what made the
     * commit STALL before.
     */
    private fun startUvcStreaming(
        connection: UsbDeviceConnection,
        vsInterface: UsbInterface,
        maxPayloadSize: Int,
        formats: List<UvcFormat>,
        probeLen: Int
    ): Boolean {
        lastUvcCommitOk = false
        try {
            // Build probe trials from the camera's REAL descriptors when known,
            // preferring MJPEG; otherwise fall back to guessed indices 1..3.
            val intervals = intArrayOf(333333, 500000, 666666, 1000000)
            val trials = ArrayList<IntArray>() // [formatIndex, frameIndex, interval]
            if (formats.isNotEmpty()) {
                // MJPEG first, then prefer ~720p: the largest frame at or below 720p first
                // (so we get a real HUD image), then the smallest above it. Fastest
                // interval first; combos whose payload won't fit a URB are skipped below.
                val target = 1280 * 720
                val ordered = formats.filter { it.isMjpeg } + formats.filter { !it.isMjpeg }
                for (f in ordered) {
                    if (f.frames.isNotEmpty()) {
                        val frames = f.frames.sortedWith(
                            compareBy(
                                { if (it.area <= target) 0 else 1 },
                                { if (it.area <= target) -it.area else it.area }
                            )
                        )
                        for (fr in frames) {
                            for (iv in intervals) trials.add(intArrayOf(f.formatIndex, fr.index, iv))
                        }
                    } else {
                        for (fr in intArrayOf(1, 2)) for (iv in intervals) trials.add(intArrayOf(f.formatIndex, fr, iv))
                    }
                }
            } else {
                for (fmt in intArrayOf(1, 2, 3)) for (fr in intArrayOf(1, 2)) for (iv in intervals) {
                    trials.add(intArrayOf(fmt, fr, iv))
                }
            }

            for (iface in listOf(vsInterface.id)) {
                var trialNo = 0
                for (t in trials) {
                    trialNo++
                    if (trialNo > 60) break
                    val formatIdx = t[0]
                    val frameIdx = t[1]
                    val interval = t[2]
                    val probe = buildProbe(probeLen, formatIdx, frameIdx, interval, maxPayloadSize, hint = 0x0001)
                    val set = connection.controlTransfer(0x21, 0x01, VS_PROBE_CONTROL shl 8, iface, probe, probe.size, 300)
                    if (set != probeLen) continue
                    val get = connection.controlTransfer(0xA1.toInt(), 0x81, VS_PROBE_CONTROL shl 8, iface, probe, probe.size, 300)
                    if (get != probeLen) continue
                    // libuvc (saki) issues GET_CUR / GET_MIN / GET_MAX before negotiating,
                    // "otherwise stream stall".
                    val getMin = connection.controlTransfer(0xA1.toInt(), 0x82, VS_PROBE_CONTROL shl 8, iface, ByteArray(probeLen), probeLen, 300)
                    val getMax = connection.controlTransfer(0xA1.toInt(), 0x83, VS_PROBE_CONTROL shl 8, iface, ByteArray(probeLen), probeLen, 300)
                    val negFrame = getLe32(probe, 18)
                    val negPayload = getLe32(probe, 22)
                    // The committed payload MUST fit an isochronous packet, otherwise
                    // the camera overflows every URB (EPROTO / -71) and no data arrives.
                    if (negPayload > maxPayloadSize) {
                        HudLogger.d(TAG, "UVC skip fmt=$formatIdx fr=$frameIdx iv=$interval: camera payload=$negPayload > our packet=$maxPayloadSize")
                        continue
                    }
                    if (lastProbeBuf == null) {
                        lastProbeBuf = probe.copyOf()
                        lastProbeIface = iface
                    }

                    // THE commit: CS 0x02 = VS_COMMIT_CONTROL. Previously 0x03, which is
                    // VS_STILL_PROBE_CONTROL — no real commit was ever sent (see libuvc).
                    val commit = connection.controlTransfer(0x21, 0x01, VS_COMMIT_CONTROL shl 8, iface, probe, probe.size, 300)
                    if (commit == probeLen) {
                        lastUvcCommitOk = true
                        val res = formats.firstOrNull { it.formatIndex == formatIdx }
                            ?.frames?.firstOrNull { it.index == frameIdx }
                        HudLogger.i(TAG, "UVC commit OK: iface=$iface fmt=$formatIdx fr=$frameIdx iv=$interval res=${res?.width}x${res?.height} len=$probeLen frame=$negFrame payload=$negPayload")
                        return true
                    }
                    HudLogger.d(TAG, "UVC commit fail iface=$iface fmt=$formatIdx fr=$frameIdx iv=$interval len=$probeLen: set=$set get=$get min=$getMin max=$getMax commit=$commit (frame=$negFrame payload=$negPayload)")
                }
            }
            HudLogger.w(TAG, "UVC streaming start failed for all tried configurations")
        } catch (t: Throwable) {
            HudLogger.w(TAG, "UVC control handshake failed (continuing): ${t.message}")
        }
        return lastUvcCommitOk
    }

    /** A UVC VideoStreaming frame (resolution + default interval). */
    private data class UvcFrame(
        val index: Int,
        val width: Int,
        val height: Int,
        val defaultInterval: Int
    ) {
        val area: Int get() = width * height
    }

    /** A UVC VideoStreaming format (uncompressed or MJPEG) with its frames. */
    private data class UvcFormat(
        val formatIndex: Int,
        val isMjpeg: Boolean,
        val frames: MutableList<UvcFrame>
    )

    /** Reads the raw configuration descriptor (standard GET_DESCRIPTOR request). */
    private fun readConfigDescriptor(connection: UsbDeviceConnection): ByteArray? {
        return try {
            val buf = ByteArray(4096)
            val len = connection.controlTransfer(0x80, 0x06, (0x02 shl 8) or 0x00, 0x0000, buf, buf.size, 1000)
            if (len < 9) return null
            val total = (buf[2].toInt() and 0xFF) or ((buf[3].toInt() and 0xFF) shl 8)
            buf.copyOf(minOf(len, total, buf.size))
        } catch (t: Throwable) {
            HudLogger.w(TAG, "readConfigDescriptor failed: ${t.message}")
            null
        }
    }

    private fun le16(a: ByteArray, off: Int): Int =
        (a[off].toInt() and 0xFF) or ((a[off + 1].toInt() and 0xFF) shl 8)

    /** Parses the class-specific UVC descriptors to find formats + frames. */
    private fun parseUvcFormats(cfg: ByteArray): List<UvcFormat> {
        val out = ArrayList<UvcFormat>()
        var cur: UvcFormat? = null
        var i = 0
        while (i + 1 < cfg.size) {
            val bLength = cfg[i].toInt() and 0xFF
            if (bLength <= 0 || i + bLength > cfg.size) break
            val bType = cfg[i + 1].toInt() and 0xFF
            if (bType == 0x24 && bLength >= 4) {
                val sub = cfg[i + 2].toInt() and 0xFF
                when (sub) {
                    0x04, 0x06, 0x0A, 0x10, 0x12 -> { // VS_FORMAT_*
                        cur = UvcFormat(cfg[i + 3].toInt() and 0xFF, sub == 0x06, ArrayList())
                        out.add(cur)
                    }
                    0x05, 0x07, 0x0B, 0x0D, 0x11, 0x13 -> { // VS_FRAME_*
                        if (cur != null && bLength >= 26) {
                            val w = le16(cfg, i + 5)
                            val h = le16(cfg, i + 7)
                            val defIv = getLe32(cfg, i + 21)
                            cur.frames.add(UvcFrame(cfg[i + 3].toInt() and 0xFF, w, h, defIv))
                        }
                    }
                }
            }
            i += bLength
        }
        return out
    }

    /**
     * Extracts bcdUVC from the VideoControl interface's VC_HEADER descriptor.
     * Must be matched inside the VideoControl interface because the VideoStreaming
     * interface's VS_INPUT_HEADER also uses class-specific subtype 0x01.
     */
    private fun parseBcdUvc(cfg: ByteArray): Int {
        var i = 0
        var subClass = -1
        while (i + 1 < cfg.size) {
            val bLength = cfg[i].toInt() and 0xFF
            if (bLength <= 0 || i + bLength > cfg.size) break
            val bType = cfg[i + 1].toInt() and 0xFF
            if (bType == 0x04 && bLength >= 8) {
                val cls = cfg[i + 5].toInt() and 0xFF
                subClass = if (cls == 0x0E) (cfg[i + 6].toInt() and 0xFF) else -1
            } else if (bType == 0x24 && bLength >= 5 && subClass == 1 && (cfg[i + 2].toInt() and 0xFF) == 0x01) {
                return le16(cfg, i + 3)
            }
            i += bLength
        }
        return 0
    }

    private fun putLe32(a: ByteArray, off: Int, v: Int) {
        a[off] = (v and 0xFF).toByte()
        a[off + 1] = ((v shr 8) and 0xFF).toByte()
        a[off + 2] = ((v shr 16) and 0xFF).toByte()
        a[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun getLe32(a: ByteArray, off: Int): Int =
        (a[off].toInt() and 0xFF) or
            ((a[off + 1].toInt() and 0xFF) shl 8) or
            ((a[off + 2].toInt() and 0xFF) shl 16) or
            ((a[off + 3].toInt() and 0xFF) shl 24)

    /** Builds a UVC VS_PROBE_CONTROL / VS_COMMIT_CONTROL structure of [len] bytes. */
    private fun buildProbe(
        len: Int,
        formatIdx: Int,
        frameIdx: Int,
        interval: Int,
        maxPayloadSize: Int,
        hint: Int,
        frameSize: Int = 1 shl 20
    ): ByteArray {
        val probe = ByteArray(len)
        probe[0] = (hint and 0xFF).toByte()
        probe[1] = ((hint shr 8) and 0xFF).toByte()
        probe[2] = formatIdx.toByte()
        probe[3] = frameIdx.toByte()
        probe[4] = (interval and 0xFF).toByte()
        probe[5] = ((interval shr 8) and 0xFF).toByte()
        probe[6] = ((interval shr 16) and 0xFF).toByte()
        probe[7] = ((interval shr 24) and 0xFF).toByte()
        putLe32(probe, 18, frameSize)
        putLe32(probe, 22, maxPayloadSize)
        return probe
    }

    private fun startStreamingLoop() {
        streamingJob?.cancel()
        streamingJob = scope.launch {
            val connection = usbConnection
            val endpoint = videoEndpoint
            if (connection == null || endpoint == null) {
                HudLogger.w(TAG, "Streaming loop aborted: no connection/endpoint")
                return@launch
            }

            val isIsochronous = endpoint.type == UsbConstants.USB_ENDPOINT_XFER_ISOC
            HudLogger.i(TAG, "Streaming loop started (chunk=${CHUNK_BUFFER_SIZE}B, isochronous=$isIsochronous, endpoint=${endpoint.address} maxPkt=${endpoint.maxPacketSize}, decoder=${mjpegDecoder != null})")

            // Isochronous endpoints require UsbRequest (bulkTransfer won't work).
            val usbRequest: UsbRequest? = if (isIsochronous) {
                try {
                    UsbRequest().apply { initialize(connection, endpoint) }
                } catch (t: Throwable) {
                    HudLogger.e(TAG, "UsbRequest initialize failed", t)
                    null
                }
            } else null
            val isocBuffer = if (isIsochronous) ByteBuffer.allocateDirect(endpoint.maxPacketSize * 8) else null
            val bulkBuffer = ByteArray(CHUNK_BUFFER_SIZE)

            var byteCount = 0L
            var framesThisSecond = 0
            var frameCount = 0
            var lastStatsTime = System.currentTimeMillis()
            var consecutiveErrors = 0
            var lastDataMs = System.currentTimeMillis()

            // If isochronous data stalls, cancel the request so the loop retries
            // instead of blocking forever in requestWait().
            val stuckWatchdog = if (isIsochronous) scope.launch {
                var cancels = 0
                while (isActive && isStreaming.get()) {
                    delay(3000)
                    if (System.currentTimeMillis() - lastDataMs > 5000) {
                        cancels++
                        if (cancels >= 3) {
                            HudLogger.w(TAG, "No isochronous data for 15s — Android UsbManager isochronous reads appear unsupported on this device; a bulk-MJPEG UVC camera will work with this app")
                        } else if (!lastUvcCommitOk) {
                            HudLogger.w(TAG, "No isochronous data for 5s — UVC commit was rejected, camera likely not streaming")
                        } else {
                            HudLogger.w(TAG, "No isochronous data for 5s — cancelling stuck request")
                        }
                        try {
                            usbRequest?.cancel()
                        } catch (_: Exception) {
                        }
                    }
                }
            } else null

            while (isActive && isStreaming.get()) {
                val conn = usbConnection ?: break
                val ep = videoEndpoint ?: break

                val bytesRead: Int
                val chunk: ByteArray
                if (isIsochronous && usbRequest != null && isocBuffer != null) {
                    isocBuffer.clear()
                    if (usbRequest.queue(isocBuffer, isocBuffer.capacity())) {
                        if (conn.requestWait() == usbRequest) {
                            // Isochronous byte count isn't exposed; feed the whole
                            // transfer (the frame assembler resyncs on SOI/EOI).
                            isocBuffer.position(0)
                            chunk = ByteArray(isocBuffer.capacity())
                            isocBuffer.get(chunk)
                            bytesRead = chunk.size
                        } else {
                            bytesRead = -1
                            chunk = ByteArray(0)
                        }
                    } else {
                        bytesRead = -1
                        chunk = ByteArray(0)
                    }
                } else {
                    val n = conn.bulkTransfer(ep, bulkBuffer, bulkBuffer.size, 200)
                    bytesRead = n
                    chunk = bulkBuffer
                }

                if (bytesRead > 0) {
                    consecutiveErrors = 0
                    lastDataMs = System.currentTimeMillis()
                    byteCount += bytesRead

                    val frames = frameAssembler?.feed(chunk, bytesRead) ?: emptyList()
                    for (f in frames) {
                        mjpegDecoder?.feedFrame(f)
                    }
                    framesThisSecond += frames.size
                    frameCount++

                    val now = System.currentTimeMillis()
                    if (now - lastStatsTime >= 1000) {
                        val fps = (frameCount * 1000f / (now - lastStatsTime)).toInt()
                        HudLogger.i(
                            TAG,
                            "stream: ${byteCount / 1024}KB/s chunkFps=$fps decodedFrames/s=$framesThisSecond dropped=${frameAssembler?.droppedCount ?: 0}"
                        )
                        listener.onFpsUpdated(framesThisSecond, byteCount)
                        frameCount = 0
                        framesThisSecond = 0
                        byteCount = 0
                        lastStatsTime = now
                    }
                } else {
                    consecutiveErrors++
                    if (consecutiveErrors > 25) {
                        HudLogger.w(TAG, "High packet loss/timeout count on OTG link. Re-synchronizing...")
                        consecutiveErrors = 0
                        delay(100)
                    }
                }
            }
            try {
                usbRequest?.cancel()
            } catch (_: Exception) {
            }
            stuckWatchdog?.cancel()
            HudLogger.w(TAG, "Streaming loop exited")
        }
    }

    private fun disconnectCamera(reason: String) {
        HudLogger.w(TAG, "disconnectCamera: $reason")
        isStreaming.set(false)
        streamingJob?.cancel()
        streamingJob = null

        if (isoStreaming.get()) {
            HudLogger.i(TAG, "Stopping raw-ioctl isochronous capture")
            isoStreaming.set(false)
            isoStatsJob?.cancel()
            isoStatsJob = null
            isoDecodeJob?.cancel()
            isoDecodeJob = null
            try {
                isoFrameChannel?.close()
            } catch (_: Throwable) {
            }
            isoFrameChannel = null
            try {
                isoUsbIso?.dispose()
            } catch (t: Throwable) {
                HudLogger.e(TAG, "UsbIso dispose error", t)
            }
            isoUsbIso = null
            try {
                isoThread?.interrupt()
                isoThread?.join(1000)
            } catch (t: Throwable) {
                HudLogger.e(TAG, "iso thread join error", t)
            }
            isoThread = null
        }

        mjpegDecoder?.release()
        mjpegDecoder = null
        frameAssembler = null
        CameraNativeBridge.nativeStop()

        try {
            videoInterface?.let { usbConnection?.releaseInterface(it) }
            usbConnection?.close()
        } catch (e: Exception) {
            HudLogger.e(TAG, "Error during cleanup", e)
        }

        usbConnection = null
        activeDevice = null
        videoInterface = null
        videoEndpoint = null

        listener.onDisconnected()
        listener.onStatusChanged(reason)
    }

    // Auto-reconnect watchdog to rescue janky OTG connections
    fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var attempts = 0
            HudLogger.i(TAG, "Watchdog started (2s interval when not streaming)")
            while (isActive) {
                delay(2000)
                if (!isStreaming.get()) {
                    attempts++
                    if (attempts % 15 == 0) {
                        HudLogger.w(TAG, "Watchdog: still no stream after $attempts scan attempts")
                    }
                    // Scan on the main thread — requestPermission must not be
                    // called from a background thread.
                    mainHandler.post { scanAndConnect() }
                } else {
                    attempts = 0
                }
            }
        }
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }
}
