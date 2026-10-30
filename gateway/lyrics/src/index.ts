// TMPlayer Music's lyrics gateway: the app asks here, this asks LRCLIB. The app sends only title,
// artist, album and duration. This Worker keeps nothing about who asked: no logging, no analytics,
// no storage. The only thing kept is LRCLIB's answer in the edge cache, keyed by a hash of the
// song's four values, so the next person asking for the same song does not reach LRCLIB again.

import { DEFAULT_USER_AGENT, type Lyrics, lookUp, type Outcome } from "./lrclib";
import { type LyricsQuery, normalise, parseQuery } from "./query";

export interface Env {
  /** Cloudflare's rate limiting binding (`ratelimits` in wrangler.jsonc). Optional: tests and local dev. */
  LIMITER?: RateLimit;
  /** The User-Agent sent to LRCLIB. */
  USER_AGENT?: string;
}

/** Edge cache lifetimes. */
export const FOUND_TTL_SECONDS = 7 * 86_400;
export const MISSING_TTL_SECONDS = 6 * 3600;
/** What the app may keep on its own side (it keeps lyrics per device anyway). */
const CLIENT_FOUND_MAX_AGE = 86_400;
const CLIENT_MISSING_MAX_AGE = 3600;

/** Upstream calls this isolate may make per minute, whoever asks: LRCLIB allows about 90 for a named client. */
export const UPSTREAM_PER_MINUTE = 60;

const CACHE_HOST = "https://lyrics-cache.invalid";
const STATUS_HEADER = "X-Lyrics-Status";

function json(status: number, body: unknown, cacheControl = "no-store", extra: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": cacheControl,
      "X-Content-Type-Options": "nosniff",
      ...extra,
    },
  });
}

const found = (lyrics: Lyrics) =>
  json(200, { synced: lyrics.synced, plain: lyrics.plain, instrumental: lyrics.instrumental, source: "LRCLIB" }, `private, max-age=${CLIENT_FOUND_MAX_AGE}`);
const missing = () => json(404, { error: "not_found", message: "No lyrics found for this track." }, `private, max-age=${CLIENT_MISSING_MAX_AGE}`);
const limited = (seconds: number) =>
  json(429, { error: "rate_limited", message: "Too many lookups. Try again later.", retryAfter: seconds }, "no-store", { "Retry-After": String(seconds) });
const upstreamFailed = () => json(502, { error: "upstream", message: "The lyrics service did not answer. Try again later." });

// ---- Rate limits, none of which keeps who asked -------------------------------------------------

/**
 * A coarse bucket for the caller: 16 bits of a SHA-256 of their address, so about one in 65,536
 * of all addresses share it. Enough to slow one noisy client, not enough to tell who anyone is.
 * The address itself is never stored, logged or sent on.
 */
export async function bucketOf(address: string | null): Promise<string> {
  if (!address) return "b-none";
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(address)));
  return `b-${(((hash[0] ?? 0) << 8) | (hash[1] ?? 0)).toString(16)}`;
}

/** A token bucket for upstream calls in this isolate, shared by everyone; it only counts. */
export class TokenBucket {
  private tokens: number;
  private last: number;
  private blockedUntil = 0;

  constructor(
    private readonly capacity: number,
    private readonly perSecond: number,
    private readonly now: () => number = Date.now,
  ) {
    this.tokens = capacity;
    this.last = now();
  }

  /** Takes one token; false when none is left or LRCLIB asked us to wait. */
  take(): boolean {
    const now = this.now();
    if (now < this.blockedUntil) return false;
    this.tokens = Math.min(this.capacity, this.tokens + ((now - this.last) / 1000) * this.perSecond);
    this.last = now;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }

  /** LRCLIB said 429: no upstream call from this isolate for [seconds]. */
  block(seconds: number): void {
    this.blockedUntil = Math.max(this.blockedUntil, this.now() + seconds * 1000);
  }

  /** Whole seconds until a call may go, at least 1. */
  wait(): number {
    const now = this.now();
    if (now < this.blockedUntil) return Math.max(1, Math.ceil((this.blockedUntil - now) / 1000));
    return Math.max(1, Math.ceil((1 - this.tokens) / this.perSecond));
  }
}

let upstream = new TokenBucket(UPSTREAM_PER_MINUTE, UPSTREAM_PER_MINUTE / 60);

