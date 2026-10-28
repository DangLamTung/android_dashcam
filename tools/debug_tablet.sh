#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# On-device debug session for the SYSTEM (built-in) camera path.
#
# Runs the app on a connected device with the camera source forced to the tablet's
# own rear lens, drives it straight into the HUD (so the SurfaceView exists and a
# preview session can be created), then collects the evidence.
#
# Usage:
#   tools/debug_tablet.sh [--serial SERIAL] [--no-build] [--detect] [--secs N]
#
#   --serial S    target a specific adb serial (default: the first device)
#   --no-build    skip :app:assembleDebug and install the existing APK
#   --source S    camera source to force: system (default), usb, wifi or dvr
#   --detect      also enable object detection (exercises the analysis stream)
#   --secs N      how long to let the app stream before sampling (default 15)
#
# Why logcat and not the log file: HudLogger mirrors every line to logcat, and on
# Android 11+ /sdcard/Android/data/<pkg> is NOT adb-pullable, so logcat is the
# reliable channel.
# ---------------------------------------------------------------------------
set -uo pipefail

PKG=com.driving.hud.usbcamera
ACT="$PKG/.MainActivity"
EXTRA_KEY=com.driving.hud.usbcamera.START_CALIBRATION

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
export JAVA_HOME=${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}

SERIAL=""
DO_BUILD=1
DETECT=false
SECS=15
SOURCE=system

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) SERIAL="$2"; shift 2 ;;
        --no-build) DO_BUILD=0; shift ;;
        --source) SOURCE="$2"; shift 2 ;;
        --detect) DETECT=true; shift ;;
        --secs) SECS="$2"; shift 2 ;;
        -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

case "$SOURCE" in
    system|usb|wifi|dvr) ;;
    *) echo "!! --source must be system, usb, wifi or dvr (got '$SOURCE')" >&2; exit 2 ;;
esac

if [ "$SOURCE" = usb ]; then
    echo "== NOTE: the source is forced to USB (UVC), so the OTG camera must be attached to the"
    echo "   tablet, and the tablet's own USB-C link is not available as an adb transport."
fi

if [ "$SOURCE" = dvr ]; then
    echo "== NOTE: the U2 DVR (mass-storage Buildwin dashcam) must be attached, its volume must be"
    echo "   free (com.fvision.camera grabs the interface), and this app needs USB access to it."
fi

A() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }

OUT="$ROOT/build/debug-session"
mkdir -p "$OUT"

# ---------------------------------------------------------------- device
if [ -z "$SERIAL" ]; then
    SERIAL=$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
    echo "!! No adb device. Either plug the tablet in over USB, or run:"
    echo "   adb connect <tablet-ip>:<port>      # port 5555 after 'adb tcpip 5555'"
    "$ADB" devices -l
    exit 1
fi
echo "== device: $SERIAL ($(A shell getprop ro.product.model | tr -d '\r'), SDK $(A shell getprop ro.build.version.sdk | tr -d '\r'))"

# ---------------------------------------------------------------- build
if [ "$DO_BUILD" = 1 ]; then
    echo "== building"
    ( cd "$ROOT" && ./gradlew :app:assembleDebug --no-daemon -q ) || { echo "!! build failed"; exit 1; }
fi
[ -f "$APK" ] || { echo "!! $APK missing (run without --no-build)"; exit 1; }

echo "== installing"
INSTALL_OUT=$(A install -r "$APK" 2>&1)
echo "$INSTALL_OUT" | tail -2
case "$INSTALL_OUT" in
    *Success*) ;;
    *)
        # Without this the run silently exercises whichever build is already installed, which is
        # exactly how a session can "verify" a fix that never reached the device.
        echo
        echo "!! install FAILED — aborting, because the results would come from the OLD build."
        case "$INSTALL_OUT" in
            *signature*|*UPDATE_INCOMPATIBLE*)
                echo "   The installed app was signed with a different key. Back up, then reinstall:"
                echo "     adb -s $SERIAL pull /sdcard/Android/data/$PKG/files/Movies $OUT/backup-Movies"
                echo "     adb -s $SERIAL shell run-as $PKG cat shared_prefs/hud_prefs.xml > $OUT/backup-hud_prefs.xml"
                echo "     adb -s $SERIAL uninstall $PKG && adb -s $SERIAL install -r $APK"
                ;;
        esac
        exit 1
        ;;
esac

# The system camera needs CAMERA granted; without it openCamera throws and the run
# is wasted. Pre-granting also suppresses the dialog that would block a headless run.
A shell pm grant "$PKG" android.permission.CAMERA 2>/dev/null

# ---------------------------------------------------------------- prefs
# Force the camera source so the run does not depend on tapping the UI, but MERGE into the
# app's existing prefs. Overwriting the file also drops the remembered detector backend, which
# forces a ~15 s backend shoot-out on every single run and badly distorts any latency
# measurement. Written with push + cp: a `run-as ... sh -c 'cat > file'` redirect fails.
A shell "run-as $PKG cat shared_prefs/hud_prefs.xml" > "$OUT/prefs-before.xml" 2>/dev/null
if [ -s "$OUT/prefs-before.xml" ] && grep -q "<map>" "$OUT/prefs-before.xml"; then
    python3 - "$OUT/prefs-before.xml" "$OUT/hud_prefs.xml" "$SOURCE" "$DETECT" <<'PY'
