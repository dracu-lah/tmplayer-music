// Typed loaders for the release data: src/data/release.json (the latest stable release, what the
// Download and Install pages show) and src/data/releases.json (every release the changelog lists).
// Both are written by scripts/refresh-release.mjs from the GitHub releases.
//
// The notes in releases.json (title, summary, highlights) are English data, as the release notes on
// GitHub are, and are not in the string catalog; the words around them are.
import latestJson from '../data/release.json';
import releasesJson from '../data/releases.json';
import { REPO_URL } from './support';
import { ORIGIN } from '../../site.config.mjs';

export interface Apk { name: string; size: number; sha256: string; url: string }
export interface Latest { version: string; tag: string; date: string; prerelease: boolean; minAndroid: string; apk: Apk }
export interface Release {
  version: string;
  tag: string;
  /** YYYY-MM-DD */
  date: string;
  /** the full time, when known, for the feed */
  published?: string;
  prerelease: boolean;
  title: string;
  summary: string;
  highlights: string[];
  apk?: Apk;
}

export const latest = latestJson as Latest;

/** Every release, newest first, whatever order the file is in. */
export const releases: Release[] = [...(releasesJson as Release[])].sort((a, b) =>
  a.date === b.date ? (a.version < b.version ? 1 : -1) : a.date < b.date ? 1 : -1,
);

/** A release by its tag (v0.1.0, the route param) or by its version (0.1.0). */
export const byVersion = (version: string): Release | undefined => releases.find((r) => r.tag === version || r.version === version);
export const pathOf = (r: Release) => `/changelog/${r.tag}/`;
export const urlOf = (r: Release) => ORIGIN + pathOf(r);
export const githubUrlOf = (r: Release) => `${REPO_URL}/releases/tag/${r.tag}`;

/** The next older release, or undefined for the oldest. */
export function previousOf(r: Release): Release | undefined {
  const i = releases.findIndex((x) => x.tag === r.tag);
  return i >= 0 ? releases[i + 1] : undefined;
}
/** The next newer release, or undefined for the newest. */
export function nextOf(r: Release): Release | undefined {
  const i = releases.findIndex((x) => x.tag === r.tag);
  return i > 0 ? releases[i - 1] : undefined;
}
/** Every release except the one the Download page offers. */
export const earlier: Release[] = releases.filter((r) => r.tag !== latest.tag);

/** 27.7 for 29028246 bytes (MB as 1,000,000 bytes, as GitHub shows it). */
export const megabytes = (bytes: number) => (bytes / 1_000_000).toFixed(1);
/**
 * 30 October 2026 in the page's language (English by default), whatever the build machine's time zone.
 * The Gregorian calendar is asked for by name: some languages (Persian) would use their own by default.
 */
export const longDate = (iso: string, locale = 'en-GB') =>
  new Intl.DateTimeFormat(`${locale === 'en' ? 'en-GB' : locale}-u-ca-gregory`, { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(`${iso.slice(0, 10)}T12:00:00Z`));
