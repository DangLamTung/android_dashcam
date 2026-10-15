package com.driving.hud.usbcamera.util

import android.content.Context
import android.content.SharedPreferences

/** Persisted HUD settings (SharedPreferences). */
object HudPrefs {
    private const val FILE = "hud_prefs"

    /**
     * Bundled detector used when nothing else is configured. Kept public and referenced by
     * Settings so the default cannot drift between the two places that name a model.
     */
    const val DEFAULT_MODEL = "yolov10n-224.onnx"

    private const val KEY_GRID = "grid"
    private const val KEY_NIGHT = "night"
    private const val KEY_MIRROR = "mirror"
    private const val KEY_WIDTH = "preview_width"
    private const val KEY_HEIGHT = "preview_height"
    private const val KEY_DETECT = "detect"
    private const val KEY_CONF = "detect_conf"
    private const val KEY_MODEL = "detect_model"
    private const val KEY_SAMPLE_FPS = "sample_fps"
    private const val KEY_TARGET_FPS = "target_fps"
    private const val KEY_DETECT_INTERVAL = "detect_interval_ms"
    private const val KEY_PROVIDER = "detect_provider"
    private const val KEY_PROVIDER_KEY = "detect_provider_key"
    private const val KEY_CALIB_HEIGHT = "calib_height_m"
    private const val KEY_CALIB_PITCH = "calib_pitch_deg"
    private const val KEY_CALIB_FOV = "calib_fov_deg"
    private const val KEY_RECORD_OVERLAY = "record_overlay"
    private const val KEY_SWAP_RECORD_CHROMA = "swap_record_chroma"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun grid(ctx: Context) = sp(ctx).getBoolean(KEY_GRID, true)
    fun night(ctx: Context) = sp(ctx).getBoolean(KEY_NIGHT, false)
    fun mirror(ctx: Context) = sp(ctx).getBoolean(KEY_MIRROR, false)

    fun previewWidth(ctx: Context) = sp(ctx).getInt(KEY_WIDTH, 1280)
    fun previewHeight(ctx: Context) = sp(ctx).getInt(KEY_HEIGHT, 720)

    fun detect(ctx: Context) = sp(ctx).getBoolean(KEY_DETECT, false)

    /**
     * When true an extra RAW frame callback is registered to measure the camera frame rate.
     * The engine implements RAW as a full MJPEG->YUYV conversion, so this roughly doubles
     * decode cost — it is a diagnostic, not a default.
     */
    fun sampleFps(ctx: Context) = sp(ctx).getBoolean(KEY_SAMPLE_FPS, true)

    /**
     * Frame rate requested from the camera. libuvc picks the closest interval the camera
     * advertises, so a lower value only helps if the camera supports it.
     */
    fun targetFps(ctx: Context) = sp(ctx).getInt(KEY_TARGET_FPS, 30)

    fun setTargetFps(ctx: Context, v: Int) = sp(ctx).edit().putInt(KEY_TARGET_FPS, v).apply()

    fun confidence(ctx: Context) = sp(ctx).getFloat(KEY_CONF, 0.35f)

    /**
     * Bundled detector to load. The 224 export is the default: half the compute of the 320
     * model for the same head, which matters far more on this tablet than the ~1.4x extra
     * detection range the 320 export would give.
     */
    fun modelAsset(ctx: Context) = sp(ctx).getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL

    /**
     * Minimum gap between detection passes, in ms. Inference is by far the most expensive
     * thing the app does on CPU, so this is the main throttle: 500 ms = 2 passes/second.
     */
    fun detectIntervalMs(ctx: Context) = sp(ctx).getInt(KEY_DETECT_INTERVAL, 500)

