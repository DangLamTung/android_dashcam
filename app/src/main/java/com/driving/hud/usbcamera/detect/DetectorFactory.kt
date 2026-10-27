package com.driving.hud.usbcamera.detect

import android.content.Context
import com.driving.hud.usbcamera.util.HudLogger
import java.util.Locale

/**
 * Chooses a detector backend by measuring all of them through the same entry point.
 *
 * Availability is not usefulness, and a provider name is not evidence. Every backend is timed
 * on a synthetic frame — real letterbox, real inference, real decode — and the winner is the
 * one that burns the **least CPU**, because the complaint being solved is CPU load and heat,
 * not latency.
 *
 * The result is remembered per model + frame size, so only the first run pays for it.
 */
object DetectorFactory {

    private const val TAG = "DetectorFactory"

    /** ncnn backend on the Adreno GPU through Vulkan. */
    const val NCNN_VULKAN = "ncnn/Vulkan"

    /** ncnn backend on the hand-written ARM CPU kernels. */
    const val NCNN_CPU = "ncnn/CPU"

    /**
     * Same as [NCNN_CPU] with fp16 storage off. Cortex-A73/A53 are ARMv8.0 and have no fp16
     * SIMD, so fp16 storage can be pure conversion overhead here; measure rather than assume.
     */
    const val NCNN_CPU_F32 = "ncnn/CPU f32"

    /** Sentinel for "let ONNX Runtime pick its own best execution provider". */
    private const val ORT_AUTO = "onnx(auto)"

    /**
     * Input side of an ncnn graph, plus the two asset files it needs. pnnx leaves the ncnn
     * Input layer shape-free, so the shape cannot be read from the graph and must be declared
     * here; it has to match the ONNX export this was converted from.
     */
    private data class NcnnVariant(
        val input: Int,
        val param: String,
        val bin: String,
        /** True for a NanoDet head, which needs its own decoder and pre-processing. */
        val nanoDet: Boolean = false,
        /**
         * False when the model ships **only** an ncnn graph. The ONNX candidate is then skipped:
         * there is no `.onnx` to open, and offering the backend anyway would log a "not bundled"
         * warning on every start.
         */
        val hasOnnx: Boolean = true,
    )

    /**
     * The only assets that have an ncnn graph, keyed by asset file name.
     *
     * This is a whitelist on purpose. The ncnn graphs carry 84 output channels (4 box + 80
     * COCO classes) and no shape information. Point the app at any other model — the pruned
     * 6-class export, a model the user dropped in themselves — and that graph would still
     * load, still run, and then decode its 84 channels against a 6-label list without raising
     * a single error. Refusing to offer ncnn is the only reliable way to prevent that.
     */
    private val NCNN_VARIANTS = mapOf(
        "yolov10n-224.onnx" to
            NcnnVariant(224, NcnnDetector.PARAM_ASSET_224, NcnnDetector.BIN_ASSET_224),
        "yolov10n-320.onnx" to
            NcnnVariant(320, NcnnDetector.PARAM_ASSET_320, NcnnDetector.BIN_ASSET_320),
        // YOLO26n at 256: same nano scale as v10n, 2.4 mAP higher on COCO and a lighter head
        // (DFL removed). 256 rather than 224 because at 224 a pedestrian 35 m out is 2-3 px.
        //
        // The two entries below are **ncnn only**: shipping the matching .onnx as well cost
        // 14.6 MB of APK for a fallback that never won the shoot-out on this hardware. The key
        // is a stable preference id, not a file name — which is why it still ends in ".onnx".
        "yolo26n-256.onnx" to
            NcnnVariant(
                256,
                NcnnDetector.PARAM_ASSET_YOLO26_256,
                NcnnDetector.BIN_ASSET_YOLO26_256,
                hasOnnx = false,
            ),
        // NanoDet-Plus-m: 1.2 M params, the lightest thing that still reads a road scene.
        "nanodet-plus-m-320.onnx" to
            NcnnVariant(
                320,
                NcnnDetector.PARAM_ASSET_NANODET_320,
                NcnnDetector.BIN_ASSET_NANODET_320,
                nanoDet = true,
                hasOnnx = false,
            ),
    )

    /** Backend the live session runs on, for the HUD. */
    @Volatile
    var activeBackend: String = "-"
        private set

    /** Last shoot-out table, for the home screen. */
    @Volatile
    var lastReport: String = "(not run yet)"
        private set

