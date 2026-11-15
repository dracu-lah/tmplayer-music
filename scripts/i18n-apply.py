#!/usr/bin/env python3
"""Merges a translator's flat {key: text} JSON into a locale file, after checking it.

    python3 scripts/i18n-apply.py <tag> <app|site> <translated.json>

Each text must keep the English string's {placeholders} (same names), keep an `other` branch in every
ICU plural/select, contain no em or en dash and not be empty. A text that fails is skipped and listed;
the rest is written (keys sorted, nested like en.json). Exit code 1 if anything was skipped.
"""
import importlib.util, json, re, sys
from pathlib import Path

spec = importlib.util.spec_from_file_location('todo', Path(__file__).with_name('i18n-todo.py'))
todo = importlib.util.module_from_spec(spec); spec.loader.exec_module(todo)

ARG = re.compile(r'\{\s*(\w+)\s*[,}]')
DASH = re.compile('[\u2012-\u2015]')

def args(s):
    return sorted(set(ARG.findall(s)))

def problems(en, tr):
    if not tr.strip():
        return 'empty'
    if DASH.search(tr):
        return 'dash'
    if args(en) != args(tr):
        return f'placeholders {args(en)} vs {args(tr)}'
    if 'plural' in en or 'select' in en:
        if tr.count('{') != tr.count('}'):
            return 'unbalanced braces'
        if 'other' not in tr:
            return 'plural without other'
    if en.count('{') and tr.count('{') != tr.count('}'):
        return 'unbalanced braces'
    return None

if __name__ == '__main__':
    tag, kind, src = sys.argv[1], sys.argv[2], Path(sys.argv[3])
    d = todo.CATALOGS[kind]
    en, have = todo.load(d / 'en.json'), todo.load(d / f'{tag}.json')
    new = json.loads(src.read_text('utf8'))
    bad, added = [], 0
    for k, v in new.items():
        if k not in en or not isinstance(v, str):
            bad.append((k, 'unknown key or not a string')); continue
        why = problems(en[k], v)
        if why:
            bad.append((k, why)); continue
        have[k] = v; added += 1
    (d / f'{tag}.json').write_text(json.dumps(todo.nest(have), ensure_ascii=False, indent=2) + '\n', 'utf8')
    print(f'{tag} {kind}: added {added}, skipped {len(bad)}, still missing {len([k for k in en if k not in have])}')
    for k, why in bad[:40]:
        print('  skip', k, '->', why)
    sys.exit(1 if bad else 0)
