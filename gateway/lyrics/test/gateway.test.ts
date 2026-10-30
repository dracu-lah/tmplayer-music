import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { onRequest } from "../functions/[[route]]";
import {
  bucketOf,
  CLIENT_PER_MINUTE,
  ClientCounter,
  type Env,
  MAX_CLIENT_BUCKETS,
  resetClientCounter,
  resetUpstreamBucket,
  serve,
  TokenBucket,
  UPSTREAM_BURST,
} from "../src/index";
import { LRCLIB, MAX_GET_BYTES, pickBest, retryAfterOf } from "../src/lrclib";
import { normalise, parseQuery } from "../src/query";

const BASE = "https://lyrics.example.org";
const SYNCED = "[00:01.00] line one\n[00:03.00] line two";
const PLAIN = "line one\nline two";

/** A record in LRCLIB's shape, with made-up names. */
function record(over: Record<string, unknown> = {}) {
  return {
    id: 1,
    trackName: "Sample Song",
    artistName: "Example Band",
    albumName: "First Album",
    duration: 200,
    instrumental: false,
    plainLyrics: PLAIN,
    syncedLyrics: SYNCED,
    ...over,
  };
}

type Route = (url: URL, init: RequestInit | undefined) => Response | Promise<Response>;

/** Stubs fetch: /api/get and /api/search answer from [routes]; anything else fails the test. */
function upstream(routes: { get?: Route; search?: Route }) {
  const calls: { url: URL; init: RequestInit | undefined }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
    calls.push({ url, init });
    if (url.origin !== LRCLIB) throw new Error(`unexpected fetch ${url}`);
    const route = url.pathname === "/api/get" ? routes.get : url.pathname === "/api/search" ? routes.search : undefined;
    if (!route) throw new Error(`unexpected fetch ${url}`);
    return route(url, init);
  });
  return calls;
}

const jsonResponse = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const notFound = () => jsonResponse({ message: "Failed to find specified track", name: "TrackNotFound", statusCode: 404 }, 404);

let unique = 0;
/** A query string for a song no other test has asked for, so the edge cache never leaks between tests. */
function song(over: Record<string, string> = {}) {
  unique++;
  return new URLSearchParams({ title: `Sample Song ${unique}`, artist: "Example Band", album: "First Album", duration: "200", ...over });
}

/** Calls the Pages entry point the way Pages does, with an EventContext around a test ExecutionContext. */
async function send(request: Request, env: Env = {}) {
  const ctx = createExecutionContext();
  const context = {
    request,
    env,
    params: {},
    data: {},
    functionPath: "/[[route]]",
    waitUntil: (promise: Promise<unknown>) => ctx.waitUntil(promise),
    passThroughOnException: () => {},
    next: async () => new Response("static", { status: 200 }),
  } as unknown as EventContext<Env, string, Record<string, unknown>>;
  const res = await onRequest(context);
  await waitOnExecutionContext(ctx);
  return res;
}

function call(path: string, env: Env = {}, headers: Record<string, string> = {}, method = "GET") {
  return send(new Request(`${BASE}${path}`, { headers, method }), env);
}

const lyricsPath = (params: URLSearchParams) => `/v1/lyrics?${params}`;

beforeEach(() => {
  resetUpstreamBucket();
  resetClientCounter();
});
afterEach(() => vi.restoreAllMocks());

describe("health", () => {
  it("answers ok without reaching LRCLIB", async () => {
    const calls = upstream({});
    const res = await call("/v1/health");
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(calls).toHaveLength(0);
  });

  it("has no CORS headers", async () => {
    const res = await call("/v1/health", {}, { Origin: "https://evil.example" });
    expect(res.headers.get("Access-Control-Allow-Origin")).toBeNull();
  });
});