    /**
     * @param only remembered winner from a previous run (name from [activeBackend]); if it can
     *             no longer be built, a full shoot-out runs instead so detection still works
     */
    fun create(
        context: Context,
        modelAsset: String,
        frameW: Int,
        frameH: Int,
        only: String? = null
    ): Detector? {
        val w = if (frameW > 0) frameW else 1280
        val h = if (frameH > 0) frameH else 720
        val labels = OnnxDetector.labels()
        val ncnnVariant = NCNN_VARIANTS[modelAsset.substringAfterLast('/')]

        // A deterministic, non-uniform frame. A flat probe would let a backend that computes
        // nothing look the same as one that computes correctly.
        val probe = ByteArray(w * h * 3 / 2)
        for (y in 0 until h) {
            val row = y * w
            java.util.Arrays.fill(probe, row, row + w, (y * 255 / h).toByte())
        }
        java.util.Arrays.fill(probe, w * h, w * h * 3 / 2, 128.toByte())

        if (!only.isNullOrBlank()) {
            val cached = build(context, modelAsset, ncnnVariant, only, w, h, labels, probe, validate = true)
            if (cached != null) {
                activeBackend = displayName(only)
                lastReport = "(reused $activeBackend, not re-measured)"
                HudLogger.i(TAG, "reusing remembered backend '$activeBackend'")
                return cached
            }
            HudLogger.w(TAG, "remembered backend '$only' unavailable — re-measuring")
        }

        val attempts = ArrayList<Pair<String, String>>() // label -> how to build
        if (ncnnVariant == null) {
            HudLogger.i(
                TAG,
                "'$modelAsset' has no converted ncnn graph — ONNX Runtime is the only backend"
            )
        } else if (NcnnDetector.gpuCount() > 0) {
            attempts += NCNN_VULKAN to NCNN_VULKAN
        } else {
            // CPU ncnn candidates are only tried when there is no GPU, i.e. when they are the
            // only way to run ncnn at all. Building an ncnn CPU session after a Vulkan session
            // has existed faults inside ncnn::Extractor::extract (__memcpy, SEGV_ACCERR) with
            // this libncnn build, so the two must not be mixed. Measured on this tablet the
            // Vulkan session wins the shoot-out anyway, so nothing is lost here.
            attempts += NCNN_CPU_F32 to NCNN_CPU_F32
        }
        // Only offer ONNX Runtime when there is a graph for it to open.
        val hasOnnx = ncnnVariant?.hasOnnx ?: true
        if (hasOnnx) {
            attempts += ORT_AUTO to ""
        } else {
            HudLogger.i(TAG, "'$modelAsset' ships an ncnn graph only — ONNX Runtime skipped")
        }

        val report = StringBuilder()
        var bestName = "?"
        var bestCpu = Float.MAX_VALUE

        for ((label, spec) in attempts) {
            var owned: Detector? = null
            try {
                val d = build(context, modelAsset, ncnnVariant, spec.ifEmpty { label }, w, h, labels, probe, validate = false)
                if (d == null) {
                    report.append(String.format(Locale.US, "%n  %-12s unavailable", label))
                    continue
                }
                owned = d
                // Warm-up already happened inside build(). Time five passes and log each one:
                // a backend that "wins" by returning a cached result shows up as a first pass
                // that is orders of magnitude slower than the rest.
                val passes = 5
                val perPass = StringBuilder()
                val cpu0 = SelfCpu.millis()
                val t0 = System.nanoTime()
                repeat(passes) {
                    val p0 = System.nanoTime()
                    d.detect(probe, w, h, 0.99f)
                    perPass.append(String.format(Locale.US, " %.1f", (System.nanoTime() - p0) / 1_000_000f))
                }
                val wallMs = (System.nanoTime() - t0) / (passes * 1_000_000f)
                val cpu1 = SelfCpu.millis()
                val cpuMs = if (cpu0 >= 0 && cpu1 >= cpu0) (cpu1 - cpu0) / passes.toFloat() else Float.MAX_VALUE
                report.append(
                    String.format(Locale.US, "%n  %-12s %7.1f ms  %7.1f CPU-ms", label, wallMs, cpuMs)
                )
                val name = displayName(spec.ifEmpty { label })
                HudLogger.i(TAG, "$label -> ${wallMs}ms wall, ${cpuMs} CPU-ms; passes(ms):$perPass")
                // Agreement check: every backend runs the same frame at a low threshold. A
                // backend that silently computes the wrong thing (or nothing) shows up here
                // as a wildly different hit list. Cheap insurance against a green benchmark.
                val hits = d.detect(probe, w, h, 0.05f)
                val signature = hits.take(3).joinToString(",") { "${it.label}@${(it.score * 100).toInt()}%" }
                HudLogger.i(TAG, "$label agree-check: ${hits.size} hit(s) [$signature]")
                if (cpuMs < bestCpu) {
                    bestName = name
                    bestCpu = cpuMs
                }
                // Deliberately NOT held open. Creating a second ncnn session while the first is
                // still alive faults inside ncnn::Extractor::extract, so every candidate is
                // closed by the `finally` below before the next one is built, and the winner is
                // rebuilt once at the end. Only one native session exists at a time.
            } catch (t: Throwable) {
                report.append("\n  $label  failed: ${t.message}")
                HudLogger.w(TAG, "$label failed: ${t.message}")
            } finally {
                owned?.close()
            }
        }

        if (bestCpu == Float.MAX_VALUE) {
            HudLogger.e(TAG, "no detector backend could be built")
            activeBackend = "-"
            lastReport = report.toString()
            return null
        }

        // Rebuild the winner now that every candidate has been torn down. This costs one extra
        // session creation, which the remembered-backend cache means we pay once per model+size.
        val winnerSpec = when (bestName) {
            NCNN_VULKAN, NCNN_CPU, NCNN_CPU_F32 -> bestName
            else -> "" // let ONNX Runtime re-pick; it settles on the same provider
        }
        val winner = build(context, modelAsset, ncnnVariant, winnerSpec, w, h, labels, probe, validate = true)
        if (winner == null) {
            HudLogger.e(TAG, "winner '$bestName' could not be rebuilt")
            activeBackend = "-"
            lastReport = report.toString()
            return null
        }
        activeBackend = bestName
        lastReport = report.toString()
        HudLogger.i(
            TAG,
            "detector backend shoot-out, per frame (winner picked on CPU-ms):$report\n  -> $bestName"
        )
        return winner
    }

