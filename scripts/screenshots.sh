#!/usr/bin/env bash
# Captures the debug build's demo library (invented artists, albums and chats, no real content) for
# store and site screenshots: one PNG per screen, <screen>.png, in the folder given. The app has one
# look (pure black), so there is one theme to capture.
#
#   scripts/screenshots.sh OUT_DIR [screen ...]
#
# Needs the debug build installed (./gradlew :app:installDebug). The device is $ANDROID_SERIAL, or
# the first one `adb devices` lists. It needs no `run-as` (some phones refuse it): the first start
# with `--ez demo true` arms the demo and restarts the app by itself, and from then on each start with
# that extra goes straight into the demo. Each shot force-stops the app, opens the screen with the
# intent extras documented in app/src/debug/java/com/tmplayer/music/demo/DemoMode.kt, waits and
# captures.
#
# Environment:
#   WAIT=6                seconds to wait before each capture (Now Playing, lyrics and the queue get 3 more)
#   ARM_WAIT=15           seconds the first start gets to arm the demo and restart itself
#   SYSUI_DEMO=1          a clean status bar (12:00, full battery and signal, no other icons) while
#                         capturing; 0 leaves it as it is
#
# At the end the app is started once with `--ez demo false`, which leaves the demo, and is stopped,
# so its next start is the user's own library.
set -euo pipefail

OUT="${1:-}"
if [ -z "$OUT" ]; then
  echo "usage: $0 OUT_DIR [screen ...]" >&2
  exit 2
fi
shift
SCREENS=("$@")
if [ ${#SCREENS[@]} -eq 0 ]; then
  SCREENS=(home chats songs albums artists playlists favourites album artist playlist settings account nowplaying lyrics queue)
fi

PKG=com.tmplayer.music.debug
COMPONENT=com.tmplayer.music.debug/com.tmplayer.music.MainActivity
WAIT="${WAIT:-6}"
ARM_WAIT="${ARM_WAIT:-15}"
SYSUI_DEMO="${SYSUI_DEMO:-1}"

SERIAL="${ANDROID_SERIAL:-$(adb devices | awk 'NR > 1 && $2 == "device" { print $1; exit }')}"
if [ -z "$SERIAL" ]; then
  echo "no device: connect one or set ANDROID_SERIAL" >&2
  exit 1
fi
adb_() { adb -s "$SERIAL" "$@"; }

if ! adb_ shell pm path "$PKG" >/dev/null 2>&1; then
  echo "$PKG is not installed on $SERIAL: run ./gradlew :app:installDebug first" >&2
  exit 1
fi

mkdir -p "$OUT"

sysui() { adb_ shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null 2>&1 || true; }

cleanup() {
  adb_ shell am force-stop "$PKG" || true
  # Leaves the demo: the marker is removed and the app restarts into the user's own library.
  adb_ shell am start -n "$COMPONENT" --ez demo false >/dev/null 2>&1 || true
  sleep 3
  adb_ shell am force-stop "$PKG" || true
  if [ "$SYSUI_DEMO" = "1" ]; then sysui exit; fi
}
trap cleanup EXIT

if [ "$SYSUI_DEMO" = "1" ]; then
  adb_ shell settings put global sysui_demo_allowed 1 >/dev/null 2>&1 || true
  sysui enter
  sysui clock -e hhmm 1200
  sysui battery -e level 100 -e plugged false -e powersave false
  sysui network -e wifi show -e level 4 -e fully true
  sysui network -e mobile show -e datatype none -e level 4 -e fully true
  sysui network -e airplane hide
  sysui network -e nosim hide
  sysui network -e carriernetworkchange hide
  # Every status icon the demo mode knows; a phone that does not know one ignores it.
  sysui status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e alarm_clock hide \
    -e zen hide -e sync hide -e tty hide -e eri hide -e mute hide -e speakerphone hide -e managed_profile hide
  sysui notifications -e visible false
fi

# Arms the demo: a process not yet in it writes the marker and restarts itself into the demo.
adb_ shell am force-stop "$PKG"
adb_ shell am start -n "$COMPONENT" --ez demo true >/dev/null
sleep "$ARM_WAIT"

for screen in "${SCREENS[@]}"; do
  adb_ shell am force-stop "$PKG"
  adb_ shell am start -W -n "$COMPONENT" --ez demo true --es demo_screen "$screen" >/dev/null
  pause="$WAIT"
  case "$screen" in nowplaying|lyrics|queue) pause=$((pause + 3)) ;; esac
  sleep "$pause"
  file="$OUT/$screen.png"
  adb_ exec-out screencap -p > "$file"
  echo "$file"
done
