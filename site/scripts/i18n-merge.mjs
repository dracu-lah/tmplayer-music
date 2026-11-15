// Folds i18n/parts/*.json into i18n/en.json: every key sorted, the parts removed. Run it when a feature's
// strings are done: `node scripts/i18n-merge.mjs`. A key that exists in both with different text stops it.
import { readFileSync, writeFileSync, readdirSync, rmSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'i18n');
const sortDeep = (o) => (o && typeof o === 'object' && !Array.isArray(o)
  ? Object.fromEntries(Object.keys(o).sort().map((k) => [k, sortDeep(o[k])]))
  : o);
const merge = (into, from, path = '') => {
  for (const [k, v] of Object.entries(from)) {
    const here = path ? `${path}.${k}` : k;
    if (v && typeof v === 'object') { into[k] ??= {}; merge(into[k], v, here); }
    else if (k in into && into[k] !== v) throw new Error(`conflict at ${here}`);
    else into[k] = v;
  }
};
const en = JSON.parse(readFileSync(join(root, 'en.json'), 'utf8'));
const dir = join(root, 'parts');
const parts = existsSync(dir) ? readdirSync(dir).filter((f) => f.endsWith('.json')) : [];
for (const f of parts) merge(en, JSON.parse(readFileSync(join(dir, f), 'utf8')));
writeFileSync(join(root, 'en.json'), JSON.stringify(sortDeep(en), null, 2) + '\n');
for (const f of parts) rmSync(join(dir, f));
console.log(`merged ${parts.length} part file(s) into en.json`);