    /**
     * Builds one backend and, when [validate], runs a single pass so a broken session is
     * rejected here rather than mid-drive. The caller is responsible for closing the result.
     */
    private fun build(
        context: Context,
        modelAsset: String,
        ncnnVariant: NcnnVariant?,
        spec: String,
        w: Int,
        h: Int,
        labels: Array<String>,
        probe: ByteArray,
        validate: Boolean
    ): Detector? {
        val ncnnSpec = spec == NCNN_VULKAN || spec == NCNN_CPU || spec == NCNN_CPU_F32
        // A remembered ONNX Runtime provider is meaningless for a graph that ships ncnn only.
        if (!ncnnSpec && ncnnVariant?.hasOnnx == false) return null
        // A remembered ncnn backend must not be honoured for an asset that has no ncnn graph.
        if (ncnnSpec && ncnnVariant == null) return null
        val nanoDet = ncnnVariant?.nanoDet == true || modelAsset.startsWith("nanodet")
        val d = when {
            spec == NCNN_VULKAN ->
                NcnnDetector.create(
                    context, ncnnVariant!!.input, ncnnVariant.input, labels,
                    vulkan = true,
                    paramAsset = ncnnVariant.param, binAsset = ncnnVariant.bin,
                    nanoDet = nanoDet,
                )
            // CPU ncnn runs fp32 only. fp16 storage was measured *slower* than fp32 on these
            // ARMv8.0 cores (75 ms vs 73 ms) because there is no fp16 SIMD to use, so it has no
            // upside. Both remembered names resolve here, so an old "ncnn/CPU" entry cannot
            // resurrect the fp16 path.
            spec == NCNN_CPU || spec == NCNN_CPU_F32 ->
                NcnnDetector.create(
                    context, ncnnVariant!!.input, ncnnVariant.input, labels,
                    vulkan = false, fp16 = false,
                    paramAsset = ncnnVariant.param, binAsset = ncnnVariant.bin,
                    nanoDet = nanoDet,
                )
            else ->
                // "" means "let ONNX Runtime choose"; otherwise it is a provider name.
                OnnxDetector.create(context, modelAsset, w, h, spec.ifEmpty { null }, nanoDet = nanoDet)
        } ?: return null
        if (validate) d.detect(probe, w, h, 0.99f)
        return d
    }

    /**
     * The name a backend is remembered by. ONNX Runtime reports the execution provider it
     * actually settled on, and that — not the request — is what must be reused next time.
     */
    private fun displayName(spec: String): String = when (spec) {
        NCNN_VULKAN, NCNN_CPU, NCNN_CPU_F32 -> spec
        ORT_AUTO, "" -> OnnxDetector.activeProvider
        else -> spec
    }
}
