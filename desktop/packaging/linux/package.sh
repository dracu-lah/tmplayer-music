#!/usr/bin/env bash
# Builds the Linux packages that work on any distribution from the app image that
# `:desktop:createDistributable` makes. Adapted from ../TMPlayer's desktop/packaging/linux/package.sh (GPL-3.0).
#
#   ./gradlew :desktop:createDistributable -PdesktopVersion=0.1.0
#   desktop/packaging/linux/package.sh 0.1.0 [tar] [appimage]
#
# With no format named it builds both into desktop/build/packages (OUT overrides):
#
#   TMPlayerMusic-<v>-linux-x64.tar.xz   by hand on any distribution
#   TMPlayerMusic-<v>-x86_64.AppImage    any distribution, nothing installed (needs appimagetool)
#
# Nothing here signs anything and nothing needs a secret. The AppImage comes from appimagetool; set
# APPIMAGETOOL to its path if it is not on PATH.
#
# Every native the app loads (TDLib inside the tdl-coroutines jar, libmpv and FFmpeg inside the
# mediamp runtime jar) was linked against a recent glibc, so the floor is Ubuntu 24.04, Debian 13,
# Fedora 39 or newer.
set -euo pipefail

version="${1:?usage: package.sh <version> [tar] [appimage]}"
shift
formats=("$@")
[ ${#formats[@]} -eq 0 ] && formats=(tar appimage)
for f in "${formats[@]}"; do
  case "$f" in tar|appimage) ;; *) echo "unknown format: $f (tar or appimage)" >&2; exit 1 ;; esac
done

here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/../../.." && pwd)"
app="${APP_IMAGE:-$root/desktop/build/compose/binaries/main/app/TMPlayerMusic}"
out="${OUT:-$root/desktop/build/packages}"
work="${WORK:-$root/desktop/build/packaging-work}"
id="io.github.dracu_lah.TMPlayerMusic"
icons="$root/desktop/packaging/icons"

[ -x "$app/bin/TMPlayerMusic" ] || { echo "no app image at $app; run :desktop:createDistributable first" >&2; exit 1; }
release_date="$(git -C "$root" log -1 --format=%cs 2>/dev/null || date -u +%F)"

rm -rf "$work"
mkdir -p "$work" "$out"

# The freedesktop integration both formats share: launcher, icons, AppStream metadata, licence.
share="$work/share"
install -Dm644 "$here/$id.desktop" "$share/applications/$id.desktop"
for png in "$icons"/hicolor/*.png; do
  size="$(basename "$png" .png)"
  install -Dm644 "$png" "$share/icons/hicolor/$size/apps/$id.png"
done
mkdir -p "$share/metainfo"
sed -e "s/@VERSION@/$version/" -e "s/@DATE@/$release_date/" \
  "$here/$id.metainfo.xml" > "$share/metainfo/$id.metainfo.xml"
install -Dm644 "$root/LICENSE" "$share/doc/tmplayer-music/copyright"
install -Dm644 "$root/LICENSE-OPENSSL-EXCEPTION.md" "$share/doc/tmplayer-music/LICENSE-OPENSSL-EXCEPTION.md"
install -Dm644 "$root/THIRD_PARTY_NOTICES.md" "$share/doc/tmplayer-music/THIRD_PARTY_NOTICES.md"
if command -v desktop-file-validate >/dev/null; then desktop-file-validate "$share/applications/$id.desktop"; fi
if command -v appstreamcli >/dev/null; then appstreamcli validate --no-net "$share/metainfo/$id.metainfo.xml"; fi

# One staging copy of the app image, with the unstripped ELF natives stripped, shared by both formats.
stage="$work/app"
cp -a "$app" "$stage"
if command -v strip >/dev/null; then
  is_elf() { [ "$(head -c4 "$1" 2>/dev/null | od -An -c | tr -d ' ')" = '177ELF' ]; }
  while IFS= read -r -d '' f; do
    if is_elf "$f"; then strip --strip-unneeded "$f" 2>/dev/null || true; fi
  done < <(find "$stage" -type f \( -name '*.so' -o -name '*.so.*' \) -print0)
fi

build_tar() {
  local name="TMPlayerMusic-$version-linux-x64"
  local pack="$work/tar/$name"
  mkdir -p "$pack"
  cp -a "$stage/." "$pack/"
  cp -a "$share" "$pack/share"
  tar -C "$work/tar" -cf - "$name" | xz -T0 -9e > "$out/$name.tar.xz"
  echo "built $out/$name.tar.xz"
}

build_appimage() {
  local tool="${APPIMAGETOOL:-appimagetool}"
  command -v "$tool" >/dev/null || { echo "appimagetool not found (set APPIMAGETOOL)" >&2; exit 1; }
  local dir="$work/AppDir"
  mkdir -p "$dir/usr/lib/tmplayer-music" "$dir/usr/share"
  cp -a "$stage/." "$dir/usr/lib/tmplayer-music/"
  cp -a "$share/." "$dir/usr/share/"
  cp "$here/AppRun" "$dir/AppRun"
  chmod +x "$dir/AppRun"
  cp "$here/$id.desktop" "$dir/$id.desktop"
  cp "$icons/tmplayer-music.png" "$dir/$id.png"
  ARCH=x86_64 "$tool" "$dir" "$out/TMPlayerMusic-$version-x86_64.AppImage"
  echo "built $out/TMPlayerMusic-$version-x86_64.AppImage"
}

for f in "${formats[@]}"; do
  case "$f" in
    tar) build_tar ;;
    appimage) build_appimage ;;
  esac
done
