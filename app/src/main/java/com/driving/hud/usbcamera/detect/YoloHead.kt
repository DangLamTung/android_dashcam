package com.driving.hud.usbcamera.detect

import com.driving.hud.usbcamera.util.HudLogger

/**
 * Turns a raw YOLO detection head into normalised [Detection]s, and applies NMS.
 *
 * Shared by every detector backend so the *decoding* cannot differ between libraries: this is
 * where the head layout is inferred and where a wrong guess produces visible nonsense (an
 * earlier version painted `bicycle 7900%` on the HUD by reading a box coordinate as a class
 * score). One copy, exercised by whichever backend is in use.
 *
 * @param labelTag log tag of the owning backend, so the one-shot layout log lands in context
 */
internal class YoloHead(
    private val labels: Array<String>,
    private val labelTag: String
) : HeadDecoder {

    /** One-shot log of how the detection head was interpreted. */
    private var layoutLogged = false

    /**
     * Decode the head into normalised boxes.
     *
     * The tensor metadata cannot be trusted to say which axis is "channels" and which is
     * "anchors": a QNN session reports no static output shape at all, and even on CPU a head
     * like `[1, 6, 4]` is ambiguous from shape alone. So both readings are evaluated from the
     * actual data layout and whichever finds objects wins; channels-first (`[1, 4+nc, N]`,
     * the YOLOv8/v11 convention) breaks ties.
     *
     * @param outer  per-row data; `outer[i]` is a `FloatArray` of one head row
     * @param dimA   leading dim (interpreted as channels or rows depending on layout)
     * @param dimB   trailing dim
     * @param conf   minimum score to keep
     * @param scaleX model-input pixels per source pixel, for undoing the letterbox
     * @param scaleY identical to [scaleX] here: YOLO is always fed a letterbox, never stretched
     * @param dx/dy  letterbox padding offset
     */
    override fun decode(
        outer: Array<*>,
        dimA: Int,
        dimB: Int,
        conf: Float,
        srcW: Int,
        srcH: Int,
        scaleX: Float,
        scaleY: Float,
        dx: Float,
        dy: Float
    ): List<Detection> {
        val scale = scaleX
        // YOLOv10 and other NMS-free end-to-end heads emit [1, N, 6] where each row is an
        // already-filtered, already NMS'd detection: x1, y1, x2, y2, score, class_id.
        //
        // This must be a *terminal* decision. Falling through to the grid decoders when the
        // model legitimately finds nothing makes them read box coordinates as class scores
        // and emit nonsense like "bicycle 7900%".
        //
        // Caveat: a single-class YOLOv5 head is also 6 wide. Those are rare and would need
        // the row-first reading; every COCO export with 6 columns is end-to-end.
        if (dimB == 6) {
            val endToEnd = decodeEndToEnd(outer, dimA, conf, srcW, srcH, scale, dx, dy)
            if (!layoutLogged) {
                layoutLogged = true
                HudLogger.i(
                    labelTag,
                    "head layout [1][$dimA][6]: end-to-end (YOLOv10 style), ${endToEnd.size} box(es)"
                )
            }
            return endToEnd
        }

        val channelsFirst = decodeChannelsFirst(outer, dimA, dimB, conf, srcW, srcH, scale, dx, dy)
        val rowFirst = decodeRowFirst(outer, dimA, dimB, conf, srcW, srcH, scale, dx, dy)
        // Orientation is decided from the dimensions, not guessed: a YOLO head is either
        // [4+nc, N] or [N, 5+nc], and 4+nc (84) / 5+nc (85) are unmistakable for COCO.
        val preferChannels = isChannelsFirst(dimA, dimB)
        val chosen = if (preferChannels) channelsFirst else rowFirst
        val other = if (preferChannels) rowFirst else channelsFirst

        // Only rescue with the other reading when the layout is genuinely ambiguous. If one
        // dimension is exactly 4+nc or 5+nc the head is known, so "found nothing" is the true
        // answer — and re-reading the same buffer the other way round turns box coordinates
        // into class scores, which is what produced imaginary detections on the HUD.
        val conclusive = dimA == 4 + labels.size || dimA == 5 + labels.size ||
            dimB == 4 + labels.size || dimB == 5 + labels.size

        if (!layoutLogged) {
            layoutLogged = true
            HudLogger.i(
                labelTag,
                "head layout [$dimA][$dimB]: " +
                    (if (preferChannels) "channels-first" else "row-first") +
                    (if (conclusive) " (conclusive)" else " (ambiguous)") +
                    " (${chosen.size} box(es), other reading ${other.size})"
            )
        }
        val picked = if (chosen.isNotEmpty() || conclusive || other.isEmpty()) chosen else other
        return nms(picked, 0.45f)
    }

    /**
     * `[4+nc, N]` (YOLOv8/v10 export) versus `[N, 5+nc]` (YOLOv5). The channel dim is
     * recognised by the known class count; anything unrecognised falls back to
     * "the smaller dim is the channel dim".
     */
    private fun isChannelsFirst(a: Int, b: Int): Boolean {
        val headNoObj = 4 + labels.size
        val headWithObj = 5 + labels.size
        return when {
            a == headNoObj || a == headWithObj -> true
            b == headNoObj || b == headWithObj -> false
            else -> a <= 512 && a < b
        }
    }

    /**
     * `[1, N, 6]` = `x1, y1, x2, y2, score, class_id` in model-input pixels, confidence
     * sorted and already NMS'd by the graph (YOLOv10). No extra NMS is applied.
     */
    private fun decodeEndToEnd(
        outer: Array<*>,
        rows: Int,
        conf: Float,
        srcW: Int,
        srcH: Int,
        scale: Float,
        dx: Float,
        dy: Float
    ): List<Detection> {
        val out = ArrayList<Detection>()
        for (i in 0 until rows) {
            val r = outer[i] as? FloatArray ?: continue
            if (r.size < 6) continue
            // clamp: never let a malformed head paint ">100%" on the HUD
            val score = r[4].coerceIn(0f, 1f)
            if (score < conf) continue
            val cls = r[5].toInt()
            if (cls < 0 || cls >= labels.size) continue
            val x1 = r[0]
            val y1 = r[1]
            val x2 = r[2]
            val y2 = r[3]
            if (x2 <= x1 || y2 <= y1) continue
            out += Detection(
                labels[cls],
                score,
                (((x1 - dx) / scale) / srcW).coerceIn(0f, 1f),
                (((y1 - dy) / scale) / srcH).coerceIn(0f, 1f),
                (((x2 - dx) / scale) / srcW).coerceIn(0f, 1f),
                (((y2 - dy) / scale) / srcH).coerceIn(0f, 1f)
            )
        }
        return out
    }

    /** `[1, 4+nc, N]` — each channel is a length-N row (YOLOv8 / YOLOv11 export). */
    private fun decodeChannelsFirst(
        outer: Array<*>,
        channels: Int,
        anchors: Int,
        conf: Float,
        srcW: Int,
        srcH: Int,
        scale: Float,
        dx: Float,
        dy: Float
    ): List<Detection> {
        val nc = channels - 4
        if (nc <= 0) return emptyList()
        val cxRow = outer[0] as? FloatArray ?: return emptyList()
        val cyRow = outer[1] as? FloatArray ?: return emptyList()
        val wRow = outer[2] as? FloatArray ?: return emptyList()
        val hRow = outer[3] as? FloatArray ?: return emptyList()
        if (cxRow.size < anchors || cyRow.size < anchors || wRow.size < anchors || hRow.size < anchors) {
            return emptyList()
        }
        val out = ArrayList<Detection>()
        for (i in 0 until anchors) {
            var best = 0
            var bestScore = 0f
            for (k in 0 until nc) {
                val row = outer[4 + k] as? FloatArray ?: continue
                if (i >= row.size) continue
                val s = row[i]
                if (s > bestScore) {
                    bestScore = s
                    best = k
                }
            }
            if (bestScore < conf) continue
            addBox(out, best, bestScore, cxRow[i], cyRow[i], wRow[i], hRow[i], scale, dx, dy, srcW, srcH)
        }
        return out
    }

    /** `[1, N, 5+nc]` (YOLOv5, with objectness) or `[1, N, 4+nc]`. */
    private fun decodeRowFirst(
        outer: Array<*>,
        rows: Int,
        cols: Int,
        conf: Float,
        srcW: Int,
        srcH: Int,
        scale: Float,
        dx: Float,
        dy: Float
    ): List<Detection> {
        // A YOLOv5 COCO head carries an objectness channel: 4 + 1 + 80
        val withObj = cols == 5 + labels.size
        val nc = if (withObj) cols - 5 else cols - 4
        if (nc <= 0) return emptyList()
        val base = if (withObj) 5 else 4
        val out = ArrayList<Detection>()
        for (i in 0 until rows) {
            val r = outer[i] as? FloatArray ?: continue
            if (r.size < base + 1) continue
            var best = 0
            var bestScore = 0f
            for (k in 0 until minOf(nc, labels.size)) {
                val s = r[base + k]
                if (s > bestScore) {
                    bestScore = s
                    best = k
                }
            }
            if (withObj) bestScore *= r[4]
            if (bestScore < conf) continue
            addBox(out, best, bestScore, r[0], r[1], r[2], r[3], scale, dx, dy, srcW, srcH)
        }
        return out
    }

    private fun addBox(
        out: MutableList<Detection>,
        cls: Int,
        score: Float,
        cx: Float,
        cy: Float,
        bw: Float,
        bh: Float,
        scale: Float,
        dx: Float,
        dy: Float,
        srcW: Int,
        srcH: Int
    ) {
        // boxes are in model-input pixel space -> undo letterbox -> normalise
        val l = ((cx - bw / 2f) - dx) / scale
        val t = ((cy - bh / 2f) - dy) / scale
        val r = ((cx + bw / 2f) - dx) / scale
        val b = ((cy + bh / 2f) - dy) / scale
        out.add(
            Detection(
                labels.getOrElse(cls) { "cls$cls" },
                // Same clamp as the end-to-end path: a class score is a probability, and a
                // malformed or mismatched head must never paint ">100%" on the HUD.
                score.coerceIn(0f, 1f),
                (l / srcW).coerceIn(0f, 1f),
                (t / srcH).coerceIn(0f, 1f),
                (r / srcW).coerceIn(0f, 1f),
                (b / srcH).coerceIn(0f, 1f)
            )
        )
    }

    private fun nms(input: List<Detection>, iouThreshold: Float): List<Detection> {
        if (input.size < 2) return input
        val sorted = input.sortedByDescending { it.score }.toMutableList()
        val kept = ArrayList<Detection>(sorted.size)
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept.add(best)
            sorted.removeAll { iou(best, it) > iouThreshold && best.label == it.label }
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.left, b.left)
        val y1 = maxOf(a.top, b.top)
        val x2 = minOf(a.right, b.right)
        val y2 = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val areaA = (a.right - a.left) * (a.bottom - a.top)
        val areaB = (b.right - b.left) * (b.bottom - b.top)
        val union = areaA + areaB - inter
        return if (union <= 0f) 0f else inter / union
    }
}
