// /changelog/feed.xml: RSS 2.0 of the releases in src/data/releases.json, newest first. Each item's guid
// is the release page's URL, its pubDate is RFC 822 (UTC), and its enclosure is the APK. The release
// notes are English data; the channel's title and description come from the catalog.
import type { APIRoute } from 'astro';
import { ORIGIN } from '../../../site.config.mjs';
import { t } from '../../i18n/t';
import { releases, urlOf } from '../../lib/releases';

const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
/** RFC 822 date, as RSS wants it: Fri, 30 Oct 2026 15:16:17 GMT */
const rfc822 = (iso: string) => new Date(iso.length === 10 ? `${iso}T00:00:00Z` : iso).toUTCString();

export const GET: APIRoute = () => {
  const items = releases.map((r) => {
    const body = [r.summary, ...r.highlights.map((h) => `- ${h}`)].join('\n');
    return [
      '    <item>',
      `      <title>${esc(r.title)}</title>`,
      `      <link>${esc(urlOf(r))}</link>`,
      `      <guid isPermaLink="true">${esc(urlOf(r))}</guid>`,
      `      <pubDate>${rfc822(r.published ?? r.date)}</pubDate>`,
      `      <description>${esc(body)}</description>`,
      r.apk ? `      <enclosure url="${esc(r.apk.url)}" length="${r.apk.size}" type="application/vnd.android.package-archive"/>` : '',
      '    </item>',
    ].filter(Boolean).join('\n');
  });
  const newest = releases[0];
  const xml = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom">',
    '  <channel>',
    `    <title>${esc(t('changelog.feed_title'))}</title>`,
    `    <link>${ORIGIN}/changelog/</link>`,
    `    <description>${esc(t('changelog.feed_description'))}</description>`,
    '    <language>en</language>',
    newest ? `    <lastBuildDate>${rfc822(newest.published ?? newest.date)}</lastBuildDate>` : '',
    `    <atom:link href="${ORIGIN}/changelog/feed.xml" rel="self" type="application/rss+xml"/>`,
    ...items,
    '  </channel>',
    '</rss>',
    '',
  ].filter((l) => l !== '').join('\n');
  return new Response(xml, { headers: { 'Content-Type': 'application/rss+xml; charset=utf-8' } });
};