import re, sys
src, dst, source, detect = sys.argv[1:5]
xml = open(src, encoding='utf-8', errors='replace').read()

# SharedPreferences writes strings as an element with text (<string name="x">v</string>) but
# booleans/ints self-closing with a value attribute. Writing a string the boolean way silently
# produces an entry with no text, and the app then falls back to its default -- which is how a
# merge can leave the camera source unchanged.
def put(text, kind, name, value):
    if kind == 'string':
        pat = r'<string name="%s">[^<]*</string>' % re.escape(name)
        new = '<string name="%s">%s</string>' % (name, value)
    else:
        pat = r'<%s name="%s"[^>]*/>' % (kind, re.escape(name))
        new = '<%s name="%s" value="%s" />' % (kind, name, value)
    if re.search(pat, text):
        return re.sub(pat, new, text)
    return text.replace('</map>', '    %s\n</map>' % new)

xml = put(xml, 'string', 'camera_mode', source)
xml = put(xml, 'string', 'system_facing', 'back')
xml = put(xml, 'boolean', 'detect', detect)
# Drop any malformed self-closing string entries a previous run may have left behind.
xml = re.sub(r'\s*<string name="[^"]+" value="[^"]*" />', '', xml)
open(dst, 'w', encoding='utf-8').write(xml)
PY
    echo "== prefs merged (kept remembered backend + calibration)"
else
    cat > "$OUT/hud_prefs.xml" <<XML
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="camera_mode">$SOURCE</string>
    <string name="system_facing">back</string>
    <boolean name="detect" value="$DETECT" />
    <int name="preview_width" value="1280" />
    <int name="preview_height" value="720" />
</map>
XML
    echo "== prefs written fresh (no existing prefs to merge)"
fi

A shell am force-stop "$PKG"
A push "$OUT/hud_prefs.xml" /data/local/tmp/hud_prefs.xml >/dev/null
A shell "run-as $PKG mkdir -p shared_prefs" >/dev/null 2>&1
if A shell "run-as $PKG cp /data/local/tmp/hud_prefs.xml shared_prefs/hud_prefs.xml" 2>/dev/null; then
    echo "== prefs forced: camera_mode=$SOURCE detect=$DETECT"
else
    echo "!! could not write shared_prefs (is this a release build? use the debug APK) — UI selection needed"
fi

# ---------------------------------------------------------------- run
# START_CALIBRATION makes MainActivity enter the HUD, which is what brings the
# preview SurfaceView into existence. Without the HUD there is no surface to render to.
A logcat -c 2>/dev/null
A shell am start -n "$ACT" --ez "$EXTRA_KEY" true >/dev/null
if [ "$SOURCE" = usb ]; then
    # The USB permission dialog is a system dialog: nothing can pre-grant it, so the run is only
    # meaningful once someone taps Allow. Say so before the sleep, not after.
    echo "== TAP Allow on the USB access dialog now, then let it stream"
fi
echo "== streaming for ${SECS}s"
sleep "$SECS"

SHOT="$OUT/screen_$(date +%H%M%S).png"
A exec-out screencap -p > "$SHOT" 2>/dev/null
[ -s "$SHOT" ] && echo "== screenshot: $SHOT"

LOG="$OUT/logcat.txt"
A logcat -d -v time -s SystemCameraCtrl:V MainActivity:V HudSurfaceView:V UVCCameraEngine:V UsbCameraManager:V CameraCoordinator:V DvrCameraCtrl:V DvrBot:V DvrVolume:V DvrUsbProbe:V CrashReporter:V AndroidRuntime:E > "$LOG" 2>/dev/null
echo "== logcat: $LOG ($(wc -l < "$LOG" | tr -d ' ') lines)"

# A crash takes the process down before any of the tags above can explain it, and it lands in
# the crash buffer rather than main, so it needs its own capture.
CRASH="$OUT/crash.txt"
A logcat -d -v time -b crash > "$CRASH" 2>/dev/null
if [ -s "$CRASH" ] && grep -q "$PKG" "$CRASH"; then
    echo "== CRASH DUMP: $CRASH"
    grep -A30 "FATAL EXCEPTION" "$CRASH" | head -40
fi

# Best effort: the on-device log file (often not readable on Android 11+).
A pull /sdcard/Android/data/$PKG/files/logs/hud.log "$OUT/hud.log" >/dev/null 2>&1 && echo "== also pulled hud.log"

A shell am force-stop "$PKG" >/dev/null

