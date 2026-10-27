package com.driving.hud.usbcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.util.HudLogger
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Writes what the detector actually sees to disk, so "the boxes are in the wrong place" can be
 * answered with evidence instead of a guess.
 *
 * Two files per dump:
 *  - `analysis.png` — the NV21 luma plane exactly as the detector receives it (grayscale),
 *  - `boxes.png` — the same frame with the returned boxes drawn on it.
 *
 * If the boxes sit on the objects **in the analysis image**, the analysis differs from the
 * preview (orientation, size or crop) and the fix is in the transform. If they do not, the
 * decoder itself is wrong. Pull them with:
 *
 * `adb pull /sdcard/Android/data/com.driving.hud.usbcamera/files/diag/`
 */
internal object Camera2Diag {

    private const val TAG = "Camera2Diag"

    /** Dumps are written here; app-specific external storage needs no permission. */
    private const val DIR = "diag"

    fun dump(
        context: Context,
        nv21: ByteArray,
        width: Int,
        height: Int,
        detections: List<Detection>,
        geometry: String,
    ): String? {
        if (width <= 0 || height <= 0 || nv21.size < width * height) return null
        return try {
            val dir = File(context.getExternalFilesDir(null), DIR).apply { mkdirs() }
            val stamp = System.currentTimeMillis()

            val gray = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (i in pixels.indices) {
                val y = nv21[i].toInt() and 0xFF
                pixels[i] = (0xFF shl 24) or (y shl 16) or (y shl 8) or y
            }
            gray.setPixels(pixels, 0, width, 0, 0, width, height)

            val plain = File(dir, "analysis-$stamp.png")
            FileOutputStream(plain).use { gray.compress(Bitmap.CompressFormat.PNG, 100, it) }

            val marked = gray.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(marked)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = (width / 240f).coerceAtLeast(2f)
                color = Color.RED
            }
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GREEN
                textSize = (width / 48f).coerceAtLeast(12f)
                isFakeBoldText = true
            }
            for (d in detections) {
                canvas.drawRect(
                    d.left * width,
                    d.top * height,
                    d.right * width,
                    d.bottom * height,
                    paint,
                )
                canvas.drawText("${d.label} ${(d.score * 100).toInt()}%", d.left * width, d.top * height, label)
            }
            val boxesFile = File(dir, "boxes-$stamp.png")
            FileOutputStream(boxesFile).use { marked.compress(Bitmap.CompressFormat.PNG, 100, it) }
            gray.recycle()
            marked.recycle()

            File(dir, "geometry-$stamp.txt").writeText(
                buildString {
                    appendLine(geometry)
                    appendLine(
                        String.format(
                            Locale.US,
                            "analysis %dx%d, %d detection(s)",
                            width,
                            height,
                            detections.size,
                        )
                    )
                    for (d in detections) {
                        appendLine(
                            String.format(
                                Locale.US,
                                "  %-12s %.2f  [%.3f, %.3f, %.3f, %.3f]",
                                d.label,
                                d.score,
                                d.left,
                                d.top,
                                d.right,
                                d.bottom,
                            )
                        )
                    }
                }
            )
            HudLogger.i(TAG, "dumped ${plain.name} and ${boxesFile.name} (${detections.size} boxes)")
            boxesFile.absolutePath
        } catch (t: Throwable) {
            HudLogger.w(TAG, "dump failed: ${t.message}")
            null
        }
    }
}
