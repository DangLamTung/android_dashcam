package com.driving.hud.usbcamera

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
        val swRecordOverlay = findViewById<Switch>(R.id.swRecordOverlay)
        val swDetect = findViewById<Switch>(R.id.swDetect)
        val rbCamUsb = findViewById<RadioButton>(R.id.rbCamUsb)
        val rbCamSystem = findViewById<RadioButton>(R.id.rbCamSystem)
        val rbCamWifi = findViewById<RadioButton>(R.id.rbCamWifi)
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
        swRecordOverlay.isChecked = HudPrefs.recordOverlay(this)
        swDetect.isChecked = HudPrefs.detect(this)
        etModel.setText(HudPrefs.modelAsset(this))

        when (HudPrefs.cameraMode(this)) {
            HudPrefs.MODE_SYSTEM -> rbCamSystem.isChecked = true
            HudPrefs.MODE_WIFI -> rbCamWifi.isChecked = true
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
            HudPrefs.setRecordOverlay(this, swRecordOverlay.isChecked)
            HudPrefs.setDetect(this, swDetect.isChecked)

            val mode = when {
                rbCamSystem.isChecked -> HudPrefs.MODE_SYSTEM
                rbCamWifi.isChecked -> HudPrefs.MODE_WIFI
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
            HudPrefs.setModelAsset(this, etModel.text.toString().trim().ifEmpty { HudPrefs.DEFAULT_MODEL })

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

            HudLogger.i(TAG, "saved: grid=${swGrid.isChecked} night=${swNight.isChecked} mirror=${swMirror.isChecked} res=${w}x$h fps=$fps detect=${swDetect.isChecked} conf=${sbConf.progress} interval=${interval}ms")
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
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
