// Runs every page's render inside the language its URL names, so t(), raw() and <T> know the page's
// language without it being passed to them. Astro.locals.lang is set too, for the layouts.
//
// The render goes inside an AsyncLocalStorage (src/i18n/t.ts, langStore). The response body is read
// before the store is left: Astro streams a page lazily, and a stream read after `run` returns would
// look up English. Pages are small and static, so buffering costs nothing.
import { defineMiddleware } from 'astro:middleware';
import { ENGLISH } from './i18n/langs';
import { langStore, published } from './i18n/t';

export const onRequest = defineMiddleware((context, next) => {
  const first = context.url.pathname.split('/')[1] ?? '';
  const lang = published.find((l) => l !== ENGLISH && l.path === first) ?? ENGLISH;
  context.locals.lang = lang;
  return langStore.run(lang, async () => {
    const res = await next();
    if (!res.body) return res;
    const body = await res.arrayBuffer();
    return new Response(body, { status: res.status, statusText: res.statusText, headers: res.headers });
  });
});
