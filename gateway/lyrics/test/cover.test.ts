import { createExecutionContext, waitOnExecutionContext } from "cloudflare:test";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { onRequest } from "../functions/[[route]]";
import { allowedHost, COVER_ART_ARCHIVE, looksLike, MAX_IMAGE_BYTES, MUSICBRAINZ, phrase, pickRecordingReleases, pickReleases, Spacer } from "../src/cover";
import { COVER_CLIENT_PER_MINUTE, type Env, resetCoverClientCounter, resetMusicBrainzSpacer } from "../src/index";
import { parseCoverQuery } from "../src/query";

const BASE = "https://meta.example.org";
const MBID_A = "11111111-1111-4111-8111-111111111111";
const MBID_B = "22222222-2222-4222-8222-222222222222";
const MBID_C = "33333333-3333-4333-8333-333333333333";
const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10, 0x4a, 0x46, 0x49, 0x46, 0xff, 0xd9]);
const PNG = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0]);

type Route = (url: URL, init: RequestInit | undefined) => Response | Promise<Response>;

/** Stubs fetch: MusicBrainz searches and the archive answer from [routes]; anything else fails the test. */
function upstream(routes: { release?: Route; recording?: Route; front?: Route; other?: Route }) {
  const calls: { url: URL; init: RequestInit | undefined }[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
    calls.push({ url, init });
    let route: Route | undefined;
    if (url.origin === MUSICBRAINZ && url.pathname === "/ws/2/release/") route = routes.release;
    else if (url.origin === MUSICBRAINZ && url.pathname === "/ws/2/recording/") route = routes.recording;
    else if (url.origin === COVER_ART_ARCHIVE && url.pathname.startsWith("/release/")) route = routes.front;
    else route = routes.other;
    if (!route) throw new Error(`unexpected fetch ${url}`);
    return route(url, init);
  });
  return calls;
}

const jsonResponse = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const image = (bytes: Uint8Array = JPEG, type = "image/jpeg", extra: Record<string, string> = {}) =>
  new Response(bytes, { status: 200, headers: { "Content-Type": type, ...extra } });
const redirect = (location: string, status = 307) => new Response(null, { status, headers: { Location: location } });

function release(id: string, title: string, artist = "Example Band", status = "Official") {
  return { id, title, status, score: 100, "artist-credit": [{ name: artist, artist: { id: "x", name: artist } }] };
}

function recording(title: string, releases: { id: string; title: string; status?: string }[], artist = "Example Band") {
  return { id: "rec", title, "artist-credit": [{ name: artist }], releases };
}

let unique = 0;
/** A query for an album no other test asked for, so the edge cache never leaks between tests. */
function album(over: Record<string, string> = {}) {
  unique++;
  return new URLSearchParams({ title: "Sample Song", artist: "Example Band", album: `First Album ${unique}`, ...over });
}

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

const call = (params: URLSearchParams | string, env: Env = {}, headers: Record<string, string> = {}) =>
  send(new Request(`${BASE}/v1/cover?${params}`, { headers }), env);

beforeEach(() => {
  resetCoverClientCounter();
  // No real waiting in tests: the queue has its own clock, which its sleep moves on at once.
  let clock = 0;
  resetMusicBrainzSpacer(new Spacer(1100, 2500, () => clock, async (ms) => {
    clock += ms;
  }));
});
afterEach(() => vi.restoreAllMocks());

