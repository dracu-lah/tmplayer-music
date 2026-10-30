// The one switch for search engines. It is read by astro.config.mjs (the _headers file it writes),
// src/layouts/Base.astro (meta robots, JSON-LD), src/pages/robots.txt.ts and src/pages/sitemap.xml.ts.
//
// INDEXABLE is true now that 0.1.0 is out. While it is false (a placeholder), every page says noindex in its
// <meta name="robots"> and in an X-Robots-Tag header, robots.txt disallows everything and the
// sitemap lists nothing. Flip it to true only at launch, with the checklist in site/README.md
// ("Going live").
export const INDEXABLE = true;

// The production address. Canonical links, og:url, the sitemap and robots.txt are built on it, so
// they are already right before the custom domain is attached.
export const ORIGIN = 'https://music.tmplayer.org';
