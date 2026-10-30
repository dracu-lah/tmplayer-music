// Cover art from MusicBrainz (which release is it) and the Cover Art Archive (its front image), as
// outside data: every answer may be slow, cut short, huge, the wrong type or not what it says.
// Nothing here throws for that; it becomes a failed Outcome.
//
// Only the artist, album and title go upstream, with our User-Agent and an Accept header. No header,
// address or anything else of the caller's is ever forwarded. Redirects are followed by hand, and
// only to the Cover Art Archive and the Internet Archive, which stores its files.

import { sameArtist } from "./lrclib";
import { type CoverQuery, normalise } from "./query";

export const MUSICBRAINZ = "https://musicbrainz.org";
export const COVER_ART_ARCHIVE = "https://coverartarchive.org";
/** MusicBrainz asks for a name, a version and a way to reach the maintainers on every request. */
export const DEFAULT_COVER_USER_AGENT = "TMPlayerMusic-gateway/1 (https://music.tmplayer.org)";

/** A search answer of 10 records is a few tens of KB; 512 KB is far above a real one. */
export const MAX_SEARCH_BYTES = 512 * 1024;
/** The largest image passed on. A 1200 px JPEG is usually 150 to 500 KB. */
export const MAX_IMAGE_BYTES = 2 * 1024 * 1024;
export const UPSTREAM_TIMEOUT_MS = 4000;
/** The whole lookup, queue wait included, so the app's 6 s timeout sees an answer. */
export const LOOKUP_BUDGET_MS = 5000;
/** Releases tried on the Cover Art Archive per lookup; most hits are the first. */
export const MAX_CANDIDATES = 3;
export const MAX_REDIRECTS = 4;
export const SEARCH_LIMIT = 10;

const IMAGE_TYPES = new Set(["image/jpeg", "image/png"]);

export type CoverFailReason = "timeout" | "network" | "status" | "too_large" | "bad_json" | "bad_type" | "redirect";

export interface Cover {
  bytes: Uint8Array;
  contentType: "image/jpeg" | "image/png";
}

export type CoverOutcome =
  | { kind: "found"; cover: Cover }
  | { kind: "missing" }
  /** [byUpstream]: MusicBrainz or the archive said to wait. Otherwise our own queue was full. */
  | { kind: "limited"; retryAfter: number; byUpstream: boolean }
  | { kind: "failed"; reason: CoverFailReason };

/** Seconds from a Retry-After header, kept between 1 s and 5 minutes; [fallback] when absent or unreadable. */
export function waitOf(header: string | null, fallback: number): number {
  if (header == null) return fallback;
  const trimmed = header.trim();
  if (/^\d{1,6}$/.test(trimmed)) return Math.min(300, Math.max(1, Number(trimmed)));
  const at = Date.parse(trimmed);
  if (Number.isNaN(at)) return fallback;
  return Math.min(300, Math.max(1, Math.ceil((at - Date.now()) / 1000)));
}

/**
 * One call to MusicBrainz at a time per isolate, at least [gapMs] apart (MusicBrainz allows about
 * one a second per client). A caller waits its turn, but never longer than [maxWaitMs]: past that
 * it is told to come back later instead of queueing without bound.
 */
export class Spacer {
  private nextAt = 0;
  private blockedUntil = 0;

