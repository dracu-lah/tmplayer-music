import { cloudflareTest } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

// Tests run inside the Workers runtime (Miniflare), with the real Cache API. They call the
// handler directly with their own env, so the rate limiter is a stub and LRCLIB is never reached.
export default defineConfig({
  plugins: [
    cloudflareTest({
      miniflare: { compatibilityDate: "2026-10-01" },
    }),
  ],
});
