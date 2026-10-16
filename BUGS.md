# Bug list

Findings from on-device testing and code review of the multi-source camera work (`65fa9b1` and the
fixes on top of it). Every entry is verifiable — either by reading the code path named in it, or by
reproducing it on the Lenovo TB331FC with `tools/debug_tablet.sh`.

Legend: **🔴 high** (blocks or freezes the UI) · **🟠 medium** (wrong behaviour / wasted battery) ·
**🟡 low** (robustness, cosmetics).

---

## Fixed — detector start-up cost (2026-10-16)

The report that started this: *at start-up there is a stretch where the CPU is pegged at 200 %+ and
the model gets loaded again*. All four entries below feed the same defect — the JPEG sources
(WiFi MJPEG and the U2 DVR) built a detector the worst possible way.

| # | Severity | Bug | Evidence | Fix |
| :-- | :-- | :--- | :--- | :--- |
| 1 | 🔴 | **Detection toggle ignored by the JPEG sources.** With *Detect objects* switched off in Settings, the WiFi/DVR path still built a detector and still ran inference on every frame — the switch only affected the USB and System paths. | `MjpegFrameSink.isDetDue` had no `HudPrefs.detect` check, unlike `SystemCameraController.kt:518` and `AusbcCameraController.kt:771`. Tablet prefs had `detect_interval_ms=0`, i.e. a pass on **every** frame when the switch was off. | `MjpegFrameSink` now checks `HudPrefs.detect(context)` both in `initDetector()` (no session is built at all) and per frame in `isDetDue`. |
| 2 | 🔴 | **Backend shoot-out ran inline on the UI thread.** `open()` → `sink.prepare()` → `DetectorFactory.create(..., only = null)` did a first-run benchmark of every backend on the main thread. | Tablet log: `15:50:32.833 onResume` → `15:50:46.658` winner rebuilt → `15:50:46.659 Connected`. **13.8 s of main-thread work**, i.e. an ANR for the first tap; several native threads saturate every core for the whole stretch — the 200 %+ CPU. | The sink now builds on its `inferExecutor` with a generation guard, so the UI keeps rendering and a build that finishes after the source closed is discarded. |
| 3 | 🔴 | **The winning backend was never remembered for the JPEG sources.** `DetectorFactory.create(..., only = null)` always meant "measure everything", so the shoot-out — load ncnn (4.5 MB) **and** ONNX (9 MB), time them, then load ncnn again to rebuild the winner — re-ran on every connect, for a result that never changes. | `MjpegFrameSink.kt:87` passed `null`; the USB and System paths pass the remembered provider and store it with `HudPrefs.setDetectProvider`. | Reuses `detectProviderKey`/`detectProvider` the same way (`"<model>@<w>x<h>"`), and remembers the winner when the shoot-out is genuinely needed (new model or frame size). |
| 4 | 🟠 | **No prewarm for the JPEG sources.** Even a reused session costs seconds of native init, and it landed on the first frame. | `CameraCoordinator.prewarmDetector()` only handled `MODE_SYSTEM`. | `prewarmDetector()` now covers `MODE_WIFI` and `MODE_DVR`, so the session is built from `onCreate` while the home screen is showing. |

**Residual cost, accepted:** the shoot-out still loads ncnn twice when ncnn wins — every candidate is
closed before the next is built, because two live ncnn sessions fault inside
`ncnn::Extractor::extract` with this libncnn build. With #3 in place that only happens once per
model + frame size instead of on every connect.

**Residual cost, open:** `detect_provider` / `detect_provider_key` is a single global pair, so a
source that negotiates a different frame size (say a 1920×1080 system camera) overwrites the DVR's
1280×720 entry and switching back re-runs the shoot-out. Fix sketch: store a small map keyed by
model + size instead of one pair.

---

## Open

