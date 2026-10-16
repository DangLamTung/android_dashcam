# Bug list

Findings from on-device testing and code review of the multi-source camera work. Every entry is
verifiable — by reading the code path named in it, or by reproducing it on the Lenovo TB331FC with
`tools/debug_tablet.sh`.

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
| 6 | 🔴 | A pulled cable was never reported: `readFrame()` returned null, the loop slept 60 ms and re-ran the **whole** slot search forever, with no `onError`/`onDisconnected` (and unlike the UVC path there was no detach receiver for the DVR). | `DvrUsbCameraController` | Registers `ACTION_USB_DEVICE_DETACHED` so an unplug closes and reports `onDisconnected` immediately; 20 empty polls report a status, 60 raise `onError` and trigger a **self-repair** re-claim, then retries continue so a temporary stall recovers by itself. | ✅ stall detection verified (a wedged session stopped the loop and reported instead of spinning). ⚠️ the unplug path itself needs a physical replug to exercise |
| 7 | 🟠 | `close()` blocked the caller for up to 1.5 s (`thread?.join(1500)`), and the callers are `switchMode()` / `onAppBackgrounded()` — the UI thread. | `DvrUsbCameraController.close()` | `close()` only signals now; the **reader** releases the interface and connection in its `finally`, so the UI never waits. `open()` joins a lingering reader with a bounded 800 ms timeout, which is only reachable when a mode is switched twice inside one transfer. | ✅ verified: HOME → `DVR stream released (volume can remount)` → resume → `claimed interface 0` and frames flowing again |
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

## Known residuals (accepted, documented)

- **The shoot-out still loads ncnn twice when ncnn wins.** Every candidate is torn down before the
  next is built, because two live ncnn sessions fault inside `ncnn::Extractor::extract` with this
  libncnn build. It now happens once per model + frame size instead of on every connect.
- **A reused session still costs seconds of native init** (model load plus Vulkan pipelines). That is
  what the prewarm is for; it is now paid while the home screen is showing.
- **`open()` can wait up to 800 ms** for a previous reader to hand the interface back — only when a
  mode is switched twice within a single USB transfer.
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
