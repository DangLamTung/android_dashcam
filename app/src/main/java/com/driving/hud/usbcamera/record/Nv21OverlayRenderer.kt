package com.driving.hud.usbcamera.record

import com.driving.hud.usbcamera.detect.Detection
import kotlin.math.max
import kotlin.math.min

/**
 * High-performance, zero-allocation overlay renderer that draws detection bounding boxes
 * and telemetry badges directly onto NV21 camera frames before hardware encoding.
 *
 * Runs on the background recorder thread so it never adds latency to camera capture or UI.
 */
object Nv21OverlayRenderer {

    // Neon HUD Cyan
    private const val NEON_Y: Byte = 210.toByte()
    private const val NEON_U: Byte = 175.toByte()
    private const val NEON_V: Byte = 20.toByte()

    // Warning Amber/Red (for close objects or alerts)
    private const val WARN_Y: Byte = 170.toByte()
    private const val WARN_U: Byte = 90.toByte()
    private const val WARN_V: Byte = 230.toByte()

    // Dark Shadow/Outline (for contrast against bright backgrounds)
    private const val DARK_Y: Byte = 20.toByte()
    private const val DARK_U: Byte = 128.toByte()
    private const val DARK_V: Byte = 128.toByte()

    // Text Bright White
    private const val TEXT_Y: Byte = 250.toByte()
    private const val TEXT_U: Byte = 128.toByte()
    private const val TEXT_V: Byte = 128.toByte()

    /**
     * Renders bounding boxes and label badges onto the given NV21 byte buffer.
     */
    fun render(nv21: ByteArray, width: Int, height: Int, detections: List<Detection>) {
        if (detections.isEmpty() || width <= 0 || height <= 0) return

        for (d in detections) {
            val x1 = (d.left * width).toInt().coerceIn(0, width - 1)
            val y1 = (d.top * height).toInt().coerceIn(0, height - 1)
            val x2 = (d.right * width).toInt().coerceIn(0, width - 1)
            val y2 = (d.bottom * height).toInt().coerceIn(0, height - 1)
            if (x2 <= x1 || y2 <= y1) continue

            val isWarn = d.label.contains("TTC") || d.label.contains("WARN")
            val yVal = if (isWarn) WARN_Y else NEON_Y
            val uVal = if (isWarn) WARN_U else NEON_U
            val vVal = if (isWarn) WARN_V else NEON_V

            // 1. Draw outer dark border for contrast
            drawBox(nv21, width, height, x1 - 1, y1 - 1, x2 + 1, y2 + 1, thickness = 1, DARK_Y, DARK_U, DARK_V)

            // 2. Draw neon main bounding box (thickness = 2)
            drawBox(nv21, width, height, x1, y1, x2, y2, thickness = 2, yVal, uVal, vVal)

            // 3. Draw corner accent brackets (tactical HUD look)
            val cornerLen = min(24, max(8, min(x2 - x1, y2 - y1) / 4))
            drawCornerBrackets(nv21, width, height, x1, y1, x2, y2, cornerLen, thickness = 3, yVal, uVal, vVal)

            // 4. Render label text badge
            val firstLine = d.label.lines().firstOrNull() ?: d.label
            val badgeText = firstLine.uppercase()
            val textScale = if (width >= 1280) 2 else 1
            val charW = 6 * textScale
            val charH = 8 * textScale
            val badgeW = (badgeText.length * charW + 8).coerceAtMost(width - 4)
            val badgeH = charH + 6

            val badgeX = x1.coerceIn(2, (width - badgeW - 2).coerceAtLeast(2))
            val badgeY = (y1 - badgeH - 2).let { if (it < 2) y1 + 3 else it }

            // Dark badge background
            fillDarkRect(nv21, width, height, badgeX, badgeY, badgeW, badgeH)
            // Accent left bar
            drawBox(nv21, width, height, badgeX, badgeY, badgeX + 2, badgeY + badgeH, 1, yVal, uVal, vVal)
            // Draw text
            drawString(nv21, width, height, badgeX + 5, badgeY + 3, badgeText, textScale, TEXT_Y, TEXT_U, TEXT_V)
        }
    }

    private fun drawBox(
        nv21: ByteArray, w: Int, h: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        thickness: Int, yVal: Byte, uVal: Byte, vVal: Byte
    ) {
        val clampedX1 = x1.coerceIn(0, w - 1)
        val clampedY1 = y1.coerceIn(0, h - 1)
        val clampedX2 = x2.coerceIn(0, w - 1)
        val clampedY2 = y2.coerceIn(0, h - 1)

        // Top and bottom horizontal lines
        for (t in 0 until thickness) {
            val topY = (clampedY1 + t).coerceIn(0, h - 1)
            val botY = (clampedY2 - t).coerceIn(0, h - 1)
            for (x in clampedX1..clampedX2) {
                setPixel(nv21, w, h, x, topY, yVal, uVal, vVal)
                setPixel(nv21, w, h, x, botY, yVal, uVal, vVal)
            }
        }

        // Left and right vertical lines
        for (t in 0 until thickness) {
            val leftX = (clampedX1 + t).coerceIn(0, w - 1)
            val rightX = (clampedX2 - t).coerceIn(0, w - 1)
            for (y in clampedY1..clampedY2) {
                setPixel(nv21, w, h, leftX, y, yVal, uVal, vVal)
                setPixel(nv21, w, h, rightX, y, yVal, uVal, vVal)
            }
        }
    }

