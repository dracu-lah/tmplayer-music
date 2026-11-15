// Catalog lookups with a fallback to English per string, {placeholders} filled from `vars`.
//
// i18n/en.json is the source of every string. While a feature is being built, its strings may sit in
// i18n/parts/<namespace>.json instead; both are English and are merged here (scripts/i18n-merge.mjs folds
// the parts into en.json, sorted). i18n/<tag>.json holds a translation with the same keys. A translation
// is used only when its {placeholders} match English, so a string that lost a {a1} falls back to English
// rather than dropping a link.
import { ENGLISH, LANGS, type Lang } from './langs';

type Flat = Record<string, string>;

const files = import.meta.glob<Record<string, unknown>>('../../i18n/{*,parts/*}.json', { eager: true, import: 'default' });

function unnest(data: Record<string, unknown>, prefix = ''): Flat {
  const out: Flat = {};
  for (const [k, v] of Object.entries(data)) {
    const key = prefix ? `${prefix}.${k}` : k;
    if (v && typeof v === 'object') Object.assign(out, unnest(v as Record<string, unknown>, key));
    else if (typeof v === 'string') out[key] = v;
  }
  return out;
}

const english: Flat = {};
for (const [path, data] of Object.entries(files)) {
  if (path.endsWith('/en.json') || path.includes('/parts/')) Object.assign(english, unnest(data));
}
const catalogs = new Map<string, Flat>([['en', english]]);
for (const lang of LANGS) {
  const data = files[`../../i18n/${lang.tag}.json`];
  catalogs.set(lang.tag, data ? unnest(data) : {});
}

const PH = /\{(\w+)\}/g;
const placeholders = (s: string) => [...s.matchAll(PH)].map((m) => m[1]).sort().join(',');

/** Every key a build looked up, for scripts/i18n-check.mjs --built. */
const used: Set<string> = ((globalThis as Record<string, unknown>).__tmUsedKeys ??= new Set<string>()) as Set<string>;

/** The raw string for a key in a language: the translation, or English when missing or when it lost a placeholder. */
export function raw(key: string, lang: Lang = ENGLISH): string {
  used.add(key);
  const en = english[key];
  const tr = catalogs.get(lang.tag)?.[key];
  if (tr && en !== undefined && placeholders(tr) === placeholders(en)) return tr;
  if (en === undefined) throw new Error(`no string for key ${key}`);
  return en;
}

/** The string with {name} filled from vars; a {name} with no value is left as it is. */
export function t(key: string, vars: Record<string, string | number> = {}, lang: Lang = ENGLISH): string {
  return raw(key, lang).replace(PH, (m, name: string) => (name in vars ? String(vars[name]) : m));
}

export const escapeHtml = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
