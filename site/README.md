# music.tmplayer.org

The site of TMPlayer Music: a player for the audio files already in your own Telegram chats. Unofficial,
not affiliated with Telegram. It is open to search engines (`INDEXABLE` is `true`, see "Going live").
English only for now; the catalog is built for more languages (see "Strings and languages").

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
node site/scripts/i18n-check.mjs   # the catalog against the source (below)
```

(The script is `pages:deploy`, not `deploy`: that name is a pnpm command of its own.)

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
| `src/layouts/Base.astro` | `<html>` and the whole `<head>`: canonical, robots, Open Graph, Twitter, icons, manifest; the `notFound` and `noCard` options |
| `src/layouts/Page.astro` | a content page: breadcrumb, eyebrow, one h1, lede, the slot, the "updated" date and the sponsor offer; adds the BreadcrumbList JSON-LD |
| `src/pages/` | one folder per page, each an `index.astro`: home, download, install, features, faq, changelog, troubleshooting, privacy, legal, security, support; plus `404.astro` |
| `src/pages/robots.txt.ts`, `sitemap.xml.ts`, `llms.txt.ts` | robots.txt (AI crawlers named, with the `Sitemap:` line), the sitemap (the home page's screenshots as `<image:image>`) and `/llms.txt`, a plain Markdown summary for language models |
| `src/data/lastmod.json` | each page's sitemap entry and the page list of `llms.txt`; move `lastmod` when the page's content changes, add a line for a new page (and a one-line description in `llms.txt.ts`) |
| `src/data/release.json` | the latest release: version, tag, date, Android floor, APK name, size, SHA-256 and URL (below) |
| `src/lib/app-ld.ts` | the JSON-LD: `WebSite`, `Organization` (the publisher) and `SoftwareApplication` (rendered only while indexable), built from `release.json` |
| `src/lib/support.ts`, `nav.ts`, `breadcrumb.ts` | the support and repository links, the header and footer link lists, the BreadcrumbList |
| `src/components/` | the header, footer, sponsor offer, card and dialogs (Buy Me a Coffee, UPI), `T.astro` for catalog strings with links, `PhoneFrame.astro` and the rest |
| `src/scripts/site.ts` | the site's one script: menu, copy buttons, the support card, the two dialogs. Every part is an enhancement |
| `src/styles/styles.css` | the stylesheet: one look, pure black with white text, as the app |
| `i18n/` | the string catalog (below) |
| `public/` | served as is: icons (from the launcher icon), `og.png`, `site.webmanifest`, `.well-known/security.txt`, `_redirects`, fonts, screenshots, brand art, the IndexNow key file |
| `../scripts/indexnow-site.py`, `../.github/workflows/indexnow.yml` | IndexNow, started by hand after a deploy (below) |

`public/favicon.svg` is the launcher icon (`app/src/main/res/drawable/ic_launcher_foreground.xml`) on
its tile; `icon-512.png`, `favicon.ico` and `og.png` were rendered from it with headless Chromium.
Change `og.png` and bump `?v=` in `Base.astro`.

## Strings and languages

Every user-visible string, label, alt text, title and description comes from the catalog, never from the
page, so a language is a file and not a rewrite.

- `i18n/en.json` is the source: nested keys, `{placeholders}`, one key per paragraph or list item.
  `src/i18n/t.ts` looks a key up (`t('ns.key')`) and `<T k="ns.key">` fills `{a1}` style slots with
  inline links, so a translator moves the link with the words (the link text is its own key,
  `ns.key__a1`).
- While a page is being built its strings may sit in `i18n/parts/<namespace>.json` instead (English,
  nested, keys sorted). Both are read. When the page is done, `node scripts/i18n-merge.mjs` folds the
  parts into `en.json` (sorted) and removes them; a key that exists in both with different text stops
  it.
- `node scripts/i18n-check.mjs [--strict] [--parts-ok]` checks the catalog against the source: every
  key a page asks for exists, no key is unused (a warning, an error with `--strict`), `en.json` is
  sorted, and no string has an em or en dash. Without `--parts-ok` it fails while `i18n/parts/` still
  holds files. Keys built at run time are declared in a comment in the file that builds them:
  `// i18n-keys: a.b, a.c`.
