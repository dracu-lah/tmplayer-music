// BreadcrumbList JSON-LD for a page under the home page. `crumbs` are the pages between Home and the
// page itself, the page last: [{ name: 'Features', path: '/features/' }]. Paths are English paths; the
// URLs in the list are those of the page's language.
import { ORIGIN } from '../../site.config.mjs';
import { l } from '../i18n/links';

export interface Crumb { name: string; path: string }

export function breadcrumbLd(crumbs: Crumb[]) {
  const all: Crumb[] = [{ name: 'TMPlayer Music', path: '/' }, ...crumbs];
  return {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: all.map((c, i) => ({ '@type': 'ListItem', position: i + 1, name: c.name, item: ORIGIN + l(c.path) })),
  };
}
