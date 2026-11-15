// Localized URLs: /<prefix>/<page path> for the localized pages, English at the root. A page that is
// not localized (privacy, legal, security, the changelog and its feed, the 404) has one URL, in English,
// and localized pages link to it.
import type { Lang } from './langs';
import { GUIDES } from '../data/troubleshooting';
import { currentLang, published } from './t';

/** The English paths of the localized pages. */
export const LOCALIZED: readonly string[] = [
  '/',
  '/features/',
  '/download/',
  '/install/',
  '/faq/',
  '/troubleshooting/',
  ...GUIDES.map((g) => `/troubleshooting/${g.slug}/`),
  '/support/',
];
const LOCALIZED_SET = new Set(LOCALIZED);

export const isLocalized = (path: string): boolean => LOCALIZED_SET.has(path);

/** The URL path of an English page path in a language: prefixed for a localized page, unchanged otherwise. */
export function pathIn(lang: Lang, path: string): string {
  if (!lang.path || !LOCALIZED_SET.has(path)) return path;
  return path === '/' ? `/${lang.path}/` : `/${lang.path}${path}`;
}

/**
 * An internal link for the page being rendered: a localized page stays in the reader's language, anything
 * else (English-only pages, assets, the feed, other sites, a bare #anchor) is left as it is. Accepts a
 * trailing ?query or #hash.
 */
export function l(href: string, lang: Lang = currentLang()): string {
  if (!lang.path || !href.startsWith('/') || href.startsWith('//')) return href;
  const m = /^([^#?]*)([#?]?.*)$/.exec(href)!;
  const base = m[1].endsWith('/') ? m[1] : m[1] + '/';
  return pathIn(lang, base) + m[2];
}

/** The alternates of a localized page: every published language with its URL path. */
export const alternatesOf = (path: string) => published.map((lang) => ({ lang, path: pathIn(lang, path) }));