    private fun drawCornerBrackets(
        nv21: ByteArray, w: Int, h: Int,
        x1: Int, y1: Int, x2: Int, y2: Int,
        len: Int, thickness: Int, yVal: Byte, uVal: Byte, vVal: Byte
    ) {
        // Top-left
        drawHLine(nv21, w, h, x1, min(x1 + len, x2), y1, thickness, yVal, uVal, vVal)
        drawVLine(nv21, w, h, x1, y1, min(y1 + len, y2), thickness, yVal, uVal, vVal)
        // Top-right
        drawHLine(nv21, w, h, max(x2 - len, x1), x2, y1, thickness, yVal, uVal, vVal)
        drawVLine(nv21, w, h, x2, y1, min(y1 + len, y2), thickness, yVal, uVal, vVal)
        // Bottom-left
        drawHLine(nv21, w, h, x1, min(x1 + len, x2), y2, thickness, yVal, uVal, vVal)
        drawVLine(nv21, w, h, x1, max(y2 - len, y1), y2, thickness, yVal, uVal, vVal)
        // Bottom-right
        drawHLine(nv21, w, h, max(x2 - len, x1), x2, y2, thickness, yVal, uVal, vVal)
        drawVLine(nv21, w, h, x2, max(y2 - len, y1), y2, thickness, yVal, uVal, vVal)
    }

    private fun drawHLine(nv21: ByteArray, w: Int, h: Int, x1: Int, x2: Int, y: Int, thickness: Int, yVal: Byte, uVal: Byte, vVal: Byte) {
        val startX = min(x1, x2).coerceIn(0, w - 1)
        val endX = max(x1, x2).coerceIn(0, w - 1)
        for (t in 0 until thickness) {
            val curY = (y + t).coerceIn(0, h - 1)
            for (x in startX..endX) setPixel(nv21, w, h, x, curY, yVal, uVal, vVal)
        }
    }

    private fun drawVLine(nv21: ByteArray, w: Int, h: Int, x: Int, y1: Int, y2: Int, thickness: Int, yVal: Byte, uVal: Byte, vVal: Byte) {
        val startY = min(y1, y2).coerceIn(0, h - 1)
        val endY = max(y1, y2).coerceIn(0, h - 1)
        for (t in 0 until thickness) {
            val curX = (x + t).coerceIn(0, w - 1)
            for (y in startY..endY) setPixel(nv21, w, h, curX, y, yVal, uVal, vVal)
        }
    }

    private fun fillDarkRect(nv21: ByteArray, w: Int, h: Int, rx: Int, ry: Int, rw: Int, rh: Int) {
        val xEnd = min(rx + rw, w)
        val yEnd = min(ry + rh, h)
        for (y in max(0, ry) until yEnd) {
            val yOffset = y * w
            for (x in max(0, rx) until xEnd) {
                // Dim Y value directly for translucent appearance
                nv21[yOffset + x] = ((nv21[yOffset + x].toInt() and 0xFF) / 4 + 10).toByte()
            }
        }
    }

    private fun setPixel(nv21: ByteArray, w: Int, h: Int, x: Int, y: Int, yVal: Byte, uVal: Byte, vVal: Byte) {
        if (x !in 0 until w || y !in 0 until h) return
        nv21[y * w + x] = yVal
        val uvBase = w * h
        val uvIdx = uvBase + (y shr 1) * w + (x and 1.inv())
        if (uvIdx + 1 < nv21.size) {
            nv21[uvIdx] = vVal
            nv21[uvIdx + 1] = uVal
        }
    }

    private fun drawString(
        nv21: ByteArray, w: Int, h: Int,
        startX: Int, startY: Int, text: String,
        scale: Int, yVal: Byte, uVal: Byte, vVal: Byte
    ) {
        var curX = startX
        for (ch in text) {
            val glyph = FONT_5X7[ch] ?: FONT_5X7['?'] ?: continue
            drawGlyph(nv21, w, h, curX, startY, glyph, scale, yVal, uVal, vVal)
            curX += 6 * scale
            if (curX + 6 * scale >= w) break
        }
    }

    private fun drawGlyph(
        nv21: ByteArray, w: Int, h: Int,
        x0: Int, y0: Int, glyph: ByteArray,
        scale: Int, yVal: Byte, uVal: Byte, vVal: Byte
    ) {
        for (col in 0 until 5) {
            val colBits = glyph[col].toInt() and 0xFF
            for (row in 0 until 7) {
                if ((colBits and (1 shl row)) != 0) {
                    val px = x0 + col * scale
                    val py = y0 + row * scale
                    for (dx in 0 until scale) {
                        for (dy in 0 until scale) {
                            setPixel(nv21, w, h, px + dx, py + dy, yVal, uVal, vVal)
                        }
                    }
                }
            }
        }
    }

