package com.driving.hud.usbcamera.camera

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.driving.hud.usbcamera.util.HudLogger
import java.nio.ByteBuffer

/**
 * Decodes MJPEG frames and renders them to a Surface.
 *
 * Prefers MediaCodec ("video/mjpeg") for speed, but many budget ROMs (e.g.
 * Unisoc/itel) ship WITHOUT the MJPEG codec (createDecoderByType throws
 * NAME_NOT_FOUND). In that case it falls back to software BitmapFactory decode
 * (always present) drawn straight onto the surface.
 */
class MjpegDecoder(private val tag: String) {

    private var codec: MediaCodec? = null
    private var surface: Surface? = null
    private val bufferInfo = MediaCodec.BufferInfo()
    private var useSoftware = false
    private var mirrored = false
    private var loggedDims = false

    // True once start() has been requested. The pipeline is usually started
    // BEFORE the HUD SurfaceView has a surface (the camera connects on the home
    // screen, where the HUD panel — and its Surface — is GONE). start() then
    // cannot create the codec, so attachSurface() must finish the job when the
    // surface finally arrives; otherwise the HUD stays black forever.
    private var started = false

    /** (Re)attaches the output surface; (re)creates the codec as needed. */
    @Synchronized
    fun attachSurface(newSurface: Surface?) {
        val previous = surface
        surface = newSurface
        when {
            newSurface == null -> {
                // Surface went away; tear down so we can start clean next time.
                if (codec != null) {
                    HudLogger.i(tag, "Surface destroyed — releasing MJPEG codec")
                    releaseLocked()
                }
            }
            codec != null && previous !== newSurface -> {
                HudLogger.i(tag, "Surface changed mid-stream, recreating MJPEG codec")
                releaseLocked()
                startLocked(newSurface)
            }
            codec == null && !useSoftware && started -> {
                // start() ran before a surface existed — start the decoder now.
                HudLogger.i(tag, "Surface arrived after start() — starting MJPEG decoder now")
                startLocked(newSurface)
            }
        }
    }

    @Synchronized
    fun setMirror(mirror: Boolean) {
        mirrored = mirror
    }

    @Synchronized
    fun start(surface: Surface?) {
        this.surface = surface
        started = true
        releaseLocked()
        startLocked(surface)
    }

    private fun startLocked(surface: Surface?) {
        if (surface == null) {
            HudLogger.w(tag, "MJPEG decoder start skipped: no surface yet")
            return
        }
        try {
            val format = MediaFormat.createVideoFormat("video/mjpeg", 1280, 720)
            val c = MediaCodec.createDecoderByType("video/mjpeg")
            c.configure(format, surface, null, 0)
            c.start()
            codec = c
            useSoftware = false
            HudLogger.i(tag, "MJPEG MediaCodec started (surface=$surface)")
        } catch (t: Throwable) {
            HudLogger.w(tag, "MediaCodec video/mjpeg unavailable (${t.message}) — using BitmapFactory software decode")
            codec = null
            useSoftware = true
        }
    }

    /** Feeds one complete MJPEG frame. Must be called from the streaming thread. */
    @Synchronized
    fun feedFrame(frame: ByteArray) {
        if (useSoftware) {
            renderSoftware(frame)
            return
        }
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(20000)
            if (inIdx >= 0) {
                val inBuf: ByteBuffer? = c.getInputBuffer(inIdx)
                if (inBuf != null && inBuf.remaining() >= frame.size) {
                    inBuf.clear()
                    inBuf.put(frame)
                    c.queueInputBuffer(inIdx, 0, frame.size, System.nanoTime() / 1000, 0)
                } else {
                    c.queueInputBuffer(inIdx, 0, 0, 0, 0)
                }
            }
            // Drain decoded frames straight to the surface.
            var outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
            while (outIdx >= 0) {
                c.releaseOutputBuffer(outIdx, true)
                outIdx = c.dequeueOutputBuffer(bufferInfo, 0)
            }
        } catch (t: Throwable) {
            HudLogger.e(tag, "feedFrame error", t)
        }
    }

    private fun renderSoftware(frame: ByteArray) {
        val surf = surface ?: return
        try {
            val bmp = BitmapFactory.decodeByteArray(frame, 0, frame.size) ?: return
            if (!loggedDims) {
                loggedDims = true
                HudLogger.i(tag, "software decode: ${bmp.width}x${bmp.height}, jpeg=${frame.size} bytes")
            }
            val canvas: Canvas = surf.lockCanvas(null)
            try {
                val srcRect = Rect(0, 0, bmp.width, bmp.height)
                val scale = minOf(canvas.width.toFloat() / bmp.width, canvas.height.toFloat() / bmp.height)
                val w = (bmp.width * scale).toInt()
                val h = (bmp.height * scale).toInt()
                val left = (canvas.width - w) / 2
                val top = (canvas.height - h) / 2
                val dstRect = Rect(left, top, left + w, top + h)
                if (mirrored) {
                    canvas.save()
                    canvas.scale(-1f, 1f, dstRect.exactCenterX(), dstRect.exactCenterY())
                    canvas.drawBitmap(bmp, srcRect, dstRect, null)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bmp, srcRect, dstRect, null)
                }
            } finally {
                surf.unlockCanvasAndPost(canvas)
            }
            bmp.recycle()
        } catch (t: Throwable) {
            // surface may be gone; ignore
        }
    }

    @Synchronized
    fun release() {
        started = false
        releaseLocked()
        surface = null
    }

    private fun releaseLocked() {
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        useSoftware = false
    }
}