- Not in the catalog on purpose: `robots.txt`, `sitemap.xml`, `llms.txt` and `security.txt` (machine
  files, English), code samples and commands, and the JSON-LD names.

### Adding a language later

1. Copy `i18n/en.json` to `i18n/<tag>.json` and translate the values; keep the keys and every
   `{placeholder}` (a string that loses one falls back to English, so a link is never dropped).
2. Add the language to `LANGS` in `src/i18n/langs.ts`.
3. Build the routing for it (a `/<tag>/` copy of the pages, `hreflang` alternates in `Base.astro`, a
   picker in the header, the sitemap entries). None of that exists yet: only English is published.

Translations are the maintainer's job; do not run a translation sync tool from here.

## Release data

`src/data/release.json` feeds the home page's eyebrow, the download and install pages, `llms.txt`,
the security page's hash and the `SoftwareApplication` JSON-LD. After each release, edit it by hand
from the GitHub release: `version`, `tag`, `date`, `prerelease`, and the `apk` block (`name`, `size` in
bytes, `sha256` as GitHub shows it beside the file, `url`). Then add the release's page under
`src/pages/changelog/`, add it to `src/data/lastmod.json` with the moved `lastmod` dates, and check
`minAndroid`. Nothing else needs a change.

## Redirects and headers

- `public/_redirects` (Cloudflare Pages): `/apk` goes to the latest release, a short address to type on a
  phone or say aloud. Add a line here when a page moves.
- `dist/_headers` is written by `tmHeaders` in `astro.config.mjs` on every build, never by hand: nosniff,
  referrer policy, `X-Frame-Options: SAMEORIGIN`, a permissions policy (payment allowed for the Buy Me a
  Coffee frame), one year immutable caching for `/_astro/` and `/fonts/`, and `X-Robots-Tag: noindex` on
  the `*.pages.dev` addresses (and on every response while `INDEXABLE` is false).
- `public/.well-known/security.txt` (RFC 9116) points at the private advisory form and `/security/`. Its
  `Expires` is one year after it was written (2027-11-16): renew it before then.

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

Everything that keeps the site out of search engines follows one constant, `INDEXABLE` in
`site/site.config.mjs`. **It is `true` now** (0.1.0 is out). While it is `false` (a placeholder):

- every page has `<meta name="robots" content="noindex, nofollow">`;
- every response has `X-Robots-Tag: noindex, nofollow` (`dist/_headers`);
- `robots.txt` is `Disallow: /` with no `Sitemap:` line, and `sitemap.xml` is an empty urlset;
- `llms.txt` says the site is not open yet;
- the `SoftwareApplication` JSON-LD is not rendered;
- `scripts/indexnow-site.py` sends nothing.

Setting it back and rebuilding flips all of these at once. The `*.pages.dev` addresses keep
`X-Robots-Tag: noindex` either way, so only music.tmplayer.org is ever indexed.

### Status

- [x] A release to point at (0.1.0), the download button, WebP screenshots, copy limited to what the
      release does.
- [x] `INDEXABLE = true`; `robots.txt` has `Allow: /` and the `Sitemap:` line.
- [x] JSON-LD built from `release.json`.
- [x] Every page has its entry in `src/data/lastmod.json`.
- [ ] The GitHub repository is public (the "Source on GitHub" link is a 404 for visitors until then).
- [ ] music.tmplayer.org is attached and answers 200 (above), and
      `curl -s https://music.tmplayer.org/ | grep canonical` gives the production address.
- [ ] Search Console and Bing Webmaster Tools have the domain and `https://music.tmplayer.org/sitemap.xml`.
- [ ] IndexNow has been run once after the deploy (below).
- [ ] `og.png` (1200 x 630) still matches the site; check a share preview after a change.

After every deploy:

- `curl -sI https://music.tmplayer.org/` has no `x-robots-tag`;
  `curl -sI https://tmplayer-music-site.pages.dev/` still says `noindex`;
- `curl -s https://music.tmplayer.org/robots.txt` has `Allow: /` and the `Sitemap:` line;
  `/sitemap.xml`, `/llms.txt` and `/.well-known/security.txt` answer 200;
- validate the home page's JSON-LD with the Rich Results Test after a change to `app-ld.ts`.

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