  constructor(
    private readonly gapMs: number,
    private readonly maxWaitMs: number,
    private readonly now: () => number = Date.now,
    private readonly sleep: (ms: number) => Promise<void> = (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
  ) {}

  /** Waits for a turn; false when the queue is too long or MusicBrainz asked us to wait. */
  async turn(): Promise<boolean> {
    const now = this.now();
    if (now < this.blockedUntil) return false;
    const at = Math.max(now, this.nextAt);
    if (at - now > this.maxWaitMs) return false;
    this.nextAt = at + this.gapMs;
    if (at > now) await this.sleep(at - now);
    return true;
  }

  /** MusicBrainz said 503 or 429: no call from this isolate for [seconds]. */
  block(seconds: number): void {
    this.blockedUntil = Math.max(this.blockedUntil, this.now() + seconds * 1000);
  }

  /** Whole seconds until a call may go, at least 1. */
  wait(): number {
    const now = this.now();
    const until = Math.max(this.blockedUntil, this.nextAt - this.maxWaitMs);
    return Math.max(1, Math.ceil((until - now) / 1000));
  }
}

/** A phrase for MusicBrainz's search syntax (Lucene): quoted, with quotes and backslashes escaped. */
export function phrase(value: string): string {
  return `"${value.replace(/[\\"]/g, (c) => `\\${c}`)}"`;
}

export function releaseSearchUrl(query: CoverQuery): string {
  const lucene = `release:${phrase(query.album)} AND artist:${phrase(query.artist)}`;
  return `${MUSICBRAINZ}/ws/2/release/?${new URLSearchParams({ query: lucene, fmt: "json", limit: String(SEARCH_LIMIT) })}`;
}

export function recordingSearchUrl(query: CoverQuery): string {
  const lucene = `recording:${phrase(query.title)} AND artist:${phrase(query.artist)}`;
  return `${MUSICBRAINZ}/ws/2/recording/?${new URLSearchParams({ query: lucene, fmt: "json", limit: String(SEARCH_LIMIT) })}`;
}

export function frontUrl(mbid: string, size: number): string {
  return `${COVER_ART_ARCHIVE}/release/${mbid}/front-${size}`;
}

const MBID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

function asObject(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
}

/** The credit as one line ("A feat. B") and each credited name, normalised. */
function creditsOf(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const names: string[] = [];
  let line = "";
  for (const item of value.slice(0, 20)) {
    const credit = asObject(item);
    if (!credit) continue;
    const name = typeof credit.name === "string" ? credit.name : typeof asObject(credit.artist)?.name === "string" ? (asObject(credit.artist)?.name as string) : "";
    const join = typeof credit.joinphrase === "string" ? credit.joinphrase : "";
    if (name !== "") names.push(normalise(name));
    line += name + join;
  }
  if (line !== "") names.unshift(normalise(line));
  return names;
}

function creditMatches(artist: string, credit: unknown): boolean {
  const asked = normalise(artist);
  return creditsOf(credit).some((name) => sameArtist(asked, name));
}

/** The ids of the releases in a release search that are this album by this artist, best first. */
export function pickReleases(body: unknown, query: CoverQuery): string[] {
  const releases = asObject(body)?.releases;
  if (!Array.isArray(releases)) return [];
  const album = normalise(query.album);
  const out: string[] = [];
  for (const item of releases.slice(0, 50)) {
    const r = asObject(item);
    if (!r || typeof r.id !== "string" || !MBID.test(r.id)) continue;
    if (typeof r.title !== "string" || normalise(r.title) !== album) continue;
    if (!creditMatches(query.artist, r["artist-credit"])) continue;
    if (!out.includes(r.id)) out.push(r.id);
    if (out.length >= MAX_CANDIDATES) break;
  }
  return out;
}

/**
 * The releases a recording search found this song by this artist on. Releases with the asked album's
 * title come first, then official ones, then the rest in MusicBrainz's order.
 */
export function pickRecordingReleases(body: unknown, query: CoverQuery): string[] {
  const recordings = asObject(body)?.recordings;
  if (!Array.isArray(recordings)) return [];
  const title = normalise(query.title);
  const album = normalise(query.album);
  const scored: { id: string; score: number; order: number }[] = [];
  let order = 0;
  for (const item of recordings.slice(0, 50)) {
    const rec = asObject(item);
    if (!rec || typeof rec.title !== "string" || normalise(rec.title) !== title) continue;
    if (!creditMatches(query.artist, rec["artist-credit"])) continue;
    if (!Array.isArray(rec.releases)) continue;
    for (const releaseItem of rec.releases.slice(0, 50)) {
      const release = asObject(releaseItem);
      if (!release || typeof release.id !== "string" || !MBID.test(release.id)) continue;
      if (scored.some((s) => s.id === release.id)) continue;
      const sameAlbum = album !== "" && typeof release.title === "string" && normalise(release.title) === album;
      const official = release.status === "Official";
      scored.push({ id: release.id, score: (sameAlbum ? 10 : 0) + (official ? 1 : 0), order: order++ });
    }
  }
  scored.sort((a, b) => b.score - a.score || a.order - b.order);
  return scored.slice(0, MAX_CANDIDATES).map((s) => s.id);
}

/** The bytes of [response]'s body, or why they could not be read whole within [maxBytes] and the [signal]. */
async function boundedBytes(response: Response, maxBytes: number, signal: AbortSignal): Promise<Uint8Array | "too_large" | "timeout" | "network"> {
  const declared = Number(response.headers.get("Content-Length"));
  if (Number.isFinite(declared) && declared > maxBytes) {
    await response.body?.cancel().catch(() => {});
    return "too_large";
  }
  const reader = response.body?.getReader();
  if (!reader) return new Uint8Array(0);
  const chunks: Uint8Array[] = [];
  let total = 0;
  const aborted = new Promise<"timeout">((resolve) => {
    if (signal.aborted) resolve("timeout");
    signal.addEventListener("abort", () => resolve("timeout"), { once: true });
  });
  try {
    for (;;) {
      const next = await Promise.race([reader.read(), aborted]);
      if (next === "timeout") {
        await reader.cancel().catch(() => {});
        return "timeout";
      }
      if (next.done) break;
      total += next.value.byteLength;
      if (total > maxBytes) {
        await reader.cancel().catch(() => {});
        return "too_large";
      }
      chunks.push(next.value);
    }
  } catch {
    return signal.aborted ? "timeout" : "network";
  }
  const all = new Uint8Array(total);
  let at = 0;
  for (const chunk of chunks) {
    all.set(chunk, at);
    at += chunk.byteLength;
  }
  return all;
}

/** Whether [bytes] start like the [type] says: JPEG FF D8 FF, PNG 89 'PNG'. */
export function looksLike(bytes: Uint8Array, type: string): boolean {
  if (type === "image/jpeg") return bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff;
  if (type === "image/png") return bytes.length > 8 && bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47;
  return false;
}

/** Hosts a cover may be fetched from: the archive and the Internet Archive, which serves its files. */
export function allowedHost(url: URL): boolean {
  if (url.protocol !== "https:" || url.username !== "" || url.password !== "" || (url.port !== "" && url.port !== "443")) return false;
  const host = url.hostname.toLowerCase();
  return host === "coverartarchive.org" || host === "archive.org" || host.endsWith(".archive.org");
}

type Fetched<T> =
  | { kind: "ok"; value: T }
  | { kind: "missing" }
  | { kind: "limited"; retryAfter: number; byUpstream: true }
  | { kind: "failed"; reason: CoverFailReason };

/** A clock for the whole lookup: each call gets what is left of the budget, at most its own timeout. */
class Budget {
  private readonly end: number;
  constructor(ms: number) {
    this.end = Date.now() + ms;
  }
  left(cap: number): number {
    return Math.min(cap, this.end - Date.now());
  }
}

async function searchJson(url: string, userAgent: string, budget: Budget): Promise<Fetched<unknown>> {
  const timeout = budget.left(UPSTREAM_TIMEOUT_MS);
  if (timeout <= 0) return { kind: "failed", reason: "timeout" };
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeout);
  try {
    let response: Response;
    try {
      response = await fetch(url, {
        method: "GET",
        redirect: "manual",
        headers: { "User-Agent": userAgent, Accept: "application/json" },
        signal: controller.signal,
      });
    } catch {
      return { kind: "failed", reason: controller.signal.aborted ? "timeout" : "network" };
    }
    // MusicBrainz answers 503 when a client goes over its rate.
    if (response.status === 503 || response.status === 429) {
      await response.body?.cancel().catch(() => {});
      return { kind: "limited", retryAfter: waitOf(response.headers.get("Retry-After"), 5), byUpstream: true };
    }
    if (response.status === 404) {
      await response.body?.cancel().catch(() => {});
      return { kind: "missing" };
    }
    if (response.status !== 200) {
      await response.body?.cancel().catch(() => {});
      return { kind: "failed", reason: "status" };
    }
    const bytes = await boundedBytes(response, MAX_SEARCH_BYTES, controller.signal);
    if (typeof bytes === "string") return { kind: "failed", reason: bytes };
    try {
      return { kind: "ok", value: JSON.parse(new TextDecoder("utf-8").decode(bytes)) as unknown };
    } catch {
      return { kind: "failed", reason: "bad_json" };
    }
  } finally {
    clearTimeout(timer);
  }
}

