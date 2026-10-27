# Bug list

Findings from on-device testing and code review of the multi-source camera work. Every entry is
verifiable — by reading the code path named in it, or by reproducing it on the Lenovo TB331FC with
`tools/debug_tablet.sh`.

Numbers are stable: **1–12** are the original passes (verified on hardware), **13–19** were fixed in
1.0.14–1.0.16 and still owe a hardware run, **20–24** are the plan items (their Status column says
what closed them), **25** was fixed in 1.0.17, **26–33** came from an external review and were fixed
in 1.0.18. Section order follows the version they were found in, not the number.

Severity: **🔴 high** (blocks or freezes the UI) · **🟠 medium** (wrong behaviour / wasted CPU) ·
**🟡 low** (robustness, cosmetics).

Status: **✅ fixed, verified on hardware** · **⚠️ fixed, one scenario not yet exercised** ·
**⬜ open**

---

## Fixed — detector start-up cost

The report that started this: *at start-up there is a stretch above 200 % CPU where the model gets
loaded again*. Four defects fed one symptom.

| # | Sev | Bug | Evidence | Fix | Status |
| :-- | :-- | :--- | :--- | :--- | :-- |
| 1 | 🔴 | **Detection toggle ignored by the JPEG sources.** With *Detect objects* off, the WiFi/DVR path still built a detector and still ran inference on every frame; the switch only affected the USB and System paths. | `MjpegFrameSink.isDetDue` had no `HudPrefs.detect` check, unlike `SystemCameraController` and `AusbcCameraController`. Tablet prefs had `detect_interval_ms=0`, i.e. a pass per frame. | Checks `HudPrefs.detect` in `initDetector()` (no session built at all) and per frame in `isDetDue`. | ✅ `detection disabled in settings — running preview only`, **0** model-load lines, CPU ~0 % |
| 2 | 🔴 | **Backend shoot-out ran inline on the UI thread.** `open()` → `sink.prepare()` → `DetectorFactory.create(..., only = null)` benchmarked every backend on the main thread. | Tablet log: `15:50:32.833 onResume` → `15:50:46.658` winner rebuilt → `15:50:46.659 Connected`. **13.8 s of main-thread work** — an ANR for the first tap, with every core saturated. | Builds on the sink's `inferExecutor` behind a generation guard; the UI keeps running and a late build is discarded. | ✅ `onStart`/`onResume` on time while the single load runs in the background; CPU peaked at 97 % of **one** core |
| 3 | 🔴 | **The winning backend was never remembered for the JPEG sources.** `only = null` re-ran the shoot-out — load ncnn (4.5 MB) *and* ONNX (9 MB), then load ncnn again for the winner — on every connect. | `MjpegFrameSink` passed `null`; the USB and System paths pass the remembered provider. | Reuses and stores the backend per model + frame size via `HudPrefs.detectProviderFor` / `setDetectProviderFor`. | ✅ `reusing detector backend 'ncnn/Vulkan' for yolov10n-224.onnx@1280x720 (skipping benchmark)`, one model load, no ONNX load, no shoot-out |
| 4 | 🟠 | **No prewarm for the JPEG sources**, so even a reused session landed on the first frame. | `CameraCoordinator.prewarmDetector()` only handled `MODE_SYSTEM`. | Covers `MODE_WIFI` and `MODE_DVR`: the session is built from `onCreate` while the home screen is up. | ✅ the reuse line is logged before `onResume` |

**Also fixed here:** `detect_provider` / `detect_provider_key` was a **single** global pair, so two
sources that negotiate different frame sizes — the DVR's 1280×720 and, say, a 1920×1080 system
camera — overwrote each other, and every switch between them re-ran the benchmark. They are now a
bounded per-size map (newest first), with the old pair still honoured once so an existing install
keeps its remembered backend. Covered by `testProviderMemoryKeepsOneEntryPerFrameSize`.

---

## Fixed — DVR robustness pass