# ---------------------------------------------------------------- evidence
echo
echo "======================= CAMERA SOURCE EVIDENCE ======================="
grep -E "camera output sizes|Selected system camera resolution|preview buffer fixed|System camera opened|detection disabled|capture session (started|rejected)|retrying with|Failed to start capture request|Camera cannot be configured|Failed to open camera|Surface (created|changed|destroyed)|video fit|Scanning USB devices|UVC device found|no UVC video interface|permission granted|USB permission result received|USB Permission (granted|denied)|Requesting USB permission|USB Device (attached|detached)|openDevice returned null|openCamera|preview size ->|preview committed|advertised:|frame callback|stats: fps|libusb|DvrCameraCtrl|U2 DVR|stream slot|delivered [0-9]+ frames" "$LOG" | sed 's/^[0-9-]* [0-9:.]* *[0-9]* *[0-9]* [A-Z] //' | sort -u

echo
echo "------------------------------- VERDICT ------------------------------"
# Report the LAST session that actually started a repeating request, so both the old and the
# new log wording are understood.
LAST_SESSION=$(grep -E "(capture session started|repeating request started) with [0-9]+ surface" "$LOG" \
    | tail -1 | sed 's/.*\(with [0-9]* surface.*\)/\1/')
echo "LAST SESSION: ${LAST_SESSION:-<none>}"

if grep -q "U2 DVR: streaming" "$LOG"; then
    echo "DVR      : $(grep -m1 'stream slot found' "$LOG" | sed 's/.*DvrCameraCtrl[^:]*: //' || true)"
    echo "VERDICT  : U2 DVR STREAMING - the dashcam's mass-storage interface is claimed and frames"
    echo "           are being read with SCSI Bulk-Only Transport."
elif grep -q "U2 DVR: grant USB access to start" "$LOG"; then
    echo "VERDICT  : U2 DVR NEEDS USB PERMISSION - tap Allow on the tablet, then re-run."
elif grep -q "U2 DVR not connected" "$LOG"; then
    echo "VERDICT  : U2 DVR NOT ATTACHED (VID 0x1902 / PID 0x3241)."
elif grep -q "USB Permission denied" "$LOG"; then
    echo "VERDICT  : USB PERMISSION DENIED - the system dialog was refused, so no UVC session can"
    echo "           start. Re-run and tap Allow."
elif grep -q "Requesting USB permission" "$LOG" \
    && ! grep -Eq "USB permission result received|USB Permission granted|permission already granted" "$LOG"; then
    echo "VERDICT  : USB PERMISSION PENDING - the system dialog was never answered. Tap Allow and"
    echo "           re-run with a larger --secs so the stream is sampled after the grant."
elif grep -q "UVC device found" "$LOG"; then
    echo "ATTACHED : $(grep -m1 'UVC device found' "$LOG" | sed 's/.*UVC device found: //')"
    echo "VERDICT  : UVC CAMERA CLAIMED - the USB webcam exposed a video interface and the app"
    echo "           opened it. Whether frames arrive is the FRAMES line below."
elif grep -q "Scanning USB devices. Count: 0" "$LOG"; then
    echo "VERDICT  : NO USB DEVICE ATTACHED (nothing enumerated, or it dropped off mid-run)."
elif grep -q "no UVC video interface on any attached device" "$LOG"; then
    echo "ATTACHED : $(grep -m1 'no UVC video interface' "$LOG" | sed 's/.*on any attached device: //')"
    echo "VERDICT  : NO UVC CAMERA - the attached USB device exposes no video interface, so"
    echo "           there is nothing to stream over UVC (wrong USB mode, or the device only"
    echo "           streams through its own app / over WiFi)."
elif grep -q "Camera cannot be configured" "$LOG"; then
    echo "FAIL  : the camera refused every session for the selected size."
elif grep -q "capture session rejected" "$LOG"; then
    if grep -Eq "with 1 surface\(s\).*preview" "$LOG"; then
        echo "PARTIAL: preview-only session. Video should be visible; detection/recording unavailable."
    else
        echo "FAIL  : combined session rejected and the retry did not start a preview."
    fi
elif grep -Eq "(capture session started|repeating request started) with 2 surface" "$LOG"; then
    echo "PASS  : analysis+preview session started (both streams configured)."
elif grep -Eq "with 1 surface\(s\).*analysis" "$LOG"; then
    echo "WAIT  : analysis-only session — the HUD surface was not available yet."
    echo "        Enter the HUD (START HUD) and re-check."
else
    echo "FAIL  : no capture session was started at all — see the log above."
fi

# The defect this harness exists to catch: a superseded session's callback touching a closed
# session. Should be absent once the session-generation guard is in place.
if grep -q "Session has been closed" "$LOG"; then
    echo "RACE   : BAD     - 'Session has been closed' thrown (superseded session still used)."
else
    echo "RACE   : clean   - no closed-session errors."
fi

if grep -q "tracks:" "$LOG"; then
    echo "FRAMES: yes — the 1 Hz telemetry line is present, so the stream is delivering."
else
    echo "FRAMES: none logged. Telemetry only fires when frames arrive, so the stream"
    echo "        is not delivering (compare with the screenshot: black = no video)."
fi

echo
echo "Screenshot to eyeball: $SHOT   (black = preview dead, scene = preview live)"