describe("lookups", () => {
  it("returns synced and plain lyrics from /api/get", async () => {
    const params = song();
    const calls = upstream({ get: () => jsonResponse(record({ trackName: params.get("title") })) });
    const res = await call(lyricsPath(params));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ synced: SYNCED, plain: PLAIN, instrumental: false, source: "LRCLIB" });
    expect(calls).toHaveLength(1);
    const asked = calls[0]!.url.searchParams;
    expect(Object.fromEntries(asked)).toEqual({
      track_name: params.get("title"),
      artist_name: "Example Band",
      album_name: "First Album",
      duration: "200",
    });
  });

  it("sends only its own User-Agent and Accept upstream, nothing of the caller's", async () => {
    const calls = upstream({ get: () => jsonResponse(record()) });
    await call(lyricsPath(song({ chat: "My Secret Chat", chat_id: "-100123" })), { USER_AGENT: "TestAgent/1.0" }, {
      "CF-Connecting-IP": "203.0.113.7",
      "X-Forwarded-For": "203.0.113.7",
      Cookie: "session=abc",
      Authorization: "Bearer secret",
      "User-Agent": "TMPlayerMusic/1.0 Android",
    });
    const headers = new Headers(calls[0]!.init?.headers);
    expect([...headers.keys()].sort()).toEqual(["accept", "user-agent"]);
    expect(headers.get("User-Agent")).toBe("TestAgent/1.0");
    const url = calls[0]!.url.href;
    expect(url).not.toContain("Secret");
    expect(url).not.toContain("100123");
    expect(url).not.toContain("203.0.113.7");
  });

  it("says instrumental with no lyrics", async () => {
    upstream({ get: () => jsonResponse(record({ instrumental: true, plainLyrics: null, syncedLyrics: null })) });
    const res = await call(lyricsPath(song()));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ synced: null, plain: null, instrumental: true, source: "LRCLIB" });
  });

  it("falls back to /api/search on a 404 and picks the closest match", async () => {
    const params = song();
    const title = params.get("title")!;
    const calls = upstream({
      get: notFound,
      search: () =>
        jsonResponse([
          record({ trackName: title, duration: 260, syncedLyrics: "[00:00.00] wrong length" }),
          record({ trackName: `${title} (Live)`, duration: 200, syncedLyrics: "[00:00.00] live" }),
          record({ trackName: title, artistName: "Other Artist", duration: 200, syncedLyrics: "[00:00.00] other" }),
          record({ trackName: title.toUpperCase(), duration: 201, syncedLyrics: null, plainLyrics: "plain only" }),
          record({ trackName: `${title}!`, duration: 199, syncedLyrics: "[00:00.00] right" }),
        ]),
    });
    const res = await call(lyricsPath(params));
    expect(res.status).toBe(200);
    expect(((await res.json()) as { synced: string }).synced).toBe("[00:00.00] right");
    expect(calls.map((c) => c.url.pathname)).toEqual(["/api/get", "/api/search"]);
    expect(calls[1]!.url.searchParams.has("album_name")).toBe(false);
  });

  it("is 404 when the search has nothing close enough", async () => {
    const params = song();
    upstream({ get: notFound, search: () => jsonResponse([record({ trackName: params.get("title"), duration: 230 })]) });
    const res = await call(lyricsPath(params));
    expect(res.status).toBe(404);
    expect(((await res.json()) as { error: string }).error).toBe("not_found");
  });

  it("does not search when /api/get answers something other than 404", async () => {
    const calls = upstream({ get: () => new Response("oops", { status: 500 }) });
    const res = await call(lyricsPath(song()));
    expect(res.status).toBe(502);
    expect(calls).toHaveLength(1);
  });

  it("goes straight to search for a track longer than LRCLIB's one hour cap", async () => {
    const params = song({ duration: "5400" });
    const calls = upstream({ search: () => jsonResponse([record({ trackName: params.get("title"), duration: 5401 })]) });
    const res = await call(lyricsPath(params));
    expect(res.status).toBe(200);
    expect(calls.map((c) => c.url.pathname)).toEqual(["/api/search"]);
  });

  it("leaves out album_name when no album is given", async () => {
    const calls = upstream({ get: () => jsonResponse(record()) });
    const params = song();
    params.delete("album");
    await call(lyricsPath(params));
    expect(calls[0]!.url.searchParams.has("album_name")).toBe(false);
  });
});

