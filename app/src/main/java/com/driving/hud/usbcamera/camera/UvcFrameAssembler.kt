package com.driving.hud.usbcamera.camera

import java.io.ByteArrayOutputStream

/**
 * Assembles raw USB bulk chunks into complete JPEG (MJPEG) frames by scanning
 * for SOI (0xFFD8) / EOI (0xFFD9) markers and handling UVC payload headers.
 *
 * Kotlin port of the C++ UvcStreamReader — kept in Kotlin so the parsed frames
 * can be fed straight to MediaCodec (no FFmpeg needed).
 */
class UvcFrameAssembler(private val tag: String) {

    private val frameBuffer = ByteArrayOutputStream(256 * 1024)
    private var inFrame = false
    private var droppedFrames = 0
    private var decodedFrames = 0

    private val maxFrameSize = 4 * 1024 * 1024 // 4MB safety cap

    val droppedCount: Int get() = droppedFrames
    val decodedCount: Int get() = decodedFrames

    fun reset() {
        frameBuffer.reset()
        inFrame = false
    }

    /** Feeds one USB chunk; returns the list of complete JPEG frames found in it. */
    fun feed(chunk: ByteArray, chunkSize: Int): List<ByteArray> {
        val frames = ArrayList<ByteArray>(1)
        if (chunkSize <= 0) return frames

        // UVC payload header: byte0 = header length (2..12), byte1 bit1 = EOF flag.
        val hle = chunk[0].toInt() and 0xFF
        val hasUvcHeader = hle in 2..12 && hle <= chunkSize
        val isEof = hasUvcHeader && (chunk[1].toInt() and 0x02) != 0
        val headerSize = if (hasUvcHeader) hle else 0

        // Search for JPEG SOI (0xFF 0xD8) to resynchronize on janky OTG drops.
        var soiIndex = -1
        var i = headerSize
        while (i + 1 < chunkSize) {
            if ((chunk[i].toInt() and 0xFF) == 0xFF && (chunk[i + 1].toInt() and 0xFF) == 0xD8) {
                if (inFrame && frameBuffer.size() > 0) {
                    droppedFrames++
                }
                frameBuffer.reset()
                inFrame = true
                soiIndex = i
                break
            }
            i++
        }

        if (!inFrame && !hasUvcHeader) return frames

        val payloadStart = if (soiIndex >= 0) soiIndex else headerSize
        if (frameBuffer.size() + (chunkSize - payloadStart) > maxFrameSize) {
            frameBuffer.reset()
            inFrame = false
            droppedFrames++
            return frames
        }
        if (payloadStart < chunkSize) {
            frameBuffer.write(chunk, payloadStart, chunkSize - payloadStart)
        }

        val buf = frameBuffer.toByteArray()
        val hasEoi = buf.size >= 2 &&
            (buf[buf.size - 2].toInt() and 0xFF) == 0xFF &&
            (buf[buf.size - 1].toInt() and 0xFF) == 0xD9

        if ((isEof || hasEoi) && inFrame && buf.size > 4) {
            frames.add(buf)
            frameBuffer.reset()
            inFrame = false
            decodedFrames++
        }
        return frames
    }
}
