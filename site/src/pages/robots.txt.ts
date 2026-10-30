// /robots.txt, built from INDEXABLE in site.config.mjs.
//
// Placeholder (INDEXABLE false): every crawler is told to stay out, and there is no Sitemap line.
// At launch (INDEXABLE true): every crawler may read every page, the same policy as tmplayer.org's
// robots.txt, which names the AI crawlers one by one only to say so plainly. A group naming a bot
// replaces the * group for that bot, so each one carries its own Allow line.
import type { APIRoute } from 'astro';
import { INDEXABLE, ORIGIN } from '../../site.config.mjs';

const BOTS = [
  'OAI-SearchBot', 'ChatGPT-User', 'GPTBot',
  'Claude-SearchBot', 'Claude-User', 'ClaudeBot',
  'PerplexityBot', 'Perplexity-User',
  'Google-Extended', 'Applebot-Extended',
  'Bingbot',
];

const PLACEHOLDER = `# music.tmplayer.org is a placeholder until TMPlayer Music launches: nothing here is to be
# crawled or indexed yet. The pages also say noindex, in a meta tag and an X-Robots-Tag header.
User-agent: *
Disallow: /
`;

const LIVE = [
  '# Every crawler may read every page.',
  '',
  ...BOTS.flatMap((b) => [`User-agent: ${b}`, 'Allow: /', '']),
  'User-agent: *',
  'Allow: /',
  '',
  `Sitemap: ${ORIGIN}/sitemap.xml`,
  '',
].join('\n');

export const GET: APIRoute = () =>
  new Response(INDEXABLE ? LIVE : PLACEHOLDER, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