    /** Compact 5x7 bitmap font table for ASCII characters. */
    private val FONT_5X7: Map<Char, ByteArray> by lazy {
        mapOf(
            ' ' to byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00),
            '!' to byteArrayOf(0x00, 0x00, 0x5F, 0x00, 0x00),
            '%' to byteArrayOf(0x23, 0x13, 0x08, 0x64, 0x62),
            '+' to byteArrayOf(0x08, 0x08, 0x3E, 0x08, 0x08),
            '-' to byteArrayOf(0x08, 0x08, 0x08, 0x08, 0x08),
            '.' to byteArrayOf(0x00, 0x60, 0x60, 0x00, 0x00),
            ':' to byteArrayOf(0x00, 0x36, 0x36, 0x00, 0x00),
            '?' to byteArrayOf(0x02, 0x01, 0x51, 0x09, 0x06),
            '/' to byteArrayOf(0x20, 0x10, 0x08, 0x04, 0x02),
            '(' to byteArrayOf(0x00, 0x1C, 0x22, 0x41, 0x00),
            ')' to byteArrayOf(0x00, 0x41, 0x22, 0x1C, 0x00),
            '[' to byteArrayOf(0x00, 0x7F, 0x41, 0x41, 0x00),
            ']' to byteArrayOf(0x00, 0x41, 0x41, 0x7F, 0x00),
            '0' to byteArrayOf(0x3E, 0x51, 0x49, 0x45, 0x3E),
            '1' to byteArrayOf(0x00, 0x42, 0x7F, 0x40, 0x00),
            '2' to byteArrayOf(0x42, 0x61, 0x51, 0x49, 0x46),
            '3' to byteArrayOf(0x21, 0x41, 0x45, 0x4B, 0x31),
            '4' to byteArrayOf(0x18, 0x14, 0x12, 0x7F, 0x10),
            '5' to byteArrayOf(0x27, 0x45, 0x45, 0x45, 0x39),
            '6' to byteArrayOf(0x3C, 0x4A, 0x49, 0x49, 0x30),
            '7' to byteArrayOf(0x01, 0x71, 0x09, 0x05, 0x03),
            '8' to byteArrayOf(0x36, 0x49, 0x49, 0x49, 0x36),
            '9' to byteArrayOf(0x06, 0x49, 0x49, 0x29, 0x1E),
            'A' to byteArrayOf(0x7E, 0x11, 0x11, 0x11, 0x7E),
            'B' to byteArrayOf(0x7F, 0x49, 0x49, 0x49, 0x36),
            'C' to byteArrayOf(0x3E, 0x41, 0x41, 0x41, 0x22),
            'D' to byteArrayOf(0x7F, 0x41, 0x41, 0x22, 0x1C),
            'E' to byteArrayOf(0x7F, 0x49, 0x49, 0x49, 0x41),
            'F' to byteArrayOf(0x7F, 0x09, 0x09, 0x09, 0x01),
            'G' to byteArrayOf(0x3E, 0x41, 0x49, 0x49, 0x7A),
            'H' to byteArrayOf(0x7F, 0x08, 0x08, 0x08, 0x7F),
            'I' to byteArrayOf(0x00, 0x41, 0x7F, 0x41, 0x00),
            'J' to byteArrayOf(0x20, 0x40, 0x41, 0x3F, 0x01),
            'K' to byteArrayOf(0x7F, 0x08, 0x14, 0x22, 0x41),
            'L' to byteArrayOf(0x7F, 0x40, 0x40, 0x40, 0x40),
            'M' to byteArrayOf(0x7F, 0x02, 0x04, 0x02, 0x7F),
            'N' to byteArrayOf(0x7F, 0x04, 0x08, 0x10, 0x7F),
            'O' to byteArrayOf(0x3E, 0x41, 0x41, 0x41, 0x3E),
            'P' to byteArrayOf(0x7F, 0x09, 0x09, 0x09, 0x06),
            'Q' to byteArrayOf(0x3E, 0x41, 0x51, 0x21, 0x5E),
            'R' to byteArrayOf(0x7F, 0x09, 0x19, 0x29, 0x46),
            'S' to byteArrayOf(0x46, 0x49, 0x49, 0x49, 0x31),
            'T' to byteArrayOf(0x01, 0x01, 0x7F, 0x01, 0x01),
            'U' to byteArrayOf(0x3F, 0x40, 0x40, 0x40, 0x3F),
            'V' to byteArrayOf(0x1F, 0x20, 0x40, 0x20, 0x1F),
            'W' to byteArrayOf(0x7F, 0x20, 0x18, 0x20, 0x7F),
            'X' to byteArrayOf(0x63, 0x14, 0x08, 0x14, 0x63),
            'Y' to byteArrayOf(0x07, 0x08, 0x70, 0x08, 0x07),
            'Z' to byteArrayOf(0x61, 0x51, 0x49, 0x45, 0x43),
            'M' to byteArrayOf(0x7F, 0x02, 0x04, 0x02, 0x7F)
        )
    }
}
