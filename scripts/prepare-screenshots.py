#!/usr/bin/env python3
"""Turns the raw phone captures of scripts/screenshots.sh into the site's and the README's images.

    python3 scripts/prepare-screenshots.py RAW_DIR [--no-readme]

RAW_DIR holds <screen>.png at 1080 x 2400: the app's one pure black look, the demo library (invented
names only), with the clean status bar (12:00, Wi-Fi, full battery) the capture already has. For each
screen it:

1. writes site/public/screenshots/<screen>.webp (1080 x 2400) and <screen>-540.webp (540 x 1200),
   WebP quality 88, the only lossy step;
2. unless --no-readme, renders the README's framed phones with headless Chromium from a small HTML
   page that draws the same frame as site/src/components/PhoneFrame.astro, and writes
   docs/screenshots/readme-<screen>.webp (quality 88, transparent around the phone).

Needs Pillow (with WebP) and chromium-browser for step 2. Screens not listed in SCREENS (settings, a
long text page, and artist and playlist) are skipped.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
SITE_OUT = ROOT / "site" / "public" / "screenshots"
README_OUT = ROOT / "docs" / "screenshots"

SCREENS = [
    "home", "chats", "songs", "albums", "artists", "playlists", "favourites",
    "album", "nowplaying", "lyrics", "queue", "account",
]
README_SCREENS = ["home", "albums", "nowplaying", "lyrics"]

W, H = 1080, 2400
QUALITY = 88


def load(path: Path) -> Image.Image:
    im = Image.open(path).convert("RGB")
    if im.size != (W, H):
        raise ValueError(f"{path.name}: expected {W}x{H}, got {im.size[0]}x{im.size[1]}")
    return im


def save_webp(im: Image.Image, path: Path) -> None:
    im.save(path, "WEBP", quality=QUALITY, method=6)


FRAME_HTML = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><style>
html, body {{ margin: 0; background: transparent; }}
body {{ padding: 28px 30px 44px; width: max-content; }}
{css}
</style></head><body>
<div class="pf" style="--pf-w: 240px">
  <div class="pf-body">
    <span class="pf-btn pf-btn-power"></span><span class="pf-btn pf-btn-vol"></span>
    <div class="pf-screen"><img src="{src}" width="1080" height="2400" alt=""><span class="pf-cam"></span></div>
  </div>
</div>
</body></html>
"""


def frame_css() -> str:
    """The frame's CSS, read from the component so the README and the site draw the same phone."""
    text = (ROOT / "site" / "src" / "components" / "PhoneFrame.astro").read_text(encoding="utf-8")
    start = text.rfind("\n<style>") + 1
    end = text.find("</style>", start)
    if start <= 0 or end < 0:
        raise ValueError("PhoneFrame.astro has no <style> block")
    return text[start + len("<style>"):end]


def render_readme(tmp: Path) -> None:
    chromium = shutil.which("chromium-browser") or shutil.which("chromium")
    if not chromium:
        sys.exit("chromium-browser not found; run with --no-readme to skip the README images")
    README_OUT.mkdir(parents=True, exist_ok=True)
    css = frame_css()
    for screen in README_SCREENS:
        src = SITE_OUT / f"{screen}.webp"
        html = tmp / f"{screen}.html"
        html.write_text(FRAME_HTML.format(css=css, src=src.as_uri()), encoding="utf-8")
        png = tmp / f"{screen}.png"
        # At 2x; the phone is 240 CSS px wide plus its shadow margin, and the transparent rest is cropped.
        subprocess.run([
            chromium, "--headless=new", "--disable-gpu", "--no-sandbox", "--hide-scrollbars",
            "--allow-file-access-from-files", "--default-background-color=00000000",
            "--force-device-scale-factor=2", "--window-size=320,640",
            f"--screenshot={png}", "--virtual-time-budget=6000", html.as_uri(),
        ], check=True, capture_output=True)
        shot = Image.open(png).convert("RGBA")
        bbox = shot.getchannel("A").getbbox()
        if bbox:
            shot = shot.crop(bbox)
        out = README_OUT / f"readme-{screen}.webp"
        shot.save(out, "WEBP", quality=QUALITY, method=6)
        print(f"  {out.relative_to(ROOT)} {shot.size[0]}x{shot.size[1]}")


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("raw_dir", type=Path)
    ap.add_argument("--no-readme", action="store_true", help="skip the framed README images")
    args = ap.parse_args()

    SITE_OUT.mkdir(parents=True, exist_ok=True)
    missing = []
    for screen in SCREENS:
        src = args.raw_dir / f"{screen}.png"
        if not src.is_file():
            missing.append(src.name)
            continue
        im = load(src)
        save_webp(im, SITE_OUT / f"{screen}.webp")
        save_webp(im.resize((W // 2, H // 2), Image.Resampling.LANCZOS), SITE_OUT / f"{screen}-540.webp")
        print(f"  {screen}")
    if missing:
        print("missing, skipped: " + ", ".join(missing), file=sys.stderr)
    if not args.no_readme:
        with tempfile.TemporaryDirectory() as tmp:
            render_readme(Path(tmp))


if __name__ == "__main__":
    main()
