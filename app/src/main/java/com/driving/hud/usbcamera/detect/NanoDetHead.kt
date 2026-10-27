package com.driving.hud.usbcamera.detect

import com.driving.hud.usbcamera.util.HudLogger
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Decoder for the NanoDet-Plus head.
 *
 * Its layout is nothing like YOLO's: one row per anchor holding `classes + 4·(reg_max+1)`
 * values — **the class probabilities come first**, already sigmoid-activated inside the graph,
 * followed by four discrete distributions (one per box side) of `reg_max + 1` bins each. Verified
 * against the published ONNX: columns 0..79 are in 0..1, columns 80..111 are unbounded logits.
 * Each side is
 * decoded as `softmax(bins) · [0..reg_max] · stride` — the "integral" of the distribution —
 * which is why a generic `4+nc` decoder reads this tensor as garbage.
 *
 * Anchors arrive level by level (strides 8, 16, 32, 64), row-major inside each level. Boxes
 * come out normalised against the square model input, which is also the normalised frame
 * because the frame was stretched onto it (see [Stretch]).
 */
internal class NanoDetHead(
    private val labels: Array<String>,
    /** Square input this graph was exported for. */
    private val inputSize: Int,
    private val labelTag: String,
) : HeadDecoder {

    private companion object {
        /** FPN levels of the exported graph, in the order the head concatenates them. */
        val STRIDES = intArrayOf(8, 16, 32, 64)

        /** Distribution bins per box side: `reg_max + 1` with reg_max = 7. */
        const val BINS = 8

        /** NanoDet's demo uses 0.6 class-aware NMS. */
        const val NMS_IOU = 0.6f

        /** Cap on candidates handed to NMS; anything past this is not actionable while driving. */
        const val MAX_CANDIDATES = 300
    }

    private var layoutLogged = false

    /** One-shot warning for a class score outside 0..1, which means the layout is wrong. */
    private var badScoreLogged = false

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
        // Stretched input: normalised model coordinates are already normalised frame
        // coordinates, so scaleX/scaleY/dx/dy are not needed here.
        val channels = minOf(dimA, dimB)
        val anchors = maxOf(dimA, dimB)
        val expected = 4 * BINS + labels.size
        if (channels != expected || anchors <= 0) {
            if (!layoutLogged) {
                layoutLogged = true
                HudLogger.w(
                    labelTag,
                    "NanoDet head is [$dimA][$dimB] but $expected channels were expected " +
                        "(${labels.size} classes); refusing to decode"
                )
            }
            return emptyList()
        }

        val rowMajor = dimB == channels
        val at: (Int, Int) -> Float = if (rowMajor) {
            { anchor, ch -> (outer[anchor] as FloatArray)[ch] }
        } else {
            { anchor, ch -> (outer[ch] as FloatArray)[anchor] }
        }

        val out = ArrayList<Detection>(16)
        var anchor = 0
        var cells = 0
        for (stride in STRIDES) {
            cells = ceil(inputSize.toFloat() / stride).toInt()
            for (gy in 0 until cells) {
                for (gx in 0 until cells) {
                    if (anchor >= anchors) break
                    var bestScore = -1f
                    var bestCls = -1
                    for (c in 0 until labels.size) {
                        val s = at(anchor, c)
                        if (s > bestScore) {
                            bestScore = s
                            bestCls = c
                        }
                    }
                    // The graph applies sigmoid to the class block, so a score above 1 is not a
                    // confidence at all: it means the two halves of the head are being read the
                    // wrong way round (regression logits are unbounded). Drop it and say so,
                    // rather than painting "car 1200%" on the HUD.
                    if (bestScore > 1f && !badScoreLogged) {
                        badScoreLogged = true
                        HudLogger.w(
                            labelTag,
                            "class score ${bestScore} > 1 — this head does not look like NanoDet " +
                                "(${labels.size} classes first, then ${4 * BINS} bins)"
                        )
                    }
                    if (bestScore in conf..1f && bestCls >= 0 && out.size < MAX_CANDIDATES) {
                        val px = (gx + 0.5f) * stride
                        val py = (gy + 0.5f) * stride
                        val l = project(at, anchor, 0) * stride
                        val t = project(at, anchor, 1) * stride
                        val r = project(at, anchor, 2) * stride
                        val b = project(at, anchor, 3) * stride
                        out.add(
                            Detection(
                                label = labels[bestCls],
                                score = bestScore,
                                left = ((px - l) / inputSize).coerceIn(0f, 1f),
                                top = ((py - t) / inputSize).coerceIn(0f, 1f),
                                right = ((px + r) / inputSize).coerceIn(0f, 1f),
                                bottom = ((py + b) / inputSize).coerceIn(0f, 1f),
                            )
                        )
                    }
                    anchor++
                }
            }
        }

        if (!layoutLogged) {
            layoutLogged = true
            HudLogger.i(
                labelTag,
                "NanoDet head [$dimA][$dimB]: ${anchors} anchors over strides " +
                    STRIDES.joinToString("/") + " (${cells}² max), ${labels.size} classes"
            )
        }
        return nms(out)
    }

    /** `softmax(bins) · [0..reg_max]`: the expected offset, in stride units. */
    private fun project(at: (Int, Int) -> Float, anchor: Int, group: Int): Float {
        // Classes occupy the first `labels.size` channels, the four distribution groups follow.
        val base = labels.size + group * BINS
        var maxV = -Float.MAX_VALUE
        for (k in 0 until BINS) {
            val v = at(anchor, base + k)
            if (v > maxV) maxV = v
        }
        var sum = 0f
        var acc = 0f
        for (k in 0 until BINS) {
            val e = exp(at(anchor, base + k) - maxV)
            sum += e
            acc += e * k
        }
        return if (sum > 0f) acc / sum else 0f
    }

    /** Class-aware greedy NMS, the same rule NanoDet's own demo applies. */
    private fun nms(candidates: List<Detection>): List<Detection> {
        if (candidates.size <= 1) return candidates
        val sorted = candidates.sortedByDescending { it.score }
        val kept = ArrayList<Detection>(sorted.size)
        for (candidate in sorted) {
            var keep = true
            for (k in kept) {
                if (k.label != candidate.label) continue
                if (iou(candidate, k) > NMS_IOU) {
                    keep = false
                    break
                }
            }
            if (keep) kept.add(candidate)
        }
        return kept
    }

    private fun iou(a: Detection, b: Detection): Float {
        val x1 = maxOf(a.left, b.left)
        val y1 = maxOf(a.top, b.top)
        val x2 = minOf(a.right, b.right)
        val y2 = minOf(a.bottom, b.bottom)
        val inter = (x2 - x1).coerceAtLeast(0f) * (y2 - y1).coerceAtLeast(0f)
        val areaA = (a.right - a.left).coerceAtLeast(0f) * (a.bottom - a.top).coerceAtLeast(0f)
        val areaB = (b.right - b.left).coerceAtLeast(0f) * (b.bottom - b.top).coerceAtLeast(0f)
        val union = areaA + areaB - inter
        return if (union > 0f) inter / union else 0f
    }
}
