package com.driving.hud.usbcamera.camera

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.system.Os
import android.system.OsConstants
import android.view.Surface
import com.driving.hud.usbcamera.R
import com.driving.hud.usbcamera.detect.Detector
import com.driving.hud.usbcamera.detect.DetectorFactory
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.detect.OnnxDetector
import com.driving.hud.usbcamera.record.MkvRecorder
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.Size
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.UVCCamera
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Focus state of the camera.
 *
 * The engine's `getFocus()/setFocus()` work in **percent (0..100)**, not raw UVC units:
 * `UVCCamera.setFocus(p)` maps `p` onto the camera's absolute focus range internally.
 * [percent] is 0 = nearest, 100 = furthest.
 */
data class FocusInfo(
    val supported: Boolean,
    val auto: Boolean,
    val percent: Int
)

/** Everything the HUD readout needs, sampled once a second. */
data class Telemetry(
    /** Frames received from the camera in the last 1 s window. */
    val fps: Int,
    /** Measured gap between consecutive frames, in ms (smoothed). */
    val frameIntervalMs: Float,
    /** This process' CPU time as a percentage of ONE core (100 = a full core). */
    val cpuPercent: Int,
    /** SoC/CPU thermal zone in °C, or -1 when unreadable. */
    val tempC: Int,
    /** Wall time of the last detection pass in ms, or -1 when detection is off. */
    val inferMs: Float,
    /** Execution provider the detector settled on, e.g. "QNN", "NNAPI", "CPU". */
    val provider: String
)

/**
 * Camera layer backed by the open-source **UVCCamera** engine from
 * jiangdongguo/AndroidUSBCamera (`libuvc` AAR: libuvc + libusb + libjpeg-turbo).
 *
 * It keeps the public surface of the old hand-rolled UsbCameraManager so the HUD UI
 * does not have to change. The engine does the UVC descriptor parsing + probe/commit
 * + isochronous capture + MJPEG decode natively, which is what the bespoke path was
 * reimplementing.
 */
