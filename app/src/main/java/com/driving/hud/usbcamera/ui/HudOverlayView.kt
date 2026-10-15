package com.driving.hud.usbcamera.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.driving.hud.usbcamera.detect.Detection

class HudOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#4000E5FF")
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }

    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8000E676")
        strokeWidth = 4f
        style = Paint.Style.STROKE
    }

    private val hudTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC00E5FF")
        textSize = 36f
        style = Paint.Style.FILL
        isFakeBoldText = true
    }

    private val trajectoryPath = Path()
    private var showGrid = true
    private var nightMode = false

    // ---- object detection overlay ---------------------------------------
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFFC400")
        strokeWidth = 5f
        style = Paint.Style.STROKE
    }

    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
        style = Paint.Style.FILL
    }

    private val detTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        isFakeBoldText = true
        style = Paint.Style.FILL
    }

    // ---- calibration mode overlay ---------------------------------------
    private val calibHorizonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF3D00") // Vivid orange-red for horizon
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val calibTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFFF3D00")
        textSize = 28f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
    }

    private val calibLadderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00E676") // Bright green distance lines
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val calibBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC002010")
        style = Paint.Style.FILL
    }

    private val calibBadgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00E676")
        textSize = 24f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        style = Paint.Style.FILL
    }

    private val calibReticlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00E5FF") // Cyan crosshair
        strokeWidth = 2.5f
        style = Paint.Style.STROKE
    }

    @Volatile
    private var calibrationMode = false

    @Volatile
    private var calibHorizonNormY: Float = 0.5f

    @Volatile
    private var calibPitchDeg: Float = 8.0f

    @Volatile
    private var calibHeightM: Float = 1.2f

    @Volatile
    private var calibFovDeg: Float = 65.0f

    @Volatile
    private var calibDistanceLinesNormY: List<Pair<Float, Float>> = emptyList()

    fun setCalibrationMode(enabled: Boolean) {
        if (calibrationMode == enabled) return
        calibrationMode = enabled
        postInvalidateOnAnimation()
    }

    fun isCalibrationMode(): Boolean = calibrationMode

    fun updateCalibrationDisplay(
        horizonNormY: Float,
        pitchDeg: Float,
        heightM: Float,
        fovDeg: Float,
        distanceLines: List<Pair<Float, Float>>
    ) {
        calibHorizonNormY = horizonNormY
        calibPitchDeg = pitchDeg
        calibHeightM = heightM
        calibFovDeg = fovDeg
        calibDistanceLinesNormY = distanceLines
        postInvalidateOnAnimation()
    }

    @Volatile
    private var detections: List<Detection> = emptyList()

    /**
     * Frame aspect (width/height). Everything normalised is drawn inside the fitted video
     * rectangle rather than the whole view, so boxes and distance ribbons stay aligned with the
     * picture in any window size, including split screen.
     */
    @Volatile
    private var videoAspect = 0f

    fun setVideoAspect(aspect: Float) {
        if (aspect == videoAspect) return
        videoAspect = aspect
        postInvalidateOnAnimation()
    }

    private fun videoRect(): RectF = VideoFit.fit(width, height, videoAspect)

    // ---- collision alert banner -----------------------------------------
    @Volatile
    private var alertText: String = ""

    @Volatile
    private var alertWarn = false

    private val alertRect = RectF()

    private val alertBgCautionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CCB36B00")
        style = Paint.Style.FILL
    }

    private val alertBgWarnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E6C62828")
        style = Paint.Style.FILL
    }

    private val alertTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 46f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        style = Paint.Style.FILL
    }

    /** Empty text hides the banner. */
    fun setAlert(text: String, warn: Boolean) {
        alertText = text
        alertWarn = warn
        postInvalidateOnAnimation()
    }

    // ---- live telemetry readout (FPS / focus) ---------------------------
    private val telemetryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF00E5FF")
        textSize = 38f
        typeface = Typeface.MONOSPACE
        isFakeBoldText = true
        style = Paint.Style.FILL
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val telemetryBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#99000000")
        style = Paint.Style.FILL
    }

    @Volatile
    private var telemetryLines: List<String> = emptyList()

    /** Extra bottom offset so telemetry floats above the bottom control bar when visible. */
    @Volatile
    private var bottomInset: Float = 0f

    fun setBottomInset(inset: Float) {
        if (inset == bottomInset) return
        bottomInset = inset
        postInvalidateOnAnimation()
    }

    /** Safe to call from any thread. */
    fun setTelemetry(lines: List<String>) {
        if (lines == telemetryLines) return
        telemetryLines = lines
        postInvalidateOnAnimation()
    }

    /** Called from the inference thread — swaps a reference and schedules a redraw. */
    fun setDetections(list: List<Detection>) {
        detections = list
        postInvalidateOnAnimation()
    }

    fun clearDetections() {
        detections = emptyList()
        postInvalidateOnAnimation()
    }

    fun detectionCount(): Int = detections.size

    fun toggleGrid(): Boolean {
        showGrid = !showGrid
        invalidate()
        return showGrid
    }

    /** Explicit state (used when applying saved settings). */
    fun setGrid(on: Boolean) {
        showGrid = on
        invalidate()
    }

    fun isGridOn(): Boolean = showGrid

    fun setNightMode(on: Boolean) {
        if (nightMode == on) return
        nightMode = on
        if (nightMode) {
            gridPaint.color = Color.parseColor("#5000FF66")
            guidePaint.color = Color.parseColor("#9000FF66")
            hudTextPaint.color = Color.parseColor("#EE00FF66")
        } else {
            gridPaint.color = Color.parseColor("#4000E5FF")
            guidePaint.color = Color.parseColor("#8000E676")
            hudTextPaint.color = Color.parseColor("#CC00E5FF")
        }
        invalidate()
    }

    fun isNightModeOn(): Boolean = nightMode

    fun toggleNightMode(): Boolean {
        nightMode = !nightMode
        if (nightMode) {
            gridPaint.color = Color.parseColor("#5000FF66")
            guidePaint.color = Color.parseColor("#9000FF66")
            hudTextPaint.color = Color.parseColor("#EE00FF66")
        } else {
            gridPaint.color = Color.parseColor("#4000E5FF")
            guidePaint.color = Color.parseColor("#8000E676")
            hudTextPaint.color = Color.parseColor("#CC00E5FF")
        }
        invalidate()
        return nightMode
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (calibrationMode) {
            drawCalibration(canvas, w, h)
        }

        // Detections are independent of the guide grid, so draw them next.
        drawDetections(canvas)
        drawAlert(canvas, w, h)
        if (!calibrationMode) {
            drawTelemetry(canvas, h)
        }

        if (!showGrid || calibrationMode) return

        val cx = w / 2f
        val cy = h / 2f

        // Center reticle / Horizon crosshair
        canvas.drawLine(cx - 60f, cy, cx + 60f, cy, guidePaint)
        canvas.drawLine(cx, cy - 40f, cx, cy + 40f, guidePaint)

        // Trajectory / Lane Guide Lines for Driving HUD
        trajectoryPath.reset()
        // Left lane guide
        trajectoryPath.moveTo(cx - 80f, cy + 40f)
        trajectoryPath.lineTo(w * 0.15f, h)
        // Right lane guide
        trajectoryPath.moveTo(cx + 80f, cy + 40f)
        trajectoryPath.lineTo(w * 0.85f, h)

        canvas.drawPath(trajectoryPath, guidePaint)

        // Horizontal distance markers
        val y1 = cy + (h - cy) * 0.35f
        val y2 = cy + (h - cy) * 0.70f
        canvas.drawLine(cx - 140f, y1, cx + 140f, y1, gridPaint)
        canvas.drawLine(cx - 240f, y2, cx + 240f, y2, gridPaint)

        // HUD Corner Accents
        val cornerSize = 40f
        val pad = 30f
        // Top-Left
        canvas.drawLine(pad, pad, pad + cornerSize, pad, gridPaint)
        canvas.drawLine(pad, pad, pad, pad + cornerSize, gridPaint)
        // Top-Right
        canvas.drawLine(w - pad, pad, w - pad - cornerSize, pad, gridPaint)
        canvas.drawLine(w - pad, pad, w - pad, pad + cornerSize, gridPaint)
        // Bottom-Left
        canvas.drawLine(pad, h - pad, pad + cornerSize, h - pad, gridPaint)
        canvas.drawLine(pad, h - pad, pad, h - pad - cornerSize, gridPaint)
        // Bottom-Right
        canvas.drawLine(w - pad, h - pad, w - pad - cornerSize, h - pad, gridPaint)
        canvas.drawLine(w - pad, h - pad, w - pad, h - pad - cornerSize, gridPaint)
    }

    private fun drawCalibration(canvas: Canvas, w: Float, h: Float) {
        val rect = videoRect()
        if (rect.isEmpty) return

        val horizonY = rect.top + calibHorizonNormY * rect.height()
        val cx = rect.centerX()

        // 1. Vanishing point reticle at (cx, horizonY)
        canvas.drawCircle(cx, horizonY, 20f, calibReticlePaint)
        canvas.drawLine(cx - 45f, horizonY, cx + 45f, horizonY, calibReticlePaint)
        canvas.drawLine(cx, horizonY - 45f, cx, horizonY + 45f, calibReticlePaint)

        // 2. Horizon line across the camera viewport
        if (horizonY in rect.top..rect.bottom) {
            canvas.drawLine(rect.left, horizonY, rect.right, horizonY, calibHorizonPaint)
            val info = "HORIZON (pitch %.1f° · H %.2fm · FOV %.1f°)".format(
                calibPitchDeg, calibHeightM, calibFovDeg
            )
            canvas.drawText(info, rect.left + 24f, (horizonY - 10f).coerceAtLeast(rect.top + 26f), calibTextPaint)
        }

        // 3. Distance ladder lines
        val corridorHalfW = rect.width() * 0.40f
        for ((distM, normY) in calibDistanceLinesNormY) {
            val y = rect.top + normY * rect.height()
            if (y in rect.top..rect.bottom) {
                // Tapered lane corridor width
                val t = (normY - calibHorizonNormY).coerceIn(0f, 1f)
                val spread = (rect.width() * 0.12f + corridorHalfW * t).coerceAtMost(rect.width() * 0.48f)
                val lineLeft = cx - spread
                val lineRight = cx + spread

                canvas.drawLine(lineLeft, y, lineRight, y, calibLadderPaint)

                // Badge showing distance
                val badgeText = "%.0fm".format(distM)
                val tw = calibBadgeTextPaint.measureText(badgeText)
                val bw = tw + 16f
                val bh = 32f
                val badgeLeft = (lineRight + 8f).coerceAtMost(rect.right - bw - 4f)
                val badgeRect = RectF(badgeLeft, y - bh / 2f, badgeLeft + bw, y + bh / 2f)
                canvas.drawRoundRect(badgeRect, 6f, 6f, calibBadgePaint)
                canvas.drawRoundRect(badgeRect, 6f, 6f, calibLadderPaint)
                canvas.drawText(badgeText, badgeRect.left + 8f, y + 8f, calibBadgeTextPaint)
            }
        }
    }

    /**
     * Bottom-left readout. Drawn on the Canvas rather than as a TextView so it stays
     * legible over any picture and needs no layout pass to update.
     */
    /**
     * Collision-alert banner across the upper part of the view. Kept above the centre so it
     * never covers the object it is warning about.
     */
    private fun drawAlert(canvas: Canvas, w: Float, h: Float) {
        val text = alertText
        if (text.isEmpty()) return
        val tw = alertTextPaint.measureText(text)
        val pad = 26f
        val top = h * 0.10f
        alertRect.set((w - tw) / 2f - pad, top, (w + tw) / 2f + pad, top + 76f)
        canvas.drawRoundRect(
            alertRect, 16f, 16f,
            if (alertWarn) alertBgWarnPaint else alertBgCautionPaint
        )
        canvas.drawText(text, (w - tw) / 2f, top + 52f, alertTextPaint)
    }

    private fun drawTelemetry(canvas: Canvas, h: Float) {
        val lines = telemetryLines
        if (lines.isEmpty()) return

        val pad = 18f
        val lineH = telemetryPaint.textSize + 14f
        var maxW = 0f
        for (line in lines) maxW = maxOf(maxW, telemetryPaint.measureText(line))

        val boxH = lineH * lines.size + pad * 2
        val boxW = maxW + pad * 2
        val left = 30f
        // Offset above bottom control bar when inset is provided
        val bottom = (h - 30f - bottomInset).coerceAtLeast(boxH + 30f)
        val top = bottom - boxH
        val bounds = RectF(left, top, left + boxW, bottom)
        canvas.drawRoundRect(bounds, 14f, 14f, telemetryBgPaint)

        var y = bounds.top + pad + telemetryPaint.textSize
        for (line in lines) {
            canvas.drawText(line, left + pad, y, telemetryPaint)
            y += lineH
        }
    }

    /**
     * Detection boxes are normalised (0..1) against the camera frame, so they are mapped onto
     * the fitted video rectangle — not the whole view. In a split-screen half or after a
     * resolution change the picture does not fill the view, and mapping to the view would drift
     * every box away from the object it belongs to.
     */
    private fun drawDetections(canvas: Canvas) {
        val dets = detections
        if (dets.isEmpty()) return
        val rect = videoRect()
        if (rect.isEmpty) return
        val bounds = RectF()
        for (d in dets) {
            bounds.set(
                rect.left + d.left * rect.width(),
                rect.top + d.top * rect.height(),
                rect.left + d.right * rect.width(),
                rect.top + d.bottom * rect.height()
            )
            canvas.drawRect(bounds, boxPaint)

            // Support multi-line labels (e.g. detailed pinhole + true distance metrics)
            val lines = d.label.split("\n")
            val lineH = detTextPaint.textSize + 8f
            var maxTextW = 0f
            for (line in lines) {
                maxTextW = maxOf(maxTextW, detTextPaint.measureText(line))
            }
            val totalTextH = lineH * lines.size + 10f
            val labelTop = if (bounds.top - totalTextH < rect.top) bounds.bottom else bounds.top - totalTextH
            val bgRect = RectF(
                bounds.left,
                labelTop,
                (bounds.left + maxTextW + 16f).coerceAtMost(rect.right),
                labelTop + totalTextH
            )
            canvas.drawRoundRect(bgRect, 8f, 8f, labelBgPaint)

            var textY = labelTop + detTextPaint.textSize + 2f
            for (line in lines) {
                canvas.drawText(line, bounds.left + 8f, textY, detTextPaint)
                textY += lineH
            }
        }
    }
}
