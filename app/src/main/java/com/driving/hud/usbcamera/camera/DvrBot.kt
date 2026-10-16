package com.driving.hud.usbcamera.camera

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import com.driving.hud.usbcamera.util.HudLogger
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal SCSI Bulk-Only Transport (BOT) client for the "U2 DVR".
 *
 * Recovered from the vendor's `libuvccamera-lib.so` (`libUsbRead` @0x1c424,
 * `libUsbWrite` @0x1c98c — see `/memories/repo/u2-dvr-protocol.md`): the DVR is a
 * mass-storage device whose video is fetched with plain BOT on the same interface:
 *
 * ```
 * libusb_bulk_transfer(handle, EP 0x01, &cbw, 31, &transferred, 1000)
 *   CBW[0:4]  = 0x43425355 "USBC"
 *   CBW[4:8]  = 0x74b4ea50            (fixed tag)
 *   CBW[8:12] = dCBWDataTransferLength (rounded up to 512-byte blocks)
 *   CBW[12]   = 0x80 data-IN / 0x00 data-OUT
 *   CBW[13]   = LUN 0
 *   CBW[14]   = CDB length (10)
 *   CBW[15..] = CDB, e.g. 0x28 READ(10) with big-endian LBA + block count
 * ```
 *
 * then the data stage and a 13-byte CSW on bulk IN EP 0x81.
 */
class DvrBot(
    private val connection: UsbDeviceConnection,
    private val outEndpoint: UsbEndpoint,
    private val inEndpoint: UsbEndpoint,
    private val timeoutMs: Int = 1000,
) {
    private companion object {
        const val TAG = "DvrBot"
        const val CBW_SIGNATURE = 0x43425355
        const val CSW_SIGNATURE = 0x53425355
        const val TAG_VALUE = 0x74b4ea50
        const val BLOCK = 512
    }

    /** Last CSW status byte (0 = passed), -1 when the transfer never produced one. */
    @Volatile
    var lastStatus: Int = -1

    private fun buildCbw(cdb: ByteArray, dataLength: Int, dataIn: Boolean): ByteArray =
        ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(TAG_VALUE)
            putInt(dataLength)
            put(if (dataIn) 0x80.toByte() else 0x00.toByte())
            put(0) // LUN
            put(cdb.size.toByte())
            put(cdb)
        }.array()

    private fun sendCbw(cbw: ByteArray): Boolean {
        val sent = connection.bulkTransfer(outEndpoint, cbw, cbw.size, timeoutMs)
        if (sent != cbw.size) {
            HudLogger.w(TAG, "CBW write failed: $sent")
            return false
        }
        return true
    }

    private fun readCsw(): Int {
        val csw = ByteArray(13)
        val got = connection.bulkTransfer(inEndpoint, csw, csw.size, timeoutMs)
        if (got != csw.size) {
            HudLogger.w(TAG, "CSW read got $got")
            return -1
        }
        val buf = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val signature = buf.int
        buf.int // tag
        buf.int // residue
        val status = buf.get().toInt() and 0xFF
        if (signature != CSW_SIGNATURE) HudLogger.w(TAG, "CSW signature 0x%08x".format(signature))
        return status
    }

    private fun roundUpToBlocks(bytes: Int): Int = ((bytes + BLOCK - 1) / BLOCK) * BLOCK

    /** SCSI READ(10) — returns exactly `blocks * 512` bytes, or null on failure. */
    fun read10(lba: Int, blocks: Int): ByteArray? {
        val cdb = ByteArray(10)
        cdb[0] = 0x28
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((blocks ushr 8) and 0xFF).toByte()
        cdb[8] = (blocks and 0xFF).toByte()
        val length = blocks * BLOCK
        if (!sendCbw(buildCbw(cdb, length, dataIn = true))) return null
        val data = ByteArray(length)
        var received = 0
        while (received < length) {
            val n = connection.bulkTransfer(inEndpoint, data, received, length - received, timeoutMs)
            if (n <= 0) {
                HudLogger.w(TAG, "read10 data stalled at $received/$length")
                break
            }
            received += n
        }
        lastStatus = readCsw()
        return if (received == length) data else null
    }

    /** SCSI WRITE(10) — the vendor uses this for its command channel. */
    fun write10(lba: Int, blocks: Int, data: ByteArray): Boolean {
        val cdb = ByteArray(10)
        cdb[0] = 0x2A
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((blocks ushr 8) and 0xFF).toByte()
        cdb[8] = (blocks and 0xFF).toByte()
        val length = roundUpToBlocks(data.size)
        val payload = data.copyOf(length)
        if (!sendCbw(buildCbw(cdb, length, dataIn = false))) return false
        var sent = 0
        while (sent < length) {
            val n = connection.bulkTransfer(outEndpoint, payload, sent, length - sent, timeoutMs)
            if (n <= 0) {
                HudLogger.w(TAG, "write10 data stalled at $sent/$length")
                break
            }
            sent += n
        }
        lastStatus = readCsw()
        return lastStatus == 0
    }

    /** SCSI INQUIRY (6) — the standard way to prove BOT works on this device. */
    fun inquiry(): ByteArray? {
        val cdb = ByteArray(6)
        cdb[0] = 0x12
        cdb[4] = 36
        if (!sendCbw(buildCbw(cdb, 36, dataIn = true))) return null
        val data = ByteArray(36)
        val n = connection.bulkTransfer(inEndpoint, data, 36, timeoutMs)
        lastStatus = readCsw()
        return if (n > 0) data else null
    }

    /** SCSI READ CAPACITY(10). */
    fun readCapacity(): Int? {
        val cdb = ByteArray(10)
        cdb[0] = 0x25
        if (!sendCbw(buildCbw(cdb, 8, dataIn = true))) return null
        val data = ByteArray(8)
        val n = connection.bulkTransfer(inEndpoint, data, 8, timeoutMs)
        lastStatus = readCsw()
        if (n <= 0) return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val lastLba = buf.int
        buf.int // block length
        return lastLba + 1
    }

    fun hex(bytes: ByteArray, length: Int = 48): String =
        bytes.take(length).joinToString("") { "%02x".format(it) }
}
