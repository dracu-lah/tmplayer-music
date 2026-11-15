// Checks the catalog against the source: every key a page, component or script asks for exists, no key is
// unused (a warning; --strict makes it an error), en.json has its keys sorted once the parts are merged,
// and no string has an em or en dash. It also checks every translation file i18n/<tag>.json: a key that is
// not in en.json, a string whose {placeholders} differ from English, or a dash is an error; and it reports each
// language's coverage of the localizable keys (all but the English-only namespaces, ENGLISH_ONLY_NAMESPACES
// in src/i18n/langs.ts) against PUBLISH_THRESHOLD, the share a language needs to be published.
// Keys built at run time are declared in a comment:
//   // i18n-keys: faq.q1, faq.q2
// Run: node scripts/i18n-check.mjs [--strict] [--parts-ok]
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = join(dirname(fileURLToPath(import.meta.url)), '..');
const strict = process.argv.includes('--strict');
const partsOk = process.argv.includes('--parts-ok');
const flat = (o, p = '', out = {}) => {
  for (const [k, v] of Object.entries(o)) {
    const key = p ? `${p}.${k}` : k;
    if (v && typeof v === 'object') flat(v, key, out); else out[key] = v;
  }
  return out;
};
const sorted = (o, p = '') => Object.keys(o).every((k, i, a) => (i === 0 || a[i - 1] < k) && (typeof o[k] !== 'object' || sorted(o[k], `${p}${k}.`)));

const en = JSON.parse(readFileSync(join(site, 'i18n/en.json'), 'utf8'));
const errors = [];
const warns = [];
if (!sorted(en)) errors.push('i18n/en.json keys are not sorted: run node scripts/i18n-merge.mjs');
const keys = flat(en);
const partsDir = join(site, 'i18n/parts');
const parts = existsSync(partsDir) ? readdirSync(partsDir).filter((f) => f.endsWith('.json')) : [];
if (parts.length && !partsOk) errors.push(`i18n/parts still holds ${parts.join(', ')}: run node scripts/i18n-merge.mjs`);
for (const f of parts) Object.assign(keys, flat(JSON.parse(readFileSync(join(partsDir, f), 'utf8'))));

for (const [k, v] of Object.entries(keys)) if (/[\u2012-\u2015]/.test(v)) errors.push(`dash in ${k}`);

const walk = (d) => readdirSync(d).flatMap((n) => {
  const p = join(d, n);
  return statSync(p).isDirectory() ? walk(p) : /\.(astro|ts|js|mjs)$/.test(n) ? [p] : [];
});
const usedKeys = new Set();
const PATS = [/\bt\(\s*['"`]([\w.]+)['"`]/g, /\braw\(\s*['"`]([\w.]+)['"`]/g, /\bk=["']([\w.]+)["']/g, /i18n-keys:([^\n]*)/g];
for (const f of walk(join(site, 'src'))) {
  const src = readFileSync(f, 'utf8');
  for (const re of PATS) for (const m of src.matchAll(re)) for (const key of m[1].split(',')) if (key.trim()) usedKeys.add(key.trim());
}
for (const k of usedKeys) if (!(k in keys)) errors.push(`missing key: ${k}`);
for (const k of Object.keys(keys)) if (!usedKeys.has(k)) warns.push(`unused key: ${k}`);

// Translations.
const langsSrc = readFileSync(join(site, 'src/i18n/langs.ts'), 'utf8');
const threshold = Number(/PUBLISH_THRESHOLD = ([\d.]+)/.exec(langsSrc)?.[1] ?? '0.95');
const englishOnly = [...(/ENGLISH_ONLY_NAMESPACES = \[([^\]]*)\]/.exec(langsSrc)?.[1] ?? '').matchAll(/'([^']+)'/g)].map((m) => m[1]);
const known = new Set([...langsSrc.matchAll(/tag: '([^']+)'/g)].map((m) => m[1]));
const ph = (v) => [...String(v).matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort().join(',');
const localizable = Object.keys(keys).filter((k) => !englishOnly.includes(k.split('.')[0]));
const catalogFiles = readdirSync(join(site, 'i18n')).filter((f) => f.endsWith('.json') && f !== 'en.json');
const coverage = [];
for (const f of catalogFiles) {
  const tag = f.slice(0, -5);
  if (!known.has(tag)) errors.push(`i18n/${f}: ${tag} is not a language in src/i18n/langs.ts`);
  let cat;
  try { cat = flat(JSON.parse(readFileSync(join(site, 'i18n', f), 'utf8'))); } catch (e) { errors.push(`i18n/${f}: not valid JSON (${e.message})`); continue; }
  for (const [k, v] of Object.entries(cat)) {
    if (!(k in keys)) { errors.push(`i18n/${f}: key ${k} is not in en.json`); continue; }
    if (typeof v !== 'string') { errors.push(`i18n/${f}: ${k} is not a string`); continue; }
    if (ph(v) !== ph(keys[k])) errors.push(`i18n/${f}: ${k} has placeholders {${ph(v)}}, English has {${ph(keys[k])}}`);
    if (/[\u2012-\u2015]/.test(v)) errors.push(`i18n/${f}: dash in ${k}`);
  }
  const done = localizable.filter((k) => typeof cat[k] === 'string' && cat[k] && ph(cat[k]) === ph(keys[k])).length;
  coverage.push({ tag, share: done / localizable.length, done });
}
coverage.sort((a, b) => b.share - a.share);
for (const c of coverage) console.log(`lang  ${c.tag.padEnd(7)} ${(c.share * 100).toFixed(1).padStart(5)}%  ${c.done}/${localizable.length}  ${c.share >= threshold ? 'published' : `not published (needs ${(threshold * 100).toFixed(0)}%)`}`);
if (!coverage.length) console.log(`lang  no translations yet (${localizable.length} localizable keys of ${Object.keys(keys).length})`);

warns.slice(0, 40).forEach((w) => console.warn('warn ', w));
if (warns.length > 40) console.warn(`warn  ... and ${warns.length - 40} more unused`);
errors.forEach((e) => console.error('error', e));
console.log(`${Object.keys(keys).length} keys, ${usedKeys.size} used, ${errors.length} errors, ${warns.length} warnings`);
process.exit(errors.length || (strict && warns.length) ? 1 : 0);
