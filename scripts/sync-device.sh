#!/usr/bin/env bash
# Pull everything the phone has produced, so the operator never has to export by hand again.
#
# WHY THIS IS NOT A PRIVACY-RULE VIOLATION (CLAUDE.md §4.3)
# --------------------------------------------------------
# The app holds no INTERNET permission and never will. Nothing here changes that: adb is the
# Android debug bridge, part of the platform tools, running on the operator's own PC and
# talking to the operator's own phone over their own network. The app does not know it
# happened and cannot initiate it. What moves is what the operator was copying by hand
# anyway — derived numbers, no image data.
#
# WHY WI-FI RATHER THAN USB
# -------------------------
# This repository is worked on from WSL2, which has no USB passthrough without usbipd, so a
# cable is invisible from here. WSL2 can reach the LAN, so adb over TCP works and — the whole
# point — can be run by the builder without the operator doing anything each time.
#
# Usage:
#   scripts/sync-device.sh                 # uses the address saved in .device
#   scripts/sync-device.sh 192.168.1.42    # connect and remember this address
#   scripts/sync-device.sh --setup         # print the one-time phone setup

set -uo pipefail
cd "$(dirname "$0")/.."

ADB="${ADB:-$HOME/Android/Sdk/platform-tools/adb}"
PKG="com.focusforge"
REMOTE="/sdcard/Android/data/$PKG/files"
DEVICE_FILE=".device"

# Screenshots land in a staging folder that is NOT tracked by git, and are never committed
# automatically. A phone's screenshot folder contains the operator's whole life, and the
# app's own Session screen shows their face — deciding what becomes public is theirs.
SHOT_STAGING="docs/screenshots/incoming"
# Only screenshots this recent are touched at all — see the note where they are pulled.
SHOT_DAYS="${SHOT_DAYS:-3}"

setup_help() {
    cat <<'EOF'
One-time setup on the phone
===========================

Android 11:
  1. Settings -> Developer options -> turn ON "Wireless debugging".
  2. Tap "Wireless debugging" (the words, not the switch) -> "Pair device with pairing code".
  3. It shows an IP:PORT and a 6-digit code. Tell the builder both, or run:
         adb pair <IP>:<PAIR_PORT>
     and enter the code.
  4. Back on the same screen, note the IP:PORT under "IP address & Port" — that is the
     CONNECT port and it differs from the pairing port. Then:
         scripts/sync-device.sh <IP>:<PORT>

Android 9 or 10 (no "Wireless debugging" entry):
  These need one cable step, done from WINDOWS (not WSL, which cannot see USB):
  1. Settings -> Developer options -> USB debugging ON. Plug the phone in, accept the prompt.
  2. In Windows PowerShell, in the platform-tools folder:
         .\adb.exe tcpip 5555
  3. Unplug. Find the phone's IP in Settings -> About phone -> Status. Then from here:
         scripts/sync-device.sh <IP>:5555
  Step 2 must be repeated after a reboot; the pairing above does not.

The phone and this PC must be on the same network. Nothing is installed on the phone and no
app permission changes — adb is part of Android.
EOF
}

