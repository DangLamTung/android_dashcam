package com.driving.hud.usbcamera

import com.driving.hud.usbcamera.alert.ClosingRateFilter
import com.driving.hud.usbcamera.calib.GroundPlaneCalibration
import com.driving.hud.usbcamera.record.H264AnnexB
import com.driving.hud.usbcamera.ui.VideoFit
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
}
