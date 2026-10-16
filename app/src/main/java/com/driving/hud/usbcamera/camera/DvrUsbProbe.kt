package com.driving.hud.usbcamera.camera

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.driving.hud.usbcamera.util.HudLogger

/**
 * Experimental USB probe for the "U2 DVR" (VID 0x1902 / PID 0x3241).
 *
 * The device advertises a single mass-storage interface, so the libuvc path in
 * [AusbcCameraController] can never open it. Its video therefore has to travel
 * over the same bulk endpoints the mass-storage interface exposes (which is what
 * the vendor's native `USBUtil` CBW/CSW module does). This probe answers the
 * cheapest possible question first: does the DVR push MJPEG on bulk IN as soon
 * as a host claims the interface, with no vendor command needed?
 *
 * Claiming the interface detaches `usb-storage`, so the volume unmounts while the
 * probe runs and remounts after [probe] releases it.
 */
object DvrUsbProbe {
    private const val TAG = "DvrUsbProbe"

    /** Broadcast action used for the DVR's USB permission request/result. */
    const val ACTION_USB_PERMISSION = "com.driving.hud.usbcamera.DVR_USB_PERMISSION"

    fun findDevice(context: Context): UsbDevice? {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return null
        return usb.deviceList.values.firstOrNull { DvrVolume.matches(it.vendorId, it.productId) }
    }

    /**
     * Pulls one live frame using the slot header (offset 6 = JPEG offset, offset 12 =
     * frame size), decodes it and writes it to the app's files dir for inspection.
     */
    private fun grabFrame(context: Context, bot: DvrBot, streamLba: Int) {
        val head = bot.read10(streamLba, 32) ?: return // first 16 KB of the slot
        val soiInHead = soiOffsets(head).firstOrNull() ?: -1
        val frameSizeHint = u32(head, 12).toInt()
        HudLogger.i(TAG, "header: soiInHead=$soiInHead frameSizeHint=$frameSizeHint u16@6=${u16(head, 6)}")
        // The header is the device's status block; look for the fields that describe the video.
        val fields = StringBuilder()
        for (i in 0 until 64 step 4) fields.append("${i}:%08x ".format(u32(head, i)))
        HudLogger.i(TAG, "header u32 fields: $fields")
        for (candidate in intArrayOf(1920, 1080, 1280, 720, 640, 480, 30, 25)) {
            var found = -1
            for (i in 0 until head.size - 1) {
                val le = (head[i].toInt() and 0xFF) or ((head[i + 1].toInt() and 0xFF) shl 8)
                val be = ((head[i].toInt() and 0xFF) shl 8) or (head[i + 1].toInt() and 0xFF)
                if (le == candidate || be == candidate) {
                    found = i
                    break
                }
            }
            HudLogger.i(TAG, "header value $candidate -> ${if (found >= 0) "at offset $found" else "absent"}")
        }
        val start = if (soiInHead >= 0) soiInHead else 8704
        val want = (start + frameSizeHint.coerceAtLeast(1024) + 1024).coerceIn(2048, 512 * 1024)
        val slot = bot.read10(streamLba, (want + 511) / 512) ?: return
        val jpegStart = soiOffsets(slot).firstOrNull() ?: start
        val eoi = findEoi(slot, jpegStart)
        if (eoi <= jpegStart) {
            HudLogger.w(TAG, "no complete JPEG in $want bytes")
            return
        }
        val jpeg = slot.copyOfRange(jpegStart, eoi)
        HudLogger.i(TAG, "frame bytes=${jpeg.size} head=${bot.hex(jpeg, 4)} status=${bot.lastStatus}")
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        HudLogger.i(TAG, "decoded frame = ${bitmap?.width}x${bitmap?.height}")
        if (bitmap != null) {
            val file = java.io.File(context.getExternalFilesDir(null), "dvr-frame.jpg")
            file.writeBytes(jpeg)
            HudLogger.i(TAG, "saved ${file.absolutePath} (${file.length()} B)")
        }
    }