| # | Sev | Bug | Where | Fix | Status |
| :-- | :-- | :--- | :--- | :--- | :-- |
| 5 | 🟠 | `locateSlot()` never checked `running`, so `close()` could not stop a sweep: 9 candidate slots plus a 16 MB scan in 128-block steps (up to 256 SCSI transfers) could outlive `join(1500)`, leaving the thread talking to a connection that was being closed. | `DvrUsbCameraController.locateSlot()` | Checks `running` in both loops, and failed sweeps are rate limited to one per second so a dead DVR is not hammered. | ✅ teardown → re-claim cycle on the tablet is immediate and clean |
| 6 | 🔴 | A pulled cable was never reported: `readFrame()` returned null, the loop slept 60 ms and re-ran the **whole** slot search forever, with no `onError`/`onDisconnected` (and unlike the UVC path there was no detach receiver for the DVR). | `DvrUsbCameraController` | Registers `ACTION_USB_DEVICE_DETACHED` so an unplug closes and reports `onDisconnected` immediately; 20 empty polls report a status, 60 raise `onError` and trigger a **self-repair** re-claim. Since 1.0.14 that repair is **bounded** — after `MAX_FAILED_RECOVERIES` (3) re-claims that produce no video the source stops and asks for a Connect tap, instead of hammering the DVR's watchdog forever. | ✅ stall detection verified (a wedged session stopped the loop and reported instead of spinning). ⚠️ the unplug path itself needs a physical replug to exercise |
| 7 | 🟠 | `close()` blocked the caller for up to 1.5 s (`thread?.join(1500)`), and the callers are `switchMode()` / `onAppBackgrounded()` — the UI thread. | `DvrUsbCameraController.close()` | `close()` only signals now (and bumps `sessionGen`, so a reader that is still winding down cannot resume or overwrite the next session); the **reader** releases the interface and connection in its `finally`, so the UI never waits. `open()` tears the previous reader down through `stopReader()` with a bounded 800 ms join, which is only reachable when a mode is switched twice inside one transfer. | ✅ verified: HOME → `DVR stream released (volume can remount)` → resume → `claimed interface 0` and frames flowing again |
| 8 | 🟡 | A short or aborted `READ(10)` data phase left Bulk-Only Transport desynchronised — no reset, so every later command failed until the DVR was physically re-plugged. | `DvrBot.read10()` | Always consume the CSW (the device sends it with the residue), and only run the class-specific **Bulk-Only Mass Storage Reset** + CLEAR_FEATURE(HALT) when the CSW is genuinely missing — rate limited to once a second, and skipping the endpoint clears when the device rejects the reset. | ✅ 20 s runs show **zero** resets and no short reads |
| 9 | 🟡 | Every frame cost **two** round trips (~75–80 ms each) because the body read re-read the 16 KB header, and each read allocated a fresh ~90 KB array at ~15 fps. | `DvrBot` / `DvrUsbCameraController.readFrame()` | A frame is read into **one** pooled buffer, sized from the previous frame's header hint, and the header comes out of that same read. A frame that grew past the hint's 1 KB slack (or a mid-write slot) falls back to a single re-read sized from the fresh hint. Covered by `testDvrSingleTransferReadCoversAndFallsBack`. | ✅ two 20 s runs at **18.7** and **16.3 fps** (baseline 14–17), no fallbacks logged |
| 10 | 🟡 | `DvrVolume.probe()` ran on **every** app start whatever the source: volume enumeration, tree walk, 512 KB sample, 3 s sleep. | `MainActivity.onCreate` | Both DVR probes (volume *and* USB) now need the `DVR_PROBE` extra, like the USB one already did. | ✅ no `DvrVolume`/`DvrUsbProbe` lines in a normal start-up |
| 11 | 🟠 | `MANAGE_EXTERNAL_STORAGE` was declared for the DVR **volume diagnostics** only — the streaming path needs no storage access at all — and Google Play rejects that permission outside file managers, backup and antivirus apps. | `AndroidManifest.xml` | Moved to `app/src/debug/AndroidManifest.xml`, so release builds no longer request it. | ✅ manifest-level |
| 12 | 🟡 | Stale-frame handling advanced the ring when two consecutive polls returned identical JPEG bytes. `contentHashCode()` really is content-based and the DVR burns a 1 Hz OSD clock into every frame, so duplicates only occur inside one second — but a burst could skip several records. | `DvrUsbCameraController.readFrame()` | The advance is rate limited (≥30 ms since the last jump) and every target is still validated as a slot header. | ✅ every frame in both runs came from the same slot — no skipped records |

