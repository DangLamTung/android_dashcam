package com.driving.hud.usbcamera.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.driving.hud.usbcamera.BuildConfig
import com.driving.hud.usbcamera.camera.CameraNativeBridge
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-device file logger for the HUD app.
 *
 * The USB port is occupied by the camera when the app runs on a real device, so
 * `adb logcat` is not available. This logger writes a timestamped, rotating log
 * file into the app's external files dir:
 *
 *   /sdcard/Android/data/com.driving.hud.usbcamera/files/logs/hud.log
 *
 * which can be retrieved with `adb pull` or a file manager, or shared via the
 * on-screen "Log" button. Every line is also mirrored to logcat so normal USB
 * debugging still works when attached.
 *
 * Native C++ logs are forwarded here through a JNI callback registered in
 * [attachNativeSink], so the whole pipeline (USB reader -> decoder -> renderer)
 * lands in the same file.
 */
object HudLogger {

    private const val TAG = "HudLogger"
    private const val LOG_FILE_NAME = "hud.log"
    private const val MAX_KEPT_PREV = 3

    private val enabled = AtomicBoolean(false)
    private val nativeSinkAttached = AtomicBoolean(false)

    private var logDir: File? = null
    private var logFile: File? = null
    private var writer: FileWriter? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Must be called once (e.g. from Application/MainActivity.onCreate). */
    @Synchronized
    fun init(context: Context) {
        if (enabled.get()) return

        val dir = File(context.getExternalFilesDir(null), "logs")
        if (!dir.exists() && !dir.mkdirs()) {
            // Fall back to app-private storage so logging still works.
            logDir = File(context.filesDir, "logs")
            logDir?.mkdirs()
        } else {
            logDir = dir
        }

        rotateLogs()

        val file = File(logDir, LOG_FILE_NAME)
        logFile = file
        writer = try {
            FileWriter(file, true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open log file", e)
            null
        }

        enabled.set(true)

        i(TAG, "==================================================")
        i(TAG, "HUD Logger started")
        i(TAG, "Device: ${Build.MANUFACTURER} ${Build.MODEL} (SDK ${Build.VERSION.SDK_INT})")
        i(TAG, "App: ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        i(TAG, "Log file: ${file.absolutePath}")
        i(TAG, "==================================================")

        attachNativeSink()
    }

    /** Flushes and closes the log file. Safe to call multiple times. */
    @Synchronized
    fun close() {
        if (!enabled.getAndSet(false)) return
        i(TAG, "HudLogger closing.")
        detachNativeSink()
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {
        }
        writer = null
    }

    /** Flushes buffered lines to disk (useful before sharing). */
    @Synchronized
    fun flush() {
        try {
            writer?.flush()
        } catch (_: Exception) {
        }
    }

    /**
     * Saves a timestamped copy of the current log (hud_&lt;ts&gt;.copy.log) next to
     * the live log file. Returns the created file, or null on failure.
     */
    @Synchronized
    fun saveCopy(): File? {
        flush()
        val src = logFile ?: return null
        if (!src.exists()) return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val copy = File(src.parentFile, "hud_$stamp.copy.log")
        return try {
            src.copyTo(copy, overwrite = true)
            i(TAG, "Log copy saved: ${copy.absolutePath}")
            copy
        } catch (e: Exception) {
            e(TAG, "Failed to save log copy", e)
            null
        }
    }

    // ---- Level API ----
    fun d(tag: String, msg: String) = log(Log.DEBUG, tag, msg, null)
    fun i(tag: String, msg: String) = log(Log.INFO, tag, msg, null)
    fun w(tag: String, msg: String) = log(Log.WARN, tag, msg, null)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(Log.ERROR, tag, msg, tr)

    /**
     * Called from native code (JNI) on any thread to append a native log line.
     * Level uses android.util.Log constants.
     */
    fun nativeLogSink(level: Int, tag: String, message: String) {
        log(level, tag, message, null)
    }

    fun getLogFile(): File? = logFile
    fun getLogPath(): String? = logFile?.absolutePath

    // ---- Internals ----
    private fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        // Mirror to logcat for normal USB debugging.
        when (level) {
            Log.DEBUG -> Log.d(tag, msg)
            Log.INFO -> Log.i(tag, msg)
            Log.WARN -> Log.w(tag, msg)
            Log.ERROR -> if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
            else -> Log.i(tag, msg)
        }

        val line = buildString {
            append(dateFormat.format(Date()))
            append(" [")
            append(levelChar(level))
            append("] ")
            append(tag)
            append(": ")
            append(msg)
            if (tr != null) {
                append(" | ")
                append(tr.toString())
            }
        }
        writeLine(line)
    }

    private fun writeLine(line: String) {
        val w = writer ?: return
        synchronized(w) {
            try {
                w.append(line)
                w.append('\n')
                w.flush()
            } catch (_: Exception) {
            }
        }
    }

    private fun rotateLogs() {
        val dir = logDir ?: return
        val current = File(dir, LOG_FILE_NAME)
        if (current.exists() && current.length() > 0) {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
            current.renameTo(File(dir, "hud_$stamp.prev.log"))
        }
        val oldOnes = dir.listFiles { f -> f.name.startsWith("hud_") && f.name.endsWith(".prev.log") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        for (old in oldOnes.drop(MAX_KEPT_PREV - 1)) {
            old.delete()
        }
    }

    private fun attachNativeSink() {
        if (nativeSinkAttached.compareAndSet(false, true)) {
            try {
                CameraNativeBridge.nativeSetLogSink(this)
                i(TAG, "Native log sink attached.")
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to attach native log sink", t)
                nativeSinkAttached.set(false)
            }
        }
    }

    private fun detachNativeSink() {
        if (nativeSinkAttached.compareAndSet(true, false)) {
            try {
                CameraNativeBridge.nativeSetLogSink(null)
            } catch (_: Throwable) {
            }
        }
    }

    private fun levelChar(level: Int): Char = when (level) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        Log.ASSERT -> 'A'
        else -> '?'
    }
}
