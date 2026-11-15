// Small helpers the Features, FAQ and Troubleshooting pages share.
import lastmod from '../../data/lastmod.json';
import { ORIGIN } from '../../../site.config.mjs';
import { l } from '../../i18n/links';

/** The date a page's content last changed, from the same list the sitemap reads. */
export const updatedOf = (path: string): string | undefined => lastmod.find((e) => e.path === path)?.lastmod;

/** An absolute URL on this site, for the page being rendered (a localized page keeps its language prefix). */
export const abs = (path: string): string => ORIGIN + l(path);

/** The bug report form of the project on GitHub, the same one INSTALL.md points to. */
export const BUG_REPORT_URL = 'https://github.com/dracu-lah/tmplayer-music/issues/new?template=bug_report.yml';
