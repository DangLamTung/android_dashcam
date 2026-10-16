#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Establish adb-over-WiFi to the tablet, so the USB port is free for the OTG camera.
#
# Three ways in, depending on what the tablet is doing right now:
#
#   1) USB is attached right now (classic, most reliable):
#        tools/adb_wifi.sh --from-usb
#      Runs `adb tcpip 5555`, reads the tablet's wlan0 address over USB, connects,
#      and tells you when it is safe to unplug the cable.
#
#   2) Android 11+ "Wireless debugging" is ON, already paired with this Mac:
#        tools/adb_wifi.sh 192.168.1.107:37145
#
#   3) Android 11+ "Wireless debugging" is ON, not yet paired (needs the 6-digit code
#      and the *pairing* port shown under "Pair device with pairing code"):
#        tools/adb_wifi.sh --pair 192.168.1.107:43210 123456 192.168.1.107:37145
#
# Notes:
#  - `adb tcpip 5555` does NOT survive a reboot; re-run --from-usb after one.
#  - The wireless-debugging connect port is random and changes every time the toggle
#    is switched, so it has to be read off the tablet's screen.
#  - mDNS discovery is unreliable on routers that block multicast; if
#    `adb mdns services` is empty, the IP:port must be typed.
# ---------------------------------------------------------------------------
set -uo pipefail

ADB=${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}
PORT=5555

FROM_USB=0
PAIR_ADDR=""
PAIR_CODE=""
CONNECT_ADDR=""

while [ $# -gt 0 ]; do
    case "$1" in
        --from-usb) FROM_USB=1; shift ;;
        --pair) PAIR_ADDR="$2"; PAIR_CODE="$3"; shift 3 ;;
        --port) PORT="$2"; shift 2 ;;
        -h|--help) sed -n '2,29p' "$0"; exit 0 ;;
        *) CONNECT_ADDR="$1"; shift ;;
    esac
done

if [ "$FROM_USB" = 0 ] && [ -z "$PAIR_ADDR" ] && [ -z "$CONNECT_ADDR" ]; then
    sed -n '2,29p' "$0"
    echo
    echo "!! nothing to do: pass --from-usb, an IP:PORT, or --pair ..."
    exit 2
fi

find_usb_serial() { "$ADB" devices | awk 'NR>1 && $2=="device" {print $1; exit}'; }

# True when $1 (a serial or ip:port) is connected and authorized; with no argument
# the check is "any device at all".
have_device() {
    if [ -n "$1" ]; then
        "$ADB" devices | awk -v a="$1" 'NR>1 && $1==a && $2=="device" {f=1} END{exit !f}'
    else
        "$ADB" devices | awk 'NR>1 && $2=="device" {f=1} END{exit !f}'
    fi
}

if [ "$FROM_USB" = 1 ]; then
    SERIAL=$(find_usb_serial)
    if [ -z "$SERIAL" ]; then
        echo "!! no USB device visible to adb. Check:"
        echo "   - a data-capable cable (charge-only cables never appear)"
        echo "   - Developer options -> USB debugging ON"
        echo "   - the 'Allow USB debugging?' prompt accepted on the tablet"
        echo "   - the USB notification set to 'File transfer' (some Lenovo/Transsion"
        echo "     builds hide the ADB interface in 'No data transfer' mode)"
        echo "   - macOS actually enumerating it:  system_profiler SPUSBDataType | grep -i -A5 usb"
        "$ADB" devices -l
        exit 1
    fi
    echo "== usb device: $SERIAL"

    IP=$("$ADB" -s "$SERIAL" shell ip -f inet addr show wlan0 2>/dev/null | tr -d '\r' | awk '/inet /{print $2}' | cut -d/ -f1 | head -1)
    if [ -z "$IP" ]; then
        echo "!! could not read a wlan0 address — is the tablet on WiFi?"
        "$ADB" -s "$SERIAL" shell ip -f inet addr | tr -d '\r' | grep -E "inet "
        exit 1
    fi
    echo "== tablet wifi ip: $IP"

    "$ADB" -s "$SERIAL" tcpip "$PORT" || exit 1
    # adbd restarts in TCP mode; the USB transport may drop for a moment.
    "$ADB" wait-for-usb-device 2>/dev/null
    CONNECT_ADDR="$IP:$PORT"
fi

if [ -n "$PAIR_ADDR" ]; then
    echo "== pairing with $PAIR_ADDR"
    # adb pair prompts for the code; feed it non-interactively.
    printf '%s\n' "$PAIR_CODE" | "$ADB" pair "$PAIR_ADDR" || { echo "!! pairing failed"; exit 1; }
fi

if [ -n "$CONNECT_ADDR" ]; then
    echo "== connecting to $CONNECT_ADDR"
    # `adb tcpip` restarts adbd, so the first connect attempt is normally refused;
    # retry briefly instead of declaring failure.
    attempt=0
    while [ "$attempt" -lt 6 ]; do
        attempt=$((attempt + 1))
        OUT=$("$ADB" connect "$CONNECT_ADDR" 2>&1)
        case "$OUT" in
            *"connected to"*) [ "$attempt" -gt 1 ] && echo "   $OUT" ;;
            *) echo "   $OUT" ;;
        esac
        have_device "$CONNECT_ADDR" && break
        sleep 1
    done
fi

echo "== devices"
"$ADB" devices -l

# Verify the address we were actually asked to reach. Checking for "any device"
# would let a still-attached USB transport make a failed WiFi connect look fine.
CHECK="$CONNECT_ADDR"
if [ -z "$CHECK" ]; then
    CHECK=$(find_usb_serial)
fi

if have_device "$CHECK"; then
    echo
    echo "OK — adb-over-WiFi is up at $CHECK. If a USB cable is still attached you can"
    echo "unplug it now; the OTG camera needs that port. Then run:"
    echo "   tools/debug_tablet.sh --serial $CHECK --no-build --detect"
else
    echo
    echo "!! $CHECK is not an authorized device yet."
    echo "   'unauthorized' -> accept the Allow USB debugging? prompt on the tablet."
    echo "   'offline'      -> adb disconnect && adb connect $CONNECT_ADDR"
    echo "   'refused'      -> the tablet is not listening: re-run with --from-usb, or"
    echo "                     turn on Developer options -> Wireless debugging."
    exit 1
fi
