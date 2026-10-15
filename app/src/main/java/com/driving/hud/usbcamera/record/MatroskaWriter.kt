package com.driving.hud.usbcamera.record

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
/**
 * Minimal Matroska (.mkv) writer for a single H.264 video track.
 *
 * Android's MediaMuxer cannot write Matroska — only MP4, WebM, 3GPP, OGG and HEIF — and WebM
 * would allow neither H.264 nor the camera's MJPEG, so the container is written by hand here.
 * Only the elements a player actually needs are emitted: EBML header, Info, Tracks carrying the
 * AVC decoder configuration, then Clusters of SimpleBlocks.
 *
 * The Segment length and the Duration are written as placeholders and back-patched in [close], so
 * the finished file has no unknown-length elements. (ffmpeg tolerates an open-ended Segment, but
 * it warns and reports duration=N/A, which is avoidable since the file is not being streamed.)
 *
 * H.264 in Matroska must be length-prefixed (AVCC) with SPS/PPS supplied out of band in
 * CodecPrivate, so frames are converted before they get here — see [H264AnnexB].
 *
 * Deliberately omitted: Cues, so seeking is coarse. Every player tested opens the result.
 */
internal class MatroskaWriter(private val file: File) {

    private companion object {
        const val ID_EBML = 0x1A45DFA3
        const val ID_EBML_VERSION = 0x4286
        const val ID_EBML_READ_VERSION = 0x42F7
        const val ID_EBML_MAX_ID_LENGTH = 0x42F2
        const val ID_EBML_MAX_SIZE_LENGTH = 0x42F3
        const val ID_DOC_TYPE = 0x4282
        const val ID_DOC_TYPE_VERSION = 0x4287
        const val ID_DOC_TYPE_READ_VERSION = 0x4285

        const val ID_SEGMENT = 0x18538067
        const val ID_INFO = 0x1549A966
        const val ID_TIMECODE_SCALE = 0x2AD7B1
        const val ID_MUXING_APP = 0x4D80
        const val ID_WRITING_APP = 0x5741
        const val ID_DURATION = 0x4489

        const val ID_TRACKS = 0x1654AE6B
        const val ID_TRACK_ENTRY = 0xAE
        const val ID_TRACK_NUMBER = 0xD7
        const val ID_TRACK_UID = 0x73C5
        const val ID_TRACK_TYPE = 0x83
        const val ID_FLAG_LACING = 0x9C
        const val ID_CODEC_ID = 0x86
        const val ID_CODEC_PRIVATE = 0x63A2
        const val ID_DEFAULT_DURATION = 0x23E383
        const val ID_VIDEO = 0xE0
        const val ID_PIXEL_WIDTH = 0xB0
        const val ID_PIXEL_HEIGHT = 0xBA

        const val ID_CLUSTER = 0x1F43B675
        const val ID_TIMESTAMP = 0xE7
        const val ID_SIMPLE_BLOCK = 0xA3

        /** Matroska timestamps are in ms. */
        const val TIMECODE_SCALE_NS = 1_000_000L

        /** Flush a cluster at least this often so a block timestamp fits in 16 bits. */
        const val MAX_CLUSTER_MS = 1_000L

        const val TRACK_NUMBER = 1
    }

    private var out: RandomAccessFile? = null
    private val cluster = ByteArrayOutputStream(64 * 1024)
    private var clusterStartMs = -1L
    private var frames = 0L

    /** Absolute file offset of the 8-byte placeholder holding the Segment size. */
    private var segmentSizeOffset = -1L

    /** Where the Segment payload starts (immediately after that placeholder). */
    private var segmentPayloadStart = -1L

    /** Absolute file offset of the Duration payload, patched in [close]. */
    private var durationOffset = -1L

    /** Timestamp of the last frame written, used for the duration. */
    private var lastFrameMs = 0L

    /** Bytes written so far when [close] is called; useful for logging. */
    var bytesWritten: Long = 0
        private set

