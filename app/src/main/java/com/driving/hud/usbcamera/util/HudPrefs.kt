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
    private const val KEY_PROVIDERS = "detect_providers"
    private const val KEY_CALIB_HEIGHT = "calib_height_m"
    private const val KEY_CALIB_PITCH = "calib_pitch_deg"
    private const val KEY_CALIB_FOV = "calib_fov_deg"
    private const val KEY_RECORD_OVERLAY = "record_overlay"
    private const val KEY_SWAP_RECORD_CHROMA = "swap_record_chroma"
    private const val KEY_ALERTS = "alerts_enabled"
    private const val KEY_SHOW_BATTERY = "show_battery"
    private const val KEY_DETECT_ZONE = "detect_zone_bottom_only"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun grid(ctx: Context) = sp(ctx).getBoolean(KEY_GRID, true)
    fun night(ctx: Context) = sp(ctx).getBoolean(KEY_NIGHT, false)
    fun mirror(ctx: Context) = sp(ctx).getBoolean(KEY_MIRROR, false)

    fun previewWidth(ctx: Context) = sp(ctx).getInt(KEY_WIDTH, 1280)
    fun previewHeight(ctx: Context) = sp(ctx).getInt(KEY_HEIGHT, 720)

    fun detect(ctx: Context) = sp(ctx).getBoolean(KEY_DETECT, false)

    /** Audible proximity alerts. Kept separate from detection so the picture can stay annotated. */
    fun alertsEnabled(ctx: Context) = sp(ctx).getBoolean(KEY_ALERTS, true)

    /** Whether the HUD telemetry shows the battery line. */
    fun showBattery(ctx: Context) = sp(ctx).getBoolean(KEY_SHOW_BATTERY, true)

    /**
     * When true only detections in the lower two thirds of the frame are kept: the top third is
     * sky, signs and the far distance, which produced alerts the driver cannot act on.
     */
    fun detectZoneBottomOnly(ctx: Context) = sp(ctx).getBoolean(KEY_DETECT_ZONE, true)

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
     * thing the app does on CPU, so this is the main throttle.
     *
     * Default 200 ms = 5 passes/second. Measured on the tablet, the old 500 ms default landed
     * passes ~0.55 s apart, and boxes that refresh twice a second visibly trail the scene — that
     * is what makes the detector feel laggy next to a plain webcam. A pass costs far less than
     * that budget, so the extra rate is what keeps the overlay attached to the picture. Set it
     * back to 1000 ms when thermals matter more than responsiveness.
     */
    fun detectIntervalMs(ctx: Context) = sp(ctx).getInt(KEY_DETECT_INTERVAL, 200)

    fun setDetectIntervalMs(ctx: Context, v: Int) =
        sp(ctx).edit().putInt(KEY_DETECT_INTERVAL, v).apply()

    /**
     * Execution provider that measured fastest last time (e.g. `NNAPI/fp16`). Stored with
     * [detectProviderKey] rather than on its own, because the winner depends on the model and
     * the frame size it was benchmarked against.
     *
     * This pair is the *most recent* measurement and is what the HUD displays. Use
     * [detectProviderFor] to look a backend up for a specific source.
     */
    fun detectProvider(ctx: Context) = sp(ctx).getString(KEY_PROVIDER, "") ?: ""

    /** Identity of the model + frame size [detectProvider] was measured for. */
    fun detectProviderKey(ctx: Context) = sp(ctx).getString(KEY_PROVIDER_KEY, "") ?: ""

    fun setDetectProvider(ctx: Context, provider: String, key: String) = sp(ctx).edit()
        .putString(KEY_PROVIDER, provider)
        .putString(KEY_PROVIDER_KEY, key)
        .apply()

    /**
     * Remembered backend for one `"<model>@<width>x<height>"` key, or "" when unknown.
     *
     * The sources do not agree on a frame size - the DVR streams 1280x720, a phone's system
     * camera may offer 1920x1080 - and with a single stored pair the second source overwrote the
     * first, so every switch between them re-ran the multi-second backend benchmark. The map
     * keeps an entry per size (see [ProviderMemory]).
     */
    fun detectProviderFor(ctx: Context, key: String): String {
        if (key.isEmpty()) return ""
        val fromMap = ProviderMemory.lookup(sp(ctx).getString(KEY_PROVIDERS, "") ?: "", key)
        if (fromMap.isNotEmpty()) return fromMap
        // Entries written before the map existed are still honoured once.
        return if (detectProviderKey(ctx) == key) detectProvider(ctx) else ""
    }

    fun setDetectProviderFor(ctx: Context, key: String, provider: String) {
        if (key.isEmpty() || provider.isEmpty()) return
        val store = sp(ctx).getString(KEY_PROVIDERS, "") ?: ""
        sp(ctx).edit()
            .putString(KEY_PROVIDERS, ProviderMemory.put(store, key, provider))
            .apply()
        // Keep the display pair in step with the newest measurement.
        setDetectProvider(ctx, provider, key)
    }

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
    fun setAlertsEnabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_ALERTS, v).apply()
    fun setShowBattery(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_SHOW_BATTERY, v).apply()
    fun setDetectZoneBottomOnly(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_DETECT_ZONE, v).apply()
    fun setConfidence(ctx: Context, v: Float) = sp(ctx).edit().putFloat(KEY_CONF, v).apply()
    fun setModelAsset(ctx: Context, v: String) = sp(ctx).edit().putString(KEY_MODEL, v).apply()

    fun recordOverlay(ctx: Context) = sp(ctx).getBoolean(KEY_RECORD_OVERLAY, true)
    fun setRecordOverlay(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_RECORD_OVERLAY, v).apply()

    fun swapRecordChroma(ctx: Context) = sp(ctx).getBoolean(KEY_SWAP_RECORD_CHROMA, false)
    fun setSwapRecordChroma(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_SWAP_RECORD_CHROMA, v).apply()

    const val MODE_USB = "usb"
    const val MODE_SYSTEM = "system"
    const val MODE_WIFI = "wifi"

    /**
     * The "U2 DVR" (Buildwin / Huiying) dashcam: a mass-storage device, not a UVC
     * camera. Its video is read with SCSI Bulk-Only Transport - see
     * [com.driving.hud.usbcamera.camera.DvrUsbCameraController].
     */
    const val MODE_DVR = "dvr"

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