    private fun u16(data: ByteArray, offset: Int): Int =
        if (offset + 2 > data.size) -1 else (((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF))

    /** Offsets of every JPEG SOI marker in a buffer. */
    private fun soiOffsets(data: ByteArray): List<Int> {
        val out = ArrayList<Int>()
        for (i in 0 until data.size - 3) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) out.add(i)
        }
        return out
    }

    /** First JPEG EOI end offset at or after [from], or -1. */
    private fun findEoi(data: ByteArray, from: Int): Int {
        var i = from + 2
        while (i < data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) return i + 2
            i++
        }
        return -1
    }

    private fun u32(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) return -1
        return (data[offset].toLong() and 0xFF) or ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Asks the system for permission; the user has to accept the dialog once. */
    fun requestPermission(context: Context) {
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val device = findDevice(context) ?: return
        if (usb.hasPermission(device)) return
        val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, 0, intent, flags)
        HudLogger.i(TAG, "requesting USB permission for the DVR (tap Allow on the tablet)")
        usb.requestPermission(device, pending)
    }

    /**
     * Claims the DVR interface, optionally nudges the firmware with the vendor's
     * `changeMode(1)` control request, then drains bulk IN for [readSeconds].
     */
    fun probe(context: Context, readSeconds: Int = 8, sendChangeMode: Boolean = true) {
        HudLogger.i(TAG, "=== DVR USB probe ===")
        val usb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usb == null) {
            HudLogger.e(TAG, "UsbManager unavailable", null)
            return
        }
        val device = findDevice(context)
        if (device == null) {
            HudLogger.w(TAG, "DVR not attached")
            return
        }
        HudLogger.i(
            TAG,
            "device=${device.deviceName} vid=0x%04x pid=0x%04x ifaces=%d permission=%s"
                .format(device.vendorId, device.productId, device.interfaceCount, usb.hasPermission(device)),
        )
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            HudLogger.i(
                TAG,
                "iface[$i] id=${intf.id} class=${intf.interfaceClass} sub=${intf.interfaceSubclass} " +
                    "proto=${intf.interfaceProtocol} endpoints=${intf.endpointCount}",
            )
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                HudLogger.i(TAG, "  ep[$e] addr=0x%02x %s type=%d maxPacket=%d".format(ep.address, dir, ep.type, ep.maxPacketSize))
            }
        }
        if (!usb.hasPermission(device)) {
            HudLogger.w(TAG, "no USB permission — requesting it now, then rerun the probe")
            requestPermission(context)
            return
        }
        val connection = usb.openDevice(device)
        if (connection == null) {
            HudLogger.e(TAG, "openDevice returned null", null)
            return
        }
        val intf = device.getInterface(0)
        try {
            HudLogger.i(TAG, "claimInterface(force)=${connection.claimInterface(intf, true)}")
            val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
            val inEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN }
            val outEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT }
            if (inEp == null || outEp == null) {
                HudLogger.w(TAG, "missing bulk endpoints (in=$inEp out=$outEp)")
                return
            }
            if (sendChangeMode) {
                val result = connection.controlTransfer(0x42, 0x00, 0, 0, byteArrayOf(1), 1, 500)
                HudLogger.i(TAG, "controlTransfer(0x42,0x00,value=0,index=0,{1})=$result")
            }
            botProbe(context, connection, outEp, inEp)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "probe failed", t)
        } finally {
            runCatching { connection.releaseInterface(intf) }
            connection.close()
            HudLogger.i(TAG, "released and closed (volume should remount)")
        }
    }

    /**
     * Talks to the DVR with the transport the vendor's `libUsbRead` uses: SCSI
     * Bulk-Only Transport over this same mass-storage interface.
     */
    private fun botProbe(
        context: Context,
        connection: android.hardware.usb.UsbDeviceConnection,
        outEp: android.hardware.usb.UsbEndpoint,
        inEp: android.hardware.usb.UsbEndpoint,
        readSeconds: Int = 8,
    ) {
        val bot = DvrBot(connection, outEp, inEp)
        bot.inquiry()?.let {
            HudLogger.i(TAG, "INQUIRY status=${bot.lastStatus} text='${String(it).trim()}' hex=${bot.hex(it)}")
        } ?: HudLogger.w(TAG, "INQUIRY failed")
        val blocks = bot.readCapacity()
        HudLogger.i(TAG, "READ CAPACITY blocks=${blocks ?: -1} status=${bot.lastStatus}")
        val lba0 = bot.read10(0, 1)
        HudLogger.i(TAG, "READ(10) LBA0 status=${bot.lastStatus} ${lba0?.let(bot::hex) ?: "null"}")

        // Map where the DVR puts the live stream while a host claims the interface.
        var streamLba = -1
        var lba = 0
        while (lba < 32768) { // first 16 MB
            val chunk = bot.read10(lba, 128) ?: break
            val sois = soiOffsets(chunk)
            if (sois.isNotEmpty() && streamLba < 0) streamLba = lba
            if (sois.isNotEmpty() || lba % 4096 == 0) {
                HudLogger.i(TAG, "lba=$lba (+${lba * 512 / 1024}KB) jpegs=${sois.size} firstAt=${sois.firstOrNull() ?: -1} head=${bot.hex(chunk, 12)}")
            }
            lba += 128
        }
        HudLogger.i(TAG, "stream starts at lba=$streamLba (+${streamLba * 512 / 1024}KB)")

        if (streamLba >= 0) {
            for (base in intArrayOf(streamLba, streamLba + 1024, streamLba + 2048, streamLba + 3072, streamLba + 4096)) {
                val head = bot.read10(base, 32) ?: continue // first 16 KB (the slot header)
                val slot = bot.read10(base, 1024) ?: continue // whole 512 KB slot
                val soi = soiOffsets(slot).firstOrNull() ?: -1
                val eoi = if (soi >= 0) findEoi(slot, soi) else -1
                HudLogger.i(
                    TAG,
                    "slot@$base head32=${bot.hex(head, 32)} " +
                        "u32@0=${u32(head, 0)} @4=${u32(head, 4)} @8=${u32(head, 8)} @12=${u32(head, 12)} @16=${u32(head, 16)}",
                )
                HudLogger.i(
                    TAG,
                    "slot@$base soi=$soi eoi=$eoi jpegBytes=${if (soi >= 0 && eoi > 0) eoi - soi else -1}",
                )
            }
            grabFrame(context, bot, streamLba)
        }

        // Does the DVR push anything on bulk IN without a command? (pure observation)
        val buffer = ByteArray(64 * 1024)
        val deadline = System.currentTimeMillis() + readSeconds * 1000L
        var reads = 0
        var total = 0
        while (System.currentTimeMillis() < deadline) {
            val n = connection.bulkTransfer(inEp, buffer, buffer.size, 1000)
            if (n <= 0) continue
            reads++
            total += n
            if (reads <= 3) HudLogger.i(TAG, "unsolicited bulk read n=$n head=${bot.hex(buffer, 24)}")
        }
        HudLogger.i(TAG, "unsolicited bulk reads=$reads bytes=$total")
    }
}