### Fixed after a hardware run — #8's first attempt made things worse

The first implementation of #8 reset the pipe *whenever a data phase came up short*. Running it
against the real DVR exposed the flaw immediately:

```
18:11:53.565 read10 stalled at 9229/16384; resetting the BOT pipe
18:11:53.971 BOT mass-storage reset -> -1          <- device rejects the request
18:11:54.387 CLEAR_FEATURE(HALT) ep 0x01 -> -1     <- ~420 ms each, and pointless
18:11:54.808 CLEAR_FEATURE(HALT) ep 0x81 -> -1
18:11:54.817 read10 stalled at 9229/16384; ...     <- and again, ~900 ms per retry
...
18:12:00.869 CBW write failed: -1                  <- device wedged, session gave up
```

Two mistakes in one place: skipping the CSW on a short data phase (leaving it in the pipe is exactly
what desynchronises the next command, so the "fix" *caused* the desync it was meant to cure), and
resetting eagerly on a benign race that the old code simply retried through. A short data phase is
normal here — the read starts while the DVR is rewriting the ring. Now the CSW is always consumed,
the reset only runs when there is no CSW at all, and it is rate limited. The same scenario is clean
in the next run: slot found on the first poll, no resets, no short reads.

---

## Fixed — 1.0.14 → 1.0.16 (mirror, audio, lanes, models)

Found while driving and while adding lane detection and the two extra detectors. **None of these
has been exercised on the tablet yet** (`adb devices` was empty during the work), so they are all
⚠️ rather than ✅: the code paths are unit-tested or numerically validated off-device, but the
hardware run is still owed.

