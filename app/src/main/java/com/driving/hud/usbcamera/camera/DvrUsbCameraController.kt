package com.driving.hud.usbcamera.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.view.Surface
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger

/**
 * Camera source for the "U2 DVR" (Buildwin / Huiying dashcam, VID 0x1902 / PID 0x3241).
 *
 * The DVR is **not** a UVC camera: it advertises a single mass-storage interface, and
 * while no application claims it the kernel mounts a FAT volume whose `VSFILE` preview
 * is a single static JPEG — useless as a camera. As soon as a host claims the interface
 * the DVR drops the FAT and turns the same LUN into a ring of 512 KB video slots, each
 * holding an 8.7 KB header plus one JPEG at +8704. That ring is what the vendor app
 * reads, using SCSI Bulk-Only Transport on the mass-storage endpoints
 * (`libUsbRead` in `libuvccamera-lib.so`, see `DvrBot`).
 *
 * So this controller:
 * 1. opens the DVR and claims interface 0 (force),
 * 2. locates a slot whose header is valid,
 * 3. reads header + frame, cutting at the JPEG markers,
 * 4. hands the JPEG to the shared [MjpegFrameSink] used by the WiFi MJPEG source, so
 *    the HUD, detector, recorder and telemetry all behave identically.
 */
class DvrUsbCameraController(
    private val context: Context,
    private val listener: AusbcCameraController.CameraEventListener,
) {
    private companion object {
        const val TAG = "DvrCameraCtrl"

        /** Bytes of slot header before the JPEG (also the value in the header field). */
        const val FRAME_OFFSET = 8704
        const val HEADER_BLOCKS = 32 // 16 KB of header
        const val SLOT_BLOCKS = 1024 // 512 KB slot

        /** Slot bases probed at startup; the ring's first record sits near 448 KB. */
        val CANDIDATE_BASES = intArrayOf(896, 0, 1024, 512, 1536, 2048, 2560, 3072, 4096)
    }

    private val sink = MjpegFrameSink(context, listener, TAG)

    private var connection: UsbDeviceConnection? = null
    private var bot: DvrBot? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    private var thread: Thread? = null
    @Volatile
    private var running = false

    @Volatile
    var isOpen = false
        private set

    private var slotLba = -1
    private var lastFrameHash = 0
    private var stalePolls = 0
    private var framesDelivered = 0

    private var permissionReceiver: android.content.BroadcastReceiver? = null

    /**
     * USB access is granted through a system dialog, so the source has to finish opening
     * when the answer arrives rather than failing.
     */
    private fun registerPermissionReceiver() {
        if (permissionReceiver != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true
                HudLogger.i(TAG, "USB permission result: granted=$granted")
                unregisterPermissionReceiver()
                if (granted) open() else listener.onError("USB access to the U2 DVR was denied")
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            rx,
            android.content.IntentFilter(DvrUsbProbe.ACTION_USB_PERMISSION),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        permissionReceiver = rx
    }

    private fun unregisterPermissionReceiver() {
        val rx = permissionReceiver ?: return
        permissionReceiver = null
        runCatching { context.unregisterReceiver(rx) }
    }

    fun setOutputSurface(surface: Surface?) = sink.setOutputSurface(surface)

    /**
     * Starts the detector session before the DVR is opened. Even with the backend remembered
     * from a previous run, creating the session costs seconds of native initialisation (model
     * load, Vulkan pipelines); doing it now overlaps that with the home screen instead of with
     * the first frame. A no-op when detection is switched off in Settings.
     */
    fun prewarmDetector() = sink.prepare()

    /** True when the DVR is attached and this app already holds USB access to it. */
    fun hasPermission(): Boolean {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = DvrUsbProbe.findDevice(context) ?: return false
        return usb.hasPermission(device)
    }

    fun open() {
        if (isOpen || thread != null) return
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) {
            listener.onError("USB service unavailable")
            return
        }
        val device = DvrUsbProbe.findDevice(context)
        if (device == null) {
            listener.onError("U2 DVR not connected (VID 0x1902 / PID 0x3241)")
            return
        }
        if (!usb.hasPermission(device)) {
            listener.onStatusChanged("U2 DVR: grant USB access to start")
            HudLogger.i(TAG, "No USB permission yet - requesting it")
            registerPermissionReceiver()
            DvrUsbProbe.requestPermission(context)
            return
        }
        val conn = usb.openDevice(device)
        if (conn == null) {
            listener.onError("Cannot open the U2 DVR (openDevice returned null)")
            return
        }
        val iface = device.getInterface(0)
        if (!conn.claimInterface(iface, true)) {
            HudLogger.e(TAG, "claimInterface failed", null)
            conn.close()
            listener.onError("U2 DVR: cannot claim its USB interface")
            return
        }
        val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        val inEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        val outEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        if (inEp == null || outEp == null) {
            conn.releaseInterface(iface)
            conn.close()
            listener.onError("U2 DVR: missing bulk endpoints")
            return
        }

        connection = conn
        inEndpoint = inEp
        outEndpoint = outEp
        bot = DvrBot(conn, outEp, inEp)
        sink.prepare()
        isOpen = true
        slotLba = -1
        lastFrameHash = 0
        stalePolls = 0
        framesDelivered = 0

        listener.onConnected(device)
        listener.onStatusChanged("U2 DVR: starting video stream")
        HudLogger.i(TAG, "claimed interface ${iface.id}; starting BOT stream reader")

        running = true
        thread = Thread({ streamLoop() }, "dvr-stream").apply {
            isDaemon = true
            start()
        }
    }

    private fun streamLoop() {
        var announced = false
        try {
            while (running) {
                val frame = readFrame()
                if (frame == null) {
                    Thread.sleep(60)
                    continue
                }
                if (!announced) {
                    sink.setFrameSize(1280, 720)
                    listener.onStatusChanged("U2 DVR: streaming")
                    announced = true
                }
                sink.handleJpegFrame(frame)
                framesDelivered++
                if (framesDelivered % 30 == 0) {
                    HudLogger.i(TAG, "delivered $framesDelivered frames from slot $slotLba")
                }
            }
        } catch (t: Throwable) {
            if (running) {
                HudLogger.e(TAG, "stream loop failed", t)
                listener.onError("U2 DVR stream failed: ${t.message}")
            }
        } finally {
            HudLogger.i(TAG, "stream loop finished after $framesDelivered frames")
        }
    }

    /** Reads one JPEG frame, or null when the ring is being rewritten / unreadable. */
    private fun readFrame(): ByteArray? {
        val bot = this.bot ?: return null
        if (slotLba < 0 && !locateSlot()) return null

        val header = bot.read10(slotLba, HEADER_BLOCKS) ?: return null
        if (!DvrFrameParser.looksLikeSlotHeader(header)) {
            HudLogger.i(TAG, "slot $slotLba no longer a slot header; re-locating")
            slotLba = -1
            return null
        }
        val soi = DvrFrameParser.firstSoi(header).takeIf { it >= 0 } ?: FRAME_OFFSET
        val blocks = DvrFrameParser.blocksFor(soi, DvrFrameParser.frameSizeHint(header), SLOT_BLOCKS)
        val slot = bot.read10(slotLba, blocks) ?: return null
        val jpeg = DvrFrameParser.extractFrame(slot) ?: return null

        val hash = jpeg.contentHashCode()
        if (hash == lastFrameHash) {
            // Same frame: the DVR has not rewritten this slot yet - step to the next record.
            stalePolls++
            if (stalePolls >= 2) {
                stalePolls = 0
                val recordBlocks = ((FRAME_OFFSET + jpeg.size + 511) / 512).coerceAtLeast(64)
                slotLba += recordBlocks
                HudLogger.i(TAG, "polled frame unchanged; moving to slot $slotLba")
            }
            return null
        }
        lastFrameHash = hash
        stalePolls = 0
        return jpeg
    }

    /** Finds a 512 KB ring slot whose header carries a JPEG. */
    private fun locateSlot(): Boolean {
        val bot = this.bot ?: return false
        for (base in CANDIDATE_BASES) {
            if (probeSlot(base)) {
                slotLba = base
                HudLogger.i(TAG, "stream slot found at lba=$base (+${base * 512 / 1024} KB)")
                return true
            }
        }
        // Fallback: walk the first 16 MB in 64 KB steps (only at startup / after a wrap).
        var lba = 0
        while (lba < 32768) {
            if (probeSlot(lba)) {
                slotLba = lba
                HudLogger.i(TAG, "stream slot found by scan at lba=$lba")
                return true
            }
            lba += 128
        }
        HudLogger.w(TAG, "no video slot found - is the DVR in video mode?")
        return false
    }

    private fun probeSlot(lba: Int): Boolean {
        val bot = this.bot ?: return false
        val head = bot.read10(lba, HEADER_BLOCKS) ?: return false
        return DvrFrameParser.looksLikeSlotHeader(head)
    }

    // ---- recording passthrough (same behaviour as the UVC / WiFi sources) ----

    fun startRecording(): AusbcCameraController.RecordingStartInfo? = sink.startRecording()

    fun stopRecording(): RecordingStorageManager.RecordingResult? = sink.stopRecording()

    fun isRecording(): Boolean = sink.isRecording()

    fun updateRecordingDetections(detections: List<Detection>) = sink.updateRecordingDetections(detections)

    fun frameSize(): Pair<Int, Int> = sink.frameSize()

    fun close() {
        running = false
        unregisterPermissionReceiver()
        try {
            thread?.join(1500)
        } catch (_: InterruptedException) {
        }
        thread = null
        sink.close()
        val conn = connection
        val botInstance = bot
        bot = null
        connection = null
        if (conn != null) {
            try {
                val device = DvrUsbProbe.findDevice(context)
                if (device != null) conn.releaseInterface(device.getInterface(0))
            } catch (_: Throwable) {
            }
            conn.close()
        }
        if (botInstance != null) HudLogger.i(TAG, "DVR stream released (volume can remount)")
        isOpen = false
    }

    fun destroy() {
        close()
        sink.destroy()
    }
}
