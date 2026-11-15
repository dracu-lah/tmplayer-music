// getStaticPaths() for a localized page: English at the root (no `lang` param), then every published
// language under its prefix (/es/, /pt/, /zh-tw/, ...).
import { published } from './t';

export function langPaths() {
  return published.map((lang) => ({ params: { lang: lang.path || undefined } }));
}