| # | Severity | Bug | Where | Notes |
| :-- | :-- | :--- | :--- | :--- |
| 5 | 🟠 | `locateSlot()` never checks `running`, so `close()` cannot stop it: 9 candidate slots plus a 16 MB scan in 128-block steps (up to 256 BOT round trips, 1000 ms timeout each) can outlive the `join(1500)`. The stream then keeps talking to a connection that is being closed. | `DvrUsbCameraController.locateSlot()` | Add `if (!running) return false` inside the scan loop. |
| 6 | 🔴 | A pulled cable or a DVR mode change is never reported: `readFrame()` returns null, `streamLoop` sleeps 60 ms and retries — and each retry re-runs the **whole** slot search. No `listener.onError()` / `onDisconnected()`, and unlike the UVC path there is no detach receiver for the DVR at all. | `DvrUsbCameraController.streamLoop()` | Count consecutive failures (or watch for `UsbManager` detach) and surface an error instead of spinning. |
| 7 | 🟠 | `close()` blocks the caller for up to 1.5 s, and the callers are `CameraCoordinator.switchMode()` and `onAppBackgrounded()` — both on the UI thread. | `DvrUsbCameraController.close()` (`thread?.join(1500)`) | Join off the main thread, or rely on the `running` flag plus #5. |
| 8 | 🟡 | A short or aborted `READ(10)` data phase leaves Bulk-Only Transport desynchronised — no `clearHalt()` and no Bulk-Only Mass Storage Reset, so every later command can fail until the DVR is physically re-plugged. | `DvrBot.read10()` | On `received < length`, run the BOT reset sequence before the next command. |
| 9 | 🟡 | `DvrBot.read10()` allocates a fresh `ByteArray` per call, and every frame costs **two** round trips (~75–80 ms each) because the slot body read re-reads the 16 KB header. That is ~92 KB and two transfers per frame at 14–17 fps. | `DvrBot.read10()` / `DvrUsbCameraController.readFrame()` | Pool the buffer and derive the body read from the header read so a frame costs one transfer. |
| 10 | 🟡 | `DvrVolume.probe()` runs on **every** app start, whatever the camera source. The expensive part (a tree walk of the DVR volume, a 512 KB sample, a 3 s sleep) only happens when a mounted volume actually holds `VSFILE`, but the probe itself is unconditional. | `MainActivity.onCreate` | Gate it behind the existing `DVR_PROBE` extra, like `DvrUsbProbe`. |
| 11 | 🟠 | `MANAGE_EXTERNAL_STORAGE` is declared for the DVR **volume diagnostics** only — the streaming path reads MJPEG over USB Bulk-Only Transport and needs no storage access at all. Google Play restricts that permission to file managers, backup and antivirus apps and rejects the rest. | `AndroidManifest.xml` | Needs a decision: move the diagnostics (and the permission) to a debug-only manifest, or drop the volume probe. |
| 12 | 🟡 | Stale-frame handling advances the slot when two consecutive polls return identical JPEG bytes. `contentHashCode()` really is content-based, and the DVR burns a 1-second OSD clock into every frame, so in practice the bytes change at least once a second and the measured 14–17 fps delivery is smooth. Only two genuinely identical frames would skip a slot. | `DvrUsbCameraController.readFrame()` | Low priority; a frame counter in the slot header would make it exact. |

---

## Reviewed and rejected (not bugs)

Checked against the code and the device logs, so they are not carried in the list above:

- **"`WifiCameraController` self-joins and blocks for 1 s"** — `close()` calls
  `streamThread?.interrupt()` and nulls it; there is no `join` anywhere in that file. The only joins
  in the app are `DvrUsbCameraController.kt:279`, `UsbCameraManager.kt:1129` and
  `MkvRecorder.kt:290`, none of which run on their own thread.
- **"The DVR never auto-connects when plugged in"** — `MainActivity` declares
  `USB_DEVICE_ATTACHED` with the `device_filter` meta-data and handles it in `onNewIntent` →
  `scanAndConnect()` → `dvrController.open()`. The `device_filter.xml` entry for
  `6402:12865` added in `65fa9b1` is exactly what makes the attach intent arrive at our app.
- **"`read10` churns 12–15 MB/s"** — a frame read is `blocksFor()` ≈ 149 blocks ≈ 76 KB plus the
  16 KB header, so ~92 KB per frame, ~1.5 MB/s at 14–17 fps. Worth pooling (see #9), but an order of
  magnitude below the reported figure.