describe("bad input", () => {
  const cases: [string, Record<string, string | null>][] = [
    ["no title", { title: null }],
    ["blank title", { title: "   " }],
    ["no artist", { artist: null }],
    ["title too long", { title: "a".repeat(201) }],
    ["album too long", { album: "a".repeat(201) }],
    ["control character", { title: "Song\u0000" }],
    ["newline", { artist: "Band\nInjected" }],
    ["no duration", { duration: null }],
    ["zero duration", { duration: "0" }],
    ["too long", { duration: "86401" }],
    ["fraction", { duration: "200.5" }],
    ["negative", { duration: "-5" }],
    ["text", { duration: "abc" }],
    ["huge number", { duration: "99999999999" }],
  ];
  for (const [name, change] of cases) {
    it(`refuses ${name} with 400 and no upstream call`, async () => {
      const calls = upstream({});
      const params = song();
      for (const [k, v] of Object.entries(change)) v == null ? params.delete(k) : params.set(k, v);
      const res = await call(lyricsPath(params));
      expect(res.status).toBe(400);
      expect(((await res.json()) as { error: string }).error).toBe("bad_request");
      expect(calls).toHaveLength(0);
    });
  }

  it("refuses a value given twice", async () => {
    const params = song();
    params.append("title", "Second");
    expect((await call(lyricsPath(params))).status).toBe(400);
  });

  it("ignores unknown parameters", async () => {
    upstream({ get: () => jsonResponse(record()) });
    expect((await call(lyricsPath(song({ foo: "bar", x: "\u0000" })))).status).toBe(200);
  });

  it("serves only GET, and only its two paths", async () => {
    const post = await call(lyricsPath(song()), {}, {}, "POST");
    expect(post.status).toBe(405);
    expect(post.headers.get("Allow")).toBe("GET");
    expect((await call("/v1/health", {}, {}, "POST")).status).toBe(405);
    const options = await call("/v1/lyrics", {}, {}, "OPTIONS");
    expect(options.status).toBe(405);
    expect(options.headers.get("Access-Control-Allow-Origin")).toBeNull();
    expect((await call("/api/get")).status).toBe(404);
  });

  it("answers every other path, the root and the static files included, with a JSON 404", async () => {
    for (const path of ["/", "/index.txt", "/_routes.json", "/v1", "/v1/lyrics/extra"]) {
      const res = await call(path);
      expect(res.status).toBe(404);
      expect(((await res.json()) as { error: string }).error).toBe("no_route");
    }
  });

  it("never throws out of the entry point, even when the platform does", async () => {
    upstream({ get: () => jsonResponse(record()) });
    const res = await serve(new Request(`${BASE}${lyricsPath(song())}`), {}, {
      waitUntil: () => {
        throw new Error("platform failure");
      },
    });
    expect(res.status).toBe(502);
  });
});