class AusbcCameraController(
    private val context: Context,
    private val listener: CameraEventListener
) {
    companion object {
        private const val TAG = "UVCCameraEngine"
        private const val PREVIEW_W = 1280
        private const val PREVIEW_H = 720
        private const val MIN_FPS = 10
        private const val MAX_FPS = 31

        /** Mode this hardware is known to stream at; used when a requested mode dies. */
        private const val SAFE_W = 1280
        private const val SAFE_H = 720
        private const val SAFE_FPS = 30
    }

    interface CameraEventListener {
        fun onStatusChanged(status: String)
        fun onConnected(device: UsbDevice)
        fun onDisconnected()
        fun onError(message: String)
        /** Result of one detection pass; empty list means "nothing found". */
        fun onDetections(detections: List<Detection>)
        /** Emitted on connect and whenever the focus value changes. */
        fun onFocusChanged(info: FocusInfo)
        /** Emitted once a second with the live HUD readout. */
        fun onTelemetry(t: Telemetry)
    }

    private var monitor: USBMonitor? = null
    private var camera: UVCCamera? = null

    /**
     * Set by [CameraCoordinator]: a device arriving only triggers a UVC scan when the active
     * source is actually the USB/UVC camera.
     */
    @Volatile
    var autoScanOnAttach: Boolean = true
    private var activeDevice: UsbDevice? = null
    private var outputSurface: Surface? = null
    private var surfaceDestroyed = false
    private var mirrored = false
    private var openInFlight = false

    // ---- detection state -------------------------------------------------
    // Written by the inference executor once the backend shoot-out finishes and read by the
    // native frame thread, hence volatile. Any backend, chosen by measurement in
    // DetectorFactory — not necessarily ONNX Runtime.
    @Volatile
    private var detector: Detector? = null
    private var detectEnabled = false
    private var confThreshold = 0.35f
    private var detectIntervalMs = 500L
    private var frameW = PREVIEW_W
    private var frameH = PREVIEW_H
    private var nv21 = ByteArray(0)
    private var busy = AtomicBoolean(false)
    private var lastInferAt = 0L
    private val inferExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "onnx-infer").apply { isDaemon = true }
    }

    /**
     * Bumped whenever the detector is torn down. Building one takes seconds, so a camera
     * restart or teardown can easily land mid-benchmark; comparing generations stops the
     * late arrival from resurrecting a session for a camera that is already gone.
     */
    private val detectorGen = AtomicInteger(0)

    /** Matroska recorder, created on demand. Null while not recording. */
    @Volatile
    private var rec: MkvRecorder? = null

    // ---- telemetry / control state ---------------------------------------
    private val framesInWindow = AtomicInteger(0)
    private val totalFrames = AtomicLong(0)
    private var rawProbeLogged = false

    // ---- telemetry sampling ----------------------------------------------
    @Volatile
    private var lastFrameNs = 0L

    @Volatile
    private var frameIntervalMs = 0f

    @Volatile
    private var inferMs = -1f
    private var lastProcTicks = 0L
    private var clkTckValue = 0L
    private var cachedTempPath: String? = null
    private var streamWatchdog: ScheduledFuture<*>? = null
    private var fallbackAttempted = false
    private var statsFuture: ScheduledFuture<*>? = null
    private var statsTick = 0
    private var statsWarmup = true
    private var lastFocus = FocusInfo(false, false, 0)
    private var focusSupported = false
    private var autoFocusSupported = false

    /** Serialises UVC control transfers (focus) and the 1 Hz stats tick. */
    private val controlExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "uvc-control").apply { isDaemon = true }
    }

    // ------------------------------------------------------------- callbacks

    private val deviceListener = object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {
            HudLogger.i(TAG, "onAttach: ${device.productName} (${device.deviceName})")
            // The active source may not be a UVC camera at all (the U2 DVR is mass storage), in
            // which case scanning here would only produce a misleading "no UVC camera" status.
            if (!autoScanOnAttach) {
                HudLogger.i(TAG, "  -> active source is not USB/UVC; leaving the device alone")
                return
            }
            listener.onStatusChanged(context.getString(R.string.status_attached_connecting))
            scanAndConnect()
        }

        override fun onDetach(device: UsbDevice) {
            HudLogger.w(TAG, "onDetach: ${device.deviceName}")
        }

        override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {
            HudLogger.i(TAG, "onConnect: ${device.productName} createNew=$createNew")
            openCamera(device, ctrlBlock)
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
            HudLogger.w(TAG, "onDisconnect: ${device.deviceName}")
            closeCamera()
            listener.onDisconnected()
        }

        override fun onCancel(device: UsbDevice) {
            HudLogger.w(TAG, "onCancel: ${device.deviceName}")
        }
    }

    // ------------------------------------------------------------- lifecycle

    private class UsbMonitorContext(base: Context) : android.content.ContextWrapper(base) {
        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter?
        ): android.content.Intent? {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                super.registerReceiver(receiver, filter, RECEIVER_EXPORTED)
            } else {
                super.registerReceiver(receiver, filter)
            }
        }

        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter?,
            broadcastPermission: String?,
            scheduler: android.os.Handler?
        ): android.content.Intent? {
            return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                super.registerReceiver(receiver, filter, broadcastPermission, scheduler, RECEIVER_EXPORTED)
            } else {
                super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
            }
        }

        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter?,
            flags: Int
        ): android.content.Intent? {
            val targetFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if ((flags and (RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED)) == 0) {
                    flags or RECEIVER_EXPORTED
                } else {
                    flags
                }
            } else {
                flags
            }
            return super.registerReceiver(receiver, filter, targetFlags)
        }

        override fun registerReceiver(
            receiver: android.content.BroadcastReceiver?,
            filter: android.content.IntentFilter?,
            broadcastPermission: String?,
            scheduler: android.os.Handler?,
            flags: Int
        ): android.content.Intent? {
            val targetFlags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if ((flags and (RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED)) == 0) {
                    flags or RECEIVER_EXPORTED
                } else {
                    flags
                }
            } else {
                flags
            }
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, targetFlags)
        }

        override fun getApplicationContext(): Context {
            return this
        }
    }

    fun registerReceiver() {
        if (monitor == null) {
            HudLogger.i(TAG, "registerReceiver() - USBMonitor")
            monitor = USBMonitor(UsbMonitorContext(context), deviceListener).also { it.register() }
        }
    }

    /** UVCCamera/USBMonitor handle attach/detach itself; nothing periodic needed. */
    fun startWatchdog() {
        HudLogger.i(TAG, "startWatchdog (no-op: engine handles detach)")
    }

    fun onAppBackgrounded() {
        HudLogger.i(TAG, "onAppBackgrounded - close camera")
        closeCamera()
    }

    fun unregister() {
        HudLogger.i(TAG, "unregister()")
        closeCamera()
        try {
            monitor?.unregister()
            monitor?.destroy()
        } catch (t: Throwable) {
            HudLogger.e(TAG, "monitor destroy failed", t)
        }
        monitor = null
    }

    // ---------------------------------------------------------------- scan

    fun scanAndConnect(forcePermission: Boolean = false) {
        val m = monitor ?: run {
            HudLogger.w(TAG, "scanAndConnect: monitor not registered")
            return
        }
        val devices = try {
            m.deviceList
        } catch (t: Throwable) {
            HudLogger.e(TAG, "getDeviceList failed", t)
            null
        }
        HudLogger.i(TAG, "Scanning USB devices. Count: ${devices?.size ?: 0}")
        if (devices.isNullOrEmpty()) {
            listener.onStatusChanged(context.getString(R.string.status_waiting_camera))
            return
        }
        devices.forEach {
            HudLogger.d(TAG, "  USB: ${it.deviceName} name=${it.productName} class=${it.deviceClass}")
        }
        // Deliberately NOT "?: devices.firstOrNull()". A DVR or dashcam plugged in as a card
        // reader advertises a mass-storage interface and no video interface at all, so opening
        // it as a camera can only fail - and the failure then looks like an app bug rather than
        // a camera sitting in the wrong USB mode. Report what is actually attached instead.
        val dev = devices.firstOrNull { isUvcDevice(it) }
        if (dev == null) {
            val summary = devices.joinToString("; ") { describeUsbFunction(it) }
            HudLogger.w(TAG, "  -> no UVC video interface on any attached device: $summary")
            listener.onStatusChanged(context.getString(R.string.status_not_a_uvc_camera, summary))
            return
        }
        HudLogger.i(TAG, "  -> UVC device found: ${dev.deviceName} (${dev.productName})")
        if (!m.hasPermission(dev)) {
            listener.onStatusChanged(context.getString(R.string.status_requesting_permission))
            m.requestPermission(dev)
        } else {
            HudLogger.i(TAG, "  -> permission granted (forcePermission=$forcePermission); opening")
            try {
                val ctrlBlock = m.openDevice(dev)
                if (ctrlBlock == null) {
                    HudLogger.e(TAG, "openDevice returned null for ${dev.deviceName}")
                    listener.onError("Cannot open USB device (openDevice returned null)")
                } else {
                    openCamera(dev, ctrlBlock)
                }
            } catch (t: Throwable) {
                HudLogger.e(TAG, "openDevice failed", t)
                listener.onError("openDevice failed: ${t.message}")
            }
        }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) return true
        }
        return device.deviceClass == 239
    }

    /** Names the function a USB device actually advertises, for the log and the status line. */
    private fun describeUsbFunction(device: UsbDevice): String {
        val ifaces = (0 until device.interfaceCount).joinToString("/") { i ->
            nameForUsbClass(device.getInterface(i).interfaceClass)
        }
        val vid = String.format(java.util.Locale.US, "%04X", device.vendorId)
        val pid = String.format(java.util.Locale.US, "%04X", device.productId)
        val name = device.productName ?: device.deviceName
        return "$name [$vid:$pid ${ifaces.ifEmpty { "no interfaces" }}]"
    }

    private fun nameForUsbClass(cls: Int): String = when (cls) {
        UsbConstants.USB_CLASS_VIDEO -> "video(UVC)"
        UsbConstants.USB_CLASS_AUDIO -> "audio"
        UsbConstants.USB_CLASS_MASS_STORAGE -> "mass-storage"
        UsbConstants.USB_CLASS_HID -> "hid"
        UsbConstants.USB_CLASS_COMM -> "comm"
        UsbConstants.USB_CLASS_HUB -> "hub"
        UsbConstants.USB_CLASS_MISC -> "misc"
        UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "wireless"
        else -> "class$cls"
    }

    fun describeUsbDevices(): String {
        val m = monitor ?: return "USB monitor not ready (0 device(s))"
        val devices = try {
            m.deviceList
        } catch (t: Throwable) {
            return "list failed: ${t.message}"
        }
        if (devices.isNullOrEmpty()) return "0 device(s)"
        return buildString {
            append("${devices.size} device(s)\n")
            var anyUvc = false
            devices.forEach { d ->
                val uvc = isUvcDevice(d)
                anyUvc = anyUvc || uvc
                append("• ${describeUsbFunction(d)}\n")
                append("   perm=${m.hasPermission(d)} uvc=$uvc\n")
            }
            append("UVC camera found: $anyUvc")
        }
    }

    // -------------------------------------------------------------- surface

    fun setOutputSurface(surface: Surface?) {
        HudLogger.i(TAG, "setOutputSurface: $surface")
        outputSurface = surface
        if (surface == null) {
            surfaceDestroyed = true
            closeCamera()
            return
        }
        surfaceDestroyed = false
        // Surface (re)available: open the camera if a device is waiting.
        if (camera == null && activeDevice != null && !openInFlight) {
            HudLogger.i(TAG, "surface available — opening camera")
            scanAndConnect()
        }
    }

    fun setMirror(mirror: Boolean) {
        mirrored = mirror
        // The engine exposes rotation/effects but not a mirror flip; log only.
        HudLogger.i(TAG, "setMirror: $mirror (engine: not wired)")
    }

    /**
     * Frames arrive on the native capture thread, so this must never block. Two shapes:
     *
     * - **RAW** (detection off): the engine hands us the *compressed* frame straight
     *   through with no conversion, so we only bump counters — the FPS/bitrate readout
     *   costs essentially nothing.
     * - **NV21** (detection on): the engine converts for us, we copy into a reusable
     *   buffer and hand the inference to a single-threaded executor. Frames that arrive
     *   while an inference is in flight are dropped; the surface preview runs on a
     *   separate queue and is never throttled.
     */
    private val frameCallback = IFrameCallback { buffer: ByteBuffer ->
        framesInWindow.incrementAndGet()
        totalFrames.incrementAndGet()
        val nowNs = System.nanoTime()
        val prevNs = lastFrameNs
        lastFrameNs = nowNs
        if (prevNs != 0L) {
            val deltaMs = (nowNs - prevNs) / 1_000_000f
            // exponential moving average keeps the readout readable
            frameIntervalMs = if (frameIntervalMs <= 0f) deltaMs else 0.8f * frameIntervalMs + 0.2f * deltaMs
        }
        // Recording takes every frame, so it is fed before the detection throttle below (which
        // deliberately samples only every detectIntervalMs).
        val recorder = rec
        if (recorder != null && recorder.isRecording) {
            recorder.submit(buffer, frameW * frameH * 3 / 2)
        }

        val det = detector
        if (det == null) {
            // One-shot probe of what the RAW callback actually delivers. Measured result: it is
            // NOT the compressed frame — capacity is w*h*3/2 and the first bytes are YUV, so
            // RAW is a decode-and-convert, which is why it costs about as much as the preview.
            if (!rawProbeLogged) {
                rawProbeLogged = true
                val head = ByteArray(4)
                buffer.rewind()
                if (buffer.capacity() >= 4) buffer.get(head, 0, 4)
                HudLogger.i(
                    TAG,
                    "RAW frame: capacity=${buffer.capacity()} magic=" +
                        head.joinToString(" ") { String.format("%02X", it) }
                )
            }
            return@IFrameCallback
        }
        val w = frameW
        val h = frameH
        val need = w * h * 3 / 2
        if (need <= 0) return@IFrameCallback
        val now = System.currentTimeMillis()
        if (detectIntervalMs > 0 && now - lastInferAt < detectIntervalMs) return@IFrameCallback
        if (!busy.compareAndSet(false, true)) return@IFrameCallback
        lastInferAt = now
        try {
            if (nv21.size != need) nv21 = ByteArray(need)
            buffer.rewind()
            if (buffer.capacity() < need) {
                busy.set(false)
                return@IFrameCallback
            }
            buffer.get(nv21, 0, need)
        } catch (t: Throwable) {
            busy.set(false)
            HudLogger.w(TAG, "frame copy failed: ${t.message}")
            return@IFrameCallback
        }
        val snapshot = nv21
        inferExecutor.execute {
            val t0 = System.nanoTime()
            try {
                val detections = detector?.detect(snapshot, w, h, confThreshold).orEmpty()
                listener.onDetections(detections)
            } catch (t: Throwable) {
                HudLogger.w(TAG, "detection pass failed: ${t.message}")
            } finally {
                inferMs = (System.nanoTime() - t0) / 1_000_000f
                busy.set(false)
            }
        }
    }

    // -------------------------------------------------------------- telemetry

    private fun startStatsLoop() {
        stopStatsLoop()
        statsTick = 0
        statsWarmup = true
        framesInWindow.set(0)
        statsFuture = controlExecutor.scheduleAtFixedRate(
            { publishStats() }, 1000L, 1000L, TimeUnit.MILLISECONDS
        )
    }

    private fun stopStatsLoop() {
        try {
            statsFuture?.cancel(false)
        } catch (_: Throwable) {
        }
        statsFuture = null
        framesInWindow.set(0)
    }

    /**
     * Runs every second on [controlExecutor]; the counter window is exactly 1 s, so the
     * reset value *is* the frame rate.
     *
     * Note the FPS measures frames **received from the camera** — the negotiated stream
     * rate — not frames actually presented on the Surface. A camera happily keeps sending
     * 30 fps while the software decoder falls behind.
     */
    private fun publishStats() {
        val frames = framesInWindow.getAndSet(0)
        val cpu = sampleCpuPercent()
        if (statsWarmup) {
            // the first window is partial (camera just started) — skip it
            statsWarmup = false
            return
        }
        val tempC = sampleTempC()
        // Logged at 1 Hz so performance can be read from logcat rather than off the screen —
        // measuring by screenshot is slow and easy to get wrong.
        HudLogger.i(
            TAG,
            "stats: fps=$frames interval=${String.format(java.util.Locale.US, "%.1f", frameIntervalMs)}ms " +
                "cpu=$cpu% temp=$tempC C infer=${
                    if (detector == null) "-" else String.format(java.util.Locale.US, "%.0f", inferMs)
                }ms backend=${DetectorFactory.activeBackend} frame=${frameW}x$frameH"
        )
        listener.onTelemetry(
            Telemetry(
                fps = frames,
                frameIntervalMs = frameIntervalMs,
                cpuPercent = cpu,
                tempC = tempC,
                inferMs = if (detector == null) -1f else inferMs,
                provider = DetectorFactory.activeBackend
            )
        )
        statsTick++
        if (focusSupported && statsTick % 2 == 0) refreshFocus(false)
    }

    // -------------------------------------------------------------- telemetry

    /**
     * Process CPU time as a percentage of one core, from /proc/self/stat (utime + stime).
     * The sample window is the 1 s stats tick, so the tick delta *is* the percentage.
     * Values above 100 mean the work is spread over more than one core.
     */
    private fun sampleCpuPercent(): Int {
        val ticks = readProcCpuTicks()
        if (ticks <= 0L) return -1
        val prev = lastProcTicks
        lastProcTicks = ticks
        if (prev == 0L) return 0
        val hz = clkTck()
        return ((ticks - prev) * 100L / hz).toInt()
    }

    private fun clkTck(): Long {
        if (clkTckValue != 0L) return clkTckValue
        clkTckValue = try {
            Os.sysconf(OsConstants._SC_CLK_TCK).toLong().takeIf { it > 0 } ?: 100L
        } catch (t: Throwable) {
            100L
        }
        return clkTckValue
    }

    private fun readProcCpuTicks(): Long {
        return try {
            val stat = File("/proc/self/stat")
            if (!stat.canRead()) return -1L
            val text = stat.readText()
            // comm is field 2 and may contain spaces/parens, so skip past the last ')'
            val close = text.lastIndexOf(')')
            if (close < 0) return -1L
            val f = text.substring(close + 2).split(' ')
            // after comm: index 0 = state (field 3), so utime = field 14 -> 11
            if (f.size < 13) return -1L
            f[11].toLong() + f[12].toLong()
        } catch (t: Throwable) {
            -1L
        }
    }

    /**
     * SoC temperature from /sys/class/thermal. Prefers a CPU/SOC zone over the PMIC one,
     * and caches the zone path once found. Returns -1 when nothing is readable.
     */
    private fun sampleTempC(): Int {
        cachedTempPath?.let { p ->
            val v = readIntFile(p)
            if (v > 0) {
                return v / 1000
            }
        }
        val base = File("/sys/class/thermal")
        val zones = base.list()?.filter { it.startsWith("thermal_zone") } ?: return -1
        var fallback: String? = null
        for (zone in zones.sorted()) {
            val type = try {
                File(base, "$zone/type").readText().trim()
            } catch (_: Throwable) {
                continue
            }
            val tempPath = File(base, "$zone/temp")
            if (readIntFile(tempPath.absolutePath) <= 0) continue
            // allow "cpu", "cpuss", "soc" but not "gpu"/"quiet"/"pmic"
            val interesting = type.startsWith("cpu") || type.contains("cpuss") || type.startsWith("soc")
            if (interesting) {
                cachedTempPath = tempPath.absolutePath
                HudLogger.i(TAG, "temperature zone: $type (${tempPath.absolutePath})")
                return readIntFile(tempPath.absolutePath) / 1000
            }
            if (fallback == null) fallback = tempPath.absolutePath
        }
        val p = fallback ?: return -1
        cachedTempPath = p
        val v = readIntFile(p)
        return if (v > 0) v / 1000 else -1
    }

    private fun readIntFile(path: String): Int {
        return try {
            File(path).readText().trim().toInt()
        } catch (t: Throwable) {
            -1
        }
    }

    // ------------------------------------------------------------------ focus

    /** Called once after the stream starts; the probe issues USB control transfers. */
    private fun probeFocus() {
        val cam = camera ?: return
        try {
            focusSupported = cam.checkSupportFlag(UVCCamera.CTRL_FOCUS_ABS.toLong())
            autoFocusSupported = cam.checkSupportFlag(UVCCamera.CTRL_FOCUS_AUTO.toLong())
        } catch (t: Throwable) {
            focusSupported = false
            autoFocusSupported = false
            HudLogger.w(TAG, "focus probe failed: ${t.message}")
        }
        HudLogger.i(TAG, "focus support: absolute=$focusSupported auto=$autoFocusSupported")
        if (focusSupported) {
            refreshFocus(true)
        } else {
            lastFocus = FocusInfo(false, false, 0)
            listener.onFocusChanged(lastFocus)
        }
    }

    private fun refreshFocus(force: Boolean) {
        val cam = camera ?: return
        try {
            // Always read before writing: getFocus() is what makes the engine populate
            // its min/max focus range, and setFocus() is a no-op without it.
            val percent = cam.focus.coerceIn(0, 100)
            val auto = autoFocusSupported && cam.autoFocus
            val info = FocusInfo(true, auto, percent)
            if (force || info != lastFocus) {
                lastFocus = info
                listener.onFocusChanged(info)
            }
        } catch (t: Throwable) {
            HudLogger.w(TAG, "refreshFocus failed: ${t.message}")
        }
    }

    fun focusInfo(): FocusInfo = lastFocus

    fun isFocusSupported(): Boolean = focusSupported

    fun hasAutoFocus(): Boolean = autoFocusSupported

    fun toggleAutoFocus() {
        if (!autoFocusSupported) return
        controlExecutor.execute {
            val cam = camera ?: return@execute
            try {
                val next = !cam.autoFocus
                cam.autoFocus = next
                HudLogger.i(TAG, "auto focus -> $next")
                refreshFocus(true)
            } catch (t: Throwable) {
                HudLogger.w(TAG, "toggleAutoFocus failed: ${t.message}")
            }
        }
    }

    /** [delta] is in percent; stepping always drops out of auto focus first. */
    fun nudgeFocus(delta: Int) {
        if (!focusSupported) return
        controlExecutor.execute {
            val cam = camera ?: return@execute
            try {
                if (autoFocusSupported && cam.autoFocus) cam.autoFocus = false
                val current = cam.focus.coerceIn(0, 100)
                val target = (current + delta).coerceIn(0, 100)
                cam.focus = target
                HudLogger.i(TAG, "focus $current -> $target")
                refreshFocus(true)
            } catch (t: Throwable) {
                HudLogger.w(TAG, "nudgeFocus failed: ${t.message}")
            }
        }
    }

    // --------------------------------------------------------------- camera

    private fun openCamera(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        activeDevice = device
        val surface = outputSurface
        if (surface == null || surfaceDestroyed) {
            HudLogger.w(TAG, "openCamera: no surface yet — deferring (tap START HUD)")
            listener.onStatusChanged(context.getString(R.string.status_ready))
            try {
                ctrlBlock.close()
            } catch (_: Throwable) {
            }
            return
        }
        if (openInFlight || camera != null) {
            HudLogger.i(TAG, "openCamera: already open/in-flight")
            return
        }
        openInFlight = true
        fallbackAttempted = false
        totalFrames.set(0)
        val cam = UVCCamera()
        try {
            // ---- settings -------------------------------------------------
            val wantW = HudPrefs.previewWidth(context)
            val wantH = HudPrefs.previewHeight(context)
            detectEnabled = HudPrefs.detect(context)
            confThreshold = HudPrefs.confidence(context)
            // Inference is the single most expensive thing we do on CPU; throttle it (or 0 for Free Run).
            detectIntervalMs = HudPrefs.detectIntervalMs(context).toLong().coerceAtLeast(0L)

            cam.open(ctrlBlock)
            // One-time dump of the descriptors the camera advertises, so the frame-rate
            // intervals we can actually negotiate are visible in the log.
            try {
                HudLogger.i(TAG, "advertised: ${cam.supportedSize}")
            } catch (t: Throwable) {
                HudLogger.w(TAG, "supportedSize dump failed: ${t.message}")
            }
            // Pick the best supported MJPEG size at/near the requested one.
            val size = pickPreviewSize(cam, wantW, wantH)
            HudLogger.i(TAG, "preview size -> ${size?.width}x${size?.height} (want ${wantW}x$wantH)")
            val useW = size?.width ?: wantW
            val useH = size?.height ?: wantH
            frameW = useW
            frameH = useH
            applyPreviewSize(cam, useW, useH, HudPrefs.targetFps(context))

            // ---- frame callback (must be set before startPreview) -----------
            if (detectEnabled) {
                // Picking an execution provider is a measurement, not a lookup: every
                // candidate gets a timed pass, which costs seconds. So the callback goes in
                // now and the detector arrives whenever it is ready — the frame path treats
                // a null detector as "not ready yet" and just keeps counting frames.
                startDetectorAsync(context, useW, useH)
            }
            configureFrameCallback(cam)

            cam.setPreviewDisplay(surface)
            cam.startPreview()
            camera = cam
            listener.onConnected(device)
            listener.onStatusChanged(
                context.getString(
                    R.string.status_connected_named,
                    device.productName ?: context.getString(R.string.status_usb_camera_fallback)
                )
            )
            HudLogger.i(TAG, "openCamera OK: ${device.productName} MJPEG -> Surface")
            startStatsLoop()
            armStreamWatchdog()
            // Focus support probing issues USB control transfers, so keep it off
            // whatever thread onConnect ran on.
            controlExecutor.execute { probeFocus() }
        } catch (t: Throwable) {
            HudLogger.e(TAG, "openCamera failed", t)
            listener.onError("openCamera failed: ${t.message}")
            stopStatsLoop()
            closeDetector()
            try {
                cam.stopPreview()
            } catch (_: Throwable) {
            }
            try {
                cam.destroy()
            } catch (_: Throwable) {
            }
        } finally {
            openInFlight = false
        }
    }

    /**
     * A UVC camera only accepts frame intervals it advertises — asking for anything else makes
     * `uvc_get_stream_ctrl_format_size` fail and the engine throws, which used to abort the
     * whole connect. So walk a candidate list and keep the first mode the camera accepts.
     * The 10-31 window is the one this hardware has always accepted.
     */
    private fun applyPreviewSize(cam: UVCCamera, w: Int, h: Int, wantedFps: Int) {
        val attempts = listOf(
            wantedFps to wantedFps,
            MIN_FPS to MAX_FPS,
            30 to 30,
            15 to 15
        ).distinct()
        for ((lo, hi) in attempts) {
            try {
                cam.setPreviewSize(w, h, lo, hi, UVCCamera.FRAME_FORMAT_MJPEG, UVCCamera.DEFAULT_BANDWIDTH)
                HudLogger.i(TAG, "preview committed: ${w}x$h MJPEG, fps window $lo-$hi")
                return
            } catch (t: Throwable) {
                HudLogger.w(TAG, "camera rejected ${w}x$h @ $lo-$hi fps: ${t.message}")
            }
        }
        throw IllegalStateException("no acceptable preview mode for ${w}x$h")
    }

    /**
     * A UVC camera will happily accept a format probe and then fail `uvc_stream_open_ctrl`
     * on the preview thread (libUVCPreview logs "could not negotiate with camera" and that
     * is the last you hear of it). This module's 640x480 MJPEG descriptor does exactly that.
     *
     * Since nothing reaches Java, watch for frames instead: if none arrive shortly after
     * startPreview, the mode is a lie — fall back to a mode this hardware is known to stream
     * and remember it so the next launch starts clean.
     */
    private fun armStreamWatchdog() {
        cancelStreamWatchdog()
        streamWatchdog = controlExecutor.schedule(
            {
                if (totalFrames.get() > 0L) return@schedule
                val cam = camera
                if (cam == null) return@schedule
                if (fallbackAttempted) {
                    HudLogger.e(TAG, "still no frames after fallback — giving up")
                    listener.onError("Camera is not delivering frames")
                    return@schedule
                }
                fallbackAttempted = true
                HudLogger.e(
                    TAG,
                    "no frames 2.5s after startPreview: ${frameW}x${frameH} MJPEG did not " +
                        "negotiate — reverting to ${SAFE_W}x${SAFE_H} @ ${MIN_FPS}-${MAX_FPS}fps"
                )
                // Persist so the next connect does not repeat the failure.
                HudPrefs.setPreviewSize(context, SAFE_W, SAFE_H)
                HudPrefs.setTargetFps(context, SAFE_FPS)
                listener.onStatusChanged(
                    context.getString(
                        R.string.status_resolution_fallback,
                        frameW, frameH, SAFE_W, SAFE_H
                    )
                )
                closeCamera()
                scanAndConnect()
            },
            2500L,
            TimeUnit.MILLISECONDS
        )
    }

    private fun cancelStreamWatchdog() {
        try {
            streamWatchdog?.cancel(false)
        } catch (_: Throwable) {
        }
        streamWatchdog = null
    }

    private fun closeDetector() {
        detectorGen.incrementAndGet()
        try {
            detector?.close()
        } catch (_: Throwable) {
        }
        detector = null
        busy.set(false)
    }

    /**
     * Choose the frame callback format from what actually needs pixel data.
     *
     * NV21 is required whenever anything consumes pixels (detection, recording); otherwise a
     * RAW callback is enough to drive the FPS readout, unless sampling is switched off entirely.
     */
    private fun configureFrameCallback(cam: UVCCamera) {
        val needPixels = detectEnabled || (rec?.isRecording == true)
        try {
            when {
                needPixels -> {
                    cam.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_NV21)
                    HudLogger.i(
                        TAG,
                        "frame callback: NV21 (detection=$detectEnabled recording=${rec?.isRecording == true})"
                    )
                }
                HudPrefs.sampleFps(context) -> {
                    cam.setFrameCallback(frameCallback, UVCCamera.PIXEL_FORMAT_RAW)
                    HudLogger.i(TAG, "frame callback: RAW (FPS telemetry)")
                }
                else -> HudLogger.i(TAG, "frame callback: disabled by setting — FPS will read --")
            }
        } catch (t: Throwable) {
            HudLogger.w(TAG, "cannot set frame callback format: ${t.message}")
        }
    }

    data class RecordingStartInfo(
        val path: String,
        val isSdCard: Boolean
    )

    /**
     * Start recording the preview to Matroska. Returns recording start details, or null if it
     * could not start.
     */
    fun startRecording(): RecordingStartInfo? {
        if (rec?.isRecording == true) return null
        val r = MkvRecorder(context)
        // The UVC engine hands us its own buffer, so its chroma order (not the app's NV21
        // convention) is what the encoder must match; the Settings toggle covers OEM variants.
        val file = r.start(frameW, frameH, HudPrefs.targetFps(context))
        if (file == null) {
            HudLogger.w(TAG, "recording could not start")
            return null
        }
        rec = r
        // Recording needs pixels every frame, so the callback format may have to change.
        camera?.let { configureFrameCallback(it) }
        return RecordingStartInfo(file.absolutePath, r.isSdCard)
    }

    /** Stop recording, finalise the container, and publish to storage. Returns the RecordingResult, or null. */
    fun stopRecording(): RecordingStorageManager.RecordingResult? {
        val r = rec ?: return null
        val file = r.stop()
        val result = r.lastRecordingResult ?: file?.let {
            RecordingStorageManager.publishRecording(context, it, r.isSdCard)
        }
        rec = null
        camera?.let { configureFrameCallback(it) }
        return result
    }

    /** Updates the latest detection overlay to be recorded on video frames. */
    fun updateRecordingDetections(detections: List<Detection>) {
        rec?.updateDetections(detections)
    }

    fun isRecording(): Boolean = rec?.isRecording == true

    /** Current preview frame size, or (0,0) before the camera opens. */
    fun frameSize(): Pair<Int, Int> = frameW to frameH

    /**
     * Build the detector on the inference thread.
     *
     * Picking a provider means timing each candidate on a real frame, which takes seconds —
     * far too long to make the preview wait, and openCamera runs on the UI thread. The
     * result is remembered per model + frame size so only the first run pays for it, and a
     * remembered provider is still re-benchmarked if it can no longer be built.
     */
    private fun startDetectorAsync(ctx: Context, w: Int, h: Int) {
        val asset = HudPrefs.modelAsset(ctx)
        val cacheKey = "$asset@${w}x$h"
        val cached = if (HudPrefs.detectProviderKey(ctx) == cacheKey) {
            HudPrefs.detectProvider(ctx).takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (cached != null) {
            HudLogger.i(TAG, "reusing provider '$cached' for $cacheKey (skipping benchmark)")
        }
        val gen = detectorGen.incrementAndGet()
        inferExecutor.execute {
            val built = DetectorFactory.create(ctx, asset, w, h, cached)
            if (gen != detectorGen.get()) {
                // The camera was torn down (or restarted) while we were benchmarking.
                HudLogger.i(TAG, "discarding detector — session was closed during setup")
                try {
                    built?.close()
                } catch (_: Throwable) {
                }
                return@execute
            }
            detector = built
            if (built == null) {
                HudLogger.w(TAG, "detection requested but model unavailable — preview only")
                return@execute
            }
            // resolveAsset() falls back to a bundled model when the preference is stale, so
            // write the name that really loaded — otherwise every launch repeats the warning.
            if (OnnxDetector.activeAsset.isNotEmpty() && HudPrefs.modelAsset(ctx) != OnnxDetector.activeAsset) {
                HudLogger.i(TAG, "model preference '$asset' -> '${OnnxDetector.activeAsset}'")
                HudPrefs.setModelAsset(ctx, OnnxDetector.activeAsset)
            }
            if (cached == null) {
                val key = "${OnnxDetector.activeAsset}@${w}x$h"
                val winner = DetectorFactory.activeBackend
                HudPrefs.setDetectProvider(ctx, winner, key)
                HudLogger.i(TAG, "remembered backend '$winner' for $key")
            }
        }
    }

    /** Exact requested size if supported; else the largest at/below it; else the smallest above. */
    private fun pickPreviewSize(cam: UVCCamera, wantW: Int, wantH: Int): Size? {
        val list = try {
            cam.getSupportedSizeList(UVCCamera.FRAME_FORMAT_MJPEG)
        } catch (t: Throwable) {
            HudLogger.w(TAG, "getSupportedSizeList failed: ${t.message}")
            null
        } ?: return null
        if (list.isEmpty()) return null
        list.forEach { s ->
            val rates = s.fps?.joinToString("/") { "%.1f".format(it) } ?: "?"
            HudLogger.i(TAG, "  MJPEG ${s.width}x${s.height} fps=[$rates]")
        }
        val target = wantW * wantH
        return list.firstOrNull { it.width == wantW && it.height == wantH }
            ?: list.filter { it.width * it.height <= target }.maxByOrNull { it.width * it.height }
            ?: list.minByOrNull { it.width * it.height }
    }

    private fun closeCamera() {
        cancelStreamWatchdog()
        stopStatsLoop()
        val cam = camera
        camera = null
        if (cam != null) {
            HudLogger.i(TAG, "closeCamera")
            try {
                cam.setFrameCallback(null, UVCCamera.PIXEL_FORMAT_RAW)
            } catch (_: Throwable) {
            }
            try {
                cam.stopPreview()
            } catch (_: Throwable) {
            }
            try {
                cam.close()
            } catch (t: Throwable) {
                HudLogger.e(TAG, "closeCamera failed", t)
            }
            try {
                cam.destroy()
            } catch (t: Throwable) {
                HudLogger.e(TAG, "cam.destroy failed", t)
            }
        }
        closeDetector()
        stopRecording()
        focusSupported = false
        autoFocusSupported = false
        lastFocus = FocusInfo(false, false, 0)
    }
}