if [ "${1:-}" = "--setup" ] || [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
    setup_help
    exit 0
fi

if [ ! -x "$ADB" ]; then
    echo "adb not found at $ADB"
    echo "Set ADB=/path/to/adb, or install platform-tools."
    exit 1
fi

ADDR="${1:-}"
if [ -z "$ADDR" ] && [ -f "$DEVICE_FILE" ]; then
    ADDR="$(tr -d '[:space:]' < "$DEVICE_FILE")"
fi
if [ -z "$ADDR" ]; then
    echo "No device address. Pass one, or run: scripts/sync-device.sh --setup"
    exit 1
fi

echo "Connecting to $ADDR ..."
"$ADB" connect "$ADDR" >/dev/null 2>&1
sleep 1
if ! "$ADB" -s "$ADDR" shell true >/dev/null 2>&1; then
    echo "Could not reach $ADDR."
    echo
    echo "  - Is the phone awake and on the same Wi-Fi?"
    echo "  - Android 11 changes the port every time wireless debugging is toggled;"
    echo "    check Settings -> Developer options -> Wireless debugging for the current one."
    echo "  - Run scripts/sync-device.sh --setup for the full procedure."
    exit 1
fi
echo "$ADDR" > "$DEVICE_FILE"
echo "Connected: $("$ADB" -s "$ADDR" shell getprop ro.product.model 2>/dev/null | tr -d '\r') " \
     "(Android $("$ADB" -s "$ADDR" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r'))"
echo

pull_dir() {
    local remote="$1" local_dir="$2" label="$3"
    mkdir -p "$local_dir"
    local listing
    listing="$("$ADB" -s "$ADDR" shell "ls $remote 2>/dev/null" | tr -d '\r' | grep -v '^$' || true)"
    if [ -z "$listing" ]; then
        echo "  $label: nothing on the phone"
        return
    fi
    local new=0 skipped=0
    while IFS= read -r name; do
        [ -z "$name" ] && continue
        if [ -f "$local_dir/$name" ]; then
            skipped=$((skipped + 1))
            continue
        fi
        if "$ADB" -s "$ADDR" pull -a "$remote/$name" "$local_dir/$name" >/dev/null 2>&1; then
            echo "    + $name"
            new=$((new + 1))
        else
            echo "    ! failed to pull $name"
        fi
    done <<< "$listing"
    echo "  $label: $new new, $skipped already here"
}

echo "Pulling derived data (numbers only — no image data is produced by the app):"
pull_dir "$REMOTE/sessions" "bench/sessions"  "session exports"
pull_dir "$REMOTE/replays"  "bench/replays"   "landmark recordings"
# The device profile the phone derived for itself — the other half of the cross-silicon
# exhibit, and the reason ProfileStore writes it beside the sessions rather than somewhere
# only a human could reach.
pull_dir "$REMOTE/profiles" "bench/profiles/incoming" "device profiles"

# Screenshots: staged, never auto-committed, and only ones taken in the last few days.
#
# The first version pulled the twelve most recent screenshots and came back with the
# operator's Discord, Gmail and Maps. They were gitignored and never at risk of being
# published, but copying somebody's private screenshots onto another machine is not
# something to do as a side effect of fetching benchmark data. A time window keeps this to
# things plausibly taken for the project, and even those are the operator's to hand over.
mkdir -p "$SHOT_STAGING"
echo
echo "Staging screenshots from the last $SHOT_DAYS days into $SHOT_STAGING (NOT committed):"
SHOTS="$("$ADB" -s "$ADDR" shell "find /sdcard/Pictures/Screenshots /sdcard/DCIM/Screenshots \
    -maxdepth 1 -type f -mtime -$SHOT_DAYS 2>/dev/null" \
    | tr -d '\r' | grep -iE '\.(png|jpg)$' | xargs -r -n1 basename || true)"
# Report "already have them" distinctly from "there are none". The first version printed
# nothing at all when every screenshot was already staged, which reads as "found none" and
# sent the builder hunting for a bug that did not exist.
if [ -z "$SHOTS" ]; then
    echo "  none in the last $SHOT_DAYS days"
else
    shot_new=0
    shot_have=0
    for dir in /sdcard/Pictures/Screenshots /sdcard/DCIM/Screenshots; do
        while IFS= read -r name; do
            [ -z "$name" ] && continue
            if [ -f "$SHOT_STAGING/$name" ]; then
                shot_have=$((shot_have + 1))
                continue
            fi
            if "$ADB" -s "$ADDR" pull -a "$dir/$name" "$SHOT_STAGING/$name" >/dev/null 2>&1; then
                echo "    + $name"
                shot_new=$((shot_new + 1))
            fi
        done <<< "$SHOTS"
    done
    echo "  screenshots: $shot_new new, $shot_have already staged"
fi

echo
echo "Done. Nothing has been committed — review, then commit what belongs in the repo."
echo "Reminder: a Session-screen screenshot contains the operator's face; see"
echo "docs/screenshots/README.md before adding one to a public repository."
