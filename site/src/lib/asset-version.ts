// A content version for a file under public/, written into its URL as ?v=<hash>.
//
// Screenshot and badge names say what a picture shows, not which take of it this is, so a retaken
// tv-home.webp keeps its name. A URL that carries the file's hash changes exactly when the bytes do,
// which lets vercel.json cache it for a year as immutable; the plain URL (the sitemap's image
// entries, the manifest) keeps the short cache it always had. Read once per file per build.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';

const seen = new Map<string, string>();

// `pnpm -C site build` and Vercel both build from site/; the second root is a build started one
// level up.
const roots = [join(process.cwd(), 'public'), join(process.cwd(), 'site', 'public')];

export function versioned(url: string): string {
  if (!url.startsWith('/') || url.startsWith('//') || url.includes('?')) return url;
  let v = seen.get(url);
  if (v === undefined) {
    const file = roots.map((r) => join(r, url)).find((f) => existsSync(f));
    if (!file) throw new Error(`asset-version: ${url} is not in site/public`);
    v = createHash('sha256').update(readFileSync(file)).digest('hex').slice(0, 10);
    seen.set(url, v);
  }
  return `${url}?v=${v}`;
}