describe("cover lookups", () => {
  it("finds the album's release and passes on the front image", async () => {
    const params = album();
    const calls = upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      front: () => image(),
    });
    const res = await call(params);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("image/jpeg");
    expect(res.headers.get("Cache-Control")).toMatch(/^public, max-age=\d+$/);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(JPEG);
    expect(calls.map((c) => c.url.pathname)).toEqual(["/ws/2/release/", `/release/${MBID_A}/front-500`]);
    const query = calls[0]!.url.searchParams.get("query");
    expect(query).toBe(`release:"${params.get("album")}" AND artist:"Example Band"`);
  });

  it("sends only artist, album and title upstream with its own User-Agent", async () => {
    const params = album({ chat: "Private Chat", file: "secret.mp3" });
    const calls = upstream({ release: () => jsonResponse({ releases: [] }), recording: () => jsonResponse({ recordings: [] }) });
    await call(params, {}, { "CF-Connecting-IP": "203.0.113.7", Cookie: "a=b", "User-Agent": "TMPlayerMusic/1.0" });
    for (const c of calls) {
      const headers = new Headers(c.init?.headers);
      expect([...headers.keys()].sort()).toEqual(["accept", "user-agent"]);
      expect(headers.get("User-Agent")).toBe("TMPlayerMusic-gateway/1 (https://music.tmplayer.org)");
      expect(c.url.href).not.toContain("Private");
      expect(c.url.href).not.toContain("secret");
      expect(c.url.href).not.toContain("203.0.113.7");
      expect(c.init?.redirect).toBe("manual");
    }
  });

  it("follows the archive's redirect to archive.org", async () => {
    const params = album();
    let hops = 0;
    upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      front: () => redirect(`https://archive.org/download/mbid-${MBID_A}/front.jpg`),
      other: (url) => {
        hops++;
        if (url.hostname === "archive.org") return redirect(`https://ia800.us.archive.org/1/items/mbid-${MBID_A}/front.jpg`, 302);
        if (url.hostname === "ia800.us.archive.org") return image(PNG, "image/png");
        throw new Error(`unexpected ${url}`);
      },
    });
    const res = await call(params);
    expect(res.status).toBe(200);
    expect(res.headers.get("Content-Type")).toBe("image/png");
    expect(hops).toBe(2);
  });

  it("never follows a redirect to another host", async () => {
    const params = album();
    upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      front: () => redirect("https://evil.example/front.jpg"),
    });
    const res = await call(params);
    expect(res.status).toBe(502);
    expect(((await res.json()) as { error: string }).error).toBe("upstream");
  });

  it("falls back to the recording search when the release has no front image", async () => {
    const params = album();
    const calls = upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      recording: () => jsonResponse({ recordings: [recording("Sample Song", [{ id: MBID_B, title: "Other", status: "Official" }, { id: MBID_C, title: params.get("album")! }])] }),
      front: (url) => (url.pathname.includes(MBID_A) ? new Response("not found", { status: 404 }) : image()),
    });
    const res = await call(params);
    expect(res.status).toBe(200);
    // The release with the asked album's title is tried first.
    expect(calls.map((c) => c.url.pathname)).toEqual(["/ws/2/release/", `/release/${MBID_A}/front-500`, "/ws/2/recording/", `/release/${MBID_C}/front-500`]);
  });

  it("goes straight to the recording search when there is no album", async () => {
    unique++;
    const params = new URLSearchParams({ title: `Lonely Song ${unique}`, artist: "Example Band", size: "250" });
    const calls = upstream({
      recording: () => jsonResponse({ recordings: [recording(params.get("title")!, [{ id: MBID_B, title: "Single" }])] }),
      front: () => image(),
    });
    expect((await call(params)).status).toBe(200);
    expect(calls.map((c) => c.url.pathname)).toEqual(["/ws/2/recording/", `/release/${MBID_B}/front-250`]);
  });

  it("is 404 not_found when nothing matches, and caches that", async () => {
    const params = album();
    const calls = upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, "A Different Album")] }),
      recording: () => jsonResponse({ recordings: [recording("Sample Song", [{ id: MBID_B, title: "x" }], "Someone Else")] }),
    });
    const res = await call(params);
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not_found", message: "No cover found for this track." });
    const again = await call(params);
    expect(again.status).toBe(404);
    expect(calls).toHaveLength(2);
  });

  it("is 404 when no release has a front image", async () => {
    const params = album();
    upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      recording: () => jsonResponse({ recordings: [] }),
      front: () => new Response("", { status: 404 }),
    });
    expect((await call(params)).status).toBe(404);
  });

  it("answers the same album again from the cache, whatever the title, case and spacing", async () => {
    const params = album();
    const calls = upstream({ release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }), front: () => image() });
    expect((await call(params)).status).toBe(200);
    const other = new URLSearchParams({ title: "Another Track", artist: "  example   BAND ", album: params.get("album")!.toUpperCase() });
    const res = await call(other);
    expect(res.status).toBe(200);
    expect(new Uint8Array(await res.arrayBuffer())).toEqual(JPEG);
    expect(calls).toHaveLength(2);
  });

  it("keeps sizes apart in the cache", async () => {
    const params = album();
    const calls = upstream({ release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }), front: () => image() });
    await call(params);
    params.set("size", "1200");
    await call(params);
    expect(calls.filter((c) => c.url.pathname.endsWith("front-1200"))).toHaveLength(1);
  });
});

