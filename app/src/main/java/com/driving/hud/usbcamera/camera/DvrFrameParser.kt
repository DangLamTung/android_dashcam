package com.driving.hud.usbcamera.camera

/**
 * Parsing helpers for the U2 DVR's video ring.
 *
 * While a host claims the DVR's mass-storage interface the disk stops being a FAT volume
 * and becomes a ring of **512 KB video slots**; each slot is an 8704-byte device header
 * followed by exactly one JPEG frame:
 *
 * ```
 * byte 0..3   00 08 00 00          slot magic
 * byte 6..7   frame offset hint (0x2200 = 8704)
 * byte 12..15 frame size in bytes, little-endian
 * byte 8704.. the JPEG itself (FF D8 FF ... FF D9)
 * ```
 *
 * The header fields are only hints (the offset field disagrees with the real SOI by a few
 * bytes), so frames are located with the JPEG markers and the size field is used to decide
 * how much to read.
 */
object DvrFrameParser {

    /** Bytes of slot header before the JPEG. */
    const val FRAME_OFFSET = 8704

    /** First four bytes of every slot header. */
    val SLOT_MAGIC = byteArrayOf(0x00, 0x08, 0x00, 0x00)

    private const val SOI = 0xFFD8
    private const val EOI = 0xFFD9

    /** How far the JPEG start marker may sit from [FRAME_OFFSET] and still count as a slot. */
    private const val SOI_SLACK = 512

    /** Smallest JPEG that is a real frame rather than a stray marker pair inside a header. */
    private const val MIN_STANDALONE_FRAME_BYTES = 2048

    /** Offset of the first JPEG SOI (`FF D8 FF`), or -1 when there is none. */
    fun firstSoi(data: ByteArray): Int = firstSoiFrom(data, 0)

    private fun firstSoiFrom(data: ByteArray, from: Int): Int {
        var i = from
        while (i + 2 < data.size) {
            if ((data[i].toInt() and 0xFF) == (SOI shr 8) &&
                (data[i + 1].toInt() and 0xFF) == (SOI and 0xFF) &&
                (data[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                return i
            }
            i++
        }
        return -1
    }

    /** First SOI within [slack] bytes of [at], or -1. */
    private fun soiNear(data: ByteArray, at: Int, slack: Int): Int {
        val from = (at - slack).coerceAtLeast(0)
        val found = firstSoiFrom(data, from)
        return if (found in from..(at + slack)) found else -1
    }

    /** End offset (exclusive) of the first EOI at or after [from], or -1. */
    fun firstEoi(data: ByteArray, from: Int): Int {
        var i = from + 2
        while (i + 1 < data.size) {
            if ((data[i].toInt() and 0xFF) == (EOI shr 8) && (data[i + 1].toInt() and 0xFF) == (EOI and 0xFF)) {
                return i + 2
            }
            i++
        }
        return -1
    }

    /**
     * True when [data] looks like the start of a video slot carrying a JPEG.
     *
     * The JPEG start marker has to sit at [FRAME_OFFSET] (within [SOI_SLACK]), because that is
     * where the device puts it. Accepting an SOI anywhere in the block made this match ordinary
     * filesystem data - lba 896 matched intermittently - and committing to such a LBA wedges the
     * DVR's whole USB stack.
     */
    fun looksLikeSlotHeader(data: ByteArray): Boolean {
        if (data.size < FRAME_OFFSET + 3) return false
        for (i in SLOT_MAGIC.indices) {
            if (data[i] != SLOT_MAGIC[i]) return false
        }
        return soiNear(data, FRAME_OFFSET, SOI_SLACK) >= 0
    }

    /** Frame size hint from header offset 12 (little-endian), or -1 when absent. */
    fun frameSizeHint(header: ByteArray): Int {
        if (header.size < 16) return -1
        return (header[12].toInt() and 0xFF) or
            ((header[13].toInt() and 0xFF) shl 8) or
            ((header[14].toInt() and 0xFF) shl 16) or
            ((header[15].toInt() and 0xFF) shl 24)
    }

    /**
     * Extracts the complete JPEG frame from a slot read, or null when the slot holds no
     * complete frame (mid-write, or not a video slot at all).
     */
    fun extractFrame(slot: ByteArray): ByteArray? {
        // Prefer the marker at the documented offset: a stray SOI inside the header would
        // otherwise produce a truncated "frame" that cannot be decoded.
        val anchored = soiNear(slot, FRAME_OFFSET, SOI_SLACK)
        val start = if (anchored >= 0) anchored else firstSoi(slot)
        if (start < 0) return null
        val end = firstEoi(slot, start)
        if (end <= start) return null
        return slot.copyOfRange(start, end)
    }

    /**
     * Extracts the **last** complete JPEG in [data], or null when there is none.
     *
     * The preview file is a header followed by JPEG frames, and it may either be rewritten in
     * place (one frame) or appended to (a run of frames). Taking the first frame would freeze the
     * picture the moment a second one appears, so the newest complete frame wins.
     */
    fun lastCompleteFrame(data: ByteArray): ByteArray? {
        var start = -1
        var bestStart = -1
        var bestEnd = -1
        var i = 0
        while (i + 1 < data.size) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            if (b0 == (SOI shr 8) && b1 == (SOI and 0xFF)) {
                start = i
                i += 2
                continue
            }
            if (start >= 0 && b0 == (EOI shr 8) && b1 == (EOI and 0xFF)) {
                val end = i + 2
                if (end - start >= MIN_STANDALONE_FRAME_BYTES) {
                    bestStart = start
                    bestEnd = end
                }
                start = -1
                i = end
                continue
            }
            i++
        }
        if (bestStart < 0) return null
        return data.copyOfRange(bestStart, bestEnd)
    }

    /** 512-byte blocks needed to read a frame that starts at [soi] and is [frameSize] long. */
    fun blocksFor(soi: Int, frameSize: Int, maxBlocks: Int): Int {
        val bytes = soi + frameSize.coerceAtLeast(1) + 1024
        return ((bytes + 511) / 512).coerceIn(1, maxBlocks)
    }

    /**
     * Blocks to read when the frame size is already known from the previous frame, so the slot
     * header and the JPEG body arrive in **one** transfer instead of two round trips per frame.
     *
     * [blocksFor] adds 1 KB of slack beyond the frame, which covers the small frame-to-frame
     * variation the DVR shows (measured 64.5-66.5 KB). A frame that grows past that makes
     * [extractFrame] return null, and the caller falls back to a second read sized from the
     * fresh hint in the header.
     */
    fun blocksForKnownSize(frameSize: Int, maxBlocks: Int): Int =
        blocksFor(FRAME_OFFSET, frameSize, maxBlocks)
}
