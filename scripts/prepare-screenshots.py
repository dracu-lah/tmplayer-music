#!/usr/bin/env python3
"""Turns the raw phone captures of scripts/screenshots.sh into the site's and the README's images.

    python3 scripts/prepare-screenshots.py RAW_DIR [--no-readme]

RAW_DIR holds <screen>-<light|dark>.png at 1080 x 2400 (the demo library: invented names only).
For each one it:

1. replaces the status bar band (the top 120 px) with a clean one: the row just below the band is
   stretched up over it (so a flat or tinted background stays as it was), then a 12:00 clock on the
   left and Wi-Fi, signal and a full battery on the right, dark on a light background and white on
   a dark one. The glyphs are drawn at 4x and scaled down, so their edges are smooth;
2. writes site/public/screenshots/<screen>-<theme>.webp (1080 x 2400) and <screen>-<theme>-540.webp
   (540 x 1200), WebP quality 88, the only lossy step;
3. unless --no-readme, renders the README's framed phones with headless Chromium from a small HTML
   page that draws the same frame as site/src/components/PhoneFrame.astro, and writes
   docs/screenshots/readme-<screen>-<theme>.webp (quality 88, transparent around the phone).

Needs Pillow (with WebP) and chromium-browser for step 3. The clock uses the site's own Poppins.
Screens not listed in SCREENS (such as lyrics, not ready yet) are skipped.
"""
from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
SITE_OUT = ROOT / "site" / "public" / "screenshots"
README_OUT = ROOT / "docs" / "screenshots"
FONT = ROOT / "site" / "public" / "fonts" / "poppins-600.woff2"

SCREENS = [
    "home", "chats", "songs", "albums", "artists", "playlists", "favourites",
    "album", "artist", "playlist", "settings", "nowplaying", "queue",
]
README_SCREENS = ["home", "albums", "nowplaying", "queue"]
THEMES = ["light", "dark"]

W, H = 1080, 2400
BAND = 120          # the status bar band that is replaced; app content starts below ~150
SS = 4              # supersampling factor for the glyphs
QUALITY = 88


def clean_status_bar(im: Image.Image) -> Image.Image:
    im = im.convert("RGB")
    if im.size != (W, H):
        raise ValueError(f"expected {W}x{H}, got {im.size[0]}x{im.size[1]}")
    # The background: the first row under the band, stretched up over it.
    row = im.crop((0, BAND, W, BAND + 1))
    im.paste(row.resize((W, BAND)), (0, 0))

    px = row.resize((1, 1), Image.Resampling.BOX).getpixel((0, 0))
    lum = 0.2126 * px[0] + 0.7152 * px[1] + 0.0722 * px[2]
    ink = (20, 20, 24, 255) if lum > 128 else (255, 255, 255, 255)

    layer = Image.new("RGBA", (W * SS, BAND * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    s = SS
    cy = 50  # the vertical centre of the status bar row, in 1x pixels

    # Clock.
    font = ImageFont.truetype(str(FONT), 40 * s)
    d.text((46 * s, cy * s), "12:00", font=font, fill=ink, anchor="lm")

    # Battery, full: rounded body, a nub on the right, filled inside.
    bx1, bx0 = 1030, 984
    bh = 24
    d.rounded_rectangle((bx0 * s, (cy - bh / 2) * s, bx1 * s, (cy + bh / 2) * s),
                        radius=6 * s, outline=ink, width=int(2.5 * s))
    d.rounded_rectangle(((bx0 + 4) * s, (cy - bh / 2 + 4) * s, (bx1 - 4) * s, (cy + bh / 2 - 4) * s),
                        radius=3 * s, fill=ink)
    d.rounded_rectangle(((bx1 + 2) * s, (cy - 5) * s, (bx1 + 6) * s, (cy + 5) * s), radius=2 * s, fill=ink)

    # Signal: four rising bars, all full.
    sx = 924
    base = cy + 13
    for i in range(4):
        h = 8 + i * 6
        x0 = sx + i * 11
        d.rounded_rectangle((x0 * s, (base - h) * s, (x0 + 7) * s, base * s), radius=2 * s, fill=ink)

    # Wi-Fi: a full fan (a pie slice) pointing down, the usual Android shape.
    wx, wy, r = 886, cy + 13, 30
    d.pieslice(((wx - r) * s, (wy - r) * s, (wx + r) * s, (wy + r) * s), start=225, end=315, fill=ink)

    small = layer.resize((W, BAND), Image.Resampling.LANCZOS)
    im.paste(small, (0, 0), small)
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
        for theme in THEMES:
            src = SITE_OUT / f"{screen}-{theme}.webp"
            html = tmp / f"{screen}-{theme}.html"
            html.write_text(FRAME_HTML.format(css=css, src=src.as_uri()), encoding="utf-8")
            png = tmp / f"{screen}-{theme}.png"
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
            out = README_OUT / f"readme-{screen}-{theme}.webp"
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
        for theme in THEMES:
            src = args.raw_dir / f"{screen}-{theme}.png"
            if not src.is_file():
                missing.append(src.name)
                continue
            im = clean_status_bar(Image.open(src))
            save_webp(im, SITE_OUT / f"{screen}-{theme}.webp")
            save_webp(im.resize((W // 2, H // 2), Image.Resampling.LANCZOS), SITE_OUT / f"{screen}-{theme}-540.webp")
            print(f"  {screen}-{theme}")
    if missing:
        print("missing, skipped: " + ", ".join(missing), file=sys.stderr)
    if not args.no_readme:
        with tempfile.TemporaryDirectory() as tmp:
            render_readme(Path(tmp))


if __name__ == "__main__":
    main()
