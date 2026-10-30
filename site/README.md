# music.tmplayer.org

The product page of TMPlayer Music. For now it is a **placeholder**: one line about what the app is,
the unofficial notice, three links, framed screenshots (demo data) and a gallery, kept out of search
engines until launch.

It is an [Astro](https://astro.build) project built the same way as tmplayer.org's site
(`../TMPlayer/site/`): pnpm, the same Astro version, `dist/` as output, directories with a trailing
slash, assets hashed under `/_astro/`. The difference is the host: Cloudflare Pages (project
`tmplayer-music-site`, see `wrangler.jsonc`) instead of Vercel, so headers live in a `_headers` file
written by the build instead of `vercel.json`.

```sh
pnpm -C site install
pnpm -C site dev          # http://localhost:4321/, hot reload
pnpm -C site build        # writes site/dist/ (and dist/_headers)
pnpm -C site check        # astro check, the type check
pnpm -C site pages:dev    # serves dist/ the way Cloudflare does, with _headers, on :8788
pnpm -C site pages:deploy # builds, then uploads dist/ as the production deploy
```

(The script is `pages:deploy`, not `deploy`: `pnpm deploy` is a pnpm command of its own.)

The project was created on 2026-10-30 with
`npx wrangler pages project create tmplayer-music-site --production-branch main --force`.
Wrangler 4.156 tries to send `pages project create` to the new "Pages on Workers" and fails on a
static site with no Worker entry; `--force` creates a classic Pages project, like the lyrics
gateway's. It is needed only for creating; deploys run against Pages without it.

`pages:deploy` is `wrangler pages deploy dist --project-name tmplayer-music-site --branch main`, with the
Cloudflare account wrangler is logged in to (`npx wrangler whoami`; `npx wrangler login` if not).
`--branch main` makes it the production deploy; any other branch name makes a preview.

## Where things are

| Path | What it is |
|---|---|
| `site.config.mjs` | `INDEXABLE`, the one switch for search engines, and `ORIGIN` |
| `astro.config.mjs` | the build, and `tmHeaders`, which writes `dist/_headers` (security headers, caching, X-Robots-Tag) |
| `src/layouts/Base.astro` | `<html>` and the whole `<head>`: canonical, robots, Open Graph, Twitter, icons, manifest |
| `src/pages/index.astro`, `src/pages/support/index.astro`, `src/pages/404.astro` | the pages: home (with a small Chip in block), Support (English only, as tmplayer.org's) and 404 |
| `src/components/PhoneFrame.astro` | a phone drawn in CSS (graphite body, punch hole, side buttons, shadow) around one screenshot (srcset 540 and 1080); `--pf-w` sets its width |
| `public/screenshots/` | `<screen>.webp` (1080 x 2400) and `<screen>-540.webp`, written by `../scripts/prepare-screenshots.py` (below) |
| `src/components/Footer.astro`, `src/components/CoffeeButton.astro`, `src/lib/support.ts` | the footer and the support links: Buy Me a Coffee first in its own yellow (`public/brand/`, their official cup), GitHub Sponsors beside it |
| `src/pages/sitemap.xml.ts`, `src/pages/robots.txt.ts` | the sitemap and robots.txt, both following `INDEXABLE` |
| `src/lib/app-ld.ts` | the JSON-LD: `WebSite` always, `SoftwareApplication` only when indexable |
| `src/components/JsonLd.astro` | writes a JSON-LD block |
| `src/data/lastmod.json` | each page's sitemap entry; move `lastmod` when the page's content changes |
| `src/styles/styles.css` | the stylesheet: one look, pure black with white text, as the app |
| `public/` | served as is: icons (from the launcher icon), `og.png`, `site.webmanifest`, fonts, the IndexNow key file |
| `../scripts/indexnow-site.py`, `../.github/workflows/indexnow.yml` | IndexNow, ready but idle until launch (below) |

`public/favicon.svg` is the launcher icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`) on
its tile; `icon-512.png`, `favicon.ico` and `og.png` were rendered from it with headless Chromium.
Change `og.png` and bump `?v=` in `Base.astro`.

## Screenshots

The hero's three phones (Albums, Home in front, Now Playing) and the gallery come from the debug
build's demo library (invented names only), in the app's one pure black look. To redo them:

```sh
scripts/screenshots.sh /tmp/shots                     # the raw <screen>.png captures from a device
python3 scripts/prepare-screenshots.py /tmp/shots     # WebP q88 for the site, and the README frames
```

The captures already have a clean status bar (12:00, Wi-Fi, full battery), so the script does not
touch them: it writes `public/screenshots/<screen>.webp` (1080 x 2400) and `<screen>-540.webp`
(540 x 1200), and renders `docs/screenshots/readme-<screen>.webp` (Home, Albums, Now Playing and
Lyrics, the framed phones in the repository's README) with headless Chromium from the `<style>` of
`PhoneFrame.astro`, on a transparent background. WebP quality 88 is the only lossy step. Settings,
Artist and Playlist are captured but not used. Only the hero's phones load eagerly; the gallery's
are lazy. The gallery scrolls sideways with snap points and Previous and Next buttons, and never
moves by itself.

## Attaching music.tmplayer.org (once, in the dashboard)

tmplayer.org's DNS is not on Cloudflare, so the domain is attached as a Pages custom domain with a
plain CNAME at the DNS provider:

1. Cloudflare dashboard, Workers and Pages, `tmplayer-music-site`, Custom domains, Set up a custom
   domain: `music.tmplayer.org`, Continue. Choose the CNAME (not "Begin DNS transfer") option.
2. At tmplayer.org's DNS provider add: name `music`, type `CNAME`, target
   `tmplayer-music-site.pages.dev` (DNS only, no proxying at the provider). If the zone has CAA
   records, they must allow `letsencrypt.org`, `pki.goog` or `ssl.com` (whichever Cloudflare names).
3. Wait for the dashboard to show the domain Active (usually minutes; up to a day for DNS), then:
   `curl -sI https://music.tmplayer.org/` should give `200` and `x-robots-tag: noindex, nofollow`.

Do the CNAME only after step 1: a CNAME to `*.pages.dev` without the domain added in the project
answers with a 522.

## Going live: the switch

Everything that keeps the page out of search engines follows one constant, `INDEXABLE` in
`site/site.config.mjs`. While it is `false`:

- every page has `<meta name="robots" content="noindex, nofollow">`;
- every response has `X-Robots-Tag: noindex, nofollow` (`dist/_headers`);
- `robots.txt` is `Disallow: /` with no `Sitemap:` line, and `sitemap.xml` is an empty urlset;
- the `SoftwareApplication` JSON-LD is not rendered;
- `scripts/indexnow-site.py` sends nothing.

Setting it to `true` and rebuilding flips all of these at once. The `*.pages.dev` addresses keep
`X-Robots-Tag: noindex` either way, so only music.tmplayer.org is ever indexed.

### Launch checklist

Before flipping:

- [ ] There is a release to point at, and the page's copy is rewritten for it (download button,
      screenshots as WebP, features that are in the release and nothing more). Public copy rules in
      `AGENTS.md`: "audio" and "tracks", the unofficial notice, a link to tmplayer.org/legal, no real
      artist, album or song names.
- [ ] The GitHub repository is public (the "Source on GitHub" link is a 404 for visitors until then).
- [ ] music.tmplayer.org is attached and answers 200 (above).
- [ ] Canonical: every page's `path` in `<Base>` gives `https://music.tmplayer.org/...`; check
      `curl -s https://music.tmplayer.org/ | grep canonical`.
- [ ] OG image: `public/og.png` (1200 x 630) still matches the page; bump `?v=` in `Base.astro` if
      it changed. Check with a share preview (e.g. the Facebook or LinkedIn post inspector).
- [ ] JSON-LD: in `src/lib/app-ld.ts` fill `softwareVersion`, `downloadUrl` and `screenshot`, and
      check `operatingSystem` against the release. Validate with the Rich Results Test.
- [ ] Sitemap: every page has its entry in `src/data/lastmod.json` with today's `lastmod`.

Flip:

- [ ] `export const INDEXABLE = true;` in `site/site.config.mjs`, then `pnpm -C site pages:deploy`.
- [ ] Check: `curl -sI https://music.tmplayer.org/` has no `x-robots-tag`;
      `curl -s https://music.tmplayer.org/robots.txt` has `Allow: /` and the `Sitemap:` line;
      `curl -s https://music.tmplayer.org/sitemap.xml` lists the pages; the page source says
      `index, follow`; `curl -sI https://tmplayer-music-site.pages.dev/` still says `noindex`.

After:

- [ ] Add music.tmplayer.org to Google Search Console and Bing Webmaster Tools and submit
      `https://music.tmplayer.org/sitemap.xml`.
- [ ] IndexNow: run the workflow "IndexNow (site)" by hand (Actions tab), or locally
      `python3 scripts/indexnow-site.py --after HEAD --dry-run`, then without `--dry-run`. The key
      file is `public/bcb44850bb54b1caf19b3c49dc082b9e.txt`; keep exactly one such file.

## IndexNow

`scripts/indexnow-site.py` is TMPlayer's, cut down to one language: it posts the pages whose
`lastmod` in `src/data/lastmod.json` changed between two commits, filtered by the live sitemap.
TMPlayer's workflow starts on each push and waits for Vercel's commit status; a direct upload to
Pages leaves no such status, so here `.github/workflows/indexnow.yml` runs only when started by hand
after a deploy. Both do nothing while `INDEXABLE` is false.

## Rolling back

Dashboard, Workers and Pages, `tmplayer-music-site`, Deployments, the earlier production deployment,
"Rollback to this deployment". `npx wrangler pages deployment list --project-name tmplayer-music-site`
lists them.
