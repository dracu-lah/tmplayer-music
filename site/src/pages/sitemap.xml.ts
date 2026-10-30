// /sitemap.xml. src/data/lastmod.json lists the pages in sitemap order with their real lastmod
// dates (the day the page's content last changed, not the build date), changefreq and priority.
//
// While INDEXABLE in site.config.mjs is false the sitemap is an empty urlset: a noindex page has
// no business in a sitemap. robots.txt does not point at it until then either.
import type { APIRoute } from 'astro';
import pages from '../data/lastmod.json';
import { INDEXABLE, ORIGIN } from '../../site.config.mjs';

interface Entry {
  path: string;
  lastmod?: string;
  changefreq?: string;
  priority?: string;
}

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function url(e: Entry): string {
  const bits = [`    <loc>${esc(ORIGIN + e.path)}</loc>`];
  if (e.lastmod) bits.push(`    <lastmod>${e.lastmod}</lastmod>`);
  if (e.changefreq) bits.push(`    <changefreq>${e.changefreq}</changefreq>`);
  if (e.priority) bits.push(`    <priority>${e.priority}</priority>`);
  return `  <url>\n${bits.join('\n')}\n  </url>`;
}

export const GET: APIRoute = () => {
  const entries = INDEXABLE ? (pages as Entry[]).map(url) : [];
  const body = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...entries,
    '</urlset>',
    '',
  ].join('\n');
  return new Response(body, { headers: { 'Content-Type': 'application/xml; charset=utf-8' } });
};
