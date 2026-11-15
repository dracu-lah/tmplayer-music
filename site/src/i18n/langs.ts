// The site's languages. English is the source of every string (i18n/en.json, with the same nested keys
// and {placeholders} as the app's catalog). A translation is a file i18n/<tag>.json with the same keys;
// add it here and in the picker when the routing for it is built. Only English is published today.
export interface Lang { tag: string; name: string; dir: 'ltr' | 'rtl' }
export const ENGLISH: Lang = { tag: 'en', name: 'English', dir: 'ltr' };
export const LANGS: Lang[] = [];