describe("cover upstream failures", () => {
  it("is 502 on a MusicBrainz 5xx, and does not cache it", async () => {
    const params = album();
    const calls = upstream({ release: () => new Response("oops", { status: 500 }) });
    expect((await call(params)).status).toBe(502);
    expect((await call(params)).status).toBe(502);
    expect(calls).toHaveLength(2);
  });

  it("is 502 on an archive 5xx", async () => {
    const params = album();
    upstream({ release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }), front: () => new Response("", { status: 503 }) });
    expect((await call(params)).status).toBe(502);
  });

  it("passes on MusicBrainz's 503 as 429 with Retry-After, and holds the queue", async () => {
    const calls = upstream({ release: () => new Response("slow down", { status: 503, headers: { "Retry-After": "7" } }) });
    const res = await call(album());
    expect(res.status).toBe(429);
    expect(Number(res.headers.get("Retry-After"))).toBeGreaterThanOrEqual(6);
    expect(((await res.json()) as { error: string }).error).toBe("rate_limited");
    const next = await call(album());
    expect(next.status).toBe(429);
    expect(calls).toHaveLength(1);
  });

  it("passes on the archive's 429", async () => {
    const params = album();
    upstream({ release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }), front: () => new Response("", { status: 429, headers: { "Retry-After": "30" } }) });
    const res = await call(params);
    expect(res.status).toBe(429);
    expect(res.headers.get("Retry-After")).toBe("30");
  });

  it("refuses an image over 2 MB by its Content-Length", async () => {
    const params = album();
    upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      front: () => image(JPEG, "image/jpeg", { "Content-Length": String(MAX_IMAGE_BYTES + 1) }),
    });
    expect((await call(params)).status).toBe(502);
  });

  it("refuses an image over 2 MB that does not say its length", async () => {
    const params = album();
    const big = new Uint8Array(MAX_IMAGE_BYTES + 10);
    big.set(JPEG.subarray(0, 3));
    upstream({
      release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }),
      front: () => {
        const stream = new ReadableStream({
          start(controller) {
            for (let at = 0; at < big.length; at += 256 * 1024) controller.enqueue(big.subarray(at, at + 256 * 1024));
            controller.close();
          },
        });
        return new Response(stream, { status: 200, headers: { "Content-Type": "image/jpeg" } });
      },
    });
    expect((await call(params)).status).toBe(502);
  });

  it("refuses a wrong content type, and bytes that are not what the type says", async () => {
    for (const answer of [() => image(JPEG, "text/html"), () => image(JPEG, "image/webp"), () => image(new TextEncoder().encode("<html>"), "image/jpeg")]) {
      const params = album();
      upstream({ release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }), front: answer });
      expect((await call(params)).status).toBe(502);
      vi.restoreAllMocks();
    }
  });

  it("refuses garbage from MusicBrainz", async () => {
    for (const body of ["not json", "[1,2,3]", '"text"']) {
      upstream({ release: () => new Response(body, { status: 200 }) });
      expect((await call(album())).status).toBe(502);
      vi.restoreAllMocks();
    }
  });

  it("skips wrong-typed records instead of failing", async () => {
    const params = album();
    upstream({
      release: () => jsonResponse({ releases: [null, 5, { id: 7, title: [] }, { id: "not-an-mbid", title: params.get("album") }, release(MBID_A, params.get("album")!)] }),
      front: () => image(),
    });
    expect((await call(params)).status).toBe(200);
  });

  it("gives up with 502 when MusicBrainz does not answer", async () => {
    upstream({ release: () => { throw new Error("network down"); } });
    expect((await call(album())).status).toBe(502);
  });
});

