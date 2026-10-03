#!/usr/bin/env bash
# Drives the headless test emulator (AVD "sca35", API 35) from the command line, so app behaviour can be
# checked without a phone. Every adb call targets the emulator only, never a plugged-in phone.
#
#   scripts/emulator.sh start          boot it headless (no window, no sound) and wait until it is ready
#   scripts/emulator.sh stop           shut it down
#   scripts/emulator.sh install        install the app and the test player (build them first)
#   scripts/emulator.sh open           bring the app to the front
#   scripts/emulator.sh ui             print each on-screen node: centre x, y, clickable, label
#   scripts/emulator.sh tap "<label>"  tap the node with that exact label, then print the screen
#   scripts/emulator.sh play | pause   start or pause the test player's looping tone (stands in for an audiobook)
#   scripts/emulator.sh log [all]      the newest night log (without plan/data lines unless "all")
#   scripts/emulator.sh <adb args>     plain adb against the emulator
#
# One-time setup (about 2 GB): sdkmanager --install emulator "system-images;android-35;default;x86_64"
#   then: avdmanager create avd -n sca35 -k "system-images;android-35;default;x86_64" -d pixel_6
set -euo pipefail
export MSYS_NO_PATHCONV=1

SDK="${ANDROID_HOME:-$HOME/android-dev/sdk}"
SERIAL="emulator-5554"
ADB=("$SDK/platform-tools/adb" -s "$SERIAL")
APP_PACKAGE="com.nikita.sleepcycle"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

dump_nodes() {
    "${ADB[@]}" shell uiautomator dump /sdcard/ui.xml > /dev/null
    "${ADB[@]}" exec-out cat /sdcard/ui.xml | python -c '
import re, sys
xml = sys.stdin.read()
def attr(n, name):
    m = re.search(" " + name + r"=\"([^\"]*)\"", n)
    return m.group(1) if m else ""
for node in re.finditer(r"<node [^>]*>", xml, re.S):
    n = node.group(0)
    text, desc, rid = attr(n, "text"), attr(n, "content-desc"), attr(n, "resource-id")
    click = "click" if "clickable=\"true\"" in n else "-"
    x1, y1, x2, y2 = map(int, re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n).groups())
    # Band addresses never reach the terminal or a log.
    label = re.sub(r"([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}", "<MAC>", text or desc or rid)
    if label or click == "click":
        print(f"{(x1 + x2) // 2}\t{(y1 + y2) // 2}\t{click}\t{label}")
'
}

case "${1:-}" in
start)
    "$SDK/emulator/emulator" -avd sca35 -no-window -no-boot-anim -no-snapshot-save -no-audio -gpu swiftshader_indirect > /dev/null 2>&1 &
    # Single quotes on purpose: the loop runs on the emulator, not here.
    # shellcheck disable=SC2016
    "${ADB[@]}" wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 2; done'
    echo "emulator ready"
    ;;
stop)
    "${ADB[@]}" emu kill
    ;;
install)
    "${ADB[@]}" install -r -g "$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
    "${ADB[@]}" install -r -g "$ROOT_DIR/testplayer/build/outputs/apk/debug/testplayer-debug.apk"
    "${ADB[@]}" shell dumpsys deviceidle whitelist +"$APP_PACKAGE" > /dev/null
    ;;
open)
    "${ADB[@]}" shell am start -n "$APP_PACKAGE/.MainActivity" > /dev/null
    ;;
ui)
    dump_nodes
    ;;
tap)
    point=$(dump_nodes | awk -F'\t' -v t="$2" '$4 == t { print $1, $2; exit }')
    if [ -z "$point" ]; then
        echo "no node labelled: $2" >&2
        exit 1
    fi
    read -r x y <<< "$point"
    "${ADB[@]}" shell input tap "$x" "$y"
    sleep 1
    dump_nodes
    ;;
play | pause)
    "${ADB[@]}" shell am start-foreground-service -n com.nikita.testplayer/.PlayerService -a "$1" > /dev/null
    ;;
log)
    latest=$("${ADB[@]}" exec-out run-as "$APP_PACKAGE" ls -t files/nightlogs | grep night | head -1 | tr -d '\r')
    if [ "${2:-}" = "all" ]; then
        "${ADB[@]}" exec-out run-as "$APP_PACKAGE" cat "files/nightlogs/$latest"
    else
        "${ADB[@]}" exec-out run-as "$APP_PACKAGE" cat "files/nightlogs/$latest" | grep -v -e '"type":"plan"' -e '"type":"data"' -e phone_alarm_set
    fi
    ;;
*)
    "${ADB[@]}" "$@"
    ;;
esac
