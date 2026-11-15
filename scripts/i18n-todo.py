#!/usr/bin/env python3
"""Lists what a locale still lacks, as a flat {key: english} JSON a translator can fill in.

    python3 scripts/i18n-todo.py <tag> <app|site> <out.json>

The app catalog is core/src/commonMain/resources/i18n/<tag>.json, the site's is site/i18n/<tag>.json.
For the app, a key whose English text is the same in TMPlayer (../TMPlayer, same author, GPL-3.0) takes
that project's translation, so only what is new to this app is left to translate. Keys already in the
locale file are never listed again. Prints how many were reused and how many are left.
"""
import json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CATALOGS = {
    'app': ROOT / 'core/src/commonMain/resources/i18n',
    'site': ROOT / 'site/i18n',
}
def _sibling():
    """TMPlayer sits beside this repo; a git worktree under .claude/worktrees is deeper, so look upward."""
    for base in [ROOT, *ROOT.parents]:
        cand = base.parent / 'TMPlayer/core/src/commonMain/resources/i18n'
        if cand.is_dir():
            return cand
    return ROOT.parent / 'TMPlayer/core/src/commonMain/resources/i18n'

TMPLAYER = _sibling()

# Site pages that stay English only (legal text, release notes, the 404): never listed for translation, as on tmplayer.org.
SITE_ENGLISH_ONLY = ('privacy.', 'legal.', 'security.', 'changelog.', 'notfound.')

def flat(o, p=''):
    out = {}
    for k, v in o.items():
        key = f'{p}.{k}' if p else k
        out.update(flat(v, key) if isinstance(v, dict) else {key: v})
    return out

def nest(f):
    root = {}
    for key in sorted(f):
        node = root
        parts = key.split('.')
        for part in parts[:-1]:
            node = node.setdefault(part, {})
        node[parts[-1]] = f[key]
    return root

def load(path):
    return flat(json.loads(path.read_text('utf8'))) if path.exists() else {}

if __name__ == '__main__':
    tag, kind, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    d = CATALOGS[kind]
    en, have = load(d / 'en.json'), load(d / f'{tag}.json')
    reused = 0
    if kind == 'app':
        t_en, t_tr = load(TMPLAYER / 'en.json'), load(TMPLAYER / f'{tag}.json')
        for k, v in en.items():
            if k not in have and k in t_en and t_en[k] == v and t_tr.get(k):
                have[k] = t_tr[k]
                reused += 1
        if reused:
            (d / f'{tag}.json').write_text(json.dumps(nest(have), ensure_ascii=False, indent=2) + '\n', 'utf8')
    todo = {k: v for k, v in en.items() if k not in have and not (kind == 'site' and k.startswith(SITE_ENGLISH_ONLY))}
    out.write_text(json.dumps(todo, ensure_ascii=False, indent=1) + '\n', 'utf8')
    print(f'{tag} {kind}: {len(en)} keys, {len(en) - len(todo)} done ({reused} reused from TMPlayer), {len(todo)} to translate -> {out}')
