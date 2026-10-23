package com.driving.hud.usbcamera

import com.driving.hud.usbcamera.alert.ClosingRateFilter
import com.driving.hud.usbcamera.calib.GroundPlaneCalibration
import com.driving.hud.usbcamera.camera.DvrFrameParser
import com.driving.hud.usbcamera.record.H264AnnexB
import com.driving.hud.usbcamera.ui.VideoFit
import com.driving.hud.usbcamera.util.ProviderMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudUnitTests {

    @Test
    fun testClosingRateFilterZeroTimestamp() {
        val filter = ClosingRateFilter(capacity = 6)
        // Ensure starting at 0L works properly and does not get skipped
        filter.add(0L, 20f)
        filter.add(500L, 15f)
        val rate = filter.rate()
        assertNotNull(rate)
        // distance changed from 20 to 15 (-5m) over 0.5s -> -10 m/s
        assertEquals(-10f, rate!!, 0.01f)
    }

    @Test
    fun testClosingRateFilterGapReset() {
        val filter = ClosingRateFilter(capacity = 6, maxGapMs = 1000L)
        filter.add(1000L, 30f)
        filter.add(1500L, 25f)
        // Big gap
        filter.add(4000L, 10f)
        // Only 1 sample in new continuous window
        assertNull(filter.rate())
        filter.add(4500L, 5f)
        val rate = filter.rate()
        assertNotNull(rate)
        assertEquals(-10f, rate!!, 0.01f)
    }

    @Test
    fun testGroundPlaneCalibration() {
        val calib = GroundPlaneCalibration(
            frameW = 1280,
            frameH = 720,
            cameraHeightM = 1.2f,
            pitchDeg = 8f,
            horizontalFovDeg = 65f
        )
        // Image center row (360) distance should be positive and finite
        val centerDist = calib.centreDistance()
        assertNotNull(centerDist)
        assertTrue(centerDist!! > 0f)

        // Pixel row near bottom (e.g. 700) should be much closer than center
        val bottomDist = calib.distanceForRow(700f)
        assertNotNull(bottomDist)
        assertTrue(bottomDist!! < centerDist)

        // Horizon or sky rows (e.g. 100f) should return null (at or above horizon)
        val skyDist = calib.distanceForRow(50f)
        assertNull(skyDist)
    }

    @Test
    fun testH264AnnexBToAvcc() {
        // Annex-B sample: 00 00 00 01 followed by 4 bytes payload
        val nalu = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65, 0x11, 0x22, 0x33)
        val avcc = H264AnnexB.toAvcc(nalu, nalu.size)
        // Expect 4-byte length prefix (0x00, 0x00, 0x00, 0x04) followed by 4 payload bytes
        assertEquals(8, avcc.size)
        assertEquals(0.toByte(), avcc[0])
        assertEquals(0.toByte(), avcc[1])
        assertEquals(0.toByte(), avcc[2])
        assertEquals(4.toByte(), avcc[3])
        assertEquals(0x65.toByte(), avcc[4])
    }

    @Test
    fun testPinholeDistanceEstimatorCarProjection() {
        val estimator = com.driving.hud.usbcamera.calib.PinholeDistanceEstimator(
            frameW = 1280,
            frameH = 720,
            cameraHeightM = 1.20f,
            pitchDeg = 8.0f,
            horizontalFovDeg = 65.0f
        )

        // At 10m distance, a car (W = 1.82m) should project to:
        // w_px = (focalPx * 1.82) / 10.0
        val expectedWidthPx = (estimator.focalPx * 1.82f) / 10.0f
        val expectedHeightPx = (estimator.focalPx * 1.48f) / 10.0f

        val prior = com.driving.hud.usbcamera.calib.PinholeDistanceEstimator.priorForLabel("car")
        val computedDist = estimator.pinholeDistance(expectedWidthPx, expectedHeightPx, prior)

        assertEquals(10.0f, computedDist, 0.05f)
    }

    @Test
    fun testPinholeTrueDistanceEstimation() {
        val estimator = com.driving.hud.usbcamera.calib.PinholeDistanceEstimator(
            frameW = 1280,
            frameH = 720,
            cameraHeightM = 1.20f,
            pitchDeg = 8.0f,
            horizontalFovDeg = 65.0f
        )

        // Target at 15m
        val targetZ = 15.0f
        val carPrior = com.driving.hud.usbcamera.calib.PinholeDistanceEstimator.priorForLabel("car")
        val wPx = (estimator.focalPx * carPrior.realWidthM) / targetZ
        val hPx = (estimator.focalPx * carPrior.realHeightM) / targetZ

        val bottomRow = estimator.rowForDistance(targetZ)
        assertNotNull(bottomRow)

        val normBottom = bottomRow!! / 720f
        val normTop = (bottomRow - hPx) / 720f
        val normLeft = (640f - wPx / 2f) / 1280f
        val normRight = (640f + wPx / 2f) / 1280f

        val det = com.driving.hud.usbcamera.detect.Detection(
            label = "car",
            score = 0.92f,
            left = normLeft,
            top = normTop,
            right = normRight,
            bottom = normBottom
        )

        val estimate = estimator.estimate(det)
        assertTrue(estimate.isGroundValid)
        assertEquals(targetZ, estimate.trueDistanceM, 0.25f)
        assertEquals(0.0f, estimate.lateralOffsetM, 0.1f)
    }

    @Test
    fun testAutoPitchSolver() {
        val estimator = com.driving.hud.usbcamera.calib.PinholeDistanceEstimator(
            frameW = 1280,
            frameH = 720,
            cameraHeightM = 1.20f,
            pitchDeg = 8.0f,
            horizontalFovDeg = 65.0f
        )

        // For a known target distance of 10.0m, find the row it corresponds to with pitch = 8.0 deg
        val row10m = estimator.rowForDistance(10.0f)
        assertNotNull(row10m)

        // Now pretend pitch was unknown, and solve pitch using targetDistance = 10.0m and contact row
        val solvedPitch = estimator.solvePitchForTarget(10.0f, row10m!!)
        assertEquals(8.0f, solvedPitch, 0.01f)
    }

    @Test
    fun testNv21OverlayRendererDoesNotCrashAndModifiesBuffer() {
        val w = 640
        val h = 480
        val buffer = ByteArray(w * h * 3 / 2) // NV21 zero-initialized
        val detections = listOf(
            com.driving.hud.usbcamera.detect.Detection(
                label = "car · 15m TTC 2.5s",
                score = 0.95f,
                left = 0.2f,
                top = 0.3f,
                right = 0.6f,
                bottom = 0.7f
            )
        )

        // Ensure initially zero
        val testYIndex = (0.3f * h).toInt() * w + (0.2f * w).toInt()
        assertEquals(0.toByte(), buffer[testYIndex])

        com.driving.hud.usbcamera.record.Nv21OverlayRenderer.render(buffer, w, h, detections)

        // After rendering, the pixel on the bounding box border should be modified (non-zero)
        assertTrue(buffer[testYIndex] != 0.toByte())
    }

    @Test
    fun testSrtWriterOutputsValidCues() {
        val tempFile = java.io.File.createTempFile("test_rec", ".mkv")
        try {
            val srt = com.driving.hud.usbcamera.record.SrtWriter(tempFile)
            val dets = listOf(
                com.driving.hud.usbcamera.detect.Detection(
                    label = "CAR · 12m",
                    score = 0.88f,
                    left = 0.1f,
                    top = 0.2f,
                    right = 0.4f,
                    bottom = 0.5f
                )
            )
            srt.onDetections(1000L, dets)
            srt.close(2500L)

            assertTrue(srt.srtFile.exists())
            val content = srt.srtFile.readText()
            assertTrue(content.contains("00:00:01,000 --> 00:00:02,500"))
            assertTrue(content.contains("CAR · 12M [88%]"))
            srt.srtFile.delete()
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testCameraModeConstants() {
        assertEquals("usb", com.driving.hud.usbcamera.util.HudPrefs.MODE_USB)
        assertEquals("system", com.driving.hud.usbcamera.util.HudPrefs.MODE_SYSTEM)
        assertEquals("wifi", com.driving.hud.usbcamera.util.HudPrefs.MODE_WIFI)
        assertEquals("back", com.driving.hud.usbcamera.util.HudPrefs.FACING_BACK)
        assertEquals("front", com.driving.hud.usbcamera.util.HudPrefs.FACING_FRONT)
    }

    @Test
    fun testConvertChromaPassthroughDirect() {
        val w = 4
        val h = 4
        val src = ByteArray(w * h * 3 / 2)
        // Fill Y with 1s
        for (i in 0 until 16) src[i] = 1
        // Fill UV with [10, 20, 30, 40, 50, 60, 70, 80]
        val chroma = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        System.arraycopy(chroma, 0, src, 16, 8)

        val dst = ByteArray(w * h * 3 / 2)
        com.driving.hud.usbcamera.record.MkvRecorder.convertChroma(
            src = src,
            dst = dst,
            width = w,
            height = h,
            stride = w,
            sliceHeight = h,
            swapChroma = false
        )

        // Verify Y unchanged
        for (i in 0 until 16) assertEquals(1.toByte(), dst[i])
        // Verify UV unswapped (correct color preservation)
        for (i in 0 until 8) assertEquals(chroma[i], dst[16 + i])
    }

    @Test
    fun testConvertChromaSwapped() {
        val w = 4
        val h = 4
        val src = ByteArray(w * h * 3 / 2)
        val chroma = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        System.arraycopy(chroma, 0, src, 16, 8)

        val dst = ByteArray(w * h * 3 / 2)
        com.driving.hud.usbcamera.record.MkvRecorder.convertChroma(
            src = src,
            dst = dst,
            width = w,
            height = h,
            stride = w,
            sliceHeight = h,
            swapChroma = true
        )

        val expected = byteArrayOf(20, 10, 40, 30, 60, 50, 80, 70)
        for (i in 0 until 8) assertEquals(expected[i], dst[16 + i])
    }

    @Test
    fun testConvertChromaWithStridePadding() {
        val w = 4
        val h = 4
        val stride = 6
        val sliceH = 4
        val src = ByteArray(w * h * 3 / 2)
        val chroma = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        System.arraycopy(chroma, 0, src, 16, 8)

        val dst = ByteArray(stride * sliceH * 3 / 2)
        com.driving.hud.usbcamera.record.MkvRecorder.convertChroma(
            src = src,
            dst = dst,
            width = w,
            height = h,
            stride = stride,
            sliceHeight = sliceH,
            swapChroma = false
        )

        val dstUv = stride * sliceH
        // Row 0 of chroma: bytes 0..3 should be 10, 20, 30, 40; bytes 4..5 should be padded with 128
        assertEquals(10.toByte(), dst[dstUv + 0])
        assertEquals(20.toByte(), dst[dstUv + 1])
        assertEquals(30.toByte(), dst[dstUv + 2])
        assertEquals(40.toByte(), dst[dstUv + 3])
        assertEquals(128.toByte(), dst[dstUv + 4])
        assertEquals(128.toByte(), dst[dstUv + 5])

        // Row 1 of chroma: bytes 6..9 should be 50, 60, 70, 80; bytes 10..11 should be padded with 128
        assertEquals(50.toByte(), dst[dstUv + stride + 0])
        assertEquals(60.toByte(), dst[dstUv + stride + 1])
        assertEquals(70.toByte(), dst[dstUv + stride + 2])
        assertEquals(80.toByte(), dst[dstUv + stride + 3])
        assertEquals(128.toByte(), dst[dstUv + stride + 4])
        assertEquals(128.toByte(), dst[dstUv + stride + 5])
    }

    @Test
    fun testSystemCameraChooseOptimalSizeExactMatch() {
        val sizes = listOf(
            1920 to 1080,
            1280 to 720,
            640 to 480
        )
        val (w, h) = com.driving.hud.usbcamera.camera.SystemCameraController.chooseOptimalDimension(
            primaryChoices = sizes,
            fallbackChoices = null,
            targetW = 1280,
            targetH = 720
        )
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun testSystemCameraChooseOptimalSizeAspectMatch() {
        // Requested 960x540 (16:9), but camera only has 1920x1080, 1280x720, and 640x480 (4:3)
        val sizes = listOf(
            1920 to 1080,
            1280 to 720,
            640 to 480
        )
        val (w, h) = com.driving.hud.usbcamera.camera.SystemCameraController.chooseOptimalDimension(
            primaryChoices = sizes,
            fallbackChoices = null,
            targetW = 960,
            targetH = 540
        )
        // Should choose closest 16:9 match, which is 1280x720
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun testSystemCameraChooseOptimalSizeFallback() {
        val fallback = listOf(
            640 to 480
        )
        val (w, h) = com.driving.hud.usbcamera.camera.SystemCameraController.chooseOptimalDimension(
            primaryChoices = null,
            fallbackChoices = fallback,
            targetW = 1280,
            targetH = 720
        )
        assertEquals(640, w)
        assertEquals(480, h)
    }

    /**
     * Regression for the system-camera black preview: the preview (SurfaceHolder) and analysis
     * (YUV_420_888) streams advertise different size lists. Sizing the analysis ImageReader from
     * the preview list alone asks for a surface the device cannot produce, the combined session
     * fails to configure, and no video appears.
     */
    @Test
    fun testSystemCameraPrefersSizeSupportedByBothStreams() {
        // A device that can preview 1080p but only analyse up to 720p — a common layout.
        val preview = listOf(1920 to 1080, 1280 to 720, 640 to 480)
        val analysis = listOf(1280 to 720, 640 to 480)

        val usable = com.driving.hud.usbcamera.camera.SystemCameraController
            .commonOutputSizes(preview, analysis)
        assertNotNull(usable)
        assertEquals(listOf(1280 to 720, 640 to 480), usable)

        // Asking for 1080p must therefore land on 720p, never on the preview-only 1920x1080.
        val (w, h) = com.driving.hud.usbcamera.camera.SystemCameraController.chooseOptimalDimension(
            primaryChoices = usable,
            fallbackChoices = null,
            targetW = 1920,
            targetH = 1080
        )
        assertEquals(1280, w)
        assertEquals(720, h)
    }

    @Test
    fun testSystemCameraCommonSizesWhenListsDisagreeCompletely() {
        val usable = com.driving.hud.usbcamera.camera.SystemCameraController
            .commonOutputSizes(listOf(1920 to 1080), listOf(640 to 480))
        assertNotNull(usable)
        assertTrue(usable!!.isEmpty())
    }

    @Test
    fun testSystemCameraCommonSizesWithoutCapabilities() {
        // No stream configuration map: the caller falls back to the requested size.
        assertNull(
            com.driving.hud.usbcamera.camera.SystemCameraController
                .commonOutputSizes(null, listOf(1280 to 720))
        )
        assertNull(
            com.driving.hud.usbcamera.camera.SystemCameraController
                .commonOutputSizes(emptyList(), emptyList())
        )
    }

    // ---------------------------------------------------------------- U2 DVR

    /** Builds a slot exactly the way the DVR lays it out: header, then one JPEG. */
    private fun dvrSlot(framePayload: ByteArray, frameSizeField: Int? = null, magic: Boolean = true): ByteArray {
        val slot = ByteArray(DvrFrameParser.FRAME_OFFSET + framePayload.size + 64)
        if (magic) {
            // 00 08 00 00, then the offset hint, then the frame size at offset 12.
            slot[1] = 0x08
            slot[6] = 0x22
            slot[7] = 0x00.toByte()
        }
        val size = frameSizeField ?: framePayload.size
        slot[12] = (size and 0xFF).toByte()
        slot[13] = ((size shr 8) and 0xFF).toByte()
        slot[14] = ((size shr 16) and 0xFF).toByte()
        slot[15] = ((size shr 24) and 0xFF).toByte()
        System.arraycopy(framePayload, 0, slot, DvrFrameParser.FRAME_OFFSET, framePayload.size)
        return slot
    }

    @Test
    fun testDvrSlotHeaderDetection() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertTrue(DvrFrameParser.looksLikeSlotHeader(dvrSlot(jpeg)))
        // A zeroed 512 KB region is what the ring shows between writes.
        assertTrue(!DvrFrameParser.looksLikeSlotHeader(ByteArray(512 * 1024)))
        // Garbage without the magic is not a slot even if it happens to contain a JPEG.
        assertTrue(!DvrFrameParser.looksLikeSlotHeader(dvrSlot(jpeg, magic = false)))
        // Too short to hold a header at all.
        assertTrue(!DvrFrameParser.looksLikeSlotHeader(ByteArray(1024)))
    }

    @Test
    fun testDvrFrameExtractionUsesJpegMarkersNotHeaderFields() {
        val payload = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46,
            0x11, 0x22, 0x33, 0xFF.toByte(), 0xD9.toByte()
        )
        // Deliberately wrong size field: the markers must win.
        val slot = dvrSlot(payload, frameSizeField = 4096)
        val frame = DvrFrameParser.extractFrame(slot)
        assertEquals(payload.size, frame!!.size)
        assertEquals(0xFF, frame[0].toInt() and 0xFF)
        assertEquals(0xD8, frame[1].toInt() and 0xFF)
        assertEquals(0xD9, frame[frame.size - 1].toInt() and 0xFF)
        assertEquals(DvrFrameParser.FRAME_OFFSET, DvrFrameParser.firstSoi(slot))
    }

    @Test
    fun testDvrFrameExtractionRejectsIncompleteFrames() {
        // SOI but no EOI: the DVR is mid-write, so the frame must be dropped.
        val truncated = ByteArray(DvrFrameParser.FRAME_OFFSET + 4096)
        System.arraycopy(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()), 0, truncated, DvrFrameParser.FRAME_OFFSET, 3)
        assertNull(DvrFrameParser.extractFrame(truncated))
        assertNull(DvrFrameParser.extractFrame(ByteArray(512 * 1024)))
    }

    // ------------------------------------------------------- recording chroma

    @Test
    fun testRecorderChromaConversionMatchesEncoderOrder() {
        val w = 4
        val h = 2
        val ySize = w * h
        val uvBytes = w * h / 2
        // NV21 source: Y plane, then interleaved V,U.
        val src = ByteArray(ySize + uvBytes)
        for (i in 0 until ySize) src[i] = 0x40
        src[ySize] = 111 // V0
        src[ySize + 1] = 22 // U0
        src[ySize + 2] = 33 // V1
        src[ySize + 3] = 44 // U1

        // swap = true turns the app's NV21 into the NV12 MediaCodec wants (U first).
        val nv12 = ByteArray(ySize + uvBytes)
        com.driving.hud.usbcamera.record.MkvRecorder.convertChroma(src, nv12, w, h, w, h, true)
        assertEquals(22, nv12[ySize].toInt() and 0xFF)
        assertEquals(111, nv12[ySize + 1].toInt() and 0xFF)
        assertEquals(44, nv12[ySize + 2].toInt() and 0xFF)
        assertEquals(33, nv12[ySize + 3].toInt() and 0xFF)

        // swap = false is the straight copy used for buffers that already match the encoder.
        val copied = ByteArray(ySize + uvBytes)
        com.driving.hud.usbcamera.record.MkvRecorder.convertChroma(src, copied, w, h, w, h, false)
        assertEquals(111, copied[ySize].toInt() and 0xFF)
        assertEquals(22, copied[ySize + 1].toInt() and 0xFF)
    }

    @Test
    fun testRecorderChromaConversionPadsStride() {
        val w = 4
        val h = 2
        val stride = 8
        val sliceHeight = h
        val src = ByteArray(w * h + w * h / 2)
        src[w * h] = 111
        src[w * h + 1] = 22
        val dst = ByteArray(stride * sliceHeight + stride * sliceHeight / 2)
        com.driving.hud.usbcamera.record.MkvRecorder.convertChroma(src, dst, w, h, stride, sliceHeight, true)
        // Chroma rows are padded to the stride, so the U/V pair lands at the row start.
        assertEquals(22, dst[stride * sliceHeight].toInt() and 0xFF)
        assertEquals(111, dst[stride * sliceHeight + 1].toInt() and 0xFF)
        assertEquals(128, dst[stride * sliceHeight + w].toInt() and 0xFF) // padding byte
    }

    @Test
    fun testDvrReadBlocksAndSizeHint() {
        // 64504-byte frame at offset 8704 needs (8704 + 64504 + 1024) / 512 = 145 blocks.
        assertEquals(145, DvrFrameParser.blocksFor(8704, 64504, 1024))
        // Never more than the slot: 8704 + 600000 + 1024 would need 1191 blocks, capped to 1024.
        assertEquals(1024, DvrFrameParser.blocksFor(8704, 600_000, 1024))
        // Small but never zero: header fetch plus slack, even for an implausible header.
        assertEquals(3, DvrFrameParser.blocksFor(0, 0, 1024))
        val header = dvrSlot(ByteArray(2048), frameSizeField = 64504)
        assertEquals(64504, DvrFrameParser.frameSizeHint(header))
    }

    @Test
    fun testDvrSingleTransferReadCoversAndFallsBack() {
        // The streaming loop now reads a frame in ONE transfer, sized from the previous frame's
        // header hint, instead of a 16 KB header read plus a ~76 KB body read.
        val jpeg = ByteArray(4096)
        jpeg[0] = 0xFF.toByte()
        jpeg[1] = 0xD8.toByte()
        jpeg[2] = 0xFF.toByte()
        jpeg[jpeg.size - 2] = 0xFF.toByte()
        jpeg[jpeg.size - 1] = 0xD9.toByte()
        val slot = dvrSlot(jpeg, frameSizeField = jpeg.size)

        val oneTransfer = DvrFrameParser.blocksForKnownSize(jpeg.size, 1024) * 512
        assertTrue(oneTransfer >= DvrFrameParser.FRAME_OFFSET + jpeg.size)
        assertEquals(jpeg.size, DvrFrameParser.extractFrame(slot.copyOf(oneTransfer))!!.size)

        // A frame that grew past the hint's 1 KB of slack is cut off by that same read ...
        val bigger = ByteArray(8192)
        bigger[0] = 0xFF.toByte()
        bigger[1] = 0xD8.toByte()
        bigger[2] = 0xFF.toByte()
        bigger[bigger.size - 2] = 0xFF.toByte()
        bigger[bigger.size - 1] = 0xD9.toByte()
        val bigSlot = dvrSlot(bigger, frameSizeField = bigger.size)
        val cut = bigSlot.copyOf(oneTransfer)
        assertNull(DvrFrameParser.extractFrame(cut))

        // ... so the caller's fallback, sized from the hint inside that very read, recovers it.
        val fallback = DvrFrameParser.blocksFor(
            DvrFrameParser.FRAME_OFFSET,
            DvrFrameParser.frameSizeHint(cut),
            1024
        ) * 512
        assertTrue(fallback > oneTransfer)
        assertEquals(bigger.size, DvrFrameParser.extractFrame(bigSlot.copyOf(fallback))!!.size)
    }

    @Test
    fun testDvrBotChunkingMath() {
        val totalBytes = 128 * 512 // 65536 bytes (typical 128-block frame read)
        val chunkSize = 16384 // MAX_USBFS_BUFFER_SIZE
        var received = 0
        val chunks = mutableListOf<Int>()
        while (received < totalBytes) {
            val toRead = minOf(totalBytes - received, chunkSize)
            chunks.add(toRead)
            received += toRead
        }
        assertEquals(4, chunks.size)
        assertTrue(chunks.all { it <= chunkSize })
        assertEquals(totalBytes, chunks.sum())
    }

    // --------------------------------------------------- remembered detector backend

    @Test
    fun testProviderMemoryKeepsOneEntryPerFrameSize() {
        var store = ProviderMemory.put("", "yolov10n-224.onnx@1280x720", "ncnn/Vulkan")
        assertEquals("ncnn/Vulkan", ProviderMemory.lookup(store, "yolov10n-224.onnx@1280x720"))

        // The DVR streams 1280x720 while a phone's system camera may offer 1920x1080. Each keeps
        // its own answer, which is the whole point: the old single pair made every switch between
        // two sizes re-run the multi-second backend benchmark.
        store = ProviderMemory.put(store, "yolov10n-224.onnx@1920x1080", "onnx(auto)")
        assertEquals("ncnn/Vulkan", ProviderMemory.lookup(store, "yolov10n-224.onnx@1280x720"))
        assertEquals("onnx(auto)", ProviderMemory.lookup(store, "yolov10n-224.onnx@1920x1080"))

        // Re-measuring a key replaces its entry and moves it to the front.
        store = ProviderMemory.put(store, "yolov10n-224.onnx@1280x720", "ncnn/CPU")
        assertEquals("ncnn/CPU", ProviderMemory.lookup(store, "yolov10n-224.onnx@1280x720"))
        assertTrue(store.startsWith("yolov10n-224.onnx@1280x720=ncnn/CPU"))

        // Unknown keys, junk lines and empty input are simply absent.
        assertEquals("", ProviderMemory.lookup(store, "yolov10n-320.onnx@1280x720"))
        assertEquals("", ProviderMemory.lookup("junk\nnokey", "x"))
        assertEquals("", ProviderMemory.lookup(store, ""))

        // The store is bounded, and the oldest entry is the one dropped.
        var bounded = ""
        for (i in 1..9) bounded = ProviderMemory.put(bounded, "model$i@1280x720", "provider$i")
        assertEquals(ProviderMemory.MAX_ENTRIES, bounded.split('\n').size)
        assertEquals("", ProviderMemory.lookup(bounded, "model1@1280x720"))
        assertEquals("provider9", ProviderMemory.lookup(bounded, "model9@1280x720"))
    }
}