describe("upstream failures", () => {
  it("gives up after the timeout with 502", async () => {
    upstream({
      get: (_url, init) =>
        new Promise<Response>((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
        }),
    });
    const started = Date.now();
    const res = await call(lyricsPath(song()));
    expect(res.status).toBe(502);
    expect(Date.now() - started).toBeLessThan(8000);
  }, 10_000);

  it("gives up on a body that stalls after the headers", async () => {
    upstream({
      get: () => new Response(new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode('{"plain')); } }), { status: 200 }),
    });
    expect((await call(lyricsPath(song()))).status).toBe(502);
  }, 10_000);

  it("refuses an oversized body by its Content-Length", async () => {
    upstream({ get: () => new Response("{}", { headers: { "Content-Length": String(MAX_GET_BYTES + 1) } }) });
    expect((await call(lyricsPath(song()))).status).toBe(502);
  });

  it("refuses an oversized body that does not say its length", async () => {
    const chunk = new TextEncoder().encode("x".repeat(64 * 1024));
    let sent = 0;
    upstream({
      get: () =>
        new Response(
          new ReadableStream({
            pull(c) {
              if (sent++ > 20) c.close();
              else c.enqueue(chunk);
            },
          }),
        ),
    });
    expect((await call(lyricsPath(song()))).status).toBe(502);
  });

  it("refuses garbage instead of JSON", async () => {
    upstream({ get: () => new Response("<html>not json</html>", { status: 200 }) });
    expect((await call(lyricsPath(song()))).status).toBe(502);
  });

  it("refuses JSON of the wrong shape", async () => {
    upstream({ get: () => jsonResponse([1, 2, 3]) });
    expect((await call(lyricsPath(song()))).status).toBe(502);
  });

  it("treats a record with wrong-typed lyrics as no lyrics", async () => {
    const calls = upstream({ get: () => jsonResponse(record({ plainLyrics: 42, syncedLyrics: { a: 1 } })) });
    expect((await call(lyricsPath(song()))).status).toBe(404);
    // A 200 without usable lyrics is not a 404: no search.
    expect(calls).toHaveLength(1);
  });

  it("refuses a search answer that is not a list", async () => {
    upstream({ get: notFound, search: () => jsonResponse({ not: "a list" }) });
    expect((await call(lyricsPath(song()))).status).toBe(502);
  });

  it("drops lyrics longer than the cap", async () => {
    upstream({ get: () => jsonResponse(record({ syncedLyrics: "x".repeat(100_001) })) });
    const res = await call(lyricsPath(song()));
    expect(await res.json()).toMatchObject({ synced: null, plain: PLAIN });
  });

  it("passes on LRCLIB's 429 with a bounded Retry-After, and waits it out", async () => {
    const calls = upstream({ get: () => new Response("slow down", { status: 429, headers: { "Retry-After": "30" } }) });
    const res = await call(lyricsPath(song()));
    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("30");
    // The next lookup does not reach LRCLIB while it asked us to wait.
    const again = await call(lyricsPath(song()));
    expect(again.status).toBe(429);
    expect(calls).toHaveLength(1);
  });

  it("does not cache failures", async () => {
    const params = song();
    upstream({ get: () => new Response("boom", { status: 503 }) });
    expect((await call(lyricsPath(params))).status).toBe(502);
    vi.restoreAllMocks();
    upstream({ get: () => jsonResponse(record()) });
    expect((await call(lyricsPath(params))).status).toBe(200);
  });
});

describe("edge cache", () => {
  it("answers the same song again from the cache, whatever its case and spacing", async () => {
    const params = song();
    const calls = upstream({ get: () => jsonResponse(record()) });
    expect((await call(lyricsPath(params))).status).toBe(200);
    const shouted = new URLSearchParams(params);
    shouted.set("title", `  ${params.get("title")!.toUpperCase()}  `);
    const hit = await call(lyricsPath(shouted));
    expect(hit.status).toBe(200);
    expect(await hit.json()).toEqual({ synced: SYNCED, plain: PLAIN, instrumental: false, source: "LRCLIB" });
    expect(calls).toHaveLength(1);
  });

  it("remembers a miss too", async () => {
    const params = song();
    const calls = upstream({ get: notFound, search: () => jsonResponse([]) });
    expect((await call(lyricsPath(params))).status).toBe(404);
    expect((await call(lyricsPath(params))).status).toBe(404);
    expect(calls).toHaveLength(2);
  });

  it("keeps a different duration apart", async () => {
    const params = song();
    const calls = upstream({ get: () => jsonResponse(record()) });
    await call(lyricsPath(params));
    params.set("duration", "300");
    await call(lyricsPath(params));
    expect(calls).toHaveLength(2);
  });
});