/** Tests only: a fresh upstream bucket. */
export function resetUpstreamBucket(bucket = new TokenBucket(UPSTREAM_PER_MINUTE, UPSTREAM_PER_MINUTE / 60)): void {
  upstream = bucket;
}

async function clientAllowed(env: Env, request: Request): Promise<boolean> {
  if (!env.LIMITER) return true;
  try {
    const key = await bucketOf(request.headers.get("CF-Connecting-IP"));
    return (await env.LIMITER.limit({ key })).success;
  } catch {
    // The binding failing must not take lyrics down; the upstream bucket still guards LRCLIB.
    return true;
  }
}

// ---- Edge cache ---------------------------------------------------------------------------------

/** The cache key: a hash of the normalised four values, so the cache holds no readable names. */
export async function cacheKeyOf(query: LyricsQuery): Promise<Request> {
  const text = JSON.stringify([normalise(query.title), normalise(query.artist), normalise(query.album), query.duration]);
  const hash = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)));
  const hex = Array.from(hash, (b) => b.toString(16).padStart(2, "0")).join("");
  return new Request(`${CACHE_HOST}/v1/${hex}`);
}

function edgeCache(): Cache | null {
  return typeof caches === "undefined" ? null : caches.default;
}

/** What the edge cache holds for [key], as the answer to send, or null. */
async function cached(key: Request): Promise<Response | null> {
  const cache = edgeCache();
  if (!cache) return null;
  try {
    const hit = await cache.match(key);
    if (!hit) return null;
    const status = hit.headers.get(STATUS_HEADER);
    if (status === "404") {
      await hit.body?.cancel().catch(() => {});
      return missing();
    }
    if (status !== "200") return null;
    const lyrics = (await hit.json()) as Lyrics;
    return found(lyrics);
  } catch {
    return null;
  }
}

/** Entries are stored as 200s with the real status in a header, so the Cache API accepts both. */
function remember(ctx: ExecutionContext, key: Request, outcome: Outcome): void {
  const cache = edgeCache();
  if (!cache) return;
  let entry: Response;
  if (outcome.kind === "found") {
    entry = new Response(JSON.stringify(outcome.lyrics), {
      headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${FOUND_TTL_SECONDS}`, [STATUS_HEADER]: "200" },
    });
  } else if (outcome.kind === "missing") {
    entry = new Response("{}", {
      headers: { "Content-Type": "application/json", "Cache-Control": `public, max-age=${MISSING_TTL_SECONDS}`, [STATUS_HEADER]: "404" },
    });
  } else {
    return;
  }
  ctx.waitUntil(cache.put(key, entry).catch(() => {}));
}

// ---- Routes -------------------------------------------------------------------------------------

async function lyrics(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const query = parseQuery(new URL(request.url).searchParams);
  if ("error" in query) return json(400, { error: "bad_request", message: query.error });

  if (!(await clientAllowed(env, request))) return limited(60);

  const key = await cacheKeyOf(query);
  const hit = await cached(key);
  if (hit) return hit;

  const outcome = await lookUp(query, {
    userAgent: env.USER_AGENT?.trim() || DEFAULT_USER_AGENT,
    mayCall: () => upstream.take(),
  });
  switch (outcome.kind) {
    case "found":
      remember(ctx, key, outcome);
      return found(outcome.lyrics);
    case "missing":
      remember(ctx, key, outcome);
      return missing();
    case "limited":
      if (outcome.byUpstream) upstream.block(outcome.retryAfter);
      return limited(Math.max(outcome.retryAfter, upstream.wait()));
    case "failed":
      return upstreamFailed();
  }
}

export async function handle(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  const { pathname } = new URL(request.url);
  if (pathname !== "/v1/lyrics" && pathname !== "/v1/health") {
    return json(404, { error: "no_route", message: "Unknown path." });
  }
  if (request.method !== "GET") {
    return json(405, { error: "method_not_allowed", message: "Only GET is served." }, "no-store", { Allow: "GET" });
  }
  if (pathname === "/v1/health") return json(200, { ok: true }, "no-store");
  return lyrics(request, env, ctx);
}

export default {
  async fetch(request, env, ctx): Promise<Response> {
    try {
      return await handle(request, env, ctx);
    } catch {
      // Nothing is logged: the request is not ours to keep, even when something breaks.
      return upstreamFailed();
    }
  },
} satisfies ExportedHandler<Env>;
