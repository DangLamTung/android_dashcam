package com.driving.hud.usbcamera.camera

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.driving.hud.usbcamera.util.HudLogger

/**
 * Access to the U2 DVR's preview file through the Storage Access Framework.
 *
 * The DVR is a plain mass-storage device, and its live preview is a **file on its card**:
 * `VSFILE` (an 8704-byte device header followed by JPEG frames). That is what the vendor app
 * reads, with the card left mounted so the kernel's `usb-storage` driver serves it.
 *
 * Claiming the USB interface through usbfs unmounts that volume, and the sectors we then read
 * never change - which is why the Bulk-Only Transport path could only ever show one frozen
 * image. Reading the file keeps the volume mounted and the preview live.
 *
 * A directory grant taken once with `ACTION_OPEN_DOCUMENT_TREE` gives read access to the
 * volume root without `MANAGE_EXTERNAL_STORAGE`, which is debug-only and not publishable.
 */
object DvrPreviewFile {

    private const val TAG = "DvrPreviewFile"

    /** Name of the live preview file, from the vendor's `new DevFileName("VSFILE", ...)`. */
    const val PREVIEW_NAME = "VSFILE"

    /** Upper bound on one poll. The file holds a header plus one or more JPEG frames. */
    const val MAX_READ_BYTES = 1 shl 20

    /** Document URI resolved from the granted tree, cached until a read fails. */
    @Volatile
    private var cachedDocument: Uri? = null

    fun hasGrant(context: Context): Boolean = treeUri(context) != null

    fun treeUri(context: Context): Uri? {
        val raw = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY_TREE_URI, null)
            ?: return null
        val uri = Uri.parse(raw)
        // A grant can be revoked (or the card removed and reformatted); make sure it is still
        // readable before pretending the file source is usable.
        return if (context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }) {
            uri
        } else {
            null
        }
    }

    fun saveGrant(context: Context, treeUri: Uri) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TREE_URI, treeUri.toString())
            .apply()
        cachedDocument = null
        HudLogger.i(TAG, "directory grant saved: $treeUri")
    }

    fun clearGrant(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY_TREE_URI).apply()
        cachedDocument = null
        HudLogger.i(TAG, "directory grant cleared")
    }

    /** Document URI of `VSFILE` under the granted tree, or null when it is not there yet. */
    fun previewDocument(context: Context): Uri? {
        cachedDocument?.let { if (exists(context, it)) return it }
        val tree = treeUri(context) ?: return null
        val found = findChild(context, tree, PREVIEW_NAME)
        cachedDocument = found
        return found
    }

    private fun exists(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
            null,
            null,
            null,
        )?.use { it.count > 0 } ?: false
    } catch (t: Throwable) {
        false
    }

    private fun findChild(context: Context, tree: Uri, name: String): Uri? = try {
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeDocId)
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            var hit: Uri? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(1)?.equals(name, ignoreCase = true) == true) {
                    hit = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))
                    break
                }
            }
            hit
        }
    } catch (t: Throwable) {
        HudLogger.w(TAG, "query failed: ${t.message}")
        null
    }

    /**
     * Reads the preview file. Returns the raw bytes, or null when the file cannot be opened.
     *
     * A fresh stream is opened on every poll on purpose: keeping one open would keep serving the
     * snapshot taken when it was opened, which is the frozen-image failure in another form.
     */
    fun readPreview(context: Context): ByteArray? {
        val doc = previewDocument(context) ?: return null
        return try {
            val size = sizeOf(context, doc)
            context.contentResolver.openInputStream(doc)?.use { input ->
                // If the preview file has grown past one buffer, only the tail matters: the newest
                // frame is at the end.
                if (size > MAX_READ_BYTES) skipTo(input, size - MAX_READ_BYTES)
                val buffer = ByteArray(MAX_READ_BYTES)
                var total = 0
                while (total < buffer.size) {
                    val n = input.read(buffer, total, buffer.size - total)
                    if (n <= 0) break
                    total += n
                }
                if (total <= 0) null else buffer.copyOf(total)
            }
        } catch (t: Throwable) {
            HudLogger.w(TAG, "read failed: ${t.message}")
            cachedDocument = null
            null
        }
    }

    private fun sizeOf(context: Context, doc: Uri): Long = try {
        context.contentResolver.query(
            doc,
            arrayOf(DocumentsContract.Document.COLUMN_SIZE),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    } catch (t: Throwable) {
        -1L
    }

    private fun skipTo(input: java.io.InputStream, target: Long) {
        var skipped = 0L
        while (skipped < target) {
            val n = try {
                input.skip(target - skipped)
            } catch (t: Throwable) {
                return
            }
            if (n <= 0) return
            skipped += n
        }
    }

    private const val PREF = "dvr_preview"
    private const val KEY_TREE_URI = "tree_uri"
}