describe("rate limits", () => {
  const client = { "CF-Connecting-IP": "203.0.113.7" };

  it("answers 429 when the client's bucket is full, before the cache or any upstream call", async () => {
    let now = 0;
    resetClientCounter(new ClientCounter(2, 60_000, 100, () => now));
    const calls = upstream({ get: () => jsonResponse(record()) });
    const params = song();
    expect((await call(lyricsPath(params), {}, client)).status).toBe(200);
    expect((await call(lyricsPath(params), {}, client)).status).toBe(200);
    now += 15_000;
    const third = await call(lyricsPath(params), {}, client);
    expect(third.status).toBe(429);
    expect(third.headers.get("Retry-After")).toBe("45");
    expect(((await third.json()) as { retryAfter: number }).retryAfter).toBe(45);
    expect(calls).toHaveLength(1);
    // Another address is its own bucket (unless it hashes to the same 16 bits, which these do not).
    expect(await bucketOf("198.51.100.1")).not.toBe(await bucketOf("203.0.113.7"));
    expect((await call(lyricsPath(params), {}, { "CF-Connecting-IP": "198.51.100.1" })).status).toBe(200);
    // A new window starts the count again.
    now += 45_000;
    expect((await call(lyricsPath(params), {}, client)).status).toBe(200);
  });

  it("allows the configured lookups a minute by default", async () => {
    upstream({ get: () => jsonResponse(record()) });
    const params = song();
    for (let i = 0; i < CLIENT_PER_MINUTE; i++) expect((await call(lyricsPath(params), {}, client)).status).toBe(200);
    expect((await call(lyricsPath(params), {}, client)).status).toBe(429);
  });

  it("does not count bad requests against the client", async () => {
    resetClientCounter(new ClientCounter(1, 60_000, 100));
    upstream({ get: () => jsonResponse(record()) });
    for (let i = 0; i < 5; i++) expect((await call(lyricsPath(song({ duration: "x" })), {}, client)).status).toBe(400);
    expect((await call(lyricsPath(song()), {}, client)).status).toBe(200);
  });

  it("holds a bounded table that is dropped each window, and only coarse hashes in it", async () => {
    let now = 0;
    const counter = new ClientCounter(1, 60_000, 3, () => now);
    for (const bucket of ["b-1", "b-2", "b-3", "b-4", "b-5"]) expect(counter.take(bucket)).toBe(0);
    expect(counter.size()).toBe(3);
    expect(counter.take("b-1")).toBeGreaterThan(0);
    // Past the cap a new bucket is not counted (fail open; the upstream bucket still guards LRCLIB).
    expect(counter.take("b-5")).toBe(0);
    now += 60_000;
    expect(counter.take("b-1")).toBe(0);
    expect(counter.size()).toBe(1);
    // A clock that goes backwards starts a new window instead of locking anyone out.
    now -= 120_000;
    expect(counter.take("b-1")).toBe(0);
    expect(MAX_CLIENT_BUCKETS).toBeLessThanOrEqual(65_536);
  });

  it("buckets are coarse: 16 bits of a hash", async () => {
    expect(await bucketOf("198.51.100.1")).toMatch(/^b-[0-9a-f]{1,4}$/);
    expect(await bucketOf("203.0.113.7")).not.toContain("203");
    expect(await bucketOf(null)).toBe("b-none");
  });

  it("caps upstream calls per isolate", async () => {
    let now = 0;
    resetUpstreamBucket(new TokenBucket(2, 1, () => now));
    const calls = upstream({ get: () => jsonResponse(record()) });
    expect((await call(lyricsPath(song()))).status).toBe(200);
    expect((await call(lyricsPath(song()))).status).toBe(200);
    const third = await call(lyricsPath(song()));
    expect(third.status).toBe(429);
    expect(third.headers.get("Retry-After")).toBe("1");
    expect(calls).toHaveLength(2);
    now += 1000;
    expect((await call(lyricsPath(song()))).status).toBe(200);
  });

  it("allows only a small burst towards LRCLIB by default", async () => {
    const calls = upstream({ get: () => jsonResponse(record()) });
    const statuses: number[] = [];
    // Different callers, so only the shared upstream bucket can stop them.
    for (let i = 0; i < UPSTREAM_BURST + 3; i++) {
      statuses.push((await call(lyricsPath(song()), {}, { "CF-Connecting-IP": `192.0.2.${i}` })).status);
    }
    expect(statuses.filter((s) => s === 200).length).toBeLessThanOrEqual(UPSTREAM_BURST + 1);
    expect(statuses).toContain(429);
    expect(calls.length).toBeLessThanOrEqual(UPSTREAM_BURST + 1);
  });

  it("a 429 from LRCLIB empties the burst, so calls resume slowly after the cooldown", async () => {
    let now = 0;
    resetUpstreamBucket(new TokenBucket(5, 1, () => now));
    let answer: () => Response = () => new Response("slow down", { status: 429, headers: { "Retry-After": "10" } });
    const calls = upstream({ get: () => answer() });
    expect((await call(lyricsPath(song()))).status).toBe(429);
    answer = () => jsonResponse(record());
    now += 9_000;
    expect((await call(lyricsPath(song()))).status).toBe(429);
    now += 2_000;
    expect((await call(lyricsPath(song()))).status).toBe(200);
    expect((await call(lyricsPath(song()))).status).toBe(429);
    expect(calls).toHaveLength(2);
  });

  it("asks LRCLIB once for the same song asked twice at the same time", async () => {
    let release: () => void = () => {};
    const gate = new Promise<void>((resolve) => (release = resolve));
    const calls = upstream({ get: async () => (await gate, jsonResponse(record())) });
    const params = song();
    const both = Promise.all([call(lyricsPath(params)), call(lyricsPath(params))]);
    await new Promise((resolve) => setTimeout(resolve, 50));
    release();
    const [a, b] = await both;
    expect([a.status, b.status]).toEqual([200, 200]);
    expect(calls).toHaveLength(1);
  });
});

