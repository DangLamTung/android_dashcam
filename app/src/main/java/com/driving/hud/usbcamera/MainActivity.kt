package com.driving.hud.usbcamera

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.driving.hud.usbcamera.camera.AusbcCameraController
import com.driving.hud.usbcamera.camera.CameraCoordinator
import com.driving.hud.usbcamera.calib.GroundPlaneCalibration
import com.driving.hud.usbcamera.alert.AlertTone
import com.driving.hud.usbcamera.alert.VehicleTracker
import com.driving.hud.usbcamera.record.RecordingStorageManager
import com.driving.hud.usbcamera.ui.VideoFit
import android.content.res.Configuration
import android.app.PictureInPictureParams
import android.os.SystemClock
import android.util.Rational
import android.view.Gravity
import android.widget.FrameLayout
import com.driving.hud.usbcamera.detect.DetectorFactory
import com.driving.hud.usbcamera.camera.CameraNativeBridge
import com.driving.hud.usbcamera.databinding.ActivityMainBinding
import android.widget.SeekBar
import com.driving.hud.usbcamera.camera.FocusInfo
import com.driving.hud.usbcamera.camera.Telemetry
import com.driving.hud.usbcamera.calib.PinholeDistanceEstimator
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.util.BatteryTracker
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs

class MainActivity : AppCompatActivity(), AusbcCameraController.CameraEventListener {

    companion object {
        private const val TAG = "MainActivity"
        const val EXTRA_START_CALIBRATION = "com.driving.hud.usbcamera.START_CALIBRATION"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraManager: CameraCoordinator
    private lateinit var batteryTracker: BatteryTracker

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val telemetryTicker = object : Runnable {
        override fun run() {
            refreshTelemetry()
            uiHandler.postDelayed(this, 1000)
        }
    }

    private var isMirrored = false
    private var isHudMode = false
    private var isCalibrationMode = false

    private var calibPitchDeg = 8.0f
    private var calibHeightM = 1.20f
    private var calibFovDeg = 65.0f
    @Volatile
    private var lastDetections: List<Detection> = emptyList()

    /** Path of the file currently being recorded, for the overlay indicator. */
    private var recPath: String? = null

    /** Distance-to-centre readout built from the ground-plane calibration. */
    private var distanceText: String = ""

    /**
     * Ground-plane model for the collision tracker. Built once the preview size is known, and
     * rebuilt whenever the frame size or the calibration parameters change.
     */
    private var calibration: GroundPlaneCalibration? = null
    private var calibrationKey: String = ""

    /** Turns per-frame detections into stable tracks and a time-to-collision alert. */
    private val tracker = VehicleTracker()

    private val alertTone = AlertTone()

    /** Last alert level spoken, so the tone fires on the transition rather than every frame. */
    private var lastAlertWarn: Boolean? = null
    private var lastDetectionCount = -1

    /** Latest telemetry strings; written from background threads, read on the UI thread. */
    @Volatile
    private var fpsText: String = "FPS --"

    @Volatile
    private var perfText: String = "CPU --  --°C"

    @Volatile
    private var inferText: String = ""

    @Volatile
    private var focusText: String = "FOCUS --"

    // Android 13+ requires CAMERA permission to grant USB access to UVC cameras.
    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            HudLogger.i(TAG, "CAMERA permission result: $granted")
            if (granted) {
                HudLogger.i(TAG, "CAMERA granted — scanning for USB camera")
                cameraManager.scanAndConnect(forcePermission = true)
            } else {
                HudLogger.w(TAG, "CAMERA denied — UVC camera will not be accessible on Android 13+")
                Toast.makeText(this, "CAMERA permission needed for USB camera", Toast.LENGTH_LONG).show()
            }
        }

    private fun ensureCameraPermission(callback: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            callback()
        } else {
            HudLogger.i(TAG, "Requesting CAMERA permission (required for UVC on Android 13+)")
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HudLogger.init(applicationContext)
        HudLogger.i(TAG, "onCreate")
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Startup diagnostic: log whether libusb initializes on this device.
        try {
            val libusbInit = CameraNativeBridge.nativeLibusbTestInit()
            HudLogger.i(TAG, "libusb init test = $libusbInit (0 = OK)")
        } catch (t: Throwable) {
            HudLogger.e(TAG, "libusb init test threw", t)
        }

        cameraManager = CameraCoordinator(this, this)
        batteryTracker = BatteryTracker(this)
        // Register the USB receiver once for the whole activity life — it must
        // stay registered through onStop so the permission result is received
        // even while the system permission dialog covers the app.
        cameraManager.registerReceiver()

        setupHomeControls()
        setupHudControls()
        setupCalibrationControls()
        applySavedSettings()

        // Keep HUD overlay telemetry floating cleanly above the control bar
        binding.controlBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateControlBarInset()
        }

        // Route the HUD surface to the camera renderer.
        binding.cameraSurfaceView.surfaceHolderListener = { surface, holder ->
            cameraManager.setOutputSurface(surface, holder)
        }

