package com.driving.hud.usbcamera.camera

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import com.driving.hud.usbcamera.util.HudLogger
import java.io.File
import java.io.RandomAccessFile

/**
 * Discovery and diagnostics for the "U2 DVR" (Buildwin / Huiying) dashcam.
 *
 * This device is **not** a UVC camera. It enumerates as a USB mass-storage
 * device (`class 8 / subclass 6 / protocol 0x50`, VID 0x1902 / PID 0x3241,
 * product name "GENERAL - AUDIO") that simply exposes its SD card to the host,
 * and its live preview is a file on that card:
 *
 * ```
 * <volume>/VSFILE                      live MJPEG preview (8.7 KB header, then JPEG frames)
 * <volume>/Android/data/<pkg>/HWC      command file written by the host
 * ```
 *
 * The vendor app (`com.fvision.camera`) declares exactly this pair via
 * `new DevFileName("VSFILE", "HWC", "com.fvision.camera", 12865, 6402)` — 6402
 * and 12865 are this camera's vendor/product ids, which is how the naming was
 * confirmed. Its `UvcCamera.initUvcCamera(devpath)` opens `VSFILE` and feeds the
 * frames to the renderer, and `CmdUtil.changeMode()` etc. write commands for the
 * DVR's firmware.
 *
 * Because the DVR is a plain mass-storage device it must be left to the kernel:
 * as soon as any app claims the USB interface through `usbfs`, `usb-storage` is
 * detached, the volume unmounts and the preview file disappears. So the USB
 * (libuvc) path must never open this camera — see [AusbcCameraController].
 */
object DvrVolume {
    private const val TAG = "DvrVolume"

    /** USB ids of the "GENERAL - AUDIO" mass-storage DVR. */
    const val VENDOR_ID = 0x1902
    const val PRODUCT_ID = 0x3241

    const val PREVIEW_FILE = "VSFILE"
    const val COMMAND_FILE = "HWC"

    /** Bytes of device header that precede the first JPEG frame in `VSFILE`. */
    const val PREVIEW_HEADER_BYTES = 8704

