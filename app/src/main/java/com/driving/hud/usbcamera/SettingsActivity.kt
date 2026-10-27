package com.driving.hud.usbcamera

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.driving.hud.usbcamera.camera.DvrPreviewFile
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs

/**
 * Settings screen: HUD overlay options, preview resolution and the ONNX detection
 * options. Values are persisted with [HudPrefs] and read by MainActivity /
 * AusbcCameraController on the next connect.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"

        /** Bundled model assets, named exactly as they sit in `assets/`. */
        const val MODEL_V10N_224 = "yolov10n-224.onnx"
        const val MODEL_V10N_320 = "yolov10n-320.onnx"
        const val MODEL_YOLO26N_256 = "yolo26n-256.onnx"
        const val MODEL_NANODET_320 = "nanodet-plus-m-320.onnx"
        private const val RES_480_W = 800
        private const val RES_480_H = 480

        /**
         * The camera advertises 640x360 and 960x544 but not 640x480 in a way it will actually
         * negotiate (that mode fails with err=-51), which is why the low options offered here
         * are 640x360 and 960x544 rather than the usual 480p.
         *
         * Decode cost tracks pixel count: 640x360 is 25% of 720p, 960x544 is 57%.
         */
        private const val RES_360_W = 640
        private const val RES_360_H = 360
        private const val RES_544_W = 960
        private const val RES_544_H = 544
        private const val RES_720_W = 1280
        private const val RES_720_H = 720
        private const val RES_1080_W = 1920
        private const val RES_1080_H = 1080
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HudLogger.i(TAG, "onCreate")
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val swGrid = findViewById<Switch>(R.id.swGrid)
        val swNight = findViewById<Switch>(R.id.swNight)
        val swMirror = findViewById<Switch>(R.id.swMirror)
        val swHudFlip = findViewById<Switch>(R.id.swHudFlip)
        val swRecordOverlay = findViewById<Switch>(R.id.swRecordOverlay)
        val swSwapChroma = findViewById<Switch>(R.id.swSwapChroma)
        val swDetect = findViewById<Switch>(R.id.swDetect)
        val swDetectZone = findViewById<Switch>(R.id.swDetectZone)
        val swLanes = findViewById<Switch>(R.id.swLanes)
        val swShowBattery = findViewById<Switch>(R.id.swShowBattery)
        val rbCamUsb = findViewById<RadioButton>(R.id.rbCamUsb)
        val rbCamSystem = findViewById<RadioButton>(R.id.rbCamSystem)
        val rbCamWifi = findViewById<RadioButton>(R.id.rbCamWifi)
        val rbCamDvr = findViewById<RadioButton>(R.id.rbCamDvr)
        val rbLensBack = findViewById<RadioButton>(R.id.rbLensBack)
        val rbLensFront = findViewById<RadioButton>(R.id.rbLensFront)
        val etWifiUrl = findViewById<EditText>(R.id.etWifiUrl)
        val rb480 = findViewById<RadioButton>(R.id.rb480)
        val rb544 = findViewById<RadioButton>(R.id.rb544)
        val rb360 = findViewById<RadioButton>(R.id.rb360)
        val rb720 = findViewById<RadioButton>(R.id.rb720)
        val rb1080 = findViewById<RadioButton>(R.id.rb1080)
        val sbConf = findViewById<SeekBar>(R.id.sbConfidence)
        val tvConf = findViewById<TextView>(R.id.tvConfidence)
        val etModel = findViewById<EditText>(R.id.etModel)
        val rgModel = findViewById<RadioGroup>(R.id.rgModel)
        val rbModelV10n224 = findViewById<RadioButton>(R.id.rbModelV10n224)
        val rbModelV10n320 = findViewById<RadioButton>(R.id.rbModelV10n320)
        val rbModelYolo26n256 = findViewById<RadioButton>(R.id.rbModelYolo26n256)
        val rbModelNanoDet320 = findViewById<RadioButton>(R.id.rbModelNanoDet320)
        val rbFps15 = findViewById<RadioButton>(R.id.rbFps15)
        val rbFps20 = findViewById<RadioButton>(R.id.rbFps20)
        val rbFps30 = findViewById<RadioButton>(R.id.rbFps30)
        val rbRate1 = findViewById<RadioButton>(R.id.rbRate1)
        val rbRate2 = findViewById<RadioButton>(R.id.rbRate2)
        val rbRate5 = findViewById<RadioButton>(R.id.rbRate5)
        val rbRateFree = findViewById<RadioButton>(R.id.rbRateFree)
        val etCamHeight = findViewById<EditText>(R.id.etCamHeight)
        val etCamPitch = findViewById<EditText>(R.id.etCamPitch)
        val etCamFov = findViewById<EditText>(R.id.etCamFov)

        // ---- load current values
        swGrid.isChecked = HudPrefs.grid(this)
        swNight.isChecked = HudPrefs.night(this)
        swMirror.isChecked = HudPrefs.mirror(this)
        swHudFlip.isChecked = HudPrefs.hudFlip(this)
        swRecordOverlay.isChecked = HudPrefs.recordOverlay(this)
        swSwapChroma.isChecked = HudPrefs.swapRecordChroma(this)
        swDetect.isChecked = HudPrefs.detect(this)
        swDetectZone.isChecked = HudPrefs.detectZoneBottomOnly(this)
        swLanes.isChecked = HudPrefs.laneDetect(this)
        swShowBattery.isChecked = HudPrefs.showBattery(this)
        val savedModel = HudPrefs.modelAsset(this)
        etModel.setText(savedModel)
        // A bundled model is picked with a radio; anything else is whatever the asset field says.
        when (savedModel) {
            MODEL_V10N_224 -> rbModelV10n224.isChecked = true
            MODEL_V10N_320 -> rbModelV10n320.isChecked = true
            MODEL_YOLO26N_256 -> rbModelYolo26n256.isChecked = true
            MODEL_NANODET_320 -> rbModelNanoDet320.isChecked = true
        }
        // Selecting a preset model fills the edit text for clarity.
        rgModel.setOnCheckedChangeListener { _, checkedId ->
            val preset = when (checkedId) {
                R.id.rbModelV10n224 -> MODEL_V10N_224
                R.id.rbModelV10n320 -> MODEL_V10N_320
                R.id.rbModelYolo26n256 -> MODEL_YOLO26N_256
                R.id.rbModelNanoDet320 -> MODEL_NANODET_320
                else -> null
            }
            if (preset != null && etModel.text.toString() != preset) {
                etModel.setText(preset)
            }
        }
        // Typing in the custom field clears presets unless matching one of them.
        etModel.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val current = etModel.text.toString().trim()
                val match = when (current) {
                    MODEL_V10N_224 -> R.id.rbModelV10n224
                    MODEL_V10N_320 -> R.id.rbModelV10n320
                    MODEL_YOLO26N_256 -> R.id.rbModelYolo26n256
                    MODEL_NANODET_320 -> R.id.rbModelNanoDet320
                    else -> null
                }
                if (match == null) rgModel.clearCheck()
            }
        }

        when (HudPrefs.cameraMode(this)) {
            HudPrefs.MODE_SYSTEM -> rbCamSystem.isChecked = true
            HudPrefs.MODE_WIFI -> rbCamWifi.isChecked = true
            HudPrefs.MODE_DVR -> rbCamDvr.isChecked = true
            else -> rbCamUsb.isChecked = true
        }

        if (HudPrefs.systemFacing(this) == HudPrefs.FACING_FRONT) {
            rbLensFront.isChecked = true
        } else {
            rbLensBack.isChecked = true
        }

        etWifiUrl.setText(HudPrefs.wifiUrl(this))

        etCamHeight.setText(String.format(java.util.Locale.US, "%.2f", HudPrefs.cameraHeightM(this)))
        etCamPitch.setText(String.format(java.util.Locale.US, "%.1f", HudPrefs.cameraPitchDeg(this)))
        etCamFov.setText(String.format(java.util.Locale.US, "%.1f", HudPrefs.cameraFovDeg(this)))

        val cw = HudPrefs.previewWidth(this)
        when {
            cw >= RES_1080_W -> rb1080.isChecked = true
            cw >= RES_720_W -> rb720.isChecked = true
            cw >= RES_544_W -> rb544.isChecked = true
            cw >= RES_480_W -> rb480.isChecked = true
            else -> rb360.isChecked = true
        }

        val confPct = (HudPrefs.confidence(this) * 100).toInt().coerceIn(1, 95)
        sbConf.progress = confPct
        tvConf.text = "$confPct%"

        when (HudPrefs.targetFps(this)) {
            15 -> rbFps15.isChecked = true
            20 -> rbFps20.isChecked = true
            else -> rbFps30.isChecked = true
        }

        when (HudPrefs.detectIntervalMs(this)) {
            0 -> rbRateFree.isChecked = true
            1000 -> rbRate1.isChecked = true
            200 -> rbRate5.isChecked = true
            else -> rbRate2.isChecked = true
        }

        sbConf.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                tvConf.text = "${progress.coerceAtLeast(1)}%"
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        findViewById<Button>(R.id.btnSave).setOnClickListener {
            HudPrefs.setGrid(this, swGrid.isChecked)
            HudPrefs.setNight(this, swNight.isChecked)
            HudPrefs.setMirror(this, swMirror.isChecked)
            HudPrefs.setHudFlip(this, swHudFlip.isChecked)
            HudPrefs.setRecordOverlay(this, swRecordOverlay.isChecked)
            HudPrefs.setSwapRecordChroma(this, swSwapChroma.isChecked)
            HudPrefs.setDetect(this, swDetect.isChecked)
            HudPrefs.setDetectZoneBottomOnly(this, swDetectZone.isChecked)
            HudPrefs.setLaneDetect(this, swLanes.isChecked)
            HudPrefs.setShowBattery(this, swShowBattery.isChecked)

            val mode = when {
                rbCamSystem.isChecked -> HudPrefs.MODE_SYSTEM
                rbCamWifi.isChecked -> HudPrefs.MODE_WIFI
                rbCamDvr.isChecked -> HudPrefs.MODE_DVR
                else -> HudPrefs.MODE_USB
            }
            HudPrefs.setCameraMode(this, mode)

            val facing = if (rbLensFront.isChecked) HudPrefs.FACING_FRONT else HudPrefs.FACING_BACK
            HudPrefs.setSystemFacing(this, facing)

            val wifiUrl = etWifiUrl.text.toString().trim()
            if (wifiUrl.isNotEmpty()) {
                HudPrefs.setWifiUrl(this, wifiUrl)
            }

            HudPrefs.setConfidence(this, sbConf.progress.coerceAtLeast(1) / 100f)
            val pickedModel = when {
                rbModelYolo26n256.isChecked -> MODEL_YOLO26N_256
                rbModelNanoDet320.isChecked -> MODEL_NANODET_320
                rbModelV10n320.isChecked -> MODEL_V10N_320
                rbModelV10n224.isChecked -> MODEL_V10N_224
                else -> etModel.text.toString().trim().ifEmpty { HudPrefs.DEFAULT_MODEL }
            }
            HudPrefs.setModelAsset(this, pickedModel)

            // Geometry calibration. Values are clamped so a typo cannot produce a division by
            // zero or a horizon behind the camera.
            val height = etCamHeight.text.toString().toFloatOrNull()?.coerceIn(0.2f, 5f)
                ?: HudPrefs.cameraHeightM(this)
            val pitch = etCamPitch.text.toString().toFloatOrNull()?.coerceIn(-45f, 60f)
                ?: HudPrefs.cameraPitchDeg(this)
            val fov = etCamFov.text.toString().toFloatOrNull()?.coerceIn(20f, 120f)
                ?: HudPrefs.cameraFovDeg(this)
            HudPrefs.setCalibration(this, height, pitch, fov)

            val (w, h) = when {
                rb1080.isChecked -> RES_1080_W to RES_1080_H
                rb720.isChecked -> RES_720_W to RES_720_H
                rb544.isChecked -> RES_544_W to RES_544_H
                rb480.isChecked -> RES_480_W to RES_480_H
                else -> RES_360_W to RES_360_H
            }
            HudPrefs.setPreviewSize(this, w, h)

            val fps = when {
                rbFps15.isChecked -> 15
                rbFps20.isChecked -> 20
                else -> 30
            }
            HudPrefs.setTargetFps(this, fps)

            val interval = when {
                rbRateFree.isChecked -> 0
                rbRate1.isChecked -> 1000
                rbRate5.isChecked -> 200
                else -> 500
            }
            HudPrefs.setDetectIntervalMs(this, interval)

            HudLogger.i(TAG, "saved: grid=${swGrid.isChecked} night=${swNight.isChecked} mirror=${swMirror.isChecked} res=${w}x$h fps=$fps detect=${swDetect.isChecked} conf=${sbConf.progress} interval=${interval}ms " +
                "model=$pickedModel lanes=${swLanes.isChecked}")
            Toast.makeText(this, "Saved. Reconnect to apply preview size.", Toast.LENGTH_LONG).show()
            finish()
        }

        findViewById<Button>(R.id.btnLiveCalib).setOnClickListener {
            val height = etCamHeight.text.toString().toFloatOrNull()?.coerceIn(0.2f, 5f)
                ?: HudPrefs.cameraHeightM(this)
            val pitch = etCamPitch.text.toString().toFloatOrNull()?.coerceIn(-45f, 60f)
                ?: HudPrefs.cameraPitchDeg(this)
            val fov = etCamFov.text.toString().toFloatOrNull()?.coerceIn(20f, 120f)
                ?: HudPrefs.cameraFovDeg(this)
            HudPrefs.setCalibration(this, height, pitch, fov)

            val intent = android.content.Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_START_CALIBRATION, true)
                addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
            finish()
        }

        findViewById<Button>(R.id.btnCancel).setOnClickListener { finish() }

        // One-time directory grant for the U2 DVR's preview file. The DVR's own card must stay
        // mounted for the preview to update, so the app reads VSFILE through the picker's grant
        // instead of claiming the USB interface.
        val tvDvrAccess = findViewById<TextView>(R.id.tvDvrAccess)
        fun showDvrAccessState() {
            tvDvrAccess.text = if (DvrPreviewFile.hasGrant(this)) {
                getString(R.string.settings_dvr_access_granted)
            } else {
                getString(R.string.settings_dvr_access_missing)
            }
        }
        showDvrAccessState()

        val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                DvrPreviewFile.saveGrant(this, uri)
                Toast.makeText(this, R.string.settings_dvr_access_saved, Toast.LENGTH_LONG).show()
            } catch (t: SecurityException) {
                HudLogger.e(TAG, "folder grant failed", t)
                Toast.makeText(this, R.string.settings_dvr_access_failed, Toast.LENGTH_LONG).show()
            }
            showDvrAccessState()
        }

        findViewById<Button>(R.id.btnDvrFolder).setOnClickListener {
            HudLogger.i(TAG, "U2 DVR folder picker opened")
            folderPicker.launch(null)
        }
        findViewById<Button>(R.id.btnDvrForget).setOnClickListener {
            DvrPreviewFile.clearGrant(this)
            HudLogger.i(TAG, "U2 DVR folder grant cleared")
            showDvrAccessState()
        }
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
