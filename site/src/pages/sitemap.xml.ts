// /sitemap.xml. src/data/lastmod.json lists the pages in sitemap order with their real lastmod
// dates (the day the page's content last changed, not the build date), changefreq and priority; every
// entry in it is listed, none is skipped. The home page also lists its screenshots as <image:image>
// entries (the phone shots the page shows), captioned with the same alt text the page uses.
//
// While INDEXABLE in site.config.mjs is false the sitemap is an empty urlset: a noindex page has
// no business in a sitemap. robots.txt does not point at it until then either.
import type { APIRoute } from 'astro';
import pages from '../data/lastmod.json';
import { INDEXABLE, ORIGIN } from '../../site.config.mjs';
import { t } from '../i18n/t';

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

function images(path: string): string[] {
  if (path !== '/') return [];
  return SCREENS.map((s) =>
    [
      '    <image:image>',
      `      <image:loc>${esc(`${ORIGIN}/screenshots/${s}.webp`)}</image:loc>`,
      `      <image:title>${esc(t(`home.shot_${s}_h`))}</image:title>`,
      `      <image:caption>${esc(t(`home.shot_${s}_alt`))}</image:caption>`,
      '    </image:image>',
    ].join('\n'));
}

function url(e: Entry): string {
  const bits = [`    <loc>${esc(ORIGIN + e.path)}</loc>`];
  if (e.lastmod) bits.push(`    <lastmod>${e.lastmod}</lastmod>`);
  if (e.changefreq) bits.push(`    <changefreq>${e.changefreq}</changefreq>`);
  if (e.priority) bits.push(`    <priority>${e.priority}</priority>`);
  bits.push(...images(e.path));
  return `  <url>\n${bits.join('\n')}\n  </url>`;
}

export const GET: APIRoute = () => {
  const entries = INDEXABLE ? (pages as Entry[]).map(url) : [];
  const body = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:image="http://www.google.com/schemas/sitemap-image/1.1">',
    ...entries,
    '</urlset>',
    '',
  ].join('\n');
  return new Response(body, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
};
