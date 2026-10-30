#!/usr/bin/env python3
"""Tells IndexNow which music.tmplayer.org pages changed.

Adapted from TMPlayer's scripts/indexnow-site.py. Bing, Yandex, Seznam and Naver (and through Bing,
ChatGPT search and Copilot) hear about a changed page within hours instead of on their next crawl.
Google does not take part and reads the sitemap.

A page counts as changed when its entry in site/src/data/lastmod.json is new or has a new date
between the two commits, so move a page's lastmod whenever its content changes. The URLs sent are
the ones the live sitemap lists. The site has one language, so there is no catalog handling.

It does nothing while INDEXABLE in site/site.config.mjs is false (the placeholder page): there is
nothing to index, and the live sitemap is empty anyway. Run it after a production deploy:

    scripts/indexnow-site.py --before <sha> --after <sha> [--dry-run]
"""
import argparse
import json
import pathlib
import re
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ORIGIN = "https://music.tmplayer.org"
HOST = "music.tmplayer.org"
ENDPOINT = "https://api.indexnow.org/indexnow"
LASTMOD = "site/src/data/lastmod.json"
CONFIG = ROOT / "site/site.config.mjs"
NS = {"s": "http://www.sitemaps.org/schemas/sitemap/0.9"}


def indexable():
    return re.search(r"^export const INDEXABLE = true;", CONFIG.read_text(), re.M) is not None


def git(*args, check=True):
    r = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and r.returncode:
        sys.exit(f"git {' '.join(args)}: {r.stderr.strip()}")
    return r


def have(rev):
    return git("cat-file", "-e", f"{rev}^{{commit}}", check=False).returncode == 0


def resolve_before(before, after):
    """The commit to compare with. A shallow checkout fetches it; a new branch or a force push
    whose old head is gone falls back to the parent of the given commit."""
    if before and not re.fullmatch(r"0+", before):
        if have(before) or git("fetch", "--quiet", "--depth=1", "origin", before, check=False).returncode == 0:
            return before
    if not have(f"{after}^"):
        git("fetch", "--quiet", "--deepen=1", "origin", check=False)
    return f"{after}^" if have(f"{after}^") else None


def lastmod(rev):
    r = git("show", f"{rev}:{LASTMOD}", check=False)
    if r.returncode:
        return {}
    try:
        entries = json.loads(r.stdout)
    except ValueError:
        return {}
    return {e["path"]: e.get("lastmod") for e in entries if isinstance(e, dict) and "path" in e}


def sitemap(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "tmplayer-music-indexnow"}), timeout=30) as r:
        root = ET.fromstring(r.read())
    for u in root.findall("s:url", NS):
        loc = (u.findtext("s:loc", default="", namespaces=NS) or "").strip()
        if loc:
            yield loc


def key():
    keys = [p.stem for p in (ROOT / "site/public").glob("*.txt") if re.fullmatch(r"[0-9a-f]{32}", p.stem)]
    if len(keys) != 1:
        sys.exit(f"expected one IndexNow key file in site/public/, found {len(keys)}")
    return keys[0]


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--before", default="")
    ap.add_argument("--after", default="HEAD")
    ap.add_argument("--sitemap", default=f"{ORIGIN}/sitemap.xml")
    ap.add_argument("--dry-run", action="store_true", help="print the URLs and send nothing")
    args = ap.parse_args()

    if not indexable():
        print("INDEXABLE is false in site/site.config.mjs (placeholder page); nothing to tell IndexNow.")
        return 0

    before = resolve_before(args.before, args.after)
    old, new = (lastmod(before) if before else {}), lastmod(args.after)
    pages = {ORIGIN + p for p, day in new.items() if old.get(p) != day}
    if not pages:
        print("No page's lastmod changed; nothing to tell IndexNow.")
        return 0

    urls = [loc for loc in sitemap(args.sitemap) if loc in pages]
    if not urls:
        print("The changed pages are not in the live sitemap; nothing to tell IndexNow.")
        return 0
    print(f"{len(urls)} changed URLs:", *urls, sep="\n  ")
    if args.dry_run:
        return 0

    k = key()
    body = json.dumps({"host": HOST, "key": k, "keyLocation": f"{ORIGIN}/{k}.txt", "urlList": urls}).encode()
    req = urllib.request.Request(ENDPOINT, data=body, method="POST",
                                 headers={"Content-Type": "application/json; charset=utf-8"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            print(f"IndexNow answered {r.status}")
    except Exception as e:  # noqa: BLE001
        # A courtesy ping: the sitemap still tells every engine on its next crawl, so a refusal or
        # an outage is reported without failing.
        print(f"::warning::IndexNow did not take the URLs: {e}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
