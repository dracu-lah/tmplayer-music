// The FAQ page's questions, as data. One list feeds the page (with its links) and the FAQPage JSON-LD
// (plain text, links stripped), so the two cannot drift apart.
//
// A question's strings are faq.<id>_q and faq.<id>_a. An answer may carry inline links as {a1}, {a2}
// placeholders, whose text is faq.<id>_a__a1 and so on; `links` gives their targets in that order.
// i18n-keys: faq.g_account, faq.g_cost, faq.g_play, faq.g_library, faq.g_updates, faq.g_privacy, faq.official_q, faq.official_a, faq.signin_q, faq.signin_a, faq.chats_q, faq.chats_a, faq.upload_q, faq.upload_a, faq.change_q, faq.change_a, faq.cost_q, faq.cost_a, faq.cost_a__a1, faq.open_q, faq.open_a, faq.open_a__a1, faq.formats_q, faq.formats_a, faq.nolength_q, faq.nolength_a, faq.big_q, faq.big_a, faq.versions_q, faq.versions_a, faq.albumsplit_q, faq.albumsplit_a, faq.missing_q, faq.missing_a, faq.missing_a__a1, faq.stores_q, faq.stores_a, faq.stores_a__a1, faq.update_q, faq.update_a, faq.update_a__a1, faq.privacy_q, faq.privacy_a, faq.privacy_a__a1, faq.privacy_a__a2
import { escapeHtml, raw, t } from '../i18n/t';
import { REPO_URL } from './support';
import { abs } from '../components/content/meta';
import { l } from '../i18n/links';

export interface FaqItem {
  id: string;
  /** hrefs for the {a1}, {a2} placeholders of the answer, in order */
  links?: string[];
}
export interface FaqGroup { id: string; h: string; items: FaqItem[] }

export const FAQ_GROUPS: FaqGroup[] = [
  { id: 'account', h: 'faq.g_account', items: [
    { id: 'official' },
    { id: 'signin' },
    { id: 'chats' },
    { id: 'upload' },
    { id: 'change' },
  ] },
  { id: 'cost', h: 'faq.g_cost', items: [
    { id: 'cost', links: ['/support/'] },
    { id: 'open', links: [REPO_URL] },
  ] },
  { id: 'play', h: 'faq.g_play', items: [
    { id: 'formats' },
    { id: 'nolength' },
    { id: 'big' },
  ] },
  { id: 'library', h: 'faq.g_library', items: [
    { id: 'versions' },
    { id: 'albumsplit' },
    { id: 'missing', links: ['/troubleshooting/no-tracks-found/'] },
  ] },
  { id: 'updates', h: 'faq.g_updates', items: [
    { id: 'stores', links: ['/install/'] },
    { id: 'update', links: ['/install/'] },
  ] },
  { id: 'privacy', h: 'faq.g_privacy', items: [
    { id: 'privacy', links: ['/privacy/', '/legal/'] },
  ] },
];

/** The answer as plain text: each {aN} placeholder becomes its link's words. */
export function plainAnswer(id: string): string {
  return raw(`faq.${id}_a`).replace(/\{(\w+)\}/g, (_m, name: string) => raw(`faq.${id}_a__${name}`));
}

/** The answer as HTML: each {aN} placeholder becomes a link to the item's Nth href, with the words of its a<N> string. */
export function answerHtml(item: FaqItem): string {
  const tmpl = raw(`faq.${item.id}_a`);
  return escapeHtml(tmpl).replace(/\{(\w+)\}/g, (m, name: string) => {
    const href = item.links?.[Number(name.slice(1)) - 1];
    return href ? `<a href="${escapeHtml(l(href))}">${escapeHtml(raw(`faq.${item.id}_a__${name}`))}</a>` : m;
  });
}

/** Every question with its answer in plain text, for the JSON-LD. */
export const faqLd = () => ({
  '@context': 'https://schema.org',
  '@type': 'FAQPage',
  url: abs('/faq/'),
  mainEntity: FAQ_GROUPS.flatMap((g) => g.items).map((i) => ({
    '@type': 'Question',
    name: t(`faq.${i.id}_q`),
    acceptedAnswer: { '@type': 'Answer', text: plainAnswer(i.id) },
  })),
});
