package com.driving.hud.usbcamera.record

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Environment
import com.driving.hud.usbcamera.detect.Detection
import com.driving.hud.usbcamera.util.HudLogger
import com.driving.hud.usbcamera.util.HudPrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Records the preview to a Matroska (.mkv) file using a hardware H.264 encoder.
 *
 * Why not MediaRecorder: it cannot write Matroska, and its WebM mode needs VP8/VP9, for which
 * this SoC has no hardware encoder — software VP8 at 720p30 would cost far more CPU than the
 * detection we just optimised away. So the stream is encoded with MediaCodec and muxed here.
 *
 * Why not MJPEG passthrough (which would be free): the UVC library only exposes *converted*
 * frames to Java — its RAW callback was measured to deliver YUV (capacity = w*h*3/2, magic
 * `FC FF FE FD`), not JPEG, so the compressed bytes are not reachable from this layer.
 *
 * Threading: [submitNv21] is called on the native capture thread and must never block, so it
 * copies into a pooled buffer and hands off to a worker. When the worker falls behind, frames
 * are dropped and counted rather than stalling the camera.
 */
internal class MkvRecorder(private val context: Context) {

    internal companion object {
        const val TAG = "MkvRecorder"

        /** Frames in flight; deeper only adds latency and memory. */
        const val QUEUE_DEPTH = 3

        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Copies camera NV21/NV12 buffer into the encoder input buffer, handling row stride,
         * slice height padding, and optional chroma swapping.
         */
        internal fun convertChroma(
            src: ByteArray,
            dst: ByteArray,
            width: Int,
            height: Int,
            stride: Int,
            sliceHeight: Int,
            swapChroma: Boolean
        ) {
            val ySize = width * height
            val uvSize = width * height / 2

            if (stride == width) {
                System.arraycopy(src, 0, dst, 0, ySize)
            } else {
                for (row in 0 until height) {
                    System.arraycopy(src, row * width, dst, row * stride, width)
                    if (stride > width) {
                        java.util.Arrays.fill(dst, row * stride + width, (row + 1) * stride, 0.toByte())
                    }
                }
            }

            val srcUv = ySize
            val dstUv = stride * sliceHeight
            val rows = height / 2

            if (!swapChroma && stride == width && dstUv == ySize) {
                System.arraycopy(src, srcUv, dst, dstUv, uvSize)
            } else {
                var d = dstUv
                var s = srcUv
                for (row in 0 until rows) {
                    if (swapChroma) {
                        var x = 0
                        val rowEnd = width - 1
                        while (x < rowEnd) {
                            dst[d + x] = src[s + x + 1]
                            dst[d + x + 1] = src[s + x]
                            x += 2
                        }
                    } else {
                        System.arraycopy(src, s, dst, d, width)
                    }
                    if (stride > width) {
                        java.util.Arrays.fill(dst, d + width, d + stride, 128.toByte())
                    }
                    s += width
                    d += stride
                }
            }
        }
    }

    @Volatile
    var isRecording = false
        private set

    /** Frames dropped because the encoder could not keep up. */
    @Volatile
    var droppedFrames = 0L
        private set

    @Volatile
    var isSdCard = false
        private set

    var lastRecordingResult: RecordingStorageManager.RecordingResult? = null
        private set

    private var srtWriter: SrtWriter? = null

    @Volatile
    private var latestDetections: List<Detection> = emptyList()

    fun updateDetections(detections: List<Detection>) {
        latestDetections = detections
    }

    private var codec: MediaCodec? = null
    private var writer: MatroskaWriter? = null
    private var worker: Thread? = null

    @Volatile
    private var running = false

    private val pending = ArrayBlockingQueue<ByteArray>(QUEUE_DEPTH)
    private val free = ArrayBlockingQueue<ByteArray>(QUEUE_DEPTH)

    private var width = 0
    private var height = 0
    private var stride = 0

    /**
     * Chroma order of the buffers handed to [submit].
     *
     * The app's own conversions ([com.driving.hud.usbcamera.camera.MjpegFrameSink],
     * `SystemCameraController.yuv420ToNv21`) produce **NV21** (V first), while MediaCodec's
     * `COLOR_FormatYUV420SemiPlanar` wants **NV12** (U first). Feeding NV21 straight through swaps
     * red and blue in the recording, so NV21 sources are converted by default.
     */
    private var sourceIsNv21 = false
    private var sliceHeight = 0
    private var fps = 30
    private var frameIndex = 0L
    private var queued = 0L
    private var lastTsMs = -1L
    private var outputFile: File? = null

    /** Pooled NV12 scratch, reused per frame (never allocated on the hot path). */
    private var nv12 = ByteArray(0)