/** The front image at [start], following at most [MAX_REDIRECTS] redirects, each to an allowed host. */
export async function fetchFront(start: string, userAgent: string, budget: Budget = new Budget(LOOKUP_BUDGET_MS)): Promise<Fetched<Cover>> {
  let url = start;
  for (let hop = 0; hop <= MAX_REDIRECTS; hop++) {
    const timeout = budget.left(UPSTREAM_TIMEOUT_MS);
    if (timeout <= 0) return { kind: "failed", reason: "timeout" };
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeout);
    try {
      let response: Response;
      try {
        response = await fetch(url, {
          method: "GET",
          redirect: "manual",
          headers: { "User-Agent": userAgent, Accept: "image/jpeg, image/png" },
          signal: controller.signal,
        });
      } catch {
        return { kind: "failed", reason: controller.signal.aborted ? "timeout" : "network" };
      }
      const status = response.status;
      if (status >= 300 && status < 400) {
        await response.body?.cancel().catch(() => {});
        const location = response.headers.get("Location");
        if (!location) return { kind: "failed", reason: "redirect" };
        let next: URL;
        try {
          next = new URL(location, url);
        } catch {
          return { kind: "failed", reason: "redirect" };
        }
        if (!allowedHost(next)) return { kind: "failed", reason: "redirect" };
        url = next.href;
        continue;
      }
      if (status === 404) {
        await response.body?.cancel().catch(() => {});
        return { kind: "missing" };
      }
      if (status === 429) {
        await response.body?.cancel().catch(() => {});
        return { kind: "limited", retryAfter: waitOf(response.headers.get("Retry-After"), 60), byUpstream: true };
      }
      if (status !== 200) {
        await response.body?.cancel().catch(() => {});
        return { kind: "failed", reason: "status" };
      }
      const type = (response.headers.get("Content-Type") ?? "").split(";")[0]?.trim().toLowerCase() ?? "";
      if (!IMAGE_TYPES.has(type)) {
        await response.body?.cancel().catch(() => {});
        return { kind: "failed", reason: "bad_type" };
      }
      const bytes = await boundedBytes(response, MAX_IMAGE_BYTES, controller.signal);
      if (typeof bytes === "string") return { kind: "failed", reason: bytes };
      if (!looksLike(bytes, type)) return { kind: "failed", reason: "bad_type" };
      return { kind: "ok", value: { bytes, contentType: type as Cover["contentType"] } };
    } finally {
      clearTimeout(timer);
    }
  }
  return { kind: "failed", reason: "redirect" };
}