        // Back exits Calibration mode first, then HUD mode, otherwise closes the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isCalibrationMode) {
                    exitCalibration()
                } else if (isHudMode) {
                    exitHud()
                } else {
                    finish()
                }
            }
        })

        if (intent.getBooleanExtra(EXTRA_START_CALIBRATION, false)) {
            binding.root.post { enterCalibration() }
        } else {
            showHome()
        }
    }

    // --------------------------------------------------------------- Home

    private fun setupHomeControls() {
        binding.tvLogPath.text = "Log: ${HudLogger.getLogPath() ?: "n/a"}"

        binding.btnResetBattery.setOnClickListener {
            batteryTracker.resetSession()
            val snap = batteryTracker.sample()
            binding.tvBatteryInfo.text = snap.toDetailedSummary()
            Toast.makeText(this, "Battery session drain reset", Toast.LENGTH_SHORT).show()
        }
        binding.tvBatteryInfo.text = batteryTracker.sample().toDetailedSummary()

        binding.btnSettings.setOnClickListener {
            HudLogger.i(TAG, "Settings pressed")
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnStartHud.setOnClickListener {
            HudLogger.i(TAG, "Start HUD pressed")
            enterHud()
        }

        binding.btnCalibration.setOnClickListener {
            HudLogger.i(TAG, "Home Calibration pressed")
            enterCalibration()
        }

        // Scan and list USB devices (primary diagnostic for "no permission prompt").
        binding.btnScanUsb.setOnClickListener {
            HudLogger.i(TAG, "Scan & list USB devices pressed")
            val result = cameraManager.describeUsbDevices()
            binding.tvUsbList.visibility = View.VISIBLE
            binding.tvUsbList.text = result
            binding.tvHomeDetail.text = when {
                result.contains("0 device(s)") ->
                    "No USB device enumerated. Check OTG cable / external power."
                result.contains("UVC camera found: true") ->
                    "UVC camera detected — tap Connect to request USB permission."
                else ->
                    "Devices found, but no UVC camera. Tap Connect to retry."
            }
        }

        binding.btnConnect.setOnClickListener {
            HudLogger.i(TAG, "Connect / request USB permission pressed")
            binding.tvHomeDetail.text = "Scanning + requesting permission…"
            ensureCameraPermission {
                cameraManager.scanAndConnect(forcePermission = true)
            }
        }

        binding.btnSaveLog.setOnClickListener {
            HudLogger.i(TAG, "Save log copy pressed")
            val copy = HudLogger.saveCopy()
            Toast.makeText(
                this,
                copy?.let { "Log copy saved:\n${it.absolutePath}" } ?: "No log to save yet",
                Toast.LENGTH_LONG
            ).show()
            binding.tvLogPath.text = "Log: ${HudLogger.getLogPath() ?: "n/a"}"
        }

        // Runs the backend shoot-out without a camera: each candidate is timed over the real
        // pre-processing + inference + decode path using a synthetic frame, so the numbers
        // reflect the runtime, not the stream. Handy for checking a build or a model without
        // going near the HUD.
        binding.btnBench.setOnClickListener {
            HudLogger.i(TAG, "Benchmark backends pressed")
            val w = HudPrefs.previewWidth(this)
            val h = HudPrefs.previewHeight(this)
            binding.btnBench.isEnabled = false
            binding.tvHomeDetail.text = "Benchmarking backends at ${w}x$h… (a few seconds)"
            Thread({
                val detector = DetectorFactory.create(this, HudPrefs.modelAsset(this), w, h, null)
                try {
                    detector?.close()
                } catch (_: Throwable) {
                }
                val summary = if (detector == null) {
                    "Backend benchmark failed — see log"
                } else {
                    "Per frame at ${w}x$h (winner picked on CPU-ms)" +
                        DetectorFactory.lastReport +
                        "\n-> using ${DetectorFactory.activeBackend}"
                }
                runOnUiThread {
                    binding.btnBench.isEnabled = true
                    binding.tvHomeDetail.text = summary
                }
            }, "backend-bench").start()
        }

        binding.btnCopyPath.setOnClickListener {
            val path = HudLogger.getLogPath()
            if (path != null) {
                val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("HUD log path", path))
                Toast.makeText(this, "Log path copied:\n$path", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, "Log not available yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun applyMirrorMode(mirror: Boolean) {
        isMirrored = mirror
        binding.cameraSurfaceView.scaleX = if (mirror) -1f else 1f
        binding.hudOverlayView.scaleX = if (mirror) -1f else 1f
        binding.btnMirror.setBackgroundColor(
            if (mirror) Color.parseColor("#8000E5FF") else Color.parseColor("#33263238")
        )
        cameraManager.setMirror(mirror)
    }

    private fun applySavedSettings() {
        val grid = HudPrefs.grid(this)
        val night = HudPrefs.night(this)
        isMirrored = HudPrefs.mirror(this)
        binding.hudOverlayView.setGrid(grid)
        binding.hudOverlayView.setNightMode(night)
        binding.btnGrid.setBackgroundColor(
            if (grid) Color.parseColor("#8000E5FF") else Color.parseColor("#33263238")
        )
        binding.btnNightMode.setBackgroundColor(
            if (night) Color.parseColor("#8000E676") else Color.parseColor("#33263238")
        )
        applyMirrorMode(isMirrored)
        maybeRefreshCalibration()
        refreshTelemetry()

        val savedMode = HudPrefs.cameraMode(this)
        if (savedMode != cameraManager.currentMode) {
            cameraManager.switchMode(savedMode)
        }
        updateCameraSourceButton()

        HudLogger.i(
            TAG,
            "applied settings: grid=$grid night=$night mirror=$isMirrored mode=$savedMode " +
                "res=${HudPrefs.previewWidth(this)}x${HudPrefs.previewHeight(this)} " +
                "detect=${HudPrefs.detect(this)} conf=${HudPrefs.confidence(this)}"
        )
    }

    private fun setupHudControls() {
        binding.btnMirror.setOnClickListener {
            val next = !isMirrored
            applyMirrorMode(next)
            HudPrefs.setMirror(this, next)
            HudLogger.i(TAG, "Mirror -> ${if (next) "ON" else "OFF"}")
            Toast.makeText(this, if (next) "Flip: ON" else "Flip: OFF", Toast.LENGTH_SHORT).show()
        }

        binding.btnNightMode.setOnClickListener {
            val isNight = binding.hudOverlayView.toggleNightMode()
            HudPrefs.setNight(this, isNight)
            HudLogger.i(TAG, "Night mode -> ${if (isNight) "ON" else "OFF"}")
            binding.btnNightMode.setBackgroundColor(
                if (isNight) Color.parseColor("#8000E676") else Color.parseColor("#33263238")
            )
        }

        binding.btnGrid.setOnClickListener {
            val isGridOn = binding.hudOverlayView.toggleGrid()
            HudPrefs.setGrid(this, isGridOn)
            HudLogger.i(TAG, "Guide grid -> ${if (isGridOn) "ON" else "OFF"}")
            binding.btnGrid.setBackgroundColor(
                if (isGridOn) Color.parseColor("#8000E5FF") else Color.parseColor("#33263238")
            )
        }

        binding.btnCameraSource.setOnClickListener {
            showCameraSourceDialog()
        }
        updateCameraSourceButton()

        binding.btnReconnect.setOnClickListener {
            HudLogger.i(TAG, "Manual reconnect requested")
            binding.tvStatus.text = getString(R.string.status_connecting)
            ensureCameraPermission {
                cameraManager.scanAndConnect(forcePermission = true)
            }
        }

        binding.btnExitHud.setOnClickListener {
            HudLogger.i(TAG, "Exit HUD pressed")
            exitHud()
        }

        // ---- focus controls (enabled only if the camera has a focus unit) ----
        setFocusButtonsEnabled(false)

        binding.btnFocusNear.setOnClickListener {
            HudLogger.i(TAG, "Focus NEAR (absolute focus down)")
            cameraManager.nudgeFocus(-10)
        }

        binding.btnFocusFar.setOnClickListener {
            HudLogger.i(TAG, "Focus FAR (absolute focus up)")
            cameraManager.nudgeFocus(+10)
        }

        binding.btnFocusAf.setOnClickListener {
            HudLogger.i(TAG, "Auto focus toggle")
            cameraManager.toggleAutoFocus()
        }

        // Share the on-device log file (logcat is unavailable while driving).
        binding.btnLog.setOnClickListener {
            shareLog()
        }

        // ---- MKV recording ----        // H.264 encoded in hardware and muxed into Matroska; the file lands in the app's
        // external Movies directory so it can be pulled off with adb or a file manager.
        binding.btnRec.setOnClickListener {
            if (cameraManager.isRecording()) {
                val result = cameraManager.stopRecording()
                HudLogger.i(TAG, "recording stopped: ${result?.file?.absolutePath}")
                recPath = null
                if (result != null) {
                    showRecordingFinishedDialog(result)
                } else {
                    Toast.makeText(this, "Recording stopped (no file)", Toast.LENGTH_SHORT).show()
                }
            } else {
                val startInfo = cameraManager.startRecording()
                recPath = startInfo?.path
                HudLogger.i(TAG, "recording started: ${startInfo?.path} sdCard=${startInfo?.isSdCard}")
                val destMsg = if (startInfo?.isSdCard == true) "Recording to SD Card" else "Recording to Internal Storage"
                Toast.makeText(
                    this,
                    startInfo?.let { "$destMsg:\n${it.path.substringAfterLast('/')}" } ?: "Could not start recording",
                    Toast.LENGTH_LONG
                ).show()
            }
            updateRecUi()
        }
        updateRecUi()

        binding.btnPip.setOnClickListener {
            HudLogger.i(TAG, "Float / PiP pressed")
            enterPipMode()
        }

        binding.btnSplit.setOnClickListener {
            HudLogger.i(TAG, "Split pressed")
            launchAdjacent()
        }

        binding.btnHudCalib.setOnClickListener {
            HudLogger.i(TAG, "HUD Calibration pressed")
            enterCalibration()
        }

        binding.btnToggleControls.setOnClickListener {
            toggleControlBar()
        }

        // Tapping the video stream or HUD overlay toggles the control bar so the driver
        // has a completely unobstructed view of the road and HUD telemetry.
        binding.hudOverlayView.setOnClickListener {
            if (!isCalibrationMode) toggleControlBar()
        }
        binding.cameraSurfaceView.setOnClickListener {
            if (!isCalibrationMode) toggleControlBar()
        }
    }

    private fun updateControlBarInset() {
        val inset = if (binding.controlBar.visibility == View.VISIBLE) {
            val lp = binding.controlBar.layoutParams as? FrameLayout.LayoutParams
            val margin = lp?.bottomMargin ?: 0
            (binding.controlBar.height + margin + 12).toFloat()
        } else {
            0f
        }
        binding.hudOverlayView.setBottomInset(inset)
    }

    private fun toggleControlBar() {
        val willBeVisible = binding.controlBar.visibility != View.VISIBLE
        binding.controlBar.visibility = if (willBeVisible) View.VISIBLE else View.GONE
        binding.controlBar.post { updateControlBarInset() }
        if (willBeVisible) {
            Toast.makeText(this, "Controls shown (tap screen to hide)", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Pop the HUD out into a floating window on top of whatever else is running.
     *
     * Picture-in-picture is Android's own floating-window mechanism, so it needs no "draw over
     * other apps" permission and the system owns the window decorations and resizing. The
     * aspect ratio is taken from the camera frame so the floating window is not letterboxed.
     */
    private fun enterPipMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, getString(R.string.pip_unavailable), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val (fw, fh) = cameraManager.frameSize()
            val w = if (fw > 0) fw else 16
            val h = if (fh > 0) fh else 9
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(w, h))
                .build()
            val ok = enterPictureInPictureMode(params)
            HudLogger.i(TAG, "enter picture-in-picture ($w:$h) -> $ok")
            if (!ok) {
                Toast.makeText(this, getString(R.string.pip_unavailable), Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            HudLogger.w(TAG, "picture-in-picture failed: ${t.message}")
        }
    }

    /**
     * In the floating window the button bar is unusable and the connection badge is noise, so
     * both are hidden. The overlay stays: boxes, distance ribbons and the alert banner are the
     * whole point of floating it over another app.
     *
     * The two-argument overload only exists from API 26, which is why lint wants an API guard
     * here. None is needed: picture-in-picture is itself API 26, so on API 24/25 the platform
     * never dispatches this callback and the super call below is unreachable.
     */
    @SuppressLint("NewApi")
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        HudLogger.i(TAG, "picture-in-picture -> $isInPictureInPictureMode")
        binding.controlBar.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.tvStatus.visibility = if (isInPictureInPictureMode) View.GONE else View.VISIBLE
        binding.controlBar.post { updateControlBarInset() }
        binding.rootContainer.post { applyVideoFit(force = true) }
    }

    /**
     * Put another app next to the HUD in one tap.
     *
     * [Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT] asks the system to place the new activity in the
     * adjacent split-screen cell; on Android 12L+ it may even pull a full-screen app into
     * split screen to make room. A chooser is used so the user picks whichever navigation app
     * they actually run — the flag survives the chooser, and the system makes a best effort
     * (it is documented as not guaranteed).
     */
    private fun launchAdjacent() {
        try {
            val geo = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q="))
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
            val chooser = Intent.createChooser(geo, getString(R.string.btn_split))
                .addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
            HudLogger.i(TAG, "adjacent launch requested (geo chooser)")
        } catch (t: Throwable) {
            HudLogger.w(TAG, "adjacent launch failed: ${t.message}")
            Toast.makeText(this, getString(R.string.split_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Multi-window loss of focus signals the docs recommend watching when an app holds an
     * exclusive resource such as the camera. The HUD deliberately keeps the USB stream and the
     * collision tracker running while another app has focus: a driver looking at the navigation
     * window still needs the proximity alert, so only the audio-focus state is logged here.
     */
    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        HudLogger.i(TAG, "top resumed activity -> $isTopResumedActivity (camera kept running)")
    }

    private fun updateRecUi() {
        val on = cameraManager.isRecording()
        binding.btnRec.text = getString(if (on) R.string.btn_rec_stop else R.string.btn_rec)
        binding.btnRec.setBackgroundColor(
            if (on) Color.parseColor("#80FF3B30") else Color.parseColor("#33263238")
        )
        refreshTelemetry()
    }

    private fun refreshTelemetry() {
        val lines = ArrayList<String>(7)
        lines += fpsText
        lines += perfText
        val batterySnap = batteryTracker.sample()
        lines += batterySnap.toHudString()
        if (inferText.isNotEmpty()) lines += inferText
        if (distanceText.isNotEmpty()) lines += distanceText
        lines += focusText
        if (cameraManager.isRecording()) {
            lines += "● REC ${recPath?.substringAfterLast('/') ?: ""}"
        }
        binding.hudOverlayView.setTelemetry(lines)
        binding.tvBatteryInfo.text = batterySnap.toDetailedSummary()
        batteryTracker.logIfNeeded(minIntervalMs = 30_000)
    }

    /**
     * Build the ground-plane model once the preview size is known, and rebuild it when the
     * frame size or the calibration parameters change. The tracker converts each detection's
     * pixel row into a range through this object, so the distance readout and the collision
     * warnings always agree.
     */
    private fun maybeRefreshCalibration(force: Boolean = false) {
        val (fw, fh) = cameraManager.frameSize()
        if (fw <= 0 || fh <= 0) return
        val height = HudPrefs.cameraHeightM(this)
        val pitch = HudPrefs.cameraPitchDeg(this)
        val fov = HudPrefs.cameraFovDeg(this)
        val key = "${fw}x$fh@$height/$pitch/$fov"
        if (!force && key == calibrationKey && calibration != null) return
        calibrationKey = key

        val calib = GroundPlaneCalibration(fw, fh, height, pitch, fov)
        calibration = calib
        tracker.reset()

        val centre = calib.centreDistance()
        distanceText = if (centre != null) "DIST  %.1f m".format(centre) else "DIST  --"
        // Logged so the model can be checked from the log without a screenshot.
        HudLogger.i(TAG, calib.describe())
    }

    private fun setFocusButtonsEnabled(enabled: Boolean) {
        binding.btnFocusNear.isEnabled = enabled
        binding.btnFocusFar.isEnabled = enabled
        // Hide the whole row when there is no focus hardware. It reclaims the width the row
        // needs, which is what keeps the remaining controls usable in a narrow split-screen
        // window on cameras like this one that report no focus unit at all.
        binding.focusRow.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun shareLog() {
        HudLogger.flush()
        val logFile = HudLogger.getLogFile()
        if (logFile != null && logFile.exists()) {
            Toast.makeText(this, "Log: ${logFile.absolutePath}", Toast.LENGTH_LONG).show()
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(contentResolver, "HUD log", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                val chooser = Intent.createChooser(send, "Share HUD log").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(chooser)
            } catch (e: Exception) {
                HudLogger.e(TAG, "Share log failed", e)
            }
        } else {
            Toast.makeText(this, "Log file not available", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecordingFinishedDialog(result: RecordingStorageManager.RecordingResult) {
        val sizeMb = result.file.length() / (1024f * 1024f)
        val builder = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Recording Saved (${result.storageLabel})")
            .setMessage("File: ${result.file.name}\nSize: %.1f MB".format(sizeMb))
            .setNegativeButton("Close", null)

        if (result.playableUri != null) {
            builder.setPositiveButton("Play") { _, _ ->
                RecordingStorageManager.openPlayback(this, result.playableUri)
            }
            builder.setNeutralButton("Share") { _, _ ->
                RecordingStorageManager.shareRecording(this, result.playableUri, "Share HUD Video")
            }
        }
        builder.show()
    }

    private fun showCameraSourceDialog() {
        val options = arrayOf(
            "USB Camera (UVC)",
            "System Camera (Rear Lens)",
            "System Camera (Front Lens)",
            "WiFi / IP Camera (RTSP / HTTP MJPEG)"
        )

        val currentIdx = when (cameraManager.currentMode) {
            HudPrefs.MODE_SYSTEM -> if (HudPrefs.systemFacing(this) == HudPrefs.FACING_FRONT) 2 else 1
            HudPrefs.MODE_WIFI -> 3
            else -> 0
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Select Camera Source")
            .setSingleChoiceItems(options, currentIdx) { dialog, which ->
                dialog.dismiss()
                when (which) {
                    0 -> cameraManager.switchMode(HudPrefs.MODE_USB)
                    1 -> {
                        HudPrefs.setSystemFacing(this, HudPrefs.FACING_BACK)
                        cameraManager.switchMode(HudPrefs.MODE_SYSTEM)
                    }
                    2 -> {
                        HudPrefs.setSystemFacing(this, HudPrefs.FACING_FRONT)
                        cameraManager.switchMode(HudPrefs.MODE_SYSTEM)
                    }
                    3 -> cameraManager.switchMode(HudPrefs.MODE_WIFI)
                }
                updateCameraSourceButton()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateCameraSourceButton() {
        binding.btnCameraSource.text = when (cameraManager.currentMode) {
            HudPrefs.MODE_SYSTEM -> {
                val isFront = HudPrefs.systemFacing(this) == HudPrefs.FACING_FRONT
                if (isFront) "CAM: PHONE (FRONT)" else "CAM: PHONE (BACK)"
            }
            HudPrefs.MODE_WIFI -> "CAM: WIFI"
            else -> "CAM: USB"
        }
    }

    // --------------------------------------------------------- mode switch

    private fun showHome() {
        isHudMode = false
        binding.hudPanel.visibility = View.GONE
        binding.homePanel.visibility = View.VISIBLE
        disableImmersiveMode()
    }

    /** Last window size the preview surface was fitted to. */
    private var fitRootW = 0
    private var fitRootH = 0
    private var fitFrameW = 0
    private var fitFrameH = 0

    /**
     * Fit the preview surface to the camera aspect inside the current window, and tell the
     * overlay to use the same rectangle.
     *
     * Both the SurfaceView size and the overlay mapping come from [VideoFit], so they cannot
     * disagree. Called whenever the frame size or the window size changes — rotation, split
     * screen, freeform, or a display-size change.
     */
    private fun applyVideoFit(force: Boolean = false) {
        val (fw, fh) = cameraManager.frameSize()
        val vw = binding.rootContainer.width
        val vh = binding.rootContainer.height
        if (fw <= 0 || fh <= 0 || vw <= 0 || vh <= 0) return
        binding.cameraSurfaceView.setFixedBufferSize(fw, fh)
        val aspect = fw.toFloat() / fh
        binding.hudOverlayView.setVideoAspect(aspect)
        if (!force && fw == fitFrameW && fh == fitFrameH && vw == fitRootW && vh == fitRootH) return
        fitFrameW = fw
        fitFrameH = fh
        fitRootW = vw
        fitRootH = vh

        val rect = VideoFit.fit(vw, vh, aspect)
        val nw = rect.width().toInt()
        val nh = rect.height().toInt()
        if (nw <= 0 || nh <= 0) return
        val lp = binding.cameraSurfaceView.layoutParams as FrameLayout.LayoutParams
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            lp.gravity = Gravity.CENTER
            binding.cameraSurfaceView.layoutParams = lp
            HudLogger.i(
                TAG,
                "video fit: frame ${fw}x$fh (aspect ${String.format(java.util.Locale.US, "%.3f", aspect)})" +
                    " -> surface ${nw}x$nh in window ${vw}x$vh"
            )
        }
    }

    private fun enterHud() {
        HudLogger.i(TAG, "Enter HUD mode")
        isHudMode = true
        batteryTracker.resetSession()
        binding.homePanel.visibility = View.GONE
        binding.hudPanel.visibility = View.VISIBLE
        enableImmersiveMode()
        binding.controlBar.post { updateControlBarInset() }
        // The camera may already be open, so fit immediately rather than waiting for a resize.
        binding.rootContainer.post { applyVideoFit(force = true) }
    }

    private fun exitHud() {
        HudLogger.i(TAG, "Exit HUD mode")
        isHudMode = false
        isCalibrationMode = false
        binding.calibrationPanel.visibility = View.GONE
        binding.hudOverlayView.setCalibrationMode(false)
        binding.hudPanel.visibility = View.GONE
        binding.homePanel.visibility = View.VISIBLE
        disableImmersiveMode()
        // Refresh the battery and USB summary so the user can see the current state.
        binding.tvBatteryInfo.text = batteryTracker.sample().toDetailedSummary()
        val result = cameraManager.describeUsbDevices()
        binding.tvUsbList.visibility = View.VISIBLE
        binding.tvUsbList.text = result
    }

    // ----------------------------------------------------- Calibration Mode

    private fun enterCalibration() {
        HudLogger.i(TAG, "enterCalibration")
        isCalibrationMode = true
        calibHeightM = HudPrefs.cameraHeightM(this)
        calibPitchDeg = HudPrefs.cameraPitchDeg(this)
        calibFovDeg = HudPrefs.cameraFovDeg(this)

        if (!isHudMode) {
            enterHud()
        }
        binding.controlBar.visibility = View.GONE
        binding.tvStatus.visibility = View.GONE
        binding.tvStats.visibility = View.GONE
        binding.calibrationPanel.visibility = View.VISIBLE
        binding.hudOverlayView.setCalibrationMode(true)
        syncCalibUi()
    }

    private fun exitCalibration() {
        HudLogger.i(TAG, "exitCalibration")
        isCalibrationMode = false
        binding.calibrationPanel.visibility = View.GONE
        binding.hudOverlayView.setCalibrationMode(false)
        binding.tvStatus.visibility = View.VISIBLE
        binding.tvStats.visibility = View.VISIBLE
        binding.controlBar.visibility = View.VISIBLE
        binding.controlBar.post { updateControlBarInset() }
        maybeRefreshCalibration(force = true)
    }

    private fun setupCalibrationControls() {
        binding.btnPitchMinus.setOnClickListener {
            calibPitchDeg = (calibPitchDeg - 0.5f).coerceIn(-5.0f, 30.0f)
            syncCalibUi()
        }
        binding.btnPitchPlus.setOnClickListener {
            calibPitchDeg = (calibPitchDeg + 0.5f).coerceIn(-5.0f, 30.0f)
            syncCalibUi()
        }
        binding.sbPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    calibPitchDeg = ((progress / 10.0f) - 5.0f).coerceIn(-5.0f, 30.0f)
                    syncCalibUi(updateSeekBars = false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnHeightMinus.setOnClickListener {
            calibHeightM = (calibHeightM - 0.05f).coerceIn(0.50f, 3.00f)
            syncCalibUi()
        }
        binding.btnHeightPlus.setOnClickListener {
            calibHeightM = (calibHeightM + 0.05f).coerceIn(0.50f, 3.00f)
            syncCalibUi()
        }
        binding.sbHeight.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    calibHeightM = (0.50f + progress / 100.0f).coerceIn(0.50f, 3.00f)
                    syncCalibUi(updateSeekBars = false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnFovMinus.setOnClickListener {
            calibFovDeg = (calibFovDeg - 1.0f).coerceIn(40.0f, 120.0f)
            syncCalibUi()
        }
        binding.btnFovPlus.setOnClickListener {
            calibFovDeg = (calibFovDeg + 1.0f).coerceIn(40.0f, 120.0f)
            syncCalibUi()
        }
        binding.sbFov.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    calibFovDeg = (40.0f + progress.toFloat()).coerceIn(40.0f, 120.0f)
                    syncCalibUi(updateSeekBars = false)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        binding.btnAutoPitch.setOnClickListener {
            autoSolvePitch()
        }

        binding.btnCalibSave.setOnClickListener {
            saveCalibration()
        }

        binding.btnCalibReset.setOnClickListener {
            calibPitchDeg = 8.0f
            calibHeightM = 1.20f
            calibFovDeg = 65.0f
            syncCalibUi()
            Toast.makeText(this, "Defaults reset. Tap Save to apply.", Toast.LENGTH_SHORT).show()
        }

        binding.btnCalibExit.setOnClickListener {
            exitCalibration()
        }
        binding.btnCalibExitTop.setOnClickListener {
            exitCalibration()
        }
    }

    private fun autoSolvePitch() {
        val targetDist = when {
            binding.rbRef15m.isChecked -> 15.0f
            binding.rbRef10m.isChecked -> 10.0f
            else -> 5.0f
        }
        val (fw, fh) = cameraManager.frameSize()
        val w = if (fw > 0) fw else HudPrefs.previewWidth(this)
        val h = if (fh > 0) fh else HudPrefs.previewHeight(this)
        val estimator = PinholeDistanceEstimator(w, h, calibHeightM, calibPitchDeg, calibFovDeg)

        // Find candidate detected vehicle or center road contact
        val lastDets = lastDetections
        val bestDet = lastDets.firstOrNull { it.bottom in 0.35f..0.98f }
        val contactRowPx = if (bestDet != null) bestDet.bottom * h else h * 0.75f

        val solvedPitch = estimator.solvePitchForTarget(targetDist, contactRowPx)
        calibPitchDeg = solvedPitch.coerceIn(-5.0f, 30.0f)
        syncCalibUi()
        val targetName = if (bestDet != null) "detected ${bestDet.label}" else "guide reticle"
        Toast.makeText(
            this,
            "Auto-Pitch aligned to $targetName at %.0fm -> %.1f°".format(targetDist, calibPitchDeg),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun saveCalibration() {
        HudPrefs.setCalibration(this, calibHeightM, calibPitchDeg, calibFovDeg)
        maybeRefreshCalibration(force = true)
        syncCalibUi()
        HudLogger.i(TAG, "Calibration saved: H=%.2fm, Pitch=%.1f°, FOV=%.1f°".format(calibHeightM, calibPitchDeg, calibFovDeg))
        Toast.makeText(
            this,
            "✓ Calibration Saved: H=%.2fm, Pitch=%.1f°, FOV=%.1f°".format(calibHeightM, calibPitchDeg, calibFovDeg),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun syncCalibUi(updateSeekBars: Boolean = true) {
        val (fw, fh) = cameraManager.frameSize()
        val w = if (fw > 0) fw else HudPrefs.previewWidth(this)
        val h = if (fh > 0) fh else HudPrefs.previewHeight(this)

        binding.tvPitchVal.text = "%.1f°".format(calibPitchDeg)
        binding.tvHeightVal.text = "%.2fm".format(calibHeightM)
        binding.tvFovVal.text = "%.1f°".format(calibFovDeg)

        if (updateSeekBars) {
            binding.sbPitch.progress = ((calibPitchDeg + 5.0f) * 10f).toInt().coerceIn(0, 350)
            binding.sbHeight.progress = ((calibHeightM - 0.50f) * 100f).toInt().coerceIn(0, 250)
            binding.sbFov.progress = (calibFovDeg - 40.0f).toInt().coerceIn(0, 80)
        }

        val estimator = PinholeDistanceEstimator(w, h, calibHeightM, calibPitchDeg, calibFovDeg)
        val horizonNormY = (estimator.horizonRow / h.toFloat()).coerceIn(0f, 1f)

        val distanceMarks = listOf(5f, 10f, 20f, 50f)
        val distanceLines = distanceMarks.mapNotNull { d ->
            estimator.rowForDistance(d)?.let { r ->
                val normY = r / h.toFloat()
                if (normY in 0f..1f) d to normY else null
            }
        }

        binding.hudOverlayView.updateCalibrationDisplay(
            horizonNormY = horizonNormY,
            pitchDeg = calibPitchDeg,
            heightM = calibHeightM,
            fovDeg = calibFovDeg,
            distanceLines = distanceLines
        )

        binding.tvCalibSummary.text =
            "PITCH: %.1f° | H: %.2fm | FOV: %.1f° | FOCAL: %.0fpx | HORIZON: Y=%.0f".format(
                calibPitchDeg, calibHeightM, calibFovDeg, estimator.fx, estimator.horizonRow
            )
    }

    private fun enableImmersiveMode() {
        if (isInMultiWindowMode) {
            // In split screen the system owns the bars; hiding them here fights the window
            // manager and can leave the app in a state the user cannot resize back.
            HudLogger.i(TAG, "immersive skipped: in multi-window mode")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
            )
        }
    }

    private fun disableImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(
                WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ------------------------------------------------------------ lifecycle

    override fun onStart() {
        super.onStart()
        HudLogger.i(TAG, "onStart - watchdog & ticker")
        cameraManager.startWatchdog()
        uiHandler.removeCallbacks(telemetryTicker)
        uiHandler.post(telemetryTicker)
    }

    override fun onResume() {
        super.onResume()
        HudLogger.i(TAG, "onResume")
        applySavedSettings()
        // Android 13+ needs CAMERA permission before it will grant USB access
        // to a UVC camera, so request it up front; scan once granted.
        ensureCameraPermission {
            cameraManager.scanAndConnect()
        }
        if (isHudMode) enableImmersiveMode() else disableImmersiveMode()
    }

    override fun onStop() {
        super.onStop()
        HudLogger.i(TAG, "onStop - background (receiver stays registered)")
        uiHandler.removeCallbacks(telemetryTicker)
        cameraManager.onAppBackgrounded()
    }

    override fun onDestroy() {
        super.onDestroy()
        HudLogger.i(TAG, "onDestroy - release native + close logger")
        alertTone.release()
        cameraManager.unregister()
        cameraManager.destroy()
        CameraNativeBridge.nativeRelease()
        HudLogger.close()
    }

    /**
     * Split screen, freeform and desktop resizing all arrive here. The camera keeps running:
     * the manifest declares the config changes, so the activity is not recreated and the
     * stream (and the measured backend choice) survives a resize.
     */
    override fun onMultiWindowModeChanged(isInMultiWindowMode: Boolean) {
        super.onMultiWindowModeChanged(isInMultiWindowMode)
        HudLogger.i(TAG, "multi-window mode -> $isInMultiWindowMode")
        if (isHudMode) {
            if (isInMultiWindowMode) disableImmersiveMode() else enableImmersiveMode()
        }
        binding.rootContainer.post { applyVideoFit(force = true) }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        HudLogger.i(
            TAG,
            "configuration changed: ${newConfig.screenWidthDp}x${newConfig.screenHeightDp}dp " +
                "orientation=${newConfig.orientation} smallestWidth=${newConfig.smallestScreenWidthDp}dp"
        )
        binding.rootContainer.post { applyVideoFit(force = true) }
    }

    // singleTask: a USB attach intent is delivered here instead of spawning a
    // duplicate activity instance.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        HudLogger.i(TAG, "onNewIntent: ${intent.action}")
        if (intent.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            HudLogger.i(TAG, "onNewIntent: USB device attached")
            cameraManager.scanAndConnect()
        }
        if (intent.getBooleanExtra(EXTRA_START_CALIBRATION, false)) {
            binding.root.post { enterCalibration() }
        }
    }

    // ------------------------------------------------------- camera events

    override fun onStatusChanged(status: String) {
        HudLogger.i(TAG, "Status: $status")
        runOnUiThread {
            binding.tvStatus.text = status
            binding.tvHomeStatus.text = status
            val color = when {
                status.contains("Connected", ignoreCase = true) ||
                    status.contains("Streaming", ignoreCase = true) -> Color.parseColor("#00E676")
                status.contains("Waiting", ignoreCase = true) ||
                    status.contains("Connecting", ignoreCase = true) -> Color.parseColor("#FFD600")
                else -> Color.parseColor("#FF1744")
            }
            binding.tvStatus.setTextColor(color)
            binding.tvHomeStatus.setTextColor(color)
            if (status.contains("Streaming", ignoreCase = true) && !isHudMode) {
                binding.tvHomeDetail.text = "Streaming. Tap START HUD for fullscreen."
            }
        }
    }

    override fun onConnected(device: UsbDevice) {
        HudLogger.i(TAG, "Connected: ${device.productName ?: "USB Camera"} (${device.deviceName})")
        runOnUiThread {
            val text = "Connected: ${device.productName ?: "USB Camera"}"
            binding.tvStatus.text = text
            binding.tvHomeStatus.text = text
            binding.tvStatus.setTextColor(Color.parseColor("#00E676"))
            binding.tvHomeStatus.setTextColor(Color.parseColor("#00E676"))
            binding.tvHomeDetail.text = "Streaming. Tap START HUD for fullscreen."
        }
    }

    override fun onDisconnected() {
        HudLogger.w(TAG, "Disconnected")
        runOnUiThread {
            val text = getString(R.string.status_disconnected)
            binding.tvStatus.text = text
            binding.tvHomeStatus.text = text
            binding.tvStatus.setTextColor(Color.parseColor("#FF1744"))
            binding.tvHomeStatus.setTextColor(Color.parseColor("#FF1744"))
            binding.tvStats.text = "FPS: 0 | Disconnected"
            binding.tvHomeDetail.text = "Camera disconnected — watchdog is reconnecting."
            binding.hudOverlayView.clearDetections()
            fpsText = "FPS --"
            perfText = "CPU --  --°C"
            inferText = ""
            focusText = "FOCUS --"
            refreshTelemetry()
            setFocusButtonsEnabled(false)
            binding.btnFocusAf.isEnabled = false
        }
    }

    override fun onError(message: String) {
        HudLogger.e(TAG, "Error: $message")
        runOnUiThread {
            val text = "Error: $message"
            binding.tvStatus.text = text
            binding.tvHomeStatus.text = text
            binding.tvStatus.setTextColor(Color.parseColor("#FF1744"))
            binding.tvHomeStatus.setTextColor(Color.parseColor("#FF1744"))
        }
    }

    override fun onTelemetry(t: Telemetry) {
        fpsText = if (t.frameIntervalMs > 0f) {
            "FPS ${t.fps}  · ${pct1(t.frameIntervalMs)}ms/frame"
        } else {
            "FPS ${t.fps}"
        }
        perfText = "CPU ${if (t.cpuPercent >= 0) "${t.cpuPercent}%" else "--"}  " +
            "TEMP ${if (t.tempC > 0) "${t.tempC}°C" else "--"}"
        inferText = if (t.inferMs >= 0f) {
            "INFER ${t.inferMs.toInt()}ms  ${t.provider}"
        } else {
            ""
        }
        runOnUiThread {
            binding.tvStats.text = "$fpsText   $perfText"
            maybeRefreshCalibration()
            refreshTelemetry()
        }
        // Periodically write battery statistics into on-device log
        batteryTracker.logIfNeeded(minIntervalMs = 30_000)
        // 1 Hz snapshot of the tracker so closing rates can be checked without a screenshot.
        if (t.fps > 0) HudLogger.i(TAG, "tracks: ${tracker.describe()}")
    }

    private fun pct1(v: Float): String = String.format(java.util.Locale.US, "%.1f", v)

    override fun onFocusChanged(info: FocusInfo) {
        focusText = when {
            !info.supported -> "FOCUS n/a"
            info.auto -> "FOCUS AF"
            else -> "FOCUS ${info.percent}%"
        }
        runOnUiThread {
            refreshTelemetry()
            setFocusButtonsEnabled(info.supported)
            binding.btnFocusAf.isEnabled = info.supported && cameraManager.hasAutoFocus()
            binding.btnFocusAf.setTextColor(
                if (info.auto) Color.parseColor("#00E676") else Color.parseColor("#CC00E5FF")
            )
        }
    }

    /**
     * Invoked on the ONNX inference thread. [HudOverlayView.setDetections] only swaps a
     * reference and schedules a redraw, so this is safe to call off the UI thread.
     */
    override fun onDetections(detections: List<Detection>) {
        val calib = calibration
        val (fw, fh) = cameraManager.frameSize()
        val now = SystemClock.elapsedRealtime()
        val update = try {
            tracker.update(detections, calib, fw, fh, now)
        } catch (t: Throwable) {
            HudLogger.w(TAG, "tracker failed: ${t.message}")
            VehicleTracker.Update(emptyList(), null)
        }

        lastDetections = detections
        // Annotate each box with its true distance and, while closing, its time-to-collision.
        val annotated = if (update.states.isEmpty()) detections else detections.mapIndexed { i, d ->
            val s = update.states.getOrNull(i)
            if (s == null || s.distanceM.isNaN()) {
                d
            } else if (isCalibrationMode) {
                val pStr = if (!s.pinholeDistanceM.isNaN()) "%.1fm".format(s.pinholeDistanceM) else "--"
                val gStr = if (!s.groundDistanceM.isNaN()) "%.1fm".format(s.groundDistanceM) else "--"
                val line1 = "${d.label.uppercase()} ${(d.score * 100).toInt()}%"
                val line2 = "TRUE: %.1fm (P:%s G:%s)".format(s.distanceM, pStr, gStr)
                val line3 = "X:%+.1fm".format(s.lateralM)
                d.copy(label = "$line1\n$line2\n$line3")
            } else {
                val extra = StringBuilder(" · %.0fm".format(s.distanceM))
                if (!s.ttcS.isNaN()) extra.append(" TTC %.1fs".format(s.ttcS))
                d.copy(label = d.label + extra)
            }
        }
        binding.hudOverlayView.setDetections(annotated)
        cameraManager.updateRecordingDetections(annotated)

        val alert = update.alert
        val alertText = if (alert == null) "" else {
            "%s %.0fm  TTC %.1fs".format(alert.label.uppercase(), alert.distanceM, alert.ttcS)
        }
        binding.hudOverlayView.setAlert(alertText, alert?.warn == true)
        if (alert != null && lastAlertWarn != alert.warn) alertTone.beep(alert.warn)
        lastAlertWarn = alert?.warn

        if (detections.size != lastDetectionCount) {
            lastDetectionCount = detections.size
            HudLogger.i(
                TAG,
                "detections(${detections.size}): " +
                    detections.take(4).joinToString(", ") { "${it.label} ${(it.score * 100).toInt()}%" }
            )
        }
        if (alert != null) {
            HudLogger.i(
                TAG,
                "alert ${if (alert.warn) "WARN" else "caution"}: ${alert.label} " +
                    "%.1fm ttc=%.1fs".format(alert.distanceM, alert.ttcS)
            )
        }
    }
}
