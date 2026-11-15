// The site's pages in one list, so the header, the footer, the 404 and the home page's links cannot
// drift apart. Labels are catalog keys (nav.*), resolved where they are drawn. Every path here has a
// page; src/data/lastmod.json lists the same paths for the sitemap.
// i18n-keys: nav.features, nav.install, nav.faq, nav.changelog, nav.support, nav.download, nav.troubleshooting, nav.privacy, nav.legal, nav.security, nav.support_project, footer.product, footer.help, footer.legal
export interface NavItem { href: string; label: string }

/** The header's links, in order. Download is the button beside them. */
export const HEADER: NavItem[] = [
  { href: '/features/', label: 'nav.features' },
  { href: '/install/', label: 'nav.install' },
  { href: '/faq/', label: 'nav.faq' },
  { href: '/changelog/', label: 'nav.changelog' },
  { href: '/support/', label: 'nav.support' },
];

export const FOOTER: { title: string; items: NavItem[] }[] = [
  { title: 'footer.product', items: [
    { href: '/features/', label: 'nav.features' },
    { href: '/download/', label: 'nav.download' },
    { href: '/install/', label: 'nav.install' },
    { href: '/changelog/', label: 'nav.changelog' },
  ] },
  { title: 'footer.help', items: [
    { href: '/faq/', label: 'nav.faq' },
    { href: '/troubleshooting/', label: 'nav.troubleshooting' },
    { href: '/support/', label: 'nav.support_project' },
  ] },
  { title: 'footer.legal', items: [
    { href: '/privacy/', label: 'nav.privacy' },
    { href: '/legal/', label: 'nav.legal' },
    { href: '/security/', label: 'nav.security' },
  ] },
];