export interface CoverOptions {
  userAgent: string;
  /** Waits for a MusicBrainz turn; false stops the lookup as rate limited. */
  turn?: () => Promise<boolean>;
  budgetMs?: number;
}

/** Tries the front image of each release in turn: the first found wins, a miss tries the next. */
async function firstFront(ids: string[], query: CoverQuery, userAgent: string, budget: Budget): Promise<CoverOutcome | null> {
  for (const id of ids) {
    const got = await fetchFront(frontUrl(id, query.size), userAgent, budget);
    if (got.kind === "ok") return { kind: "found", cover: got.value };
    if (got.kind === "missing") continue;
    return got;
  }
  return null;
}

/**
 * Looks [query] up: a release search by artist and album (when there is an album) and, only when
 * that finds no front image, a recording search by title and artist. Then the Cover Art Archive's
 * front image of the matching releases.
 */
export async function lookUpCover(query: CoverQuery, options: CoverOptions): Promise<CoverOutcome> {
  const turn = options.turn ?? (async () => true);
  const budget = new Budget(options.budgetMs ?? LOOKUP_BUDGET_MS);

  if (query.album !== "") {
    if (!(await turn())) return { kind: "limited", retryAfter: 0, byUpstream: false };
    const releases = await searchJson(releaseSearchUrl(query), options.userAgent, budget);
    if (releases.kind === "limited" || releases.kind === "failed") return releases;
    if (releases.kind === "ok") {
      if (asObject(releases.value) === null) return { kind: "failed", reason: "bad_json" };
      const front = await firstFront(pickReleases(releases.value, query), query, options.userAgent, budget);
      if (front) return front;
    }
  }

  if (!(await turn())) return { kind: "limited", retryAfter: 0, byUpstream: false };
  const recordings = await searchJson(recordingSearchUrl(query), options.userAgent, budget);
  if (recordings.kind === "missing") return { kind: "missing" };
  if (recordings.kind !== "ok") return recordings;
  if (asObject(recordings.value) === null) return { kind: "failed", reason: "bad_json" };
  const front = await firstFront(pickRecordingReleases(recordings.value, query), query, options.userAgent, budget);
  return front ?? { kind: "missing" };
}