    fun matches(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID && productId == PRODUCT_ID

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    private fun storageManager(context: Context) =
        context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    /** Root directory of a storage volume, or null when it cannot be resolved. */
    fun rootOf(volume: StorageVolume): File? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory
        } else {
            @Suppress("PrivateApi")
            StorageVolume::class.java.getMethod("getPathFile").invoke(volume) as? File
        }
    } catch (t: Throwable) {
        null
    }

    /**
     * Candidate mount roots: the framework's volumes plus anything that shows up
     * directly under `/storage` (some builds expose removable volumes there only).
     */
    fun candidateRoots(context: Context): List<File> {
        val roots = LinkedHashSet<File>()
        try {
            storageManager(context)?.storageVolumes?.forEach { volume ->
                if (!volume.isEmulated) rootOf(volume)?.let { roots.add(it) }
            }
        } catch (t: Throwable) {
            HudLogger.e(TAG, "storageVolumes failed", t)
        }
        File("/storage").listFiles()?.forEach { f ->
            if (f.isDirectory && f.name != "emulated" && f.name != "self") roots.add(f)
        }
        return roots.toList()
    }

    /** The DVR preview file, or null when no mounted volume carries one. */
    fun findPreviewFile(context: Context): File? =
        candidateRoots(context).asSequence()
            .map { File(it, PREVIEW_FILE) }
            .firstOrNull { it.isFile && it.canRead() }

    /** One-line description for status text and logs. */
    fun describe(context: Context): String {
        val file = findPreviewFile(context)
        return if (file == null) "no $PREVIEW_FILE" else "${file.absolutePath} (${file.length()} B)"
    }

    // ------------------------------------------------------------------ probe ---

    private data class Sample(
        val length: Long,
        val mtime: Long,
        val frames: Int,
        val lastFrameEnd: Int,
        val crc: Int,
        val head: String,
    )

    /** Bounded recursive listing of the volume, hex-dumping any [COMMAND_FILE] left behind. */
    private fun dumpTree(context: Context, maxEntries: Int = 90, maxDepth: Int = 4) {
        val root = findPreviewFile(context)?.parentFile ?: return
        var budget = maxEntries
        fun walk(dir: File, depth: Int) {
            val children = dir.listFiles() ?: return
            for (child in children) {
                if (budget-- <= 0) return
                val pad = "  ".repeat(depth)
                if (child.isDirectory) {
                    HudLogger.i(TAG, "$pad${child.name}/")
                    if (depth < maxDepth) walk(child, depth + 1)
                } else {
                    HudLogger.i(TAG, "$pad${child.name} ${child.length()} B mtime=${child.lastModified()}")
                    if (child.name.equals(COMMAND_FILE, true) && child.canRead()) dumpNonZero(child)
                }
            }
        }
        walk(root, 0)
    }

    /** Logs every non-zero run of a (small) file, which is how command records show up. */
    private fun dumpNonZero(file: File, maxRegions: Int = 16, maxBytesPerRegion: Int = 72) {
        val bytes = try {
            file.readBytes()
        } catch (t: Throwable) {
            HudLogger.e(TAG, "reading ${file.name} failed", t)
            return
        }
        var i = 0
        var regions = 0
        while (i < bytes.size && regions < maxRegions) {
            if (bytes[i] == 0.toByte()) {
                i++
                continue
            }
            var j = i
            while (j < bytes.size && bytes[j] != 0.toByte()) j++
            val shown = bytes.copyOfRange(i, minOf(j, i + maxBytesPerRegion))
            HudLogger.i(TAG, "${file.name} @$i len=${j - i} hex=${shown.joinToString("") { "%02x".format(it) }}")
            regions++
            i = j
        }
        if (regions == 0) HudLogger.i(TAG, "${file.name} is all zeroes (${bytes.size} B)")
    }

    private fun sample(file: File, maxBytes: Int = 512 * 1024): Sample {
        val bytes = try {
            RandomAccessFile(file, "r").use { raf ->
                val count = minOf(raf.length(), maxBytes.toLong()).toInt()
                ByteArray(count).also { raf.readFully(it) }
            }
        } catch (t: Throwable) {
            HudLogger.e(TAG, "reading ${file.name} failed", t)
            return Sample(file.length(), file.lastModified(), 0, -1, 0, "")
        }
        var frames = 0
        var lastFrameEnd = -1
        var i = 0
        while (i + 1 < bytes.size) {
            val isSoi = bytes[i] == 0xFF.toByte() && bytes[i + 1] == 0xD8.toByte()
            if (!isSoi) {
                i++
                continue
            }
            frames++
            var j = i + 2
            var end = -1
            while (j + 1 < bytes.size) {
                if (bytes[j] == 0xFF.toByte() && bytes[j + 1] == 0xD9.toByte()) {
                    end = j + 2
                    break
                }
                j++
            }
            if (end < 0) break
            lastFrameEnd = end
            i = end
        }
        val head = bytes.take(16).joinToString("") { "%02x".format(it) }
        return Sample(file.length(), file.lastModified(), frames, lastFrameEnd, bytes.contentHashCode(), head)
    }

    /**
     * Full diagnostic report: every visible volume, the preview file, and whether
     * that file is actually live (two samples three seconds apart). Runs on a
     * background thread; all output goes through [HudLogger].
     */
    fun probe(context: Context) {
        HudLogger.i(TAG, "=== DVR volume probe ===")
        HudLogger.i(TAG, "sdk=${Build.VERSION.SDK_INT} allFilesAccess=${hasAllFilesAccess()}")
        try {
            storageManager(context)?.storageVolumes?.forEach { volume ->
                val dir = rootOf(volume)
                HudLogger.i(
                    TAG,
                    "volume uuid=${volume.uuid} state=${volume.state} removable=${volume.isRemovable} " +
                        "emulated=${volume.isEmulated} dir=${dir?.absolutePath ?: "?"} " +
                        "exists=${dir?.exists()} read=${dir?.canRead()} write=${dir?.canWrite()}",
                )
            }
        } catch (t: Throwable) {
            HudLogger.e(TAG, "volume enumeration failed", t)
        }
        candidateRoots(context).forEach { root ->
            val names = root.listFiles()?.joinToString(",") { it.name }?.take(400)
            HudLogger.i(TAG, "root ${root.absolutePath} readable=${root.canRead()} -> $names")
        }
        dumpTree(context)

        val preview = findPreviewFile(context)
        if (preview == null) {
            HudLogger.w(TAG, "no $PREVIEW_FILE on any mounted volume")
            return
        }
        HudLogger.i(TAG, "preview ${preview.absolutePath} len=${preview.length()} mtime=${preview.lastModified()}")
        val first = sample(preview)
        HudLogger.i(TAG, "sample1 $first")
        try {
            Thread.sleep(3000)
        } catch (_: InterruptedException) {
        }
        val second = sample(preview)
        HudLogger.i(TAG, "sample2 $second")
        HudLogger.i(
            TAG,
            "live=${first != second} length ${first.length}->${second.length} " +
                "frames ${first.frames}->${second.frames} lastFrameEnd ${first.lastFrameEnd}->${second.lastFrameEnd}",
        )
    }
}
