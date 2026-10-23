package com.driving.hud.usbcamera.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import com.driving.hud.usbcamera.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Persists crash information somewhere the user can actually reach.
 *
 * While the app runs, the camera occupies the phone's USB port, so `adb logcat` is
 * usually unavailable, and everything under `Android/data` is hidden from file
 * managers and MTP. Reports are therefore written to `Download/HUD/`, which any file
 * manager can open, and which also survives an app uninstall.
 *
 * Two failure modes are covered:
 *  - a Java exception reaches the default handler (written immediately);
 *  - the process dies without shutting down (native abort, SIGSEGV, low-memory kill).
 *    A session marker makes the *next* launch notice it and dump the end of `hud.log`,
 *    which is the only trace available for a native crash.
 */
object CrashReporter {

    private const val TAG = "CrashReporter"
    private const val PREFS = "crash_reporter"
    private const val KEY_SESSION_RUNNING = "session_running"
    private const val SUBDIR = "HUD"

    /** Lines of hud.log appended to a report: enough for the run-up to the failure. */
    private const val LOG_TAIL_LINES = 400

    /** Cap so a pathological log cannot produce a multi-megabyte report. */
    private const val LOG_TAIL_MAX_BYTES = 256 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                write(appContext, "crash", describeException(thread, error))
            }
            // The report above already covers this death; clear the marker so the next
            // launch does not report the same crash a second time.
            endSession(appContext)
            // Let the platform keep its own behaviour (process death, Play Store reporting).
            previous?.uncaughtException(thread, error)
        }
    }

    /** Records that this process is alive, so the next launch can tell it died abruptly. */
    fun beginSession(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_SESSION_RUNNING, true).commit()
        }
    }

    /** Called on a clean shutdown; without it the next launch reports the previous run. */
    fun endSession(context: Context) {
        runCatching {
            prefs(context).edit().putBoolean(KEY_SESSION_RUNNING, false).commit()
        }
    }

    /**
     * Writes a report if the previous process never reached [endSession]. Covers native
     * crashes, which never run the uncaught-exception handler above.
     */
    fun reportPreviousRunIfCrashed(context: Context) {
        val appContext = context.applicationContext
        val running = runCatching {
            prefs(appContext).getBoolean(KEY_SESSION_RUNNING, false)
        }.getOrDefault(false)
        if (!running) return

        runCatching {
            write(
                appContext,
                "crash-previous-run",
                buildString {
                    appendLine("The previous run did not shut down cleanly.")
                    appendLine()
                    appendLine("That means the process died without going through MainActivity.onDestroy()")
                    appendLine("(HudLogger.close() never ran, so the log below just stops).")
                    appendLine("Typical causes: a native crash in libusb/libuvc/ncnn/ONNX, a Java exception")
                    appendLine("thrown before CrashReporter was installed, or the system killing the app.")
                    appendLine()
                    appendLine("Latest app log lines follow.")
                }
            )
        }
    }

    // ------------------------------------------------------------------ report

    private fun describeException(thread: Thread, error: Throwable): String = buildString {
        appendLine("Uncaught exception on thread \"${thread.name}\"")
        appendLine()
        appendLine(error.stackTraceToString())
    }

    private fun deviceHeader(): String = buildString {
        appendLine("HUD USB Camera crash report")
        appendLine("time      : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
        appendLine("device    : ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("android   : ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("abis      : ${Build.SUPPORTED_ABIS.joinToString(", ")}")
        appendLine("page size : ${pageSizeKb()} KB")
        appendLine("app       : ${BuildConfig.APPLICATION_ID} v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine()
    }

    /**
     * The kernel page size matters here: devices built for 16 KB pages refuse to load
     * native libraries that were linked for 4 KB, so the value makes that failure mode
     * obvious from the report alone.
     */
    private fun pageSizeKb(): Long = runCatching {
        val out = ProcessBuilder("getconf", "PAGESIZE").start().inputStream.bufferedReader().use { it.readText() }
        out.trim().toLong() / 1024
    }.getOrDefault(-1L)

    private fun write(context: Context, prefix: String, body: String): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = "$prefix-$stamp.txt"
        val text = deviceHeader() + body + logTail()
        HudLogger.e(TAG, "saving $name")
        val saved = saveToDownloads(context, name, text) ?: saveToAppDir(context, name, text)
        HudLogger.e(TAG, if (saved != null) "report saved: ${saved.absolutePath}" else "report could not be saved")
        return saved
    }

    private fun logTail(): String {
        val file = HudLogger.getLogFile() ?: return "\n(no app log available)\n"
        val text = runCatching {
            if (file.length() > LOG_TAIL_MAX_BYTES) {
                file.inputStream().use { stream ->
                    stream.skip(file.length() - LOG_TAIL_MAX_BYTES)
                    stream.bufferedReader().readText()
                }
            } else {
                file.readText()
            }
        }.getOrNull() ?: return "\n(app log unreadable)\n"

        val lines = text.lines()
        val tail = if (lines.size > LOG_TAIL_LINES) lines.takeLast(LOG_TAIL_LINES) else lines
        return buildString {
            appendLine()
            appendLine("---- hud.log (last ${tail.size} lines) ----")
            tail.forEach { appendLine(it) }
            appendLine("---- end of hud.log ----")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToDownloadsQ(context: Context, name: String, text: String): File? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + SUBDIR)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        val written = runCatching {
            val stream = resolver.openOutputStream(uri) ?: return@runCatching false
            stream.use { it.write(text.toByteArray()) }
            true
        }.getOrDefault(false)
        if (!written) {
            resolver.delete(uri, null, null)
            return null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        // MediaStore hides the real path; report the user-visible location.
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "$SUBDIR/$name")
    }

    @Suppress("DEPRECATION")
    private fun saveToDownloadsLegacy(name: String, text: String): File? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        return runCatching {
            file.writeText(text)
            file
        }.getOrNull()
    }

    private fun saveToDownloads(context: Context, name: String, text: String): File? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloadsQ(context, name, text)
        } else {
            saveToDownloadsLegacy(name, text)
        }
    }.getOrNull()

    private fun saveToAppDir(context: Context, name: String, text: String): File? = runCatching {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "crashes")
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, name)
        file.writeText(text)
        file
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
