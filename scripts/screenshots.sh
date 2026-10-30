#!/usr/bin/env bash
# Captures the debug build's demo library (invented artists, albums and chats, no real content) for
# store and site screenshots: each screen in light and dark, as PNGs in the folder given.
#
#   scripts/screenshots.sh OUT_DIR [screen ...]
#
# Needs the debug build installed (./gradlew :app:installDebug). The device is $ANDROID_SERIAL, or
# the first one `adb devices` lists. Each shot force-stops the app, arms the demo with run-as (so the
# process starts straight in the demo and never starts Telegram), opens the screen with the intent
# extras documented in app/src/debug/java/com/tmplayer/music/demo/DemoMode.kt, waits and captures.
#
# Environment:
#   THEMES="light dark"   the themes to capture
#   WAIT=6                seconds to wait before each capture (Now Playing and the queue get 3 more)
#   SYSUI_DEMO=1          a clean status bar (12:00, full battery and signal) while capturing; 0 leaves it
#
# At the end the demo is disarmed and the app stopped, so its next start is the user's own library.
set -euo pipefail

OUT="${1:-}"
if [ -z "$OUT" ]; then
  echo "usage: $0 OUT_DIR [screen ...]" >&2
  exit 2
fi
shift
SCREENS=("$@")
if [ ${#SCREENS[@]} -eq 0 ]; then
  SCREENS=(home chats songs albums artists playlists favourites album nowplaying queue settings)
fi

PKG=com.tmplayer.music.debug
ACTIVITY=com.tmplayer.music.MainActivity
THEMES="${THEMES:-light dark}"
WAIT="${WAIT:-6}"
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
  adb_ shell run-as "$PKG" rm -f files/demo_mode || true
  if [ "$SYSUI_DEMO" = "1" ]; then sysui exit; fi
}
trap cleanup EXIT

if [ "$SYSUI_DEMO" = "1" ]; then
  adb_ shell settings put global sysui_demo_allowed 1 >/dev/null 2>&1 || true
  sysui enter
  sysui clock -e hhmm 1200
  sysui battery -e level 100 -e plugged false
  sysui network -e wifi show -e level 4
  sysui network -e mobile show -e datatype none -e level 4
  sysui notifications -e visible false
fi

first=1
for theme in $THEMES; do
  case "$theme" in
    dark) dark=true ;;
    light) dark=false ;;
    *) echo "unknown theme: $theme (light or dark)" >&2; exit 2 ;;
  esac
  for screen in "${SCREENS[@]}"; do
    adb_ shell am force-stop "$PKG"
    adb_ shell run-as "$PKG" sh -c "'mkdir -p files && touch files/demo_mode'"
    adb_ shell am start -W -n "$PKG/$ACTIVITY" \
      --ez demo true --es demo_screen "$screen" --ez demo_dark "$dark" >/dev/null
    pause="$WAIT"
    case "$screen" in nowplaying|queue) pause=$((pause + 3)) ;; esac
    # The very first start draws the covers once; later starts reuse them.
    if [ "$first" = "1" ]; then pause=$((pause + 4)); first=0; fi
    sleep "$pause"
    file="$OUT/$screen-$theme.png"
    adb_ exec-out screencap -p > "$file"
    echo "$file"
  done
done
