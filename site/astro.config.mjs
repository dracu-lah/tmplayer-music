// Same shape as tmplayer.org's site: directories with a trailing slash, HTML left as written,
// assets hashed under /_astro/. Cloudflare Pages serves dist/ as it is. The response headers come
// from dist/_headers, which tmHeaders writes after each build, so the noindex header follows the
// INDEXABLE switch in site.config.mjs and cannot be left behind by hand.
import { defineConfig } from 'astro/config';
import { writeFileSync } from 'node:fs';
import { INDEXABLE, ORIGIN } from './site.config.mjs';

const tmHeaders = {
  name: 'tm-headers',
  hooks: {
    'astro:build:done': ({ dir }) => {
      const all = [
        '/*',
        '  X-Content-Type-Options: nosniff',
        '  Referrer-Policy: strict-origin-when-cross-origin',
        '  X-Frame-Options: SAMEORIGIN',
        '  Permissions-Policy: camera=(), microphone=(), geolocation=(), payment=(), usb=()',
      ];
      // Placeholder only: keeps every response, HTML or not, out of search results.
      // INDEXABLE = true in site.config.mjs drops this line at launch.
      if (!INDEXABLE) all.push('  X-Robots-Tag: noindex, nofollow');
      const body = [
        '# Written by astro.config.mjs (tmHeaders) on every build. Edit it there, not in dist/.',
        ...all,
        '',
        // The *.pages.dev addresses (production and every preview deploy) stay out of search
        // results even after launch: the custom domain is the only canonical home.
        'https://:project.pages.dev/*',
        '  X-Robots-Tag: noindex',
        '',
        'https://:version.:project.pages.dev/*',
        '  X-Robots-Tag: noindex',
        '',
        '/_astro/*',
        '  Cache-Control: public, max-age=31536000, immutable',
        '',
        '/fonts/*',
        '  Cache-Control: public, max-age=31536000, immutable',
        '',
      ].join('\n');
      writeFileSync(new URL('_headers', dir), body);
    },
  },
};

export default defineConfig({
  site: ORIGIN,
  trailingSlash: process.argv.includes('dev') ? 'ignore' : 'always',
  build: { format: 'directory', inlineStylesheets: 'never' },
  compressHTML: false,
  outDir: 'dist',
  integrations: [tmHeaders],
  devToolbar: { enabled: false },
  vite: { build: { cssMinify: true } },
});
