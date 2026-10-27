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

        /** Jumps over empty slots tried before paying for a full re-locate sweep. */
        const val MAX_HEADER_JUMPS = 4

        /**
         * Consecutive slots that carried the same frame before giving up on walking the ring.
         * A card-reader-mode DVR serves one static image from every slot, and walking 512 KB at
         * a time then just burns device goodwill until the transport wedges.
         */
        const val MAX_STALE_ADVANCES = 8

        /** Cap on remembered dead slots before the set is reset. */
        const val MAX_STALE_SLOTS = 32

        /** Frame size reported for the file source; the preview file carries no size field. */
        const val PREVIEW_W = 1280
        const val PREVIEW_H = 720

        /** Poll interval for the preview file. 80 ms is smooth enough for a HUD and cheap to read. */
        const val PREVIEW_POLL_MS = 80L

        /**
         * Bounds on a believable `frame size` header field. A garbage value must not turn every
         * poll into a 512 KB read: this DVR resents large transfers.
         */
        const val MIN_FRAME_BYTES = 16 * 1024
        const val MAX_FRAME_BYTES = SLOT_BLOCKS * 512 - FRAME_OFFSET

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

        /**
         * A live DVR delivers ~15 frames/s. This long without a *new* frame means the session is
         * dead even if its reader thread still believes it is streaming - which is what the SAF
         * preview path looks like when the DVR is unplugged: every poll succeeds, the bytes are
         * just frozen.
         */
        const val STALL_RESTART_MS = 8000L

        /** A fresh session that never shows a frame within this is abandoned. */
        const val SESSION_START_TIMEOUT_MS = 15000L

        /**
         * Self-repairs in a row that produced no video. After this the source stops retrying and
         * reports the failure, so a Connect tap (or a replug) starts from a clean session.
         *
         * Five rather than three: a car hitting a bump can bounce the OTG contact through several
         * re-claims in a row, and each of those is cheap. The counter is reset by the first frame
         * that arrives, so only a genuinely absent DVR reaches the limit.
         */
        const val MAX_FAILED_RECOVERIES = 5
    }

    private val sink = MjpegFrameSink(context, listener, TAG)

    private var connection: UsbDeviceConnection? = null
    private var bot: DvrBot? = null
    private var outEndpoint: UsbEndpoint? = null
    private var inEndpoint: UsbEndpoint? = null

    private var thread: Thread? = null

    /** Guards the hand-over of the connection between [close] and the reader thread. */
    private val releaseLock = Any()

    /**
     * Serialises session start/stop. `open()` is called from the UI thread (Connect) *and* from
     * the reader itself (preview stall recovery), and `close()` from the UI thread while
     * `onAppBackgrounded` runs, so without this two readers could be started from one interface
     * claim - or a start could interleave with the teardown that is still releasing it.
     */
    private val lifecycleLock = Any()

    @Volatile
    private var running = false

    /**
     * Whether a session is *wanted*. Set by [open], cleared by [close]; the readers' own
     * self-repair path honours it, so a restart that was already in flight when the app went to
     * the background cannot reopen the DVR behind the lifecycle.
     */
    @Volatile
    private var wantOpen = false

    @Volatile
    var isOpen = false
        private set

    private var slotLba = -1
    private var lastFrameHash = 0
    private var stalePolls = 0
    private var framesDelivered = 0

    /**
     * Wall-clock time of the last frame delivered to the sink, or of the start of the current
     * session while none has arrived yet. The preview loop never sees a USB error - a frozen
     * VSFILE just returns the same bytes - so staleness has to be measured here.
     */
    @Volatile
    private var lastFrameAtMs = 0L

    /** When the current reader session started, so a session that never delivers can time out. */
    @Volatile
    private var sessionStartedAtMs = 0L

    /** Self-repairs in a row that produced no new frame. */
    private var failedRecoveries = 0

    /**
     * Bumped whenever a session is torn down, so a reader that is still winding down cannot
     * resurrect itself after the next session has already set `running` back to true.
     */
    @Volatile
    private var sessionGen = 0

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

    /**
     * Slots that made a transfer stall. Reading one wedges this DVR's whole USB stack (even
     * control transfers stop being answered), so a LBA that has failed once is never probed
     * again. 896 is a known false positive: it matches [DvrFrameParser.looksLikeSlotHeader] in
     * the VSFILE header region and every attempt to use it has killed the session.
     */
    private val badSlots = HashSet<Int>()

    /**
     * Slot that last produced a frame. Probed first on the next claim, which turns a ~250
     * command sweep into a single read and gets video on screen before the DVR gives up.
     */
    private var lastGoodSlot = -1

    /** Consecutive advances over LBAs that carried no slot header. */
    private var invalidHeaderJumps = 0

    /** Consecutive advances over slots that carried the same frame. */
    private var staleAdvances = 0

    /**
     * Slots that answered with a valid header but never produced a new frame. The DVR moves its
     * live slot when the interface is re-claimed, and the old slot keeps its header, so without
     * this the locate sweep would keep choosing the dead one.
     */
    private val staleSlots = HashSet<Int>()

    /** When the ring position was last advanced because the frame had not changed. */
    private var lastAdvanceMs = 0L

    private var permissionReceiver: android.content.BroadcastReceiver? = null
    private var deviceStateReceiver: android.content.BroadcastReceiver? = null
    private var permissionRequestPending = false
    private var permissionDenied = false

    /**
     * USB access is granted through a system dialog, so the source has to finish opening
     * when the answer arrives rather than failing.
     */
    private fun registerPermissionReceiver() = synchronized(lifecycleLock) {
        registerPermissionReceiverLocked()
    }

    private fun registerPermissionReceiverLocked() {
        if (permissionReceiver != null) return
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: android.content.Intent?) {
                val granted = intent?.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) == true
                HudLogger.i(TAG, "USB permission result: granted=$granted")
                unregisterPermissionReceiver()
                permissionRequestPending = false
                if (granted) {
                    permissionDenied = false
                    open(forcePermission = true)
                } else {
                    permissionDenied = true
                    listener.onError("USB access to the U2 DVR was denied")
                }
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
        permissionRequestPending = false
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
                permissionDenied = false
                permissionRequestPending = false
                badSlots.clear()
                staleSlots.clear()
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

    /** Mirrors the DVR's JPEG frames (preview, recording and detections alike). */
    fun setMirror(mirror: Boolean) = sink.setMirror(mirror)

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

    fun open(forcePermission: Boolean = false) {
        synchronized(lifecycleLock) {
            wantOpen = true
            openLocked(forcePermission)
        }
    }

    /**
     * Restart requested by a reader whose session died. Runs on its own short-lived thread so the
     * dying reader is not joined while it waits for [lifecycleLock] (the UI may be holding it).
     */
    private fun restartIfWanted() {
        Thread({
            synchronized(lifecycleLock) {
                if (!wantOpen) {
                    HudLogger.i(TAG, "session restart skipped: the source was closed")
                    return@synchronized
                }
                openLocked(forcePermission = false)
            }
        }, "dvr-restart").apply { isDaemon = true }.start()
    }

    private fun openLocked(forcePermission: Boolean) {
        if (forcePermission) {
            permissionDenied = false
            badSlots.clear()
            staleSlots.clear()
            // A user-initiated open starts the recovery budget over.
            failedRecoveries = 0
        }
        // A session that is still delivering frames wins. One that has gone quiet is dead even
        // though its reader still says it is open (the SAF preview path keeps polling a frozen
        // file forever), so a Connect tap must tear it down instead of silently returning -
        // otherwise the only fix is restarting the app.
        if (isOpen && !forcePermission && !isSessionStale()) return
        if (isOpen) {
            HudLogger.w(TAG, "re-opening the DVR: the previous session stopped delivering video")
            listener.onStatusChanged("U2 DVR: reconnecting")
        }
        if (!stopReader()) {
            HudLogger.w(TAG, "previous session is still alive; not claiming the interface twice")
            return
        }
        registerReceiver()
        // The file source is opt-in through a folder grant and wins when one exists. It is offered
        // because the Bulk-Only Transport path, which streams fine on other hardware, cannot
        // survive this phone's USB link: the DVR re-enumerates every ~6.5 s and each disappearance
        // kills the transfer in flight. Reading VSFILE issues only file reads, so a dropped one
        // costs nothing and is retried on the next poll. See DvrPreviewFile.
        if (DvrPreviewFile.hasGrant(context)) {
            val doc = DvrPreviewFile.previewDocument(context)
            HudLogger.i(TAG, "folder access granted - streaming ${DvrPreviewFile.PREVIEW_NAME} instead of claiming USB")
            HudLogger.i(TAG, "preview document: ${doc ?: "not found yet"}")
            isOpen = true
            running = true
            framesDelivered = 0
            markSessionStarted()
            listener.onStatusChanged("U2 DVR: reading preview file")
            val gen = sessionGen
            thread = Thread({ previewLoop(gen) }, "dvr-preview").apply {
                isDaemon = true
                start()
            }
            return
        }
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
            if (permissionRequestPending) {
                HudLogger.w(TAG, "USB permission request already pending — debounced")
                return
            }
            if (permissionDenied && !forcePermission) {
                HudLogger.i(TAG, "USB permission previously denied — waiting for user to tap Connect")
                listener.onStatusChanged("U2 DVR: grant USB access to start")
                return
            }
            permissionRequestPending = true
            listener.onStatusChanged("U2 DVR: grant USB access to start")
            HudLogger.i(TAG, "No USB permission yet - requesting it (forcePermission=$forcePermission)")
            registerPermissionReceiver()
            DvrUsbProbe.requestPermission(context)
            return
        }
        if (!claimStreaming(usb, device)) return

        listener.onConnected(device)
        listener.onStatusChanged("U2 DVR: starting video stream")
        HudLogger.i(TAG, "claimed interface 0; starting BOT stream reader")

        running = true
        markSessionStarted()
        val gen = sessionGen
        thread = Thread({ streamLoop(gen) }, "dvr-stream").apply {
            isDaemon = true
            start()
        }
    }

    /** Starts the stall clock for a session that is about to begin (see [isSessionStale]). */
    private fun markSessionStarted() {
        val now = System.currentTimeMillis()
        sessionStartedAtMs = now
        lastFrameAtMs = now
    }

    /**
     * True when the current session has stopped producing frames. A session that never delivered
     * one is judged by [SESSION_START_TIMEOUT_MS] instead, so the first, slower claim is not
     * mistaken for a stalled stream.
     */
    private fun isSessionStale(): Boolean {
        if (!isOpen) return true
        if (sessionStartedAtMs == 0L) return true
        val now = System.currentTimeMillis()
        val neverDelivered = lastFrameAtMs <= sessionStartedAtMs
        return if (neverDelivered) {
            now - sessionStartedAtMs > SESSION_START_TIMEOUT_MS
        } else {
            now - lastFrameAtMs > STALL_RESTART_MS
        }
    }

    /**
     * Tears down the previous reader. Returns false only when it is still holding the USB
     * connection, because claiming the interface twice would fail.
     */
    private fun stopReader(): Boolean = synchronized(lifecycleLock) { stopReaderLocked() }

    private fun stopReaderLocked(): Boolean {
        running = false
        isOpen = false
        sessionGen++
        val previous = thread
        if (previous == null) {
            releaseStreaming()
            return true
        }
        if (!previous.isAlive) {
            thread = null
            releaseStreaming()
            return true
        }
        HudLogger.i(TAG, "waiting for the previous DVR session to release the interface")
        previous.interrupt()
        try {
            previous.join(PREVIOUS_SESSION_JOIN_MS)
        } catch (_: InterruptedException) {
        }
        if (previous.isAlive) {
            // Only the Bulk-Only Transport path holds the interface; a preview reader that is
            // still winding down can be left behind, it stops at its next generation check.
            if (connection != null) return false
            return true
        }
        thread = null
        releaseStreaming()
        return true
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
        // Do NOT send the vendor's changeMode request here. It was tried in v1.0.8 as
        // controlTransfer(0x42, 0x00, 0, 0, {1}) and the DVR answers -1 every time, after which
        // the first read stalls within 30 ms - v1.0.7 delivered one frame, v1.0.8 delivered none.
        // The mode switch therefore does not exist on this mass-storage interface; the vendor app
        // changes mode through the HWC command file on the mounted card instead.
        // READ CAPACITY before anything else: a READ(10) past the end of the LUN comes back as a
        // CHECK CONDITION, which stalls the bulk-IN pipe - and a single stall is what leaves this
        // transport dead for the rest of the session. Knowing the size keeps every sweep inside
        // the LUN. It also tells a card-reader LUN apart from the video ring at a glance.
        val capacity = runCatching { newBot.readCapacity() }.getOrNull() ?: 0
        capacityBlocks = if (capacity > 0) capacity else 0
        HudLogger.i(
            TAG,
            "DVR capacity $capacityBlocks blocks (${capacityBlocks.toLong() * 512 / 1024} KB), " +
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
        invalidHeaderJumps = 0
        staleAdvances = 0
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
    private fun reclaim(): Boolean = synchronized(lifecycleLock) { reclaimLocked() }

    private fun reclaimLocked(): Boolean {
        if (!running) return false
        HudLogger.w(TAG, "re-claiming the DVR interface after a stall")
        badSlots.clear()
        staleSlots.clear()
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

    /**
     * Reads the DVR's preview file and feeds it through the shared JPEG sink.
     *
     * A frozen VSFILE cannot be told apart from a healthy one at the read level - every poll
     * succeeds, the bytes just never change - so staleness is judged against [lastFrameAtMs].
     * Without this the HUD stayed on "streaming" forever and a Connect tap did nothing, because
     * `isOpen` was still true; only restarting the app cleared it.
     */
    private fun previewLoop(gen: Int) {
        var announced = false
        var lastHash = 0
        var misses = 0
        var restart = false
        try {
            sink.prepare()
            while (running && sessionGen == gen) {
                val quietMs = System.currentTimeMillis() - lastFrameAtMs
                if (quietMs > STALL_RESTART_MS) {
                    failedRecoveries++
                    HudLogger.w(
                        TAG,
                        "preview file has not changed for $quietMs ms " +
                            "($failedRecoveries/$MAX_FAILED_RECOVERIES)"
                    )
                    if (failedRecoveries >= MAX_FAILED_RECOVERIES) {
                        HudLogger.e(TAG, "DVR preview is gone; waiting for a Connect tap", null)
                        listener.onError("U2 DVR: no new video — tap Connect to retry")
                        running = false
                        isOpen = false
                    } else {
                        listener.onStatusChanged("U2 DVR: no new frame — reconnecting")
                        restart = true
                    }
                    break
                }
                val bytes = DvrPreviewFile.readPreview(context)
                // The read can take seconds on a USB volume: do not act on it if this session
                // was torn down (and possibly replaced) while it was in flight.
                if (sessionGen != gen) break
                val jpeg = bytes?.let { DvrFrameParser.lastCompleteFrame(it) }
                if (jpeg == null) {
                    misses++
                    // Ten empty polls at 500 ms is long enough to be a real problem and short
                    // enough that a replug is noticed quickly.
                    if (misses == 10) {
                        HudLogger.w(TAG, "no JPEG in ${DvrPreviewFile.PREVIEW_NAME} (read ${bytes?.size ?: 0} bytes)")
                        listener.onStatusChanged("U2 DVR: no preview in ${DvrPreviewFile.PREVIEW_NAME}")
                    }
                    Thread.sleep(500)
                    continue
                }
                misses = 0
                val hash = jpeg.contentHashCode()
                if (hash != lastHash) {
                    lastHash = hash
                    failedRecoveries = 0
                    lastFrameAtMs = System.currentTimeMillis()
                    if (!announced) {
                        sink.setFrameSize(PREVIEW_W, PREVIEW_H)
                        listener.onStatusChanged("U2 DVR: streaming")
                        announced = true
                    }
                    sink.handleJpegFrame(jpeg)
                    framesDelivered++
                    if (framesDelivered % 30 == 0) {
                        HudLogger.i(TAG, "delivered $framesDelivered preview frames (${jpeg.size} bytes)")
                    }
                }
                Thread.sleep(PREVIEW_POLL_MS)
            }
        } catch (t: Throwable) {
            if (running && sessionGen == gen) {
                HudLogger.e(TAG, "preview loop failed", t)
                listener.onError("U2 DVR preview failed: ${t.message}")
            }
        } finally {
            if (sessionGen == gen) isOpen = false
            HudLogger.i(TAG, "preview loop finished after $framesDelivered frames")
        }
        // A session that stalled is rebuilt from scratch: re-resolving the document picks up a
        // volume that was unmounted and mounted again.
        if (restart && running && sessionGen == gen) {
            HudLogger.i(TAG, "restarting the DVR preview session")
            thread = null
            restartIfWanted()
        }
    }

    private fun streamLoop(gen: Int) {
        var announced = false
        var misses = 0
        var reclaims = 0
        var reopenAttempts = 0
        try {
            // See CLAIM_SETTLE_MS: the DVR is not ready for commands the instant the claim lands.
            Thread.sleep(CLAIM_SETTLE_MS)
            while (running && sessionGen == gen) {
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
                            failedRecoveries = 0
                            lastFrameAtMs = System.currentTimeMillis()
                        } else if (giveUpOrRetry("USB pipe did not come back")) {
                            break
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
                            failedRecoveries = 0
                            lastFrameAtMs = System.currentTimeMillis()
                        } else if (giveUpOrRetry("video did not come back")) {
                            break
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
                failedRecoveries = 0
                if (!announced) {
                    sink.setFrameSize(1280, 720)
                    listener.onStatusChanged("U2 DVR: streaming")
                    announced = true
                }
                sink.handleJpegFrame(frame)
                lastFrameAtMs = System.currentTimeMillis()
                framesDelivered++
                if (framesDelivered % 30 == 0) {
                    HudLogger.i(TAG, "delivered $framesDelivered frames from slot $slotLba")
                }
            }
        } catch (t: Throwable) {
            if (running && sessionGen == gen) {
                HudLogger.e(TAG, "stream loop failed", t)
                listener.onError("U2 DVR stream failed: ${t.message}")
            }
        } finally {
            // The reader owns the teardown: close() is called from the UI thread and must not wait
            // for a USB transfer that may still be in flight.
            if (sessionGen == gen) isOpen = false
            releaseStreaming()
            HudLogger.i(TAG, "stream loop finished after $framesDelivered frames")
        }
    }

    /**
     * Records a failed self-repair. Returns true when the session should be abandoned: after
     * [MAX_FAILED_RECOVERIES] the loop stops claiming a DVR that is not coming back, so the next
     * Connect tap (or a replug) starts from a clean state instead of hitting an "already open"
     * session that never delivers anything.
     */
    private fun giveUpOrRetry(reason: String): Boolean {
        requestPermissionIfMissing()
        failedRecoveries++
        if (failedRecoveries < MAX_FAILED_RECOVERIES) return false
        HudLogger.e(TAG, "$reason after $failedRecoveries re-claims; waiting for Connect", null)
        listener.onError("U2 DVR: $reason — tap Connect to retry")
        running = false
        isOpen = false
        return true
    }

    /** Re-asks for USB access when a re-claim failed because the permission went away. */
    private fun requestPermissionIfMissing() {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val device = DvrUsbProbe.findDevice(context) ?: return
        if (usb.hasPermission(device) || permissionRequestPending) return
        HudLogger.i(TAG, "USB access was lost while streaming - requesting it again")
        permissionRequestPending = true
        registerPermissionReceiver()
        listener.onStatusChanged("U2 DVR: grant USB access to resume")
        DvrUsbProbe.requestPermission(context)
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
        var slot = readSlot(bot, slotLba, safeBlocks) ?: run {
            // A read that produced no answer at this LBA means the request itself is what the DVR
            // choked on: blacklist it so no later session walks back into it.
            if (bot.lastStatus < 0) {
                HudLogger.w(TAG, "slot $slotLba wedged the transport; blacklisting it")
                badSlots.add(slotLba)
                slotLba = -1
                lastFrameBytes = 0
            }
            return null
        }

        if (!DvrFrameParser.looksLikeSlotHeader(slot)) {
            if (lastFrameBytes > 0) {
                // Either the cached size excluded the JPEG or the ring moved on: drop the hint and
                // let the next poll re-read the header properly.
                lastFrameBytes = 0
                return null
            }
            // Not a slot header: the ring has moved on. Step forward through the grid a few times
            // before paying for a full sweep, which costs hundreds of commands this DVR cannot
            // afford.
            if (++invalidHeaderJumps <= MAX_HEADER_JUMPS) {
                slotLba += SLOT_BLOCKS
                return null
            }
            invalidHeaderJumps = 0
            HudLogger.i(TAG, "no slot header after $MAX_HEADER_JUMPS jumps; re-locating from $slotLba")
            slotLba = -1
            return null
        }
        invalidHeaderJumps = 0

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
        if (hint in MIN_FRAME_BYTES..MAX_FRAME_BYTES) lastFrameBytes = hint

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
                if (staleAdvances >= MAX_STALE_ADVANCES) {
                    // Every slot in the ring carried the same picture. After the interface is
                    // re-claimed the DVR moves its live slot, so the one we anchored on keeps a
                    // valid header while never being rewritten - re-selecting it (which is what
                    // probing lastGoodSlot first does) loops here forever. Mark it stale and drop
                    // the anchor so the sweep actually looks at other slots.
                    staleAdvances = 0
                    if (staleSlots.size > MAX_STALE_SLOTS) staleSlots.clear()
                    staleSlots.add(slotLba)
                    lastGoodSlot = -1
                    HudLogger.w(TAG, "slot $slotLba stopped updating; re-locating from scratch")
                    slotLba = -1
                    lastFrameBytes = 0
                    return null
                }
                staleAdvances++
                // The ring is a fixed 512 KB grid, not a packed list of frames: the two slots this
                // device has been seen writing (2688 and 3712) are exactly SLOT_BLOCKS apart.
                // Advancing by the frame size instead lands in the middle of the current slot,
                // where the header check fails and the resulting re-locate sweep walks into LBAs
                // that wedge the transport.
                slotLba += SLOT_BLOCKS
                lastFrameBytes = 0
                HudLogger.i(TAG, "polled frame unchanged; moving to slot $slotLba")
            }
            return null
        }
        lastFrameHash = hash
        stalePolls = 0
        staleAdvances = 0
        lastGoodSlot = slotLba
        return jpeg
    }

    /** Reads [blocks] 512-byte blocks into the shared scratch buffer. */
    private fun readSlot(bot: DvrBot, lba: Int, blocks: Int): ByteArray? {
        val need = blocks * 512
        if (scratch.size < need) scratch = ByteArray(need)
        val startedAt = System.currentTimeMillis()
        val got = bot.read10Into(lba, blocks, scratch)
        if (got == need) return scratch
        // The (lba, blocks) pair is the one fact needed to tell a bad request from a slow device,
        // and it is what made the earlier logs unreadable.
        HudLogger.w(
            TAG,
            "read lba=$lba blocks=$blocks failed (got=$got status=${bot.lastStatus} " +
                "in ${System.currentTimeMillis() - startedAt} ms)",
        )
        return null
    }

    /** Finds a 512 KB ring slot whose header carries a JPEG. */
    private fun locateSlot(): Boolean {
        val now = System.currentTimeMillis()
        if (lastFailedLocateMs != 0L && now - lastFailedLocateMs < LOCATE_RETRY_INTERVAL_MS) {
            return false
        }
        // The slot that worked last time is almost always still a slot, and a single read costs
        // far less device goodwill than the sweep below.
        if (lastGoodSlot >= 0 && probeSlot(lastGoodSlot)) {
            slotLba = lastGoodSlot
            lastFailedLocateMs = 0L
            HudLogger.i(TAG, "stream slot found at last good lba=$lastGoodSlot")
            return true
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
        if (lba in badSlots || lba in staleSlots) return false
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

    fun close() = synchronized(lifecycleLock) { closeLocked() }

    private fun closeLocked() {
        // Cleared before the lock is released, so a pending restart sees it.
        wantOpen = false
        // Deliberately does NOT wait for the reader: this is called from MainActivity's "
        // switchMode/onAppBackgrounded", i.e. on the UI thread, and the reader may be blocked in a
        // USB transfer. The reader notices `running = false`, finishes its loop and releases the
        // interface in [releaseStreaming]; open() waits for that before claiming it again.
        val hadReader = thread != null
        running = false
        // Any reader still winding down must not resume if a new session restarts immediately.
        sessionGen++
        permissionRequestPending = false
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

    fun reloadDetector() {
        sink.reloadDetector()
    }
}