    fun start(width: Int, height: Int, fps: Int, sourceIsNv21: Boolean = false): File? {
        if (isRecording) return outputFile
        if (width <= 0 || height <= 0) return null
        this.width = width
        this.height = height
        this.sourceIsNv21 = sourceIsNv21
        this.fps = if (fps > 0) fps else 30
        droppedFrames = 0
        frameIndex = 0
        queued = 0
        lastTsMs = -1

        val name = "HUD_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mkv"
        val (dir, sdCard) = RecordingStorageManager.resolveRecordingDirectory(context)
        this.isSdCard = sdCard
        val file = File(dir, name)
        this.srtWriter = SrtWriter(file)

        val codecName = pickEncoder()
        if (codecName == null) {
            HudLogger.e(TAG, "no H.264 encoder with semi-planar input on this device")
            return null
        }

        val bitRate = (width * height * this.fps * 0.1).toInt().coerceIn(1_000_000, 12_000_000)
        val c = try {
            MediaCodec.createByCodecName(codecName)
        } catch (t: Throwable) {
            HudLogger.e(TAG, "encoder '$codecName' unavailable", t)
            return null
        }

        return try {
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, this@MkvRecorder.fps)
                // A keyframe every second keeps the file seekable and playback robust.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            c.start()

            // The encoder may only accept padded rows; ask what it wants once and honour it.
            stride = try {
                c.inputFormat.getInteger(MediaFormat.KEY_STRIDE).takeIf { it > 0 } ?: width
            } catch (_: Throwable) {
                width
            }
            sliceHeight = try {
                c.inputFormat.getInteger(MediaFormat.KEY_SLICE_HEIGHT).takeIf { it > 0 } ?: height
            } catch (_: Throwable) {
                height
            }

            codec = c
            writer = MatroskaWriter(file)
            outputFile = file
            nv12 = ByteArray(stride * sliceHeight * 3 / 2)
            free.clear()
            pending.clear()
            repeat(QUEUE_DEPTH) { free.offer(ByteArray(width * height * 3 / 2)) }

            running = true
            isRecording = true
            worker = Thread({ runLoop() }, "mkv-recorder").apply {
                isDaemon = true
                start()
            }
            HudLogger.i(
                TAG,
                "recording started: ${file.absolutePath} ${width}x$height @${this.fps}fps " +
                    "codec=$codecName bitrate=$bitRate stride=$stride slice=$sliceHeight"
            )
            file
        } catch (t: Throwable) {
            HudLogger.e(TAG, "failed to start recorder", t)
            try {
                c.stop()
            } catch (_: Throwable) {
            }
            try {
                c.release()
            } catch (_: Throwable) {
            }
            isRecording = false
            null
        }
    }

    /**
     * Hands the current camera buffer to the recorder. Called on the capture thread, so it must
     * not block: the bytes are copied into a pooled buffer and the frame is dropped (and
     * counted) if the encoder is behind.
     */
    fun submit(buffer: java.nio.ByteBuffer, length: Int) {
        if (!isRecording) return
        val expected = width * height * 3 / 2
        if (length < expected) return
        val buf = free.poll() ?: run {
            droppedFrames++
            return
        }
        try {
            buffer.rewind()
            if (buffer.remaining() < expected) {
                free.offer(buf)
                return
            }
            buffer.get(buf, 0, expected)
        } catch (t: Throwable) {
            free.offer(buf)
            droppedFrames++
            return
        }
        if (!pending.offer(buf)) {
            free.offer(buf)
            droppedFrames++
        }
    }

    /** Stops the encoder, finalises the container and returns the file. */
    fun stop(): File? {
        if (!isRecording) return outputFile
        isRecording = false
        running = false
        try {
            worker?.join(3000)
        } catch (_: InterruptedException) {
        }
        worker = null

        val durationMs = if (fps > 0) (frameIndex * 1_000L) / fps else 0L
        srtWriter?.close(durationMs)
        srtWriter = null

        val f = outputFile
        val w = writer
        val c = codec
        codec = null
        writer = null
        outputFile = null

        try {
            c?.stop()
        } catch (_: Throwable) {
        }
        try {
            c?.release()
        } catch (_: Throwable) {
        }
        val file = try {
            w?.close() ?: f
        } catch (t: Throwable) {
            HudLogger.e(TAG, "failed to finalise mkv", t)
            f
        }
        if (file != null) {
            lastRecordingResult = RecordingStorageManager.publishRecording(context, file, isSdCard)
        }
        HudLogger.i(
            TAG,
            "recording stopped: ${file?.absolutePath} size=${file?.length() ?: 0} bytes " +
                "frames=$queued dropped=$droppedFrames sdCard=$isSdCard"
        )
        return file
    }

    // ---------------------------------------------------------------- worker

    private fun runLoop() {
        try {
            while (running || pending.isNotEmpty()) {
                val frame = pending.poll(50, TimeUnit.MILLISECONDS)
                if (frame != null) {
                    feed(frame)
                    free.offer(frame)
                }
                drain(endOfStream = false)
            }
            // Flush the encoder so the tail of the recording is not lost.
            val c = codec
            if (c != null) {
                val idx = c.dequeueInputBuffer(10_000)
                if (idx >= 0) {
                    c.queueInputBuffer(idx, 0, 0, nextPts(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                }
                var guard = 0
                while (guard++ < 50) {
                    if (drain(endOfStream = true)) break
                    Thread.sleep(10)
                }
            }
        } catch (t: Throwable) {
            HudLogger.e(TAG, "recorder worker failed", t)
        }
    }

    private fun nextPts(): Long = (frameIndex * 1_000_000L) / fps

    private fun feed(nv21: ByteArray) {
        val c = codec ?: return
        val idx = try {
            c.dequeueInputBuffer(10_000)
        } catch (t: Throwable) {
            HudLogger.w(TAG, "dequeueInputBuffer failed: ${t.message}")
            return
        }
        if (idx < 0) {
            droppedFrames++
            return
        }
        val buffer = c.getInputBuffer(idx) ?: return
        val dets = latestDetections
        if (dets.isNotEmpty()) {
            if (HudPrefs.recordOverlay(context)) {
                Nv21OverlayRenderer.render(nv21, width, height, dets)
            }
            val ptsMs = if (fps > 0) (frameIndex * 1_000L) / fps else 0L
            srtWriter?.onDetections(ptsMs, dets)
        }
        convertToNv12(nv21)
        buffer.clear()
        buffer.put(nv12, 0, nv12.size)
        c.queueInputBuffer(idx, 0, nv12.size, nextPts(), 0)
        frameIndex++
        queued++
    }

    /** Converts and aligns camera buffer with encoder format, respecting user chroma preference. */
    private fun convertToNv12(nv21: ByteArray) {
        // Default: swap when the source is NV21 (the app's own convention) and when the user asked
        // for the opposite (OEM encoders that disagree).
        val swap = HudPrefs.swapRecordChroma(context) != sourceIsNv21
        convertChroma(nv21, nv12, width, height, stride, sliceHeight, swap)
    }

    /** @return true once the end-of-stream flag has been seen (only meaningful when draining). */
    private fun drain(endOfStream: Boolean): Boolean {
        val c = codec ?: return true
        val info = MediaCodec.BufferInfo()
        var sawEos = false
        while (true) {
            val idx = try {
                c.dequeueOutputBuffer(info, 0)
            } catch (t: Throwable) {
                HudLogger.w(TAG, "dequeueOutputBuffer failed: ${t.message}")
                return true
            }
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return sawEos
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> openWriter(c.outputFormat)
                idx >= 0 -> {
                    if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        val buf = c.getOutputBuffer(idx)
                        if (buf != null) {
                            val data = ByteArray(info.size)
                            buf.position(info.offset)
                            buf.get(data)
                            val ptsMs = monotonicMs(info.presentationTimeUs)
                            writer?.writeFrame(
                                H264AnnexB.toAvcc(data, data.size),
                                ptsMs,
                                (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                            )
                        }
                    }
                    c.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawEos = true
                        if (endOfStream) return true
                    }
                }
            }
        }
    }

    /** Matroska block timestamps must not go backwards (or repeat). */
    private fun monotonicMs(ptsUs: Long): Long {
        var ms = ptsUs / 1000
        if (ms <= lastTsMs) ms = lastTsMs + 1
        lastTsMs = ms
        return ms
    }

    /** Builds CodecPrivate from the encoder's SPS/PPS and opens the container. */
    private fun openWriter(format: MediaFormat) {
        val csd0 = format.getByteBuffer("csd-0")?.let { b ->
            ByteArray(b.remaining()).also { arr ->
                b.get(arr)
            }
        }
        val csd1 = format.getByteBuffer("csd-1")?.let { b ->
            ByteArray(b.remaining()).also { arr ->
                b.get(arr)
            }
        }

        val codecPrivate: ByteArray? = when {
            csd0 == null -> null
            H264AnnexB.looksLikeAvcC(csd0) -> csd0
            else -> {
                // SPS and PPS normally arrive in separate buffers (csd-0 / csd-1).
                val sps = H264AnnexB.firstNalOfType(csd0, csd0.size, 7)
                val pps = csd1?.let { H264AnnexB.firstNalOfType(it, it.size, 8) }
                    ?: H264AnnexB.firstNalOfType(csd0, csd0.size, 8)
                if (sps != null && pps != null) H264AnnexB.avcC(sps, pps) else null
            }
        }

        if (codecPrivate == null) {
            HudLogger.e(TAG, "could not build AVC decoder configuration — not muxing")
            return
        }
        try {
            writer?.open(width, height, codecPrivate, fps)
            HudLogger.i(
                TAG,
                "mkv track opened: ${width}x$height avcC=${codecPrivate.size}B " +
                    "csd0=${csd0?.size ?: 0}B csd1=${csd1?.size ?: 0}B"
            )
        } catch (t: Throwable) {
            HudLogger.e(TAG, "failed to open mkv track", t)
        }
    }

    /** Prefers a hardware H.264 encoder that accepts semi-planar input. */
    private fun pickEncoder(): String? {
        var software: String? = null
        val list = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS)
        } catch (t: Throwable) {
            return null
        }
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            if (!info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }) continue
            val caps = try {
                info.getCapabilitiesForType(MIME)
            } catch (_: Throwable) {
                continue
            }
            if (!caps.colorFormats.contains(
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                )
            ) {
                continue
            }
            val name = info.name
            val isSoftware = name.startsWith("OMX.google") || name.startsWith("c2.android")
            if (!isSoftware) return name
            if (software == null) software = name
        }
        return software
    }
}
