import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Tests run inside the Workers runtime (Miniflare), with the real Cache API. They call the
// Pages entry (functions/[[route]].ts) directly with their own env and context, and stub fetch, so
// LRCLIB is never reached.
export default defineConfig({
  plugins: [
    cloudflareTest({
      miniflare: { compatibilityDate: "2026-10-01" },
    }),
  ],
});