describe("cover input", () => {
  const cases: [string, string][] = [
    ["no title", "artist=A&album=B"],
    ["no artist", "title=T&album=B"],
    ["a blank title", "title=%20%20&artist=A"],
    ["a control character", "title=T%00&artist=A"],
    ["a line separator", "title=T&artist=A%E2%80%A8"],
    ["a long title", `title=${"x".repeat(201)}&artist=A`],
    ["a long album", `title=T&artist=A&album=${"x".repeat(201)}`],
    ["a title given twice", "title=T&title=U&artist=A"],
    ["an odd size", "title=T&artist=A&size=640"],
    ["a size that is not a number", "title=T&artist=A&size=big"],
    ["a size given twice", "title=T&artist=A&size=250&size=500"],
  ];
  for (const [name, query] of cases) {
    it(`refuses ${name} with 400 and no upstream call`, async () => {
      const calls = upstream({});
      const res = await call(query);
      expect(res.status).toBe(400);
      expect(((await res.json()) as { error: string }).error).toBe("bad_request");
      expect(calls).toHaveLength(0);
    });
  }

  it("defaults the size to 500 and ignores unknown parameters", () => {
    expect(parseCoverQuery(new URLSearchParams("title=T&artist=A&x=1"))).toEqual({ title: "T", artist: "A", album: "", size: 500 });
  });

  it("serves only GET", async () => {
    const res = await send(new Request(`${BASE}/v1/cover?title=T&artist=A`, { method: "POST" }));
    expect(res.status).toBe(405);
  });

  it("limits each client bucket per minute, without counting bad requests", async () => {
    resetCoverClientCounter();
    upstream({ release: () => jsonResponse({ releases: [] }), recording: () => jsonResponse({ recordings: [] }) });
    const headers = { "CF-Connecting-IP": "198.51.100.1" };
    for (let i = 0; i < 5; i++) await call("title=T", {}, headers);
    for (let i = 0; i < COVER_CLIENT_PER_MINUTE; i++) expect((await call(album(), {}, headers)).status).toBe(404);
    const over = await call(album(), {}, headers);
    expect(over.status).toBe(429);
    expect(Number(over.headers.get("Retry-After"))).toBeGreaterThan(0);
  });

  it("logs nothing", async () => {
    const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) => vi.spyOn(console, m).mockImplementation(() => {}));
    const params = album();
    upstream({ release: () => jsonResponse({ releases: [release(MBID_A, params.get("album")!)] }), front: () => image() });
    await call(params);
    await call("title=%00");
    for (const spy of spies) expect(spy).not.toHaveBeenCalled();
  });
});

describe("cover helpers", () => {
  it("quotes search phrases safely", () => {
    expect(phrase('A "B" \\ C')).toBe('"A \\"B\\" \\\\ C"');
  });

  it("allows only the archive hosts over https", () => {
    expect(allowedHost(new URL("https://coverartarchive.org/x"))).toBe(true);
    expect(allowedHost(new URL("https://ia600.us.archive.org/x"))).toBe(true);
    expect(allowedHost(new URL("http://archive.org/x"))).toBe(false);
    expect(allowedHost(new URL("https://archive.org.evil.example/x"))).toBe(false);
    expect(allowedHost(new URL("https://user:pw@archive.org/x"))).toBe(false);
    expect(allowedHost(new URL("https://archive.org:8443/x"))).toBe(false);
  });

  it("checks image bytes against their type", () => {
    expect(looksLike(JPEG, "image/jpeg")).toBe(true);
    expect(looksLike(PNG, "image/png")).toBe(true);
    expect(looksLike(PNG, "image/jpeg")).toBe(false);
    expect(looksLike(new Uint8Array(0), "image/png")).toBe(false);
  });

  it("picks nothing from garbage", () => {
    const query = { title: "T", artist: "A", album: "B", size: 500 as const };
    for (const body of [null, 1, "x", [], {}, { releases: "x" }, { releases: [{}] }, { recordings: [{ title: "T" }] }]) {
      expect(pickReleases(body, query)).toEqual([]);
      expect(pickRecordingReleases(body, query)).toEqual([]);
    }
  });

  it("matches an artist inside a longer credit, but not a different one", () => {
    const query = { title: "T", artist: "Example Band", album: "B", size: 500 as const };
    const body = { releases: [{ id: MBID_A, title: "B", "artist-credit": [{ name: "Example Band", joinphrase: " feat. " }, { name: "Guest" }] }, release(MBID_B, "B", "Other Band")] };
    expect(pickReleases(body, query)).toEqual([MBID_A]);
  });

  it("the queue refuses a wait longer than its limit", async () => {
    let now = 0;
    const spacer = new Spacer(1000, 2500, () => now, async () => {});
    // Turns at 0, 1 s and 2 s fit in the 2.5 s wait; the one at 3 s does not.
    expect(await spacer.turn()).toBe(true);
    expect(await spacer.turn()).toBe(true);
    expect(await spacer.turn()).toBe(true);
    expect(await spacer.turn()).toBe(false);
    now = 10_000;
    expect(await spacer.turn()).toBe(true);
    spacer.block(5);
    expect(await spacer.turn()).toBe(false);
    expect(spacer.wait()).toBeGreaterThanOrEqual(5);
  });
});
