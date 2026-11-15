// The site's languages. English is the source of every string (i18n/en.json, with the same nested keys
// and {placeholders} as the app's catalog). A translation is a file i18n/<tag>.json with the same keys;
// it is published (its pages generated, its link in the picker and the sitemap) only once it covers
// PUBLISH_THRESHOLD of the localizable keys (coverage() in t.ts). The list and its order are the
// sibling site's (tmplayer.org).
export interface Lang {
  /** catalog file name in site/i18n/ */
  tag: string;
  /** URL prefix, empty for English */
  path: string;
  /** what search engines are told (the language alone where one region stands for all) */
  hreflang: string;
  /** og:locale */
  og: string;
  /** the language's name for itself */
  name: string;
  dir: 'ltr' | 'rtl';
}
export const ENGLISH: Lang = { tag: 'en', path: '', hreflang: 'en', og: 'en_US', name: 'English', dir: 'ltr' };

/** The languages besides English, in the order the picker lists them. */
export const LANGS: Lang[] = [
  { tag: 'es-419', path: 'es', hreflang: 'es', og: 'es_LA', name: 'Español', dir: 'ltr' },
  { tag: 'pt-BR', path: 'pt', hreflang: 'pt', og: 'pt_BR', name: 'Português', dir: 'ltr' },
  { tag: 'fr', path: 'fr', hreflang: 'fr', og: 'fr_FR', name: 'Français', dir: 'ltr' },
  { tag: 'de', path: 'de', hreflang: 'de', og: 'de_DE', name: 'Deutsch', dir: 'ltr' },
  { tag: 'it', path: 'it', hreflang: 'it', og: 'it_IT', name: 'Italiano', dir: 'ltr' },
  { tag: 'pl', path: 'pl', hreflang: 'pl', og: 'pl_PL', name: 'Polski', dir: 'ltr' },
  { tag: 'cs', path: 'cs', hreflang: 'cs', og: 'cs_CZ', name: 'Čeština', dir: 'ltr' },
  { tag: 'ru', path: 'ru', hreflang: 'ru', og: 'ru_RU', name: 'Русский', dir: 'ltr' },
  { tag: 'uk', path: 'uk', hreflang: 'uk', og: 'uk_UA', name: 'Українська', dir: 'ltr' },
  { tag: 'tr', path: 'tr', hreflang: 'tr', og: 'tr_TR', name: 'Türkçe', dir: 'ltr' },
  { tag: 'id', path: 'id', hreflang: 'id', og: 'id_ID', name: 'Bahasa Indonesia', dir: 'ltr' },
  { tag: 'vi', path: 'vi', hreflang: 'vi', og: 'vi_VN', name: 'Tiếng Việt', dir: 'ltr' },
  { tag: 'ar', path: 'ar', hreflang: 'ar', og: 'ar_AR', name: 'العربية', dir: 'rtl' },
  { tag: 'fa', path: 'fa', hreflang: 'fa', og: 'fa_IR', name: 'فارسی', dir: 'rtl' },
  { tag: 'hi', path: 'hi', hreflang: 'hi', og: 'hi_IN', name: 'हिन्दी', dir: 'ltr' },
  { tag: 'bn', path: 'bn', hreflang: 'bn', og: 'bn_BD', name: 'বাংলা', dir: 'ltr' },
  { tag: 'ml', path: 'ml', hreflang: 'ml', og: 'ml_IN', name: 'മലയാളം', dir: 'ltr' },
  { tag: 'ja', path: 'ja', hreflang: 'ja', og: 'ja_JP', name: '日本語', dir: 'ltr' },
  { tag: 'ko', path: 'ko', hreflang: 'ko', og: 'ko_KR', name: '한국어', dir: 'ltr' },
  { tag: 'zh-CN', path: 'zh', hreflang: 'zh-Hans', og: 'zh_CN', name: '简体中文', dir: 'ltr' },
  { tag: 'zh-TW', path: 'zh-tw', hreflang: 'zh-Hant', og: 'zh_TW', name: '繁體中文', dir: 'ltr' },
];

/** A language is published only once its catalog covers this share of the localizable keys. Until
 * then it is not generated at all, so a half-translated catalog never puts English pages under /es/. */
export const PUBLISH_THRESHOLD = 0.95;

/** Catalog namespaces of the pages that stay English-only: they do not count toward coverage. */
export const ENGLISH_ONLY_NAMESPACES = ['privacy', 'legal', 'security', 'changelog', 'notfound'];
