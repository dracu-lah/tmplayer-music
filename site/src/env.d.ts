/// <reference types="astro/client" />

declare namespace App {
  interface Locals {
    /** the page's language, set by src/middleware.ts from the URL */
    lang: import('./i18n/langs').Lang;
  }
}