describe("privacy", () => {
  it("logs nothing about any request, good or bad", async () => {
    const spies = (["log", "info", "warn", "error", "debug", "trace"] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => {}));
    const headers = { "CF-Connecting-IP": "203.0.113.7", "User-Agent": "TMPlayerMusic/1.0" };
    upstream({ get: () => jsonResponse(record()) });
    await call(lyricsPath(song({ chat: "Private Chat" })), {}, headers);
    vi.mocked(fetch).mockImplementation(async () => new Response("garbage", { status: 200 }));
    await call(lyricsPath(song()), {}, headers);
    vi.mocked(fetch).mockImplementation(async () => { throw new Error("network down"); });
    await call(lyricsPath(song()), {}, headers);
    await call(lyricsPath(song({ duration: "nope" })), {}, headers);
    await call("/v1/health", {}, headers);
    for (const spy of spies) expect(spy).not.toHaveBeenCalled();
  });
});

describe("helpers", () => {
  it("normalises case, accents, punctuation and spacing, but keeps other scripts", () => {
    expect(normalise("  Café  Déjà-Vu! ")).toBe("cafe deja vu");
    expect(normalise("മലയാളം")).toBe(normalise("മലയാളം"));
    expect(normalise("മലയാളം")).not.toBe("");
    expect(normalise("नमस्ते")).not.toBe(normalise("नमसत"));
  });

  it("reads Retry-After within bounds", () => {
    expect(retryAfterOf(null)).toBe(60);
    expect(retryAfterOf("5")).toBe(5);
    expect(retryAfterOf("0")).toBe(1);
    expect(retryAfterOf("999999")).toBe(300);
    expect(retryAfterOf("soon")).toBe(60);
  });

  it("picks nothing from garbage search results", () => {
    const q = { title: "Sample Song", artist: "Example Band", album: "", duration: 200 };
    for (const garbage of [null, 1, "x", {}, [null, 1, "x", [], {}], [{ trackName: 5 }], [record({ duration: "200" })], [record({ duration: Number.NaN })]]) {
      expect(pickBest(garbage, q)).toBeNull();
    }
    expect(pickBest([record({ artistName: "Example Band feat. Someone" })], q)).not.toBeNull();
  });

  it("parseQuery tidies spaces", () => {
    expect(parseQuery(new URLSearchParams({ title: "  A   B ", artist: "C", duration: "10" }))).toEqual({ title: "A B", artist: "C", album: "", duration: 10 });
  });
});
