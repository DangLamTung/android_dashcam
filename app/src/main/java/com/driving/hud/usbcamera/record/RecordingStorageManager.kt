package com.driving.hud.usbcamera.record

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.driving.hud.usbcamera.util.HudLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Manages storage locations and system integration for HUD dashcam recordings.
 *
 * Rules:
 * - Prefers removable SD card when physically present and writable so dashcam footage
 *   does not fill internal phone storage.
 * - When no removable SD card is present, saves to the internal movies directory AND publishes
 *   to public MediaStore (Movies/HUD), making clips immediately playable in Gallery, Google Photos,
 *   and third-party video players without requiring ADB or specialized file managers.
 */
object RecordingStorageManager {

    private const val TAG = "RecordingStorage"

    data class RecordingResult(
        val file: File,
        val isRemovableSdCard: Boolean,
        val playableUri: Uri?,
        val storageLabel: String
    )

    /**
     * Resolves the target directory for new recordings.
     * Returns the destination directory and whether it is on a removable SD card.
     */
    fun resolveRecordingDirectory(context: Context): Pair<File, Boolean> {
        val dirs = ContextCompat.getExternalFilesDirs(context, Environment.DIRECTORY_MOVIES)
        for (dir in dirs) {
            if (dir != null) {
                try {
                    if (Environment.isExternalStorageRemovable(dir) && dir.canWrite()) {
                        HudLogger.i(TAG, "Using removable SD card: ${dir.absolutePath}")
                        return Pair(dir, true)
                    }
                } catch (t: Throwable) {
                    HudLogger.w(TAG, "Failed checking removable volume ${dir.absolutePath}: ${t.message}")
                }
            }
        }

        val primary = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        HudLogger.i(TAG, "No removable SD card found; using internal storage: ${primary.absolutePath}")
        return Pair(primary, false)
    }

    /**
     * Finalizes and registers the recorded file.
     * If recorded internally (no SD card), exports a copy to Android's public MediaStore
     * so it appears in standard Gallery and video players.
     */
    fun publishRecording(context: Context, file: File, isSdCard: Boolean): RecordingResult {
        // Trigger media scanner on the raw file
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("video/x-matroska"),
                null
            )
        } catch (t: Throwable) {
            HudLogger.w(TAG, "MediaScanner failed: ${t.message}")
        }

        val contentUri = getFileProviderUri(context, file)
        val storageLabel = if (isSdCard) "SD Card" else "Internal Movies (Public Gallery)"

        // If stored internally, publish into public MediaStore Movies/HUD in background for gallery indexing
        if (!isSdCard) {
            exportExecutor.execute {
                try {
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.TITLE, file.nameWithoutExtension)
                        put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/x-matroska")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HUD")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { out ->
                            FileInputStream(file).use { input ->
                                input.copyTo(out)
                            }
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            values.clear()
                            values.put(MediaStore.Video.Media.IS_PENDING, 0)
                            resolver.update(uri, values, null, null)
                        }
                        HudLogger.i(TAG, "Exported to public MediaStore: $uri")
                    }
                } catch (t: Throwable) {
                    HudLogger.w(TAG, "Failed to publish to MediaStore: ${t.message}")
                }
            }
        }

        return RecordingResult(
            file = file,
            isRemovableSdCard = isSdCard,
            playableUri = contentUri,
            storageLabel = storageLabel
        )
    }

    private val exportExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mediastore-export").apply { isDaemon = true }
    }

    /** Returns a content:// Uri via FileProvider. */
    fun getFileProviderUri(context: Context, file: File): Uri? {
        return try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (t: Throwable) {
            HudLogger.w(TAG, "FileProvider URI failed: ${t.message}")
            null
        }
    }

    /** Opens the video in any installed video player (VLC, MX Player, Photos, etc.). */
    fun openPlayback(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            val chooser = Intent.createChooser(intent, "Play HUD Recording").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "Failed to launch video player", t)
        }
    }

    /** Shares the video to other apps (Drive, messaging, nearby share, etc.). */
    fun shareRecording(context: Context, uri: Uri, title: String = "Share HUD Video") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newUri(context.contentResolver, "HUD Video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val chooser = Intent.createChooser(intent, title).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "Failed to share video", t)
        }
    }
}