    fun open(width: Int, height: Int, codecPrivate: ByteArray, fps: Int) {
        val f = file
        f.parentFile?.mkdirs()
        val raf = RandomAccessFile(f, "rw")
        raf.setLength(0)
        out = raf

        raf.write(ebmlHeader())

        // Segment size is written as an 8-byte placeholder and patched in close().
        raf.write(id(ID_SEGMENT))
        segmentSizeOffset = raf.filePointer
        raf.write(ByteArray(8))
        segmentPayloadStart = raf.filePointer

        val infoPayload = info()
        // Duration is the last element of Info, so its payload ends where the Info payload ends,
        // which makes the offset to patch a simple subtraction.
        durationOffset = raf.filePointer + id(ID_INFO).size + vint(infoPayload.size.toLong()).size +
            infoPayload.size - 8
        raf.write(elem(ID_INFO, infoPayload))
        raf.write(elem(ID_TRACKS, tracks(width, height, codecPrivate, fps)))
    }

    fun writeFrame(data: ByteArray, ptsMs: Long, keyframe: Boolean) {
        if (out == null) return
        if (data.isEmpty()) return
        // A cluster must start on a keyframe, so anything pending is flushed first.
        if (clusterStartMs < 0 || (keyframe && cluster.size() > 0) ||
            ptsMs - clusterStartMs >= MAX_CLUSTER_MS
        ) {
            flushCluster()
            clusterStartMs = ptsMs
        }
        val rel = (ptsMs - clusterStartMs).toInt().coerceIn(0, 32767)

        val block = ByteArrayOutputStream(data.size + 8)
        block.write(0x81) // track number 1 as a vint
        block.write((rel shr 8) and 0xFF)
        block.write(rel and 0xFF)
        block.write(if (keyframe) 0x80 else 0x00)
        block.write(data)
        cluster.write(elem(ID_SIMPLE_BLOCK, block.toByteArray()))
        frames++
        lastFrameMs = ptsMs
    }

    /** Flushes any pending cluster, patches the header, and returns the file. */
    fun close(): File {
        flushCluster()
        val raf = out
        out = null
        if (raf != null) {
            if (frames > 0L) {
                patchHeader(raf)
            }
            bytesWritten = raf.filePointer
            raf.close()
        }
        if (frames == 0L) {
            // Nothing was muxed (encoder never produced a frame); don't leave a decoy file.
            file.delete()
        }
        return file
    }

    /** Fills in the Segment length and the Duration now that the sizes are known. */
    private fun patchHeader(raf: RandomAccessFile) {
        val end = raf.filePointer
        try {
            if (segmentSizeOffset >= 0 && segmentPayloadStart >= 0) {
                val payload = end - segmentPayloadStart
                raf.seek(segmentSizeOffset)
                // The placeholder was 8 bytes, so re-encode as an 8-byte vint (0x01 + 7 bytes).
                raf.write(byteArrayOf(0x01))
                for (shift in 48 downTo 0 step 8) {
                    raf.write(((payload ushr shift) and 0xFF).toByte().toInt())
                }
            }
            if (durationOffset >= 0) {
                raf.seek(durationOffset)
                val duration = lastFrameMs.toDouble()
                val bits = java.lang.Double.doubleToLongBits(duration)
                for (shift in 56 downTo 0 step 8) {
                    raf.write(((bits ushr shift) and 0xFF).toByte().toInt())
                }
            }
        } catch (t: Throwable) {
            // A failed patch leaves a file that still plays, so this must not fail the recording.
        } finally {
            try {
                raf.seek(end)
            } catch (_: Throwable) {
            }
        }
    }

    private fun flushCluster() {
        val raf = out ?: return
        if (cluster.size() == 0) {
            // Keep an empty cluster buffer for reuse.
            clusterStartMs = -1L
            return
        }
        val payload = ByteArrayOutputStream(cluster.size() + 16)
        payload.write(elem(ID_TIMESTAMP, uintBytes(clusterStartMs)))
        payload.write(cluster.toByteArray())
        raf.write(elem(ID_CLUSTER, payload.toByteArray()))
        cluster.reset()
        clusterStartMs = -1L
    }

    // ---- element construction ----------------------------------------------------