/**
 * The remembered detector backends, stored as newline-separated `key=provider` entries with the
 * newest first.
 *
 * Kept as plain string handling (rather than a `Map` in code) so it survives in
 * SharedPreferences and can be unit tested without an Android context.
 */
internal object ProviderMemory {

    /** Enough for a few models at a few frame sizes; the least recently used entry is dropped. */
    const val MAX_ENTRIES = 6

    private const val SEPARATOR = '\n'

    /** The provider remembered for [key], or "" when there is none. */
    fun lookup(store: String, key: String): String {
        if (store.isEmpty() || key.isEmpty()) return ""
        for (line in store.split(SEPARATOR)) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            if (line.substring(0, eq) == key) return line.substring(eq + 1)
        }
        return ""
    }

    /**
     * Returns [store] with [key]=[provider] as the newest entry. An existing entry for the same
     * key is replaced, and the store is trimmed to [MAX_ENTRIES] from the front (the newest).
     */
    fun put(store: String, key: String, provider: String, maxEntries: Int = MAX_ENTRIES): String {
        if (key.isEmpty() || provider.isEmpty()) return store
        val entries = LinkedHashMap<String, String>()
        entries[key] = provider
        for (line in store.split(SEPARATOR)) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val k = line.substring(0, eq)
            if (!entries.containsKey(k)) entries[k] = line.substring(eq + 1)
            if (entries.size >= maxEntries) break
        }
        return entries.entries.joinToString(SEPARATOR.toString()) { "${it.key}=${it.value}" }
    }
}
