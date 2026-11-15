// Checks the built site (dist/) for language mix-ups: run `pnpm build` first, then
//   node scripts/i18n-dist-check.mjs
// Every localized page of every language that was built must have, in that language: <html lang dir>, a
// <title>, an <h1> or a header link taken from that language's catalog (English where it has no string),
// a canonical on its own URL, and internal links that stay in the language (a link to a localized page
// carries the prefix, a link to an English-only page does not). English pages must hold no string that
// belongs to another language. This is the proof that the per-page language (middleware.ts and an
// AsyncLocalStorage) does not leak between pages rendered in the same build.
import { readFileSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const site = join(dirname(fileURLToPath(import.meta.url)), '..');
const dist = join(site, 'dist');
if (!existsSync(dist)) { console.error('error no dist/: run pnpm build first'); process.exit(1); }

const flat = (o, p = '', out = {}) => {
  for (const [k, v] of Object.entries(o)) {
    const key = p ? `${p}.${k}` : k;
    if (v && typeof v === 'object') flat(v, key, out); else out[key] = v;
  }
  return out;
};
const read = (f) => JSON.parse(readFileSync(f, 'utf8'));
const en = flat(read(join(site, 'i18n/en.json')));
const langsSrc = readFileSync(join(site, 'src/i18n/langs.ts'), 'utf8');
const LANGS = [...langsSrc.matchAll(/tag: '([^']+)', path: '([^']*)', hreflang: '([^']+)', og: '([^']+)', name: '([^']+)', dir: '(ltr|rtl)'/g)]
  .map((m) => ({ tag: m[1], path: m[2], hreflang: m[3], og: m[4], name: m[5], dir: m[6] }))
  .filter((l) => l.tag !== 'en');
const PH = /\{(\w+)\}/g;
const ph = (s) => [...s.matchAll(PH)].map((m) => m[1]).sort().join(',');
const catalog = (tag) => {
  const f = join(site, `i18n/${tag}.json`);
  return existsSync(f) ? flat(read(f)) : {};
};
const str = (cat, key) => (cat[key] && ph(cat[key]) === ph(en[key]) ? cat[key] : en[key]);
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const PAGES = [
  ['/', 'home.title'], ['/features/', 'features.title'], ['/download/', 'download.title'], ['/install/', 'install.title'],
  ['/faq/', 'faq.title'], ['/troubleshooting/', 'trouble.index_title'], ['/support/', 'supportpage.title'], ['/troubleshooting/telegram-blocked/', 'trouble.blocked_title'],
];
const LOCALIZED = new Set(['/', '/features/', '/download/', '/install/', '/faq/', '/troubleshooting/', '/support/']);
const errors = [];
let checked = 0;
const file = (prefix, p) => join(dist, prefix, p === '/' ? '' : p, 'index.html');

const built = [{ tag: 'en', path: '', dir: 'ltr', hreflang: 'en' }, ...LANGS.filter((l) => existsSync(file(l.path, '/')))];
for (const lang of built) {
  const cat = lang.tag === 'en' ? en : catalog(lang.tag);
  for (const [p, titleKey] of PAGES) {
    const f = file(lang.path, p);
    if (!existsSync(f)) { errors.push(`${lang.tag}: missing ${f}`); continue; }
    const html = readFileSync(f, 'utf8');
    checked++;
    const where = `${lang.tag}${p}`;
    const at = (re) => re.exec(html)?.[1];
    if (at(/<html lang="([^"]*)"/) !== lang.tag) errors.push(`${where}: html lang is ${at(/<html lang="([^"]*)"/)}`);
    if (at(/<html[^>]* dir="([^"]*)"/) !== lang.dir) errors.push(`${where}: html dir is ${at(/<html[^>]* dir="([^"]*)"/)}`);
    const title = at(/<title>([^<]*)<\/title>/);
    if (title !== esc(str(cat, titleKey))) errors.push(`${where}: title ${JSON.stringify(title)} is not ${titleKey} in ${lang.tag}`);
    const feat = at(/<nav class="site-nav"[^>]*>\s*<a href="[^"]*"[^>]*>([^<]*)<\/a>/);
    if (feat !== esc(str(cat, 'nav.features'))) errors.push(`${where}: first header link ${JSON.stringify(feat)} is not nav.features in ${lang.tag}`);
    const canon = at(/<link rel="canonical" href="([^"]*)"/);
    const want = `https://music.tmplayer.org${lang.path ? `/${lang.path}` : ''}${p}`;
    if (canon !== want) errors.push(`${where}: canonical ${canon}, want ${want}`);
    for (const m of html.matchAll(/<a ([^>]*)href="(\/[^"#?]*)/g)) {
      if (/hreflang=/.test(m[0]) || /hreflang=/.exec(html.slice(m.index, html.indexOf('>', m.index)))) continue; // the language picker
      const href = m[2];
      if (href.startsWith('//') || /\.\w+$/.test(href)) continue;
      const rest = lang.path && href.startsWith(`/${lang.path}/`) ? href.slice(lang.path.length + 1) : href;
      const prefixed = rest !== href;
      if (LOCALIZED.has(rest) || /^\/troubleshooting\/[\w-]+\/$/.test(rest) && !/changelog/.test(rest)) {
        if (lang.path && !prefixed) errors.push(`${where}: link ${href} is not in /${lang.path}/`);
      } else if (prefixed) errors.push(`${where}: link ${href} points to an English-only page with a prefix`);
    }
  }
}
// A marker test for throwaway catalogs: when a catalog prefixes every string with "[tag] ", English
// pages must not hold another language's marker.
for (const lang of built.filter((l) => l.tag !== 'en')) {
  const marker = `[${lang.path}] `;
  for (const [p] of PAGES) {
    const html = readFileSync(file('', p), 'utf8');
    if (html.includes(marker)) errors.push(`en${p}: holds the ${lang.tag} marker ${marker}`);
  }
}
errors.forEach((e) => console.error('error', e));
console.log(`${built.length} language(s) built (${built.map((l) => l.tag).join(', ')}), ${checked} pages checked, ${errors.length} errors`);
process.exit(errors.length ? 1 : 0);
