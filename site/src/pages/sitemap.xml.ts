// /sitemap.xml. src/data/lastmod.json lists the pages in sitemap order with their real lastmod
// dates (the day the page's content last changed, not the build date), changefreq and priority; every
// entry in it is listed, none is skipped. The home page also lists its screenshots as <image:image>
// entries (the phone shots the page shows), captioned with the same alt text the page uses.
//
// A localized page (src/i18n/links.ts) gets one entry per published language, each carrying the same
// xhtml:link alternates (every published language and x-default, which is English), so lastmod.json
// stays the one list and lists English paths only. English-only pages have one entry.
//
// While INDEXABLE in site.config.mjs is false the sitemap is an empty urlset: a noindex page has
// no business in a sitemap. robots.txt does not point at it until then either.
import type { APIRoute } from 'astro';
import pages from '../data/lastmod.json';
import { INDEXABLE, ORIGIN } from '../../site.config.mjs';
import { published, t } from '../i18n/t';
import { ENGLISH, type Lang } from '../i18n/langs';
import { isLocalized, pathIn } from '../i18n/links';

interface Entry {
  path: string;
  lastmod?: string;
  changefreq?: string;
  priority?: string;
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

// The screens on the home page's gallery (public/screenshots/<screen>.webp).
const SCREENS = ['home', 'chats', 'songs', 'albums', 'nowplaying', 'lyrics', 'queue', 'account'];
// i18n-keys: home.shot_home_alt, home.shot_chats_alt, home.shot_songs_alt, home.shot_albums_alt, home.shot_nowplaying_alt, home.shot_lyrics_alt, home.shot_queue_alt, home.shot_account_alt, home.shot_home_h, home.shot_chats_h, home.shot_songs_h, home.shot_albums_h, home.shot_nowplaying_h, home.shot_lyrics_h, home.shot_queue_h, home.shot_account_h

function images(path: string, lang: Lang): string[] {
  if (path !== '/') return [];
  return SCREENS.map((s) =>
    [
      '    <image:image>',
      `      <image:loc>${esc(`${ORIGIN}/screenshots/${s}.webp`)}</image:loc>`,
      `      <image:title>${esc(t(`home.shot_${s}_h`, {}, lang))}</image:title>`,
      `      <image:caption>${esc(t(`home.shot_${s}_alt`, {}, lang))}</image:caption>`,
      '    </image:image>',
    ].join('\n'));
}

/** The <url> of an English-path entry in one language, with the page's alternates when it is localized. */
function url(e: Entry, lang: Lang = ENGLISH): string {
  const bits = [`    <loc>${esc(ORIGIN + pathIn(lang, e.path))}</loc>`];
  if (isLocalized(e.path) && published.length > 1) {
    for (const o of published) bits.push(`    <xhtml:link rel="alternate" hreflang="${o.hreflang}" href="${esc(ORIGIN + pathIn(o, e.path))}"/>`);
    bits.push(`    <xhtml:link rel="alternate" hreflang="x-default" href="${esc(ORIGIN + e.path)}"/>`);
  }
  if (e.lastmod) bits.push(`    <lastmod>${e.lastmod}</lastmod>`);
  if (e.changefreq) bits.push(`    <changefreq>${e.changefreq}</changefreq>`);
  if (e.priority) bits.push(`    <priority>${e.priority}</priority>`);
  bits.push(...images(e.path, lang));
  return `  <url>\n${bits.join('\n')}\n  </url>`;
}

export const GET: APIRoute = () => {
  const entries = INDEXABLE ? (pages as Entry[]).flatMap((e) => (isLocalized(e.path) ? published : [ENGLISH]).map((lang) => url(e, lang))) : [];
  const body = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">',
    ...entries,
    '</urlset>',
    '',
  ].join('\n');
  return new Response(body, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
};