| # | Sev | Bug | Evidence | Fix | Status |
| :-- | :-- | :--- | :--- | :--- | :-- |
| 13 | 🔴 | **A frozen DVR preview left the source "open" forever, and Connect did nothing.** The SAF path reads `VSFILE`; a stopped DVR still returns *valid bytes*, just never new ones, so the loop spun with `isOpen = true` and delivered no frames. `open()` began with `if (isOpen) return`, so the Connect button — and switching source and back — were both no-ops. Only killing the app cleared it. | Reading the path: `previewLoop()` looped on `running` alone and never compared the frame age; `open()` early-returns on `isOpen`. Matches the reported symptom ("lâu lâu video stuck, không connect lại được, phải tắt mở app"). | A stall clock (`lastFrameAtMs` / `sessionStartedAtMs`), a session restart after 8 s without a **new** frame, bounded by `MAX_FAILED_RECOVERIES` (3) with an explicit *tap Connect to retry*, a `sessionGen` guard so a slow read cannot resurrect a dead session, and `open()` now tears down a session that reports open but is stale instead of returning. | ⚠️ needs the frozen-DVR scenario on hardware |
| 14 | 🟠 | **The mirror was applied in the wrong place — or twice**, so it did nothing on one source and half-worked on the others. UVC: the software decoder flipped its own frames *and* `applyMirrorMode` flipped both the SurfaceView and the overlay — video ended up net-unmirrored while the overlay was flipped once, so **boxes sat mirrored relative to the picture** and the button looked dead. DVR/WiFi: only the views were flipped, so the screen was right but the **recording** stayed un-mirrored. | `MjpegDecoder.setMirror` plus `applyMirrorMode`'s `scaleX = -1` on the same UVC path; `MjpegFrameSink` had no notion of a mirror at all. | JPEG sources flip the decoded bitmap inside `MjpegFrameSink`, so preview, recorder and detectors see one corrected picture; the SurfaceView flip is skipped for those sources (`CameraCoordinator.mirrorsInFrames()`). The "rear lens is never mirrored" exception is unchanged. | ⚠️ |
| 15 | 🟠 | **The warning tone cut the navigation voice.** `ToneGenerator` on `STREAM_ALARM`: the OEM audio policy treats the alarm stream as exclusive, so Maps/Waze ducked or paused for the beep. | The old `AlertTone` opened `ToneGenerator(AudioManager.STREAM_ALARM, 80)`. | `SoundPool` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE` and **no audio-focus request**, so the tone mixes instead of interrupting; two preloaded WAVs (120 ms caution, 345 ms three-pulse warning) warmed in `onCreate` so the first warning is not swallowed. | ⚠️ verify against Maps with voice guidance on |
| 16 | 🟠 | **The lane edge sanity check had its slope sign backwards.** The two markings converge towards the vanishing point, so going up the frame the *left* edge moves right. The check demanded the opposite and therefore rejected every right edge — a clean synthetic road produced `laneWidth = NaN` with only the left lane found. | Caught by `testLaneDetectorFindsCentredLane` before the feature ever shipped: `L=12 R=0`. | `fit(points, leftSide)` with the correct sign per side, plus the two unit tests that pin it. | ✅ unit tests |
| 17 | 🟡 | **The UVC source delivered no pixels when only lanes were on.** `configureFrameCallback` requested NV21 only for detection or recording, and the frame callback returned early when `detector == null` — so with DETECT off and LANES on there was nothing to analyse. | Reading `configureFrameCallback` + the `det == null` early return in `frameCallback`. | The lane switch is part of `needPixels`; the callback branches into detect / lane-only. The lane-only branch copies just the Y plane into its own buffer so it cannot race an inference that is still reading `nv21`. | ⚠️ |
| 18 | 🟡 | **NanoDet's head is not a YOLO head, and decoding it as one produces imaginary boxes.** `112` channels per anchor = 80 sigmoid class scores + 4 sides × 8 distribution bins; `YoloHead` only understands `4+nc` / `5+nc` and would read regression logits as classes. It is also trained on a **stretched, BGR, mean/std-normalised** input, not a letterboxed RGB one. | Output shape `(1, 2125, 112)` from the published ONNX; `_forward_onnx` in the NanoDet source. | `HeadDecoder` / `Preprocessor` interfaces; `NanoDetHead` (softmax-integral per side, class-aware NMS at 0.6) and `Stretch` (no padding, BGR, published mean/std). A mismatched pairing is now rejected with a warning instead of emitting boxes. | ✅ off-device: ncnn vs ONNX correlation 0.9998; both decode `bus`/`person` correctly on a real photo |
| 19 | 🟡 | **A stale lane overlay survived a camera-source switch** — lanes fitted for the DVR picture stayed drawn over the phone camera. | `switchMode()` never touched the overlay's lane state. | `clearLanes()` in `applySavedSettings()` and in the source picker dialog, plus `LaneAnalyzer.reset()` on every source open. | ⚠️ |

### Fixed in 1.0.17

| # | Sev | Bug | Evidence | Fix | Status |
| :-- | :-- | :--- | :--- | :--- | :-- |
| 25 | 🟠 | **A named model that was not packaged was silently replaced by a different one.** `resolveAsset()` fell back to "whatever `.onnx` is bundled" whenever the requested name was missing — but the head decoder and the pre-processor are chosen from the *requested* name. Ask for NanoDet, get a YOLOv10 graph, and `NanoDetHead` rejects the channel count: detection reports **zero boxes** with the real reason buried in one warning line. | `resolveAsset`: `if (assetExists(...)) return requested` then unconditional fallback. Triggered for real by the next entry: dropping the two `.onnx` files makes their preference ids point at nothing. | `chooseAsset()` — a named-but-absent model resolves to `null` (refuse, log why); only a **blank** preference may use anything bundled. Pure and unit-tested (`testChooseAssetNeverSubstitutesANamedModel`), and `DetectorFactory` no longer even offers ONNX Runtime for an ncnn-only model, so the warning is not logged on every start. | ✅ unit tests |

---

## Fixed in 1.0.18 — external review findings

An independent review of the 1.0.16 working tree arrived as `CODE_REVIEW_AND_PLAN.md`. Four of its
claims were checked against the code and the real model files: **three were right** (one of them
catastrophic), **two were rejected** with the reason recorded below. Everything accepted is fixed
here.

| # | Sev | Bug | Evidence | Fix | Status |
| :-- | :-- | :--- | :--- | :--- | :-- |
| 26 | 🔴 | **`NanoDetHead` read the head reversed: class scores where the box distributions are, and vice versa.** The graph emits **80 class scores first, then 4×8 bins**; the decoder read classes at `4*BINS + c` and bins at `group*BINS`. Every NanoDet detection would have been noise ("car 1200%"), never a usable box. | Measured on the published `nanodet-plus-m_320.onnx`: columns 0..79 ∈ [0, 1] (sigmoid applied in-graph), columns 80..111 ∈ [−12.6, 4.3] (unbounded logits). The unit test did not catch it because `nanoDetRow()` built its synthetic rows in the *same* wrong order — it tested the implementation against itself. | Classes read at `[0, labels.size)`, bins at `labels.size + group*8`. Plus a real guard: a class score **above 1** is impossible after sigmoid, so it is dropped and logged as a layout error instead of painting nonsense. Test helper rebuilt from the graph's layout, and `testNanoDetHeadRejectsInvertedRowLayout` fails if the halves are ever swapped again. | ✅ verified end-to-end against the real ONNX on a real photo: `bus 0.848` |
| 27 | 🟠 | **Choosing a model was silently reverted.** `AusbcCameraController` wrote `OnnxDetector.activeAsset` back into the preference — but that field is only set when an ONNX Runtime session is built, and on this device ncnn wins. Pick YOLO26n, and the stale `yolov10n-224.onnx` was written straight back; the provider cache was keyed to the wrong model too. | `if (OnnxDetector.activeAsset.isNotEmpty() && HudPrefs.modelAsset(ctx) != ...) setModelAsset(...)`. Made certain by 1.0.17: the two new models are ncnn-only, so ONNX is *never* consulted for them. | The preference is never rewritten from there; the provider cache is keyed by the id that was requested, which is what the next launch asks for again. | ✅ code review |
| 28 | 🔴 | **The UVC camera stayed dead after a bump.** A bounce unplugs and replugs within tens of ms: the attach arrives while `closeCamera()` is still unwinding, hits `if (openInFlight || camera != null) return`, and is dropped — that attach was the only connect signal left. And `startWatchdog()` was a **no-op**, so a stream that stopped delivering frames without a detach froze the HUD on its last picture forever. | `openCamera` early return + `startWatchdog` logging "no-op: engine handles detach"; `armStreamWatchdog` only checks startup. | A refused open is retried (5 × 400 ms, control block closed when giving up) instead of dropped, and `startWatchdog` became a real 2 s watchdog: after 5 s without a frame it closes and reopens the camera, no more often than every 8 s, and the budget resets on the first frame that arrives. | ⚠️ needs a physical bounce/replug |
| 29 | 🟠 | **Two different meanings shared one switch**, and the latest version of it put the boxes off the picture: with `MIRROR` on, DVR/WiFi flipped the decoded pixels *and* the overlay view, so lanes and boxes were mirrored relative to the video they annotate. Meanwhile the original windshield use case (mirror the screen, keep the recording normal) had become impossible. | `applyMirrorMode` flipping `hudOverlayView` while `CameraCoordinator.mirrorsInFrames()` is true. | Split: **MIRROR** = camera mounted backwards (pixels flipped on the JPEG sources so the recording matches), **HUD FLIP** = windshield reflection (view only, recording stays normal). They are mutually exclusive by construction — flipping both cancels out. | ⚠️ |
| 30 | 🟠 | **DVR lifecycle was not serialised.** `open()` runs on the UI thread *and* from the preview reader's stall recovery, `close()` on the UI thread, `reclaim()` on the reader — all mutating `thread`, `connection` and the permission receiver with no lock. Two sessions could be started from one interface claim. | No `synchronized` outside `releaseLock` (which only guards the connection hand-over). | A `lifecycleLock` around `open`/`close`/`stopReader`/`reclaim`/receiver registration, plus `wantOpen` so a restart already in flight cannot reopen a source the app has closed, and the restart runs on its own short-lived thread so a dying reader is never joined while it waits for the lock. | ⚠️ |
| 31 | 🟠 | **The DVR gave up after three failed re-claims**, which a bouncing OTG contact can burn through in a couple of seconds. | `MAX_FAILED_RECOVERIES = 3`. | Raised to 5; the counter is still reset by the first frame that arrives, so only a genuinely absent DVR reaches the limit. | ⚠️ |
| 32 | 🟡 | **`AlertTone` fields were plain `var`s** written from `onCreate`/`onDestroy` and read from the inference thread, and `SoundPool.play()` returns 0 while the samples are still decoding — the first warning could be silent. | Field declarations; `play` return value ignored. | Fields volatile, creation and release under a lock, a load-complete counter, and exactly one delayed retry (180 ms) when a warning fires before the samples are ready. | ⚠️ |
| 33 | 🟠 | **Model changes did not reach a running session** (the new model was only used after a source switch), the `LANE DEPARTURE` banner subtracted the control-bar inset even when the picture was letterboxed well above it, and the mirror path allocated a `Canvas` per frame. | — | `reloadDetector()` on all five controllers, triggered from `applySavedSettings()` when the pref changes; banner clamped to `min(rect.bottom, barTop)`; one cached `Canvas` bound to the scratch bitmap. | ⚠️ |
| 34 | 🔴 | **Changing the model while a camera was open could crash the process.** The new `reloadDetector()` (and every teardown path) closed the old native session inline, on the UI thread. A pass already running on the inference thread still holds that session: ncnn faults inside `Extractor::extract` when it is freed underneath it, and ONNX Runtime is not safe either. Reachable from Settings → Detection model with the camera live, and from `destroy()` on every source. | `detector?.close()` immediately followed by `detector = null` in `MjpegFrameSink`, `SystemCameraController` and `AusbcCameraController.closeDetector()`. | The close is now **queued on the same single-threaded executor** that runs inference, so it can only happen after the last pass finishes; `shutdown()` still drains that queue at teardown. One `closeDetectorLater()` per source, used by both reload and destroy. | ✅ code review |


### Reviewed and rejected from that review

- **"Phone-camera bounding boxes are rotated 90° relative to the preview."** Not in this app. The
  preview is a **`SurfaceView` fed the same raw sensor buffer** as the `ImageReader` — there is no
  `TextureView` transform and no `setDisplayOrientation`, so preview and analysis share one
  orientation and boxes cannot drift from the picture. The real limitation is the opposite one: in
  portrait the *picture itself* is unrotated, which is why the HUD is used in landscape. Adding
  rotation would change a path that works on the target device with no way to verify it here.
- **"`tools/export_detection_models.sh` cannot run standalone because `yolo26n.pt` is not on
  PyPI."** It does not need PyPI: `YOLO("yolo26n.pt")` downloads the weights from the Ultralytics
  release assets, which was verified by running the export from a clean virtualenv.

---

## ⬜ Open — next up (plan)

Ordered by what a hardware run would settle first. Each item names the thing to look at, so it is
verifiable rather than a wish list.

| # | Sev | Item | How to verify | Status |
| :-- | :-- | :--- | :--- | :-- |
| 20 | 🔴 | **Nothing from 1.0.14–1.0.16 has run on the tablet.** Start-up, the DVR stall recovery, the mirror on each source, and lane drawing are all unverified on hardware. | `adb install -r` 1.0.16, then: DVR → HOME → resume; unplug mid-stream; `MIRROR` on DVR (preview **and** the recording); `LANES` on DVR. Expected log lines: `preview loop finished`, `stream loop finished`, `re-claiming the DVR interface`, `restarting the DVR preview session`. | ⬜ open |
| 35 | 🔴 | **Boxes on the *System camera* (Camera2) land in the wrong place — reported from the device, wrong in every device orientation and on both lenses.** Not reproducible in code review, and the earlier rejection of "boxes are rotated" was reasoning, not evidence. What is certain: the analysis frame and the drawn preview do not describe the same picture. Prime suspects, in order: the `YUV_420_888 → NV21` copy (stride/crop), the `ImageReader` size vs the `SurfaceView` fixed size, and the missing sensor-vs-display rotation on the analysis path. | Reported by the driver on hardware. `frameSize()` and `setFixedSize` agree on `frameW × frameH`, and no code rotates the analysis or the preview, so one of them must be feeding the detector a different image than the one on screen. |
| 36 | 🟠 | **Diagnostic dump for #35.** Each System-camera session now writes, on its third detection pass, `files/diag/analysis-<ts>.png` (exactly the luma plane the detector received), `boxes-<ts>.png` (the same with the returned boxes drawn) and `geometry-<ts>.txt` (`SENSOR_ORIENTATION`, display rotation, requested size, preview surface size, plane strides). | Pull with `adb pull /sdcard/Android/data/com.driving.hud.usbcamera/files/diag/`. If the boxes sit on the objects **in `analysis.png`**, the analysis differs from the preview → the fix is a transform (rotation/size). If they do not, the decoder is at fault. | ⬜ open — needs the dump |
| 21 | 🟠 | **The three bundled detectors have never been timed against each other on the device**, and the *System camera* source has not been re-tested at all since lanes, the model picker and the mirror split landed (it exercises a different code path: `ImageReader` + its own detector and lane submit). The published numbers say YOLO26n @256 > YOLOv10n @224, but ncnn/Vulkan on this GPU is what actually decides. | Settings → Detection model → each option, read `INFER xx ms` and the FPS line on the HUD; confirm box alignment with `MIRROR` off, then pick a default and record it in the README. | ⬜ open |
| 22 | 🟠 | **APK is now 93.8 MB** (was 70 MB) because each new model ships both an ONNX and an ncnn graph. | Decide whether to drop the two new `.onnx` files (−14.6 MB). Doing so requires `resolveAsset` to **refuse** instead of falling back (see residuals) — otherwise ONNX Runtime would silently load a different model. | ✅ 1.0.17: the two `.onnx` files are gone (APK back to **80.7 MB**, from 93.8) and #25 removes the silent substitution, so ONNX Runtime is no longer offered for them at all. |
| 23 | 🟠 | **Lane detection is untested on real Vietnamese roads**, where markings are often faint, worn, or hidden by motorbikes. The synthetic tests only prove the geometry. | Drive a stretch with a clear lane and one with worn paint; count false positives. Tuning knobs: `EDGE_PERCENTILE` (0.90), `MIN_WINDOW_PIXELS` (10), `ROI_TOP` (0.58). If it is still noisy, that is the point to switch to UFLDv2-tiny. | ⚠️ 1.0.17 instruments it: the HUD shows `LANE +0.12 · 1.4ms` and one log line every ~5 s carries `thr`, per-side window counts and the offset — the values needed to tune instead of guess. Still needs a drive. |
| 24 | 🟡 | **The UVC lane-only path copies the luma plane twice** (`laneLuma`, then `LaneAnalyzer`'s own buffer) — ~0.9 MB per pass. | `LaneAnalyzer.submit` copies unconditionally so it can return before the executor runs. A `submitOwned()` variant would remove one copy; measure before bothering. | ✅ 1.0.17: `submitOwned()` added and used by the UVC lane-only path, which writes that buffer only while no pass is in flight. |

---

## Known residuals (accepted, documented)

- **The shoot-out still loads ncnn twice when ncnn wins.** Every candidate is torn down before the
  next is built, because two live ncnn sessions fault inside `ncnn::Extractor::extract` with this
  libncnn build. It now happens once per model + frame size instead of on every connect.
- **A reused session still costs seconds of native init** (model load plus Vulkan pipelines). That is
  what the prewarm is for; it is now paid while the home screen is showing.
- **`open()` can wait up to 800 ms** for a previous reader to hand the interface back — only when a
  mode is switched twice within a single USB transfer.
- **`MIRROR` cannot mirror the recording on the UVC and system-camera sources.** Those paths draw
  straight to the SurfaceView, so the flip is a view transform and the file keeps the sensor's
  orientation. Only the JPEG sources (DVR, WiFi) can flip the pixels without a second buffer.
- **The alert tone follows media volume**, not the alarm volume. That is the price of not taking
  audio focus: at media volume 0 the warning is silent, which no longer has the alarm-stream
  override it used to have.
- **The DVR's own frame pacing is the limit**, not the read loop: the single-transfer read saves
  roughly one round trip per frame (~10 ms), and two runs measured 18.7 and 16.3 fps against a
  14–17 fps baseline. Getting past that needs the vendor command channel (`setResolution` is never
  used by their app either, so 720p is what this device outputs).

---

## Reviewed and rejected (not bugs)

Checked against the code and the device logs, so they are not carried above:

- **"`WifiCameraController` self-joins and blocks for 1 s"** — `close()` calls
  `streamThread?.interrupt()` and nulls it; there is no `join` anywhere in that file. The only joins
  in the app are in `DvrUsbCameraController`, `UsbCameraManager` and `MkvRecorder`, none of which run
  on their own thread.
- **"The DVR never auto-connects when plugged in"** — `MainActivity` declares
  `USB_DEVICE_ATTACHED` with the `device_filter` meta-data and handles it in `onNewIntent` →
  `scanAndConnect()` → `dvrController.open()`. The `device_filter.xml` entry for `6402:12865` is
  exactly what makes the attach intent arrive at our app.
- **"`read10` churns 12–15 MB/s"** — a frame read is ≈149 blocks ≈ 76 KB plus the 16 KB header, so
  ~92 KB per frame, ~1.5 MB/s at 15 fps. Worth pooling (see #9), an order of magnitude below the
  reported figure.

---

## Device notes found while verifying

- The U2 DVR flaps (`num_connects` climbing, `product_name=null`, so it stops matching
  `VID 0x1902 / PID 0x3241` and the app reports "U2 DVR not connected") whenever the vendor app
  `com.fvision.camera` is enabled — it grabs the interface on every attach. **The Lenovo's own
  "AutoRun" service re-enabled it after we disabled it**, so `pm disable-user --user 0
  com.fvision.camera` may need repeating after a reboot or a reinstall.
- The tablet's `/sys/bus/usb/devices` is not readable from `adb shell` (SELinux), so a degraded DVR
  cannot be re-enumerated in software — it needs a physical replug.
- `dumpsys usb` prints `vendor_id=6402` for *device filters*, so look at the
  `device_name=/dev/bus/usb/...` records (with a real `product_name`) to tell whether the device is
  actually enumerated.
