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
    private val interfaceId: Int = 0,
    private val timeoutMs: Int = 1000,
) {
    private companion object {
        const val TAG = "DvrBot"
        const val CBW_SIGNATURE = 0x43425355
        const val CSW_SIGNATURE = 0x53425355
        const val TAG_VALUE = 0x74b4ea50
        const val BLOCK = 512

        /**
         * Maximum transfer buffer size for UsbDeviceConnection.bulkTransfer() under Linux usbfs
         * (USBDEVFS_BULK ioctl enforces MAX_USBFS_BUFFER_SIZE = 16384 bytes). Transfers larger
         * than this fail immediately with -EINVAL on Android targetSdk >= 28, wedging the BOT pipe.
         */
        const val MAX_USBFS_BUFFER_SIZE = 16384

        /** Class-specific Bulk-Only Mass Storage Reset (bmRequestType 0x21, bRequest 0xFF). */
        const val BOT_RESET_REQUEST_TYPE = 0x21
        const val BOT_RESET_REQUEST = 0xFF

        /**
         * Minimum gap between BOT resets. On this DVR the class reset is answered with -1 and
         * each of the three control requests costs ~400 ms, so resetting eagerly costs far more
         * time than the desync it is meant to cure.
         */
        const val RESET_MIN_INTERVAL_MS = 1000L

        /**
         * CLEAR_FEATURE(HALT) is a standard request the device answers even while its data pipes
         * are halted, so it is cheap. A long timeout here only delays recovery from a device that
         * has already gone away, which is exactly when the next step is to reopen it.
         */
        const val HALT_CLEAR_TIMEOUT_MS = 100

        /** Recovery attempts after which the pipe is treated as gone and the caller reopens. */
        const val MAX_RECOVERIES = 2
    }

    /** When the pipe was last reset, so recovery attempts cannot become the bottleneck. */
    private var lastResetMs = 0L

    /** Last CSW status byte (0 = passed), -1 when the transfer never produced one. */
    @Volatile
    var lastStatus: Int = -1

    /**
     * True when a command failed before its CSW arrived: host and device then disagree about
     * where the next CSW starts, so the pipe has to be recovered before anything else is sent.
     */
    @Volatile
    var pipeDirty: Boolean = false
        private set

    /**
     * Consecutive commands that never produced a CSW. A stalled endpoint or a vanished device
     * fails every transfer instantly, and polling cannot fix either, so the caller uses this to
     * decide when to reopen the device instead of retrying.
     */
    @Volatile
    var transportFailures: Int = 0
        private set

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
        // A command whose CSW never arrived left the device expecting no further traffic until it
        // is reset. Recover first, or this CBW fails exactly like the one before it did.
        if (pipeDirty) recover()
        val sent = bulkOut(cbw, 0, cbw.size)
        if (sent != cbw.size) {
            HudLogger.w(TAG, "CBW write failed: $sent")
            pipeDirty = true
            transportFailures++
            return false
        }
        return true
    }

    /**
     * Bulk transfers throw once the connection is closed underneath us (a cable pulled while a
     * transfer is in flight, or a source being torn down). That is a normal race here, not a
     * crash: report it as a failed transfer.
     */
    private fun bulkIn(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val chunk = minOf(length, MAX_USBFS_BUFFER_SIZE)
        connection.bulkTransfer(inEndpoint, buffer, offset, chunk, timeoutMs)
    } catch (t: Throwable) {
        HudLogger.w(TAG, "bulk IN failed: ${t.message}")
        -1
    }

    private fun bulkOut(buffer: ByteArray, offset: Int, length: Int): Int = try {
        val chunk = minOf(length, MAX_USBFS_BUFFER_SIZE)
        connection.bulkTransfer(outEndpoint, buffer, offset, chunk, timeoutMs)
    } catch (t: Throwable) {
        HudLogger.w(TAG, "bulk OUT failed: ${t.message}")
        -1
    }

    /**
     * Bulk-Only Transport error recovery.
     *
     * A command that ends without its CSW leaves host and device disagreeing about where the
     * next CSW begins, and a data phase the device rejects (a CHECK CONDITION, for instance a
     * READ past the end of the LUN) **stalls the bulk-IN pipe** instead of sending one. Per the
     * spec the host must clear both endpoint halts, optionally after the class-specific Bulk-Only
     * Mass Storage Reset.
     *
     * The order matters on this DVR: its answer to the class reset is a timeout, and an earlier
     * version of this method treated that as "no endpoint is halted" and skipped the halts
     * entirely. One stall then made every later transfer fail for the rest of the session — the
     * `CBW write failed: -1` storm in `hud.log`. CLEAR_FEATURE(HALT) is therefore issued first
     * and unconditionally, and the class reset is only a best-effort extra.
     */
    fun recover() {
        pipeDirty = false
        // A device that has ignored two recoveries in a row is not coming back: its control
        // endpoint stops answering too, and the caller's reopen is the only cure. Burning another
        // ~600 ms of control transfers only delays that.
        if (transportFailures >= MAX_RECOVERIES) return
        clearHalt(outEndpoint)
        clearHalt(inEndpoint)

        val now = System.currentTimeMillis()
        if (now - lastResetMs < RESET_MIN_INTERVAL_MS) return
        lastResetMs = now
        val rc = try {
            connection.controlTransfer(
                BOT_RESET_REQUEST_TYPE, BOT_RESET_REQUEST, 0, interfaceId, null, 0, timeoutMs,
            )
        } catch (t: Throwable) {
            HudLogger.w(TAG, "BOT reset request failed: ${t.message}")
            -1
        }
        if (rc < 0) {
            HudLogger.w(TAG, "BOT mass-storage reset -> $rc (halts were cleared anyway)")
        } else {
            HudLogger.i(TAG, "BOT mass-storage reset -> $rc")
        }
    }

    /**
     * Standard CLEAR_FEATURE(ENDPOINT_HALT) for one endpoint. `UsbDeviceConnection` has no
     * `clearHalt`, so this is the raw request: bmRequestType 0x02 (standard, endpoint recipient),
     * bRequest 0x01, wValue 0 (halt), wIndex = the endpoint address.
     */
    private fun clearHalt(endpoint: UsbEndpoint) {
        try {
            val rc = connection.controlTransfer(
                0x02, 0x01, 0, endpoint.address, null, 0, HALT_CLEAR_TIMEOUT_MS,
            )
            HudLogger.w(TAG, "CLEAR_FEATURE(HALT) ep 0x%02x -> %d".format(endpoint.address, rc))
        } catch (t: Throwable) {
            HudLogger.w(TAG, "CLEAR_FEATURE(HALT) failed: ${t.message}")
        }
    }

    private fun readCsw(): Int {
        val csw = ByteArray(13)
        val got = bulkIn(csw, 0, csw.size)
        if (got != csw.size) {
            HudLogger.w(TAG, "CSW read got $got")
            // Nothing may be sent until the pipe is recovered; see recover().
            pipeDirty = true
            transportFailures++
            return -1
        }
        pipeDirty = false
        transportFailures = 0
        val buf = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val signature = buf.int
        buf.int // tag
        buf.int // residue
        val status = buf.get().toInt() and 0xFF
        if (signature != CSW_SIGNATURE) HudLogger.w(TAG, "CSW signature 0x%08x".format(signature))
        return status
    }

    private fun roundUpToBlocks(bytes: Int): Int = ((bytes + BLOCK - 1) / BLOCK) * BLOCK

    private fun readCdb(lba: Int, blocks: Int): ByteArray {
        val cdb = ByteArray(10)
        cdb[0] = 0x28
        cdb[2] = ((lba ushr 24) and 0xFF).toByte()
        cdb[3] = ((lba ushr 16) and 0xFF).toByte()
        cdb[4] = ((lba ushr 8) and 0xFF).toByte()
        cdb[5] = (lba and 0xFF).toByte()
        cdb[7] = ((blocks ushr 8) and 0xFF).toByte()
        cdb[8] = (blocks and 0xFF).toByte()
        return cdb
    }

    /**
     * SCSI READ(10) into a caller-owned buffer - the streaming path reuses one buffer per frame
     * instead of allocating ~90 KB at 15 fps.
     *
     * @return bytes read (always `blocks * 512`), or -1 when the transfer failed or came up short
     */
    fun read10Into(lba: Int, blocks: Int, buffer: ByteArray): Int {
        val length = blocks * BLOCK
        if (buffer.size < length) return -1
        if (!sendCbw(buildCbw(readCdb(lba, blocks), length, dataIn = true))) return -1
        var received = 0
        while (received < length) {
            val toRead = minOf(length - received, MAX_USBFS_BUFFER_SIZE)
            val n = bulkIn(buffer, received, toRead)
            if (n <= 0) break
            received += n
        }
        if (received != length) {
            HudLogger.w(TAG, "read10 data stalled at $received/$length")
        }
        // Always consume the CSW, even when the data phase came up short: the device sends it
        // either way (its residue field reports the shortfall), and leaving it in the pipe is
        // exactly what desynchronises the *next* command. A short read is a normal race with the
        // DVR rewriting the ring, not an error worth resetting the pipe for.
        lastStatus = readCsw()
        if (lastStatus < 0) {
            recover()
            return -1
        }
        if (received != length) {
            HudLogger.w(TAG, "read10 short: $received/$length (status=$lastStatus)")
            return -1
        }
        return length
    }

    /** SCSI READ(10) — returns exactly `blocks * 512` bytes, or null on failure. */
    fun read10(lba: Int, blocks: Int): ByteArray? {
        val data = ByteArray(blocks * BLOCK)
        return if (read10Into(lba, blocks, data) == data.size) data else null
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
            val toSend = minOf(length - sent, MAX_USBFS_BUFFER_SIZE)
            val n = bulkOut(payload, sent, toSend)
            if (n <= 0) break
            sent += n
        }
        if (sent != length) {
            HudLogger.w(TAG, "write10 data stalled at $sent/$length")
        }
        // Same rule as read10Into: the CSW has to be consumed whatever the data phase did.
        lastStatus = readCsw()
        if (lastStatus < 0) {
            recover()
            return false
        }
        if (sent != length) {
            HudLogger.w(TAG, "write10 short: $sent/$length (status=$lastStatus)")
            return false
        }
        return lastStatus == 0
    }

    /** SCSI INQUIRY (6) — the standard way to prove BOT works on this device. */
    fun inquiry(): ByteArray? {
        val cdb = ByteArray(6)
        cdb[0] = 0x12
        cdb[4] = 36
        if (!sendCbw(buildCbw(cdb, 36, dataIn = true))) return null
        val data = ByteArray(36)
        val n = bulkIn(data, 0, 36)
        lastStatus = readCsw()
        return if (n > 0) data else null
    }

    /** SCSI READ CAPACITY(10). */
    fun readCapacity(): Int? {
        val cdb = ByteArray(10)
        cdb[0] = 0x25
        if (!sendCbw(buildCbw(cdb, 8, dataIn = true))) return null
        val data = ByteArray(8)
        val n = bulkIn(data, 0, 8)
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
