#!/usr/bin/env python3
"""Writes LangSync's snapshot for the app and the site from their current English files.

LangSync classifies a key as unchanged when the hash in its snapshot equals the hash of the
current source value (sha256 of the JSON-encoded string, ensure_ascii off). After a language was
translated by hand (or by an assistant) instead of by `langsync`, run this once so the next
`langsync` run translates only keys that are new or edited afterwards:

    python3 scripts/langsync-seed.py            # both
    python3 scripts/langsync-seed.py app|site

The configs live in private/langsync.json (app) and private/langsync-site.json (site), run as
`langsync -c private/langsync.json` and `langsync -c private/langsync-site.json`. LangSync is only
run when the owner asks (AGENTS.md).
"""
import hashlib, json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGETS = {
    'app': (ROOT / 'core/src/commonMain/resources/i18n/en.json', ROOT / 'private/langsync-state.json'),
    'site': (ROOT / 'site/i18n/en.json', ROOT / 'private/langsync-site-state.json'),
}

def flat(o, p=''):
    out = {}
    for k, v in o.items():
        key = f'{p}.{k}' if p else k
        out.update(flat(v, key) if isinstance(v, dict) else {key: v})
    return out

for name in (sys.argv[1:] or TARGETS):
    src, state = TARGETS[name]
    hashes = {k: hashlib.sha256(json.dumps(v, ensure_ascii=False).encode()).hexdigest() for k, v in flat(json.loads(src.read_text('utf8'))).items()}
    state.write_text(json.dumps({'version': 1, 'hashes': hashes}, indent=2) + '\n')
    print(f'{name}: {len(hashes)} keys -> {state.relative_to(ROOT)}')
