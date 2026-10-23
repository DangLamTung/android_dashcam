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

        /**
         * Read timeout for the video path. Frames arrive every ~60 ms, so a second-long wait only
         * delays a teardown when the DVR disappears mid-transfer.
         */
        const val STREAM_TIMEOUT_MS = 400

        /** Polls without a frame before the HUD is told the DVR stopped sending video. */
        const val STALL_REPORT_POLLS = 20

        /** Polls without a frame before the session is abandoned; a replug or mode change retries. */
        const val STALL_GIVE_UP_POLLS = 60

        /** How long to wait before another self-repair attempt when re-claiming failed. */
        const val RECLAIM_RETRY_MS = 1500L

        /** A permanently dead DVR must not keep painting the UI red. */
        const val MAX_REPORTED_RECLAIMS = 2

        /** How long open() waits for a previous session to finish releasing the interface. */
        const val PREVIOUS_SESSION_JOIN_MS = 800L

        /**
         * Minimum gap between failed slot sweeps. A sweep is up to 265 SCSI transfers, so a DVR
         * that stopped answering must not be swept on every poll; one attempt per second is
         * enough to notice it coming back.
         */
        const val LOCATE_RETRY_INTERVAL_MS = 1000L

        /** Minimum gap between "frame unchanged, move on" jumps. */
        const val STALE_ADVANCE_MIN_MS = 30L

        /** Slot bases probed at startup; the ring's first record sits near 448 KB. */
        val CANDIDATE_BASES = intArrayOf(896, 0, 1024, 512, 1536, 2048, 2560, 3072, 4096)

        /** Blocks swept by the fallback scan when READ CAPACITY could not be read (16 MB). */
        const val SCAN_LIMIT_BLOCKS = 32768

        /**
         * Pause after claiming the interface. The DVR is still switching from card-reader to
         * ring mode when claimInterface() returns; commanding it immediately produces a stalled
         * transfer that costs far more than this wait.
         */
        const val CLAIM_SETTLE_MS = 250L

        /**
         * Consecutive transfers without a CSW that mean the pipe is dead rather than busy.
         * Polling cannot revive a stalled endpoint and the DVR's own watchdog resets it about
         * six seconds after it stops answering, so the device is reopened well before that.
         */
        const val TRANSPORT_FAILURES_BEFORE_REOPEN = 3

        /** Wait between re-open attempts once the DVR has stopped answering at all. */
        const val REOPEN_BACKOFF_MS = 5000L

        /** Re-open attempts that are tried back-to-back before falling back to REOPEN_BACKOFF_MS. */
        const val REOPENS_BEFORE_BACKOFF = 2
    }

    private val sink = MjpegFrameSink(context, listener, TAG)

    private var connection: UsbDeviceConnection? = null
    private var bot: DvrBot? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    private var thread: Thread? = null

    /** Guards the hand-over of the connection between [close] and the reader thread. */
    private val releaseLock = Any()

    @Volatile
    private var running = false

    @Volatile
    var isOpen = false
        private set

    private var slotLba = -1
    private var lastFrameHash = 0
    private var stalePolls = 0
    private var framesDelivered = 0

    /** Frame size reported by the last good slot header, used to read a frame in ONE transfer. */
    private var lastFrameBytes = 0

    /** Reused read buffer: a frame read is ~90 KB and the loop runs at ~15 fps. */
    private var scratch = ByteArray(0)

    /** When the last slot sweep failed, so sweeps can be rate limited. */
    private var lastFailedLocateMs = 0L

    /**
     * Block count READ CAPACITY reported for the claimed LUN, 0 when it never answered. Reads are
     * kept inside it: an out-of-range READ stalls the bulk-IN pipe, which this DVR does not
     * recover from on its own.
     */
    private var capacityBlocks = 0

    /** When the ring position was last advanced because the frame had not changed. */
    private var lastAdvanceMs = 0L

    private var permissionReceiver: android.content.BroadcastReceiver? = null
    private var deviceStateReceiver: android.content.BroadcastReceiver? = null

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

    /**
     * The DVR is hot-plugged: without this, pulling the cable mid-stream leaves the reader
     * polling a dead connection with no indication in the UI.
     */
    fun registerReceiver() {
        if (deviceStateReceiver != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
                val device = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_DEVICE,
                    android.hardware.usb.UsbDevice::class.java,
                )
                if (device == null || !DvrVolume.matches(device.vendorId, device.productId)) return
                HudLogger.w(TAG, "U2 DVR unplugged")
                val wasStreaming = isOpen
                close()
                if (wasStreaming) listener.onDisconnected()
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            rx,
            android.content.IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        deviceStateReceiver = rx
    }

    fun unregister() {
        unregisterPermissionReceiver()
        val rx = deviceStateReceiver ?: return
        deviceStateReceiver = null
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
        // A session that is already streaming wins; otherwise wait for the previous one to hand
        // the interface back before claiming it again.
        if (isOpen) return
        val previous = thread
        if (previous != null && previous.isAlive) {
            HudLogger.i(TAG, "waiting for the previous DVR session to release the interface")
            previous.interrupt()
            try {
                previous.join(PREVIOUS_SESSION_JOIN_MS)
            } catch (_: InterruptedException) {
            }
            if (previous.isAlive) {
                HudLogger.w(TAG, "previous session still alive; not claiming the interface twice")
                return
            }
        }
        thread = null
        registerReceiver()
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
        if (!claimStreaming(usb, device)) return

        listener.onConnected(device)
        listener.onStatusChanged("U2 DVR: starting video stream")
        HudLogger.i(TAG, "claimed interface 0; starting BOT stream reader")

        running = true
        thread = Thread({ streamLoop() }, "dvr-stream").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Opens the device and claims its mass-storage interface, leaving [bot] ready to read frames.
     * Failures are reported to the listener here; the return value says whether to start reading.
     */
    private fun claimStreaming(usb: UsbManager, device: android.hardware.usb.UsbDevice): Boolean {
        val conn = usb.openDevice(device)
        if (conn == null) {
            listener.onError("Cannot open the U2 DVR (openDevice returned null)")
            return false
        }
        val iface = device.getInterface(0)
        if (!conn.claimInterface(iface, true)) {
            HudLogger.e(TAG, "claimInterface failed", null)
            conn.close()
            listener.onError("U2 DVR: cannot claim its USB interface")
            return false
        }
        val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
        val inEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
        val outEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
        if (inEp == null || outEp == null) {
            conn.releaseInterface(iface)
            conn.close()
            listener.onError("U2 DVR: missing bulk endpoints")
            return false
        }
        HudLogger.i(
            TAG,
            "iface id=${iface.id} class=${iface.interfaceClass} sub=${iface.interfaceSubclass} " +
                "proto=${iface.interfaceProtocol} eps=${iface.endpointCount}; " +
                "bulk in=0x%02x out=0x%02x".format(inEp.address, outEp.address),
        )
        val newBot = DvrBot(conn, outEp, inEp, iface.id, STREAM_TIMEOUT_MS)
        // READ CAPACITY before anything else: a READ(10) past the end of the LUN comes back as a
        // CHECK CONDITION, which stalls the bulk-IN pipe - and a single stall is what leaves this
        // transport dead for the rest of the session. Knowing the size keeps every sweep inside
        // the LUN. It also tells a card-reader LUN apart from the video ring at a glance.
        val capacity = runCatching { newBot.readCapacity() }.getOrNull() ?: 0
        capacityBlocks = if (capacity > 0) capacity else 0
        HudLogger.i(
            TAG,
            "DVR capacity $capacityBlocks blocks (${capacityBlocks * 512 / 1024} KB), " +
                "status=${newBot.lastStatus}",
        )
        synchronized(releaseLock) {
            connection = conn
            inEndpoint = inEp
            outEndpoint = outEp
            bot = newBot
        }
        sink.prepare()
        isOpen = true
        slotLba = -1
        lastFrameHash = 0
        stalePolls = 0
        framesDelivered = 0
        lastFrameBytes = 0
        lastFailedLocateMs = 0L
        return true
    }

    /**
     * Releases the interface and claims it again. Measured behaviour of this DVR: once it stops
     * answering, only a fresh claim brings the video back - a replug is not needed - so the reader
     * repairs itself instead of leaving the HUD dead until the user switches source.
     */
    private fun reclaim(): Boolean {
        if (!running) return false
        HudLogger.w(TAG, "re-claiming the DVR interface after a stall")
        releaseStreaming()
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val device = DvrUsbProbe.findDevice(context) ?: return false
        if (!usb.hasPermission(device)) return false
        if (!claimStreaming(usb, device)) {
            HudLogger.w(TAG, "re-claim failed")
            return false
        }
        // Same settle as the initial open: the firmware needs a moment after the claim before it
        // accepts commands again.
        try {
            Thread.sleep(CLAIM_SETTLE_MS)
        } catch (_: InterruptedException) {
            return false
        }
        listener.onStatusChanged("U2 DVR: streaming")
        HudLogger.i(TAG, "interface re-claimed; resuming the stream")
        return true
    }

    private fun streamLoop() {
        var announced = false
        var misses = 0
        var reclaims = 0
        var reopenAttempts = 0
        try {
            // See CLAIM_SETTLE_MS: the DVR is not ready for commands the instant the claim lands.
            Thread.sleep(CLAIM_SETTLE_MS)
            while (running) {
                val frame = readFrame()
                if (frame == null) {
                    misses++
                    // Missing frames while the ring is rewritten is normal and resolves on its
                    // own; a pipe that keeps failing to produce a CSW does not. Every doomed
                    // retry costs a timeout and the DVR's watchdog resets it ~6 s after it stops
                    // answering, so a dead pipe is repaired immediately instead of after
                    // STALL_GIVE_UP_POLLS polls (~30 s, which is far too late).
                    val pipeFailures = bot?.transportFailures ?: 0
                    if (pipeFailures >= TRANSPORT_FAILURES_BEFORE_REOPEN) {
                        reopenAttempts++
                        if (reopenAttempts <= MAX_REPORTED_RECLAIMS) {
                            HudLogger.e(TAG, "$pipeFailures transfers in a row got no CSW; re-opening", null)
                            listener.onError("U2 DVR stopped answering USB transfers")
                        }
                        HudLogger.i(TAG, "re-opening the DVR to recover the USB pipe (attempt $reopenAttempts)")
                        if (reclaim()) {
                            misses = 0
                            announced = false
                        }
                        // Keep trying, but stop hammering a DVR that is not coming back: each
                        // re-open resets the device, and a fast loop would only trip its watchdog
                        // again. A replug still opens it immediately.
                        val backoff = reopenAttempts > REOPENS_BEFORE_BACKOFF
                        if (backoff) {
                            HudLogger.w(TAG, "DVR not answering after $reopenAttempts re-opens; backing off")
                        }
                        Thread.sleep(if (backoff) REOPEN_BACKOFF_MS else RECLAIM_RETRY_MS)
                        continue
                    }
                    // A null read is normal while the ring is being rewritten, but a long run of
                    // them means the DVR is not sending video any more (wrong mode, unplugged
                    // cable, or the vendor app took the interface back). Say so instead of
                    // spinning silently, then keep retrying slowly so a temporary stall recovers
                    // on its own - the reader checks `running` every iteration, so this loop can
                    // always be stopped.
                    if (misses == STALL_REPORT_POLLS) {
                        HudLogger.w(TAG, "no frame for $misses polls")
                        listener.onStatusChanged("U2 DVR: no video (check the DVR and USB mode)")
                    } else if (misses >= STALL_GIVE_UP_POLLS) {
                        reclaims++
                        if (reclaims <= MAX_REPORTED_RECLAIMS) {
                            HudLogger.e(TAG, "no video for $misses polls", null)
                            listener.onError("U2 DVR stopped sending video")
                        }
                        // A fresh claim is what brings this DVR back, so try it here rather than
                        // leaving the user to switch camera source and back.
                        if (reclaim()) {
                            misses = 0
                            announced = false
                        } else {
                            Thread.sleep(RECLAIM_RETRY_MS)
                        }
                    }
                    Thread.sleep(if (misses >= STALL_GIVE_UP_POLLS) 500 else 60)
                    continue
                }
                if (misses >= STALL_REPORT_POLLS) {
                    HudLogger.i(TAG, "video is back after $misses empty polls")
                    announced = false
                }
                misses = 0
                reclaims = 0
                reopenAttempts = 0
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
            // The reader owns the teardown: close() is called from the UI thread and must not wait
            // for a USB transfer that may still be in flight.
            isOpen = false
            releaseStreaming()
            HudLogger.i(TAG, "stream loop finished after $framesDelivered frames")
        }
    }

    /** Reads one JPEG frame, or null when the ring is being rewritten / unreadable. */
    private fun readFrame(): ByteArray? {
        val bot = this.bot ?: return null
        if (!running) return null
        if (slotLba < 0 && !locateSlot()) return null

        // One transfer per frame: size the read from the previous frame's header hint. The slot
        // header is inside that same read, so nothing is fetched twice. The first frame of a
        // session has no hint yet and reads just the header block.
        val blocks = if (lastFrameBytes > 0) {
            DvrFrameParser.blocksForKnownSize(lastFrameBytes, SLOT_BLOCKS)
        } else {
            HEADER_BLOCKS
        }
        // Clamp to the LUN: reading past it is a CHECK CONDITION, and the stall it causes is
        // exactly what kills this transport.
        val safeBlocks = if (capacityBlocks > 0) {
            (capacityBlocks - slotLba).coerceIn(1, blocks)
        } else {
            blocks
        }
        var slot = readSlot(bot, slotLba, safeBlocks) ?: return null

        if (!DvrFrameParser.looksLikeSlotHeader(slot)) {
            if (lastFrameBytes > 0) {
                // Either the cached size excluded the JPEG or the ring moved on: drop the hint and
                // let the next poll re-read the header properly.
                lastFrameBytes = 0
                return null
            }
            HudLogger.i(TAG, "slot $slotLba no longer a slot header; re-locating")
            slotLba = -1
            return null
        }

        val hint = DvrFrameParser.frameSizeHint(slot)
        var jpeg = DvrFrameParser.extractFrame(slot)
        if (jpeg == null) {
            // The cached size was short (the frame grew) or the slot was mid-write: re-read the
            // whole slot once, sized from the hint that is now in hand.
            val full = DvrFrameParser.blocksFor(FRAME_OFFSET, hint, SLOT_BLOCKS)
            if (full > safeBlocks) {
                slot = readSlot(bot, slotLba, full) ?: return null
                jpeg = DvrFrameParser.extractFrame(slot)
            }
            if (jpeg == null) {
                lastFrameBytes = 0
                return null
            }
        }
        if (hint > 0) lastFrameBytes = hint

        val hash = jpeg.contentHashCode()
        if (hash == lastFrameHash) {
            // Same frame: the DVR has not rewritten this slot yet - step to the next record.
            // The advance is rate limited so that a burst of identical polls (which is what a
            // genuinely static scene produces until the DVR's 1 Hz OSD clock ticks) cannot skip
            // several records in one go; the slot headers still validate every target.
            stalePolls++
            val nowMs = System.currentTimeMillis()
            if (stalePolls >= 2 && nowMs - lastAdvanceMs >= STALE_ADVANCE_MIN_MS) {
                stalePolls = 0
                lastAdvanceMs = nowMs
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

    /** Reads [blocks] 512-byte blocks into the shared scratch buffer. */
    private fun readSlot(bot: DvrBot, lba: Int, blocks: Int): ByteArray? {
        val need = blocks * 512
        if (scratch.size < need) scratch = ByteArray(need)
        return if (bot.read10Into(lba, blocks, scratch) == need) scratch else null
    }

    /** Finds a 512 KB ring slot whose header carries a JPEG. */
    private fun locateSlot(): Boolean {
        val now = System.currentTimeMillis()
        if (lastFailedLocateMs != 0L && now - lastFailedLocateMs < LOCATE_RETRY_INTERVAL_MS) {
            return false
        }
        for (base in CANDIDATE_BASES) {
            if (!running) return false
            if (probeSlot(base)) {
                slotLba = base
                lastFailedLocateMs = 0L
                HudLogger.i(TAG, "stream slot found at lba=$base (+${base * 512 / 1024} KB)")
                return true
            }
            // A probe that could not complete is not "this LBA holds no slot": the pipe needs
            // repairing, and firing the rest of the sweep at a device that is not answering only
            // burns its watchdog budget.
            if ((bot?.transportFailures ?: 0) > 0) return false
        }
        // Fallback: walk the first 16 MB in 64 KB steps (only at startup / after a wrap). This is
        // up to 256 SCSI transfers, so it has to abort the moment the source is asked to stop -
        // otherwise close() cannot keep its promise and the thread outlives the connection.
        var lba = 0
        val limit = if (capacityBlocks > 0) minOf(SCAN_LIMIT_BLOCKS, capacityBlocks) else SCAN_LIMIT_BLOCKS
        while (lba < limit) {
            if (!running) return false
            if (probeSlot(lba)) {
                slotLba = lba
                lastFailedLocateMs = 0L
                HudLogger.i(TAG, "stream slot found by scan at lba=$lba")
                return true
            }
            if ((bot?.transportFailures ?: 0) > 0) return false
            lba += 128
        }
        lastFailedLocateMs = System.currentTimeMillis()
        HudLogger.w(TAG, "no video slot found in $limit blocks - is the DVR in video mode?")
        return false
    }

    private fun probeSlot(lba: Int): Boolean {
        val bot = this.bot ?: return false
        // Never read past the end of the LUN: that is a CHECK CONDITION, and the CHECK CONDITION
        // is what stalls the bulk-IN pipe on this DVR.
        if (capacityBlocks > 0 && lba + HEADER_BLOCKS > capacityBlocks) return false
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
        // Deliberately does NOT wait for the reader: this is called from MainActivity's "
        // switchMode/onAppBackgrounded", i.e. on the UI thread, and the reader may be blocked in a
        // USB transfer. The reader notices `running = false`, finishes its loop and releases the
        // interface in [releaseStreaming]; open() waits for that before claiming it again.
        val hadReader = thread != null
        running = false
        unregisterPermissionReceiver()
        if (!hadReader) releaseStreaming()
        isOpen = false
        sink.close()
    }

    /**
     * Gives the USB interface and the connection back. Called by the reader thread when it stops,
     * or directly from [close] when there never was one.
     */
    private fun releaseStreaming() {
        val conn: UsbDeviceConnection?
        val botInstance: DvrBot?
        synchronized(releaseLock) {
            conn = connection
            botInstance = bot
            connection = null
            bot = null
        }
        if (conn == null) return
        try {
            val device = DvrUsbProbe.findDevice(context)
            if (device != null) conn.releaseInterface(device.getInterface(0))
        } catch (_: Throwable) {
        }
        try {
            conn.close()
        } catch (_: Throwable) {
        }
        if (botInstance != null) HudLogger.i(TAG, "DVR stream released (volume can remount)")
    }

    fun destroy() {
        close()
        unregister()
        sink.destroy()
    }
}
