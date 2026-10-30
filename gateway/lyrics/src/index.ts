// TMPlayer Music's lyrics gateway: the app asks here, this asks LRCLIB. The app sends only title,
// artist, album and duration. This Pages Function keeps nothing about who asked: no logging, no
// analytics, no storage. The only thing kept is LRCLIB's answer in the edge cache, keyed by a hash of
// the song's four values, so the next person asking for the same song does not reach LRCLIB again.
// The Pages entry point is functions/[[route]].ts, which only hands the request to serve() below.

import { DEFAULT_USER_AGENT, type Lyrics, lookUp, type Outcome } from "./lrclib";
import { type LyricsQuery, normalise, parseQuery } from "./query";

export interface Env {
  /** The User-Agent sent to LRCLIB (`vars` in wrangler.jsonc). */
  USER_AGENT?: string;
}

/** What the handler needs from the platform: Pages' EventContext and a Worker's ExecutionContext both have it. */
export interface Waiter {
  waitUntil(promise: Promise<unknown>): void;
}

/** Edge cache lifetimes. */
export const FOUND_TTL_SECONDS = 7 * 86_400;
export const MISSING_TTL_SECONDS = 6 * 3600;
/** What the app may keep on its own side (it keeps lyrics per device anyway). */
const CLIENT_FOUND_MAX_AGE = 86_400;
const CLIENT_MISSING_MAX_AGE = 3600;

/** Upstream calls this isolate may make per minute on average, whoever asks: LRCLIB allows about 90 for a named client. */
export const UPSTREAM_PER_MINUTE = 60;
/** The most upstream calls one isolate may make at once, so one noisy client cannot spend a minute's budget in a burst. */
export const UPSTREAM_BURST = 10;
/** Lookups one client bucket may make per minute in one isolate. */
export const CLIENT_PER_MINUTE = 30;
/** Most client buckets one isolate counts in a minute; past it, new buckets go uncounted (the upstream bucket still holds). */
export const MAX_CLIENT_BUCKETS = 4096;

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
//
// Pages Functions have no rate limiting binding (it is a Workers binding only), so every counter
// here lives in the memory of one isolate. Cloudflare runs several isolates per location and
// recycles them, so these limits are approximate and start again when an isolate goes away.

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

/**
 * Lookups per coarse client bucket in fixed windows, in this isolate only. The whole table is
 * dropped when a window ends, so no count outlives it, and it never holds more than [maxBuckets]
 * entries, each a 16 bit hash and a number.
 */
export class ClientCounter {
  private readonly counts = new Map<string, number>();
  private windowStart: number;

  constructor(
    private readonly limit: number,
    private readonly windowMs: number,
    private readonly maxBuckets: number,
    private readonly now: () => number = Date.now,
  ) {
    this.windowStart = now();
  }

  /** Counts one lookup for [bucket]: the whole seconds to wait when it is over the limit, else 0. */
  take(bucket: string): number {
    const now = this.now();
    if (now - this.windowStart >= this.windowMs || now < this.windowStart) {
      this.counts.clear();
      this.windowStart = now;
    }
    const count = this.counts.get(bucket);
    if (count === undefined) {
      if (this.counts.size < this.maxBuckets) this.counts.set(bucket, 1);
      return 0;
    }
    if (count >= this.limit) return Math.max(1, Math.ceil((this.windowStart + this.windowMs - now) / 1000));
    this.counts.set(bucket, count + 1);
    return 0;
  }

  /** How many buckets are held now. */
  size(): number {
    return this.counts.size;
  }
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
    this.tokens = Math.min(this.capacity, this.tokens + (Math.max(0, now - this.last) / 1000) * this.perSecond);
    this.last = now;
    if (this.tokens < 1) return false;
    this.tokens -= 1;
    return true;
  }

  /** LRCLIB said 429: no upstream call from this isolate for [seconds], and no burst right after. */
  block(seconds: number): void {
    const now = this.now();
    this.blockedUntil = Math.max(this.blockedUntil, now + seconds * 1000);
    this.tokens = 0;
    this.last = Math.max(this.last, this.blockedUntil);
  }

  /** Whole seconds until a call may go, at least 1. */
  wait(): number {
    const now = this.now();
    if (now < this.blockedUntil) return Math.max(1, Math.ceil((this.blockedUntil - now) / 1000));
    return Math.max(1, Math.ceil((1 - this.tokens) / this.perSecond));
  }
}

const freshUpstream = () => new TokenBucket(UPSTREAM_BURST, UPSTREAM_PER_MINUTE / 60);
const freshClients = () => new ClientCounter(CLIENT_PER_MINUTE, 60_000, MAX_CLIENT_BUCKETS);

let upstream = freshUpstream();
let clients = freshClients();

/** Tests only: a fresh upstream bucket. */
export function resetUpstreamBucket(bucket = freshUpstream()): void {
  upstream = bucket;
}

/** Tests only: a fresh client counter. */
export function resetClientCounter(counter = freshClients()): void {
  clients = counter;
}

/** Seconds the caller must wait, or 0. A failure here never takes lyrics down (fail open). */
async function clientWait(request: Request): Promise<number> {
  try {
    return clients.take(await bucketOf(request.headers.get("CF-Connecting-IP")));
  } catch {
    return 0;
  }
}

/**
 * Lookups in flight in this isolate, by cache key (a hash, no readable names): the same song asked
 * again while LRCLIB is still answering shares that one call. Entries go as soon as the call ends.
 */
const inFlight = new Map<string, Promise<Outcome>>();

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
function remember(ctx: Waiter, key: Request, outcome: Outcome): void {
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

async function lyrics(request: Request, env: Env, ctx: Waiter): Promise<Response> {
  const query = parseQuery(new URL(request.url).searchParams);
  if ("error" in query) return json(400, { error: "bad_request", message: query.error });

  const wait = await clientWait(request);
  if (wait > 0) return limited(wait);

  const key = await cacheKeyOf(query);
  const hit = await cached(key);
  if (hit) return hit;

  let pending = inFlight.get(key.url);
  if (!pending) {
    pending = lookUp(query, {
      userAgent: env.USER_AGENT?.trim() || DEFAULT_USER_AGENT,
      mayCall: () => upstream.take(),
    }).finally(() => inFlight.delete(key.url));
    inFlight.set(key.url, pending);
  }
  const outcome = await pending;
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

export async function handle(request: Request, env: Env, ctx: Waiter): Promise<Response> {
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

/** The whole gateway, never throwing. The Pages catch-all in functions/[[route]].ts calls this. */
export async function serve(request: Request, env: Env, ctx: Waiter): Promise<Response> {
  try {
    return await handle(request, env, ctx);
  } catch {
    // Nothing is logged: the request is not ours to keep, even when something breaks.
    return upstreamFailed();
  }
}
