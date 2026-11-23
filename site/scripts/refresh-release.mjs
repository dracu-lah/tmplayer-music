#!/usr/bin/env node
// Rewrites src/data/release.json (the latest stable release, for the Download and Install pages) and
// src/data/releases.json (every release, for the changelog) from the GitHub releases of
// dracu-lah/tmplayer-music. No dependencies: it uses fetch against the public GitHub API with no
// token (60 requests an hour is plenty; set GITHUB_TOKEN to raise it).
//
//   node scripts/refresh-release.mjs             write both files
//   node scripts/refresh-release.mjs --dry-run   print what would change, write nothing
//   node scripts/refresh-release.mjs --stable    leave pre-releases out of releases.json too
//
// What it does for each published (not draft) release:
//   - version is the tag without its leading "v", date is the publish day (UTC), published the full time.
//   - the desktop packages (.msi, .dmg, .tar.xz, .AppImage) are listed the same way under `desktop`.
//   - the APK is the first asset ending in .apk; its size is the asset's size and its SHA-256 is the
//     asset's `digest` field ("sha256:<hex>"), so nothing is downloaded. A release whose APK has no
//     digest is skipped with a warning rather than written without a checksum.
//   - an entry already in releases.json keeps its title, summary and highlights (they are edited by
//     hand); a new one gets the release name as title, the first paragraph of the body as summary and
//     the body's "- " or "* " lines as highlights.
// release.json takes the newest stable release; its minAndroid stays what the file says.
//
// After a run: a new release needs its path (/changelog/<tag>/) in src/data/lastmod.json, which this
// script does not touch, and the home page's SoftwareApplication (src/lib/app-ld.ts) names the
// version too. Check both, then `pnpm build`.
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = 'dracu-lah/tmplayer-music';
const data = join(dirname(fileURLToPath(import.meta.url)), '..', 'src', 'data');
const dryRun = process.argv.includes('--dry-run');
const stableOnly = process.argv.includes('--stable');

const readJson = (name, fallback) => {
  try { return JSON.parse(readFileSync(join(data, name), 'utf8')); } catch { return fallback; }
};

async function fetchReleases() {
  const headers = { Accept: 'application/vnd.github+json', 'User-Agent': 'site-refresh-release' };
  if (process.env.GITHUB_TOKEN) headers.Authorization = `Bearer ${process.env.GITHUB_TOKEN}`;
  const res = await fetch(`https://api.github.com/repos/${REPO}/releases?per_page=50`, { headers });
  if (!res.ok) throw new Error(`GitHub answered ${res.status} ${res.statusText}`);
  const list = await res.json();
  if (!Array.isArray(list)) throw new Error('GitHub did not answer with a list of releases');
  return list;
}

function apkOf(release) {
  const asset = (release.assets ?? []).find((a) => typeof a?.name === 'string' && a.name.endsWith('.apk') && a.state !== 'new');
  if (!asset) return undefined;
  const sha256 = typeof asset.digest === 'string' && asset.digest.startsWith('sha256:') ? asset.digest.slice(7) : '';
  if (!/^[0-9a-f]{64}$/.test(sha256)) {
    console.warn(`${release.tag_name}: ${asset.name} has no sha256 digest, skipped`);
    return undefined;
  }
  return { name: asset.name, size: Number(asset.size), sha256, url: asset.browser_download_url };
}

// The desktop packages of a release (Windows MSI, macOS DMG, Linux tarball and AppImage), the same
// shape as the APK plus a platform. A file without a sha256 digest is skipped with a warning.
const DESKTOP = [
  ['windows', /-windows-x64\.msi$/],
  ['macos', /-macos-arm64\.dmg$/],
  ['linux-appimage', /-x86_64\.AppImage$/],
  ['linux-tar', /-linux-x64\.tar\.xz$/],
];
function desktopOf(release) {
  const out = [];
  for (const [platform, re] of DESKTOP) {
    const asset = (release.assets ?? []).find((a) => typeof a?.name === 'string' && re.test(a.name) && a.state !== 'new');
    if (!asset) continue;
    const sha256 = typeof asset.digest === 'string' && asset.digest.startsWith('sha256:') ? asset.digest.slice(7) : '';
    if (!/^[0-9a-f]{64}$/.test(sha256)) {
      console.warn(`${release.tag_name}: ${asset.name} has no sha256 digest, skipped`);
      continue;
    }
    out.push({ platform, name: asset.name, size: Number(asset.size), sha256, url: asset.browser_download_url });
  }
  return out;
}

const clean = (s) => String(s ?? '').replace(/\r/g, '').replace(/[\u2012-\u2015]/g, '-');

function notesOf(release) {
  const body = clean(release.body);
  const paragraphs = body.split(/\n\s*\n/).map((p) => p.trim()).filter(Boolean);
  const summary = (paragraphs.find((p) => !/^([-*]|#)/.test(p)) ?? '').replace(/\s*\n\s*/g, ' ');
  const highlights = body.split('\n').map((l) => l.trim()).filter((l) => /^[-*]\s+/.test(l)).map((l) => l.replace(/^[-*]\s+/, ''));
  return { summary, highlights };
}

const old = readJson('releases.json', []);
const oldByTag = new Map(old.map((r) => [r.tag, r]));

let raw;
try {
  raw = await fetchReleases();
} catch (e) {
  console.error(`Could not read the releases (${e.message}). Nothing was changed.`);
  process.exit(1);
}

const releases = [];
for (const r of raw) {
  if (r.draft || typeof r.tag_name !== 'string' || !r.published_at) continue;
  if (stableOnly && r.prerelease) continue;
  const kept = oldByTag.get(r.tag_name);
  const notes = notesOf(r);
  const apk = apkOf(r);
  const desktop = desktopOf(r);
  releases.push({
    version: r.tag_name.replace(/^v/, ''),
    tag: r.tag_name,
    date: r.published_at.slice(0, 10),
    published: r.published_at,
    prerelease: Boolean(r.prerelease),
    title: kept?.title ?? clean(r.name) ?? r.tag_name,
    summary: kept?.summary ?? notes.summary,
    highlights: kept?.highlights ?? notes.highlights,
    ...(apk ? { apk } : {}),
    ...(desktop.length ? { desktop } : {}),
  });
}
releases.sort((a, b) => (a.published < b.published ? 1 : -1));

const newestStable = releases.find((r) => !r.prerelease && r.apk);
const oldLatest = readJson('release.json', {});
const latest = newestStable && {
  version: newestStable.version,
  tag: newestStable.tag,
  date: newestStable.date,
  prerelease: false,
  minAndroid: oldLatest.minAndroid ?? '6.0',
  apk: newestStable.apk,
  ...(newestStable.desktop ? { desktop: newestStable.desktop } : {}),
};

const out = [['releases.json', releases]];
if (latest) out.push(['release.json', latest]);
else console.warn('No stable release with an APK and a digest: release.json is left as it is.');

for (const [name, value] of out) {
  const text = `${JSON.stringify(value, null, 2)}\n`;
  const same = text === `${JSON.stringify(readJson(name, null), null, 2)}\n`;
  console.log(`${name}: ${same ? 'unchanged' : 'changed'}${dryRun ? ' (dry run)' : ''}`);
  if (!dryRun && !same) writeFileSync(join(data, name), text);
}
for (const r of releases) if (!oldByTag.has(r.tag)) console.log(`new release ${r.tag}: add /changelog/${r.tag}/ to src/data/lastmod.json`);