    private fun ebmlHeader(): ByteArray {
        val body = ByteArrayOutputStream(64)
        body.write(uintElem(ID_EBML_VERSION, 1))
        body.write(uintElem(ID_EBML_READ_VERSION, 1))
        body.write(uintElem(ID_EBML_MAX_ID_LENGTH, 4))
        body.write(uintElem(ID_EBML_MAX_SIZE_LENGTH, 8))
        body.write(stringElem(ID_DOC_TYPE, "matroska"))
        body.write(uintElem(ID_DOC_TYPE_VERSION, 4))
        body.write(uintElem(ID_DOC_TYPE_READ_VERSION, 2))
        return elem(ID_EBML, body.toByteArray())
    }

    private fun info(): ByteArray {
        val body = ByteArrayOutputStream(64)
        body.write(uintElem(ID_TIMECODE_SCALE, TIMECODE_SCALE_NS))
        body.write(stringElem(ID_MUXING_APP, "driving-hud"))
        body.write(stringElem(ID_WRITING_APP, "driving-hud"))
        // Duration must stay last: its offset is derived from the end of this payload.
        body.write(id(ID_DURATION))
        body.write(vint(8L))
        body.write(ByteArray(8))
        return body.toByteArray()
    }

    private fun tracks(width: Int, height: Int, codecPrivate: ByteArray, fps: Int): ByteArray {
        val video = ByteArrayOutputStream(24)
        video.write(uintElem(ID_PIXEL_WIDTH, width.toLong()))
        video.write(uintElem(ID_PIXEL_HEIGHT, height.toLong()))

        val entry = ByteArrayOutputStream(128)
        entry.write(uintElem(ID_TRACK_NUMBER, TRACK_NUMBER.toLong()))
        entry.write(uintElem(ID_TRACK_UID, TRACK_NUMBER.toLong()))
        entry.write(uintElem(ID_TRACK_TYPE, 1)) // 1 = video
        entry.write(uintElem(ID_FLAG_LACING, 0))
        entry.write(stringElem(ID_CODEC_ID, "V_MPEG4/ISO/AVC"))
        entry.write(elem(ID_CODEC_PRIVATE, codecPrivate))
        if (fps > 0) entry.write(uintElem(ID_DEFAULT_DURATION, 1_000_000_000L / fps))
        entry.write(elem(ID_VIDEO, video.toByteArray()))

        return elem(ID_TRACK_ENTRY, entry.toByteArray())
    }

    private fun elem(idValue: Int, payload: ByteArray): ByteArray {
        val size = vint(payload.size.toLong())
        val buf = ByteArray(id(idValue).size + size.size + payload.size)
        var o = 0
        for (b in id(idValue)) buf[o++] = b
        for (b in size) buf[o++] = b
        for (b in payload) buf[o++] = b
        return buf
    }

    private fun uintElem(idValue: Int, value: Long) = elem(idValue, uintBytes(value))

    private fun stringElem(idValue: Int, s: String) = elem(idValue, s.toByteArray(Charsets.UTF_8))

    /** Minimal big-endian encoding, at least one byte. */
    private fun uintBytes(value: Long): ByteArray {
        var len = 1
        var v = value
        while (v ushr (8 * len) != 0L) len++
        val out = ByteArray(len)
        for (i in len - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    /**
     * Element IDs already contain their own length marker, so they are written in the fewest
     * bytes that hold the value.
     */
    private fun id(value: Int): ByteArray = when {
        value <= 0xFF -> byteArrayOf(value.toByte())
        value <= 0xFFFF -> byteArrayOf((value ushr 8).toByte(), value.toByte())
        value <= 0xFFFFFF ->
            byteArrayOf((value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
        else -> byteArrayOf(
            (value ushr 24).toByte(), (value ushr 16).toByte(),
            (value ushr 8).toByte(), value.toByte()
        )
    }

    /** EBML variable-length integer, with the leading length marker set. */
    private fun vint(value: Long): ByteArray {
        var len = 1
        while (len < 8 && value >= (1L shl (7 * len)) - 1) len++
        val out = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        out[0] = (out[0].toInt() or (0x80 ushr (len - 1))).toByte()
        return out
    }
}
