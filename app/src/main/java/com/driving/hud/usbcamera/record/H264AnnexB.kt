package com.driving.hud.usbcamera.record

import java.io.ByteArrayOutputStream

/**
 * H.264 bitstream helpers for the Matroska muxer.
 *
 * MediaCodec hands out **Annex-B** access units (each NAL preceded by a `00 00 01` start code),
 * but Matroska stores **AVCC** — each NAL prefixed with its own 4-byte big-endian length, with
 * SPS/PPS moved out into the track's CodecPrivate. Getting this wrong yields a file that a
 * player opens and then shows nothing, so the conversion is done explicitly here.
 */
internal object H264AnnexB {

    /** Start-code scan: returns `[start, end)` payload ranges of each NAL in an Annex-B buffer. */
    private fun ranges(data: ByteArray, length: Int): List<IntArray> {
        val limit = if (length in 1..data.size) length else data.size
        val starts = ArrayList<Int>(32)
        var i = 0
        while (i + 2 < limit) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) {
                    starts.add(i + 3)
                    i += 3
                    continue
                }
                if (i + 3 < limit && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    starts.add(i + 4)
                    i += 4
                    continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return emptyList()
        val out = ArrayList<IntArray>(starts.size)
        for (k in starts.indices) {
            val from = starts[k]
            // The next start code belongs to the following NAL; step back over its zeros.
            var to = if (k + 1 < starts.size) starts[k + 1] - 3 else limit
            if (k + 1 < starts.size) {
                // starts[k+1] points just past the start code, which is 3 or 4 bytes long.
                val sc = starts[k + 1]
                val fourByte = sc >= 4 && data[sc - 4].toInt() == 0
                to = if (fourByte) sc - 4 else sc - 3
            }
            while (to > from && data[to - 1].toInt() == 0) to--
            if (to > from) out.add(intArrayOf(from, to))
        }
        return out
    }

    /** True when the buffer is already an AVCDecoderConfigurationRecord rather than Annex-B. */
    fun looksLikeAvcC(data: ByteArray): Boolean = data.isNotEmpty() && (data[0].toInt() and 0xFF) == 1

    /** Converts one Annex-B access unit to AVCC (4-byte length prefixes). */
    fun toAvcc(data: ByteArray, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length + 16)
        for (r in ranges(data, length)) {
            val len = r[1] - r[0]
            out.write((len ushr 24) and 0xFF)
            out.write((len ushr 16) and 0xFF)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
            out.write(data, r[0], len)
        }
        return out.toByteArray()
    }

    /** Pulls the first SPS (type 7) and PPS (type 8) out of an Annex-B buffer. */
    fun findParameterSets(data: ByteArray, length: Int): Pair<ByteArray, ByteArray>? {
        val s = firstNalOfType(data, length, 7) ?: return null
        val p = firstNalOfType(data, length, 8) ?: return null
        return s to p
    }

    /**
     * First NAL of the given type in an Annex-B buffer, or null.
     *
     * MediaCodec typically hands SPS in csd-0 and PPS in csd-1, so they have to be looked up
     * separately rather than assumed to sit in the same buffer.
     */
    fun firstNalOfType(data: ByteArray, length: Int, type: Int): ByteArray? {
        for (r in ranges(data, length)) {
            if ((data[r[0]].toInt() and 0x1F) == type) return data.copyOfRange(r[0], r[1])
        }
        return null
    }

    /**
     * Builds the AVCDecoderConfigurationRecord (CodecPrivate) from SPS and PPS.
     *
     * Returns null when the SPS is too short to carry the profile/level bytes the record needs.
     */
    fun avcC(sps: ByteArray, pps: ByteArray): ByteArray? {
        if (sps.size < 4 || pps.isEmpty()) return null
        val out = ByteArrayOutputStream(64)
        out.write(1) // configurationVersion
        out.write(sps[1].toInt() and 0xFF) // AVCProfileIndication
        out.write(sps[2].toInt() and 0xFF) // profile_compatibility
        out.write(sps[3].toInt() and 0xFF) // AVCLevelIndication
        out.write(0xFF) // reserved(6) + lengthSizeMinusOne = 3 (4-byte lengths)
        out.write(0xE1) // reserved(3) + numOfSequenceParameterSets = 1
        out.write((sps.size ushr 8) and 0xFF)
        out.write(sps.size and 0xFF)
        out.write(sps)
        out.write(1) // numOfPictureParameterSets
        out.write((pps.size ushr 8) and 0xFF)
        out.write(pps.size and 0xFF)
        out.write(pps)

        // High profiles (100/110/122/144) carry three extra trailing bytes in the record.
        val profile = sps[1].toInt() and 0xFF
        if (profile == 100 || profile == 110 || profile == 122 || profile == 144) {
            out.write(0xFC or 1) // chroma_format = 4:2:0
            out.write(0xF8) // bit_depth_luma_minus8
            out.write(0xF8) // bit_depth_chroma_minus8
            out.write(0) // numOfSequenceParameterSetExt
        }
        return out.toByteArray()
    }
}