    fun setDetectIntervalMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_DETECT_INTERVAL, v).apply()

    /**
     * Execution provider that measured fastest last time (e.g. `NNAPI/fp16`). Stored with
     * [detectProviderKey] rather than on its own, because the winner depends on the model and
     * the frame size it was benchmarked against.
     */
    fun detectProvider(ctx: Context) = sp(ctx).getString(KEY_PROVIDER, "") ?: ""

    /** Identity of the model + frame size [detectProvider] was measured for. */
    fun detectProviderKey(ctx: Context) = sp(ctx).getString(KEY_PROVIDER_KEY, "") ?: ""

    fun setDetectProvider(ctx: Context, provider: String, key: String) = sp(ctx).edit()
        .putString(KEY_PROVIDER, provider)
        .putString(KEY_PROVIDER_KEY, key)
        .apply()

    // ---- ground-distance calibration ------------------------------------

    /** Camera height above the road, in metres. Dominates the distance accuracy. */
    fun cameraHeightM(ctx: Context) = sp(ctx).getFloat(KEY_CALIB_HEIGHT, 1.2f)

    /** Downward tilt of the optical axis in degrees. Positive means looking down. */
    fun cameraPitchDeg(ctx: Context) = sp(ctx).getFloat(KEY_CALIB_PITCH, 8f)

    /** Horizontal field of view in degrees; sets the focal length in pixels. */
    fun cameraFovDeg(ctx: Context) = sp(ctx).getFloat(KEY_CALIB_FOV, 65f)

    fun setCalibration(ctx: Context, heightM: Float, pitchDeg: Float, fovDeg: Float) =
        sp(ctx).edit()
            .putFloat(KEY_CALIB_HEIGHT, heightM)
            .putFloat(KEY_CALIB_PITCH, pitchDeg)
            .putFloat(KEY_CALIB_FOV, fovDeg)
            .apply()

    fun setGrid(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_GRID, v).apply()
    fun setNight(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_NIGHT, v).apply()
    fun setMirror(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_MIRROR, v).apply()
    fun setPreviewSize(ctx: Context, w: Int, h: Int) =
        sp(ctx).edit().putInt(KEY_WIDTH, w).putInt(KEY_HEIGHT, h).apply()

    fun setDetect(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_DETECT, v).apply()
    fun setConfidence(ctx: Context, v: Float) = sp(ctx).edit().putFloat(KEY_CONF, v).apply()
    fun setModelAsset(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_MODEL, v).apply()

    fun recordOverlay(ctx: Context) = sp(ctx).getBoolean(KEY_RECORD_OVERLAY, true)
    fun setRecordOverlay(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_RECORD_OVERLAY, v).apply()

    fun swapRecordChroma(ctx: Context) = sp(ctx).getBoolean(KEY_SWAP_RECORD_CHROMA, false)
    fun setSwapRecordChroma(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_SWAP_RECORD_CHROMA, v).apply()

    const val MODE_USB = "usb"
    const val MODE_SYSTEM = "system"
    const val MODE_WIFI = "wifi"

    const val FACING_BACK = "back"
    const val FACING_FRONT = "front"

    private const val KEY_CAMERA_MODE = "camera_mode"
    private const val KEY_SYSTEM_FACING = "system_facing"
    private const val KEY_WIFI_URL = "wifi_url"

    fun cameraMode(ctx: Context): String = sp(ctx).getString(KEY_CAMERA_MODE, MODE_USB) ?: MODE_USB
    fun setCameraMode(ctx: Context, mode: String) = sp(ctx).edit().putString(KEY_CAMERA_MODE, mode).apply()

    fun systemFacing(ctx: Context): String = sp(ctx).getString(KEY_SYSTEM_FACING, FACING_BACK) ?: FACING_BACK
    fun setSystemFacing(ctx: Context, facing: String) = sp(ctx).edit().putString(KEY_SYSTEM_FACING, facing).apply()

    fun wifiUrl(ctx: Context): String =
        sp(ctx).getString(KEY_WIFI_URL, "http://192.168.1.100:8080/video") ?: "http://192.168.1.100:8080/video"
    fun setWifiUrl(ctx: Context, url: String) = sp(ctx).edit().putString(KEY_WIFI_URL, url).apply()
}
