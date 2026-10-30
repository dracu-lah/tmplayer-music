// LRCLIB (https://lrclib.net/docs) as outside data: every answer may be slow, cut short, huge,
// the wrong type or not JSON at all. Nothing here throws for that; it becomes a failed Outcome.
//
// Only the four lookup values go upstream, with our User-Agent and an Accept header. No header,
// address or anything else of the caller's is ever forwarded.

import { type LyricsQuery, normalise } from "./query";

export const LRCLIB = "https://lrclib.net";
/** LRCLIB's /api/get refuses a duration above this (its docs: "must be between 1 and 3600"). */
export const LRCLIB_MAX_DURATION = 3600;
/** LRCLIB matches only within this many seconds of the record, and so do we in a search. */
export const DURATION_TOLERANCE = 2;
export const UPSTREAM_TIMEOUT_MS = 5000;
/** One record carries plain, synced and a Lyricsfile YAML copy; 512 KB is far above a real one. */
export const MAX_GET_BYTES = 512 * 1024;
/** A search answers up to 20 records. */
export const MAX_SEARCH_BYTES = 2 * 1024 * 1024;
/** Longer lyrics than this are treated as garbage and dropped. */
export const MAX_LYRICS_CHARS = 100_000;
export const DEFAULT_USER_AGENT = "TMPlayerMusic-gateway/1.0 (https://tmplayer.org)";

export interface Lyrics {
  synced: string | null;
  plain: string | null;
  instrumental: boolean;
}

export type FailReason = "timeout" | "network" | "status" | "too_large" | "bad_json";

export type Outcome =
  | { kind: "found"; lyrics: Lyrics }
  | { kind: "missing" }
  /** [byUpstream]: LRCLIB said 429. Otherwise our own upstream budget ran out. */
  | { kind: "limited"; retryAfter: number; byUpstream: boolean }
  | { kind: "failed"; reason: FailReason };

type Fetched =
  | { kind: "ok"; body: unknown }
  | { kind: "missing" }
  | { kind: "limited"; retryAfter: number; byUpstream: true }
  | { kind: "failed"; reason: FailReason };

/** Seconds from a Retry-After header, kept between 1 s and 5 minutes; 60 when absent or unreadable. */
export function retryAfterOf(header: string | null): number {
  if (header == null) return 60;
  const seconds = /^\d{1,6}$/.test(header.trim()) ? Number(header.trim()) : NaN;
  if (Number.isFinite(seconds)) return Math.min(300, Math.max(1, seconds));
  const at = Date.parse(header);
  if (Number.isNaN(at)) return 60;
  return Math.min(300, Math.max(1, Math.ceil((at - Date.now()) / 1000)));
}

/** The text of [response]'s body, or why it could not be read whole within [maxBytes] and the [signal]. */
async function boundedText(response: Response, maxBytes: number, signal: AbortSignal): Promise<string | "too_large" | "timeout" | "network"> {
  const declared = Number(response.headers.get("Content-Length"));
  if (Number.isFinite(declared) && declared > maxBytes) {
    await response.body?.cancel().catch(() => {});
    return "too_large";
  }
  const reader = response.body?.getReader();
  if (!reader) return "";
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
  return new TextDecoder("utf-8").decode(all);
}

async function fetchJson(url: string, maxBytes: number, userAgent: string, timeoutMs: number): Promise<Fetched> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    let response: Response;
    try {
      response = await fetch(url, {
        method: "GET",
        headers: { "User-Agent": userAgent, Accept: "application/json" },
        signal: controller.signal,
      });
    } catch {
      return { kind: "failed", reason: controller.signal.aborted ? "timeout" : "network" };
    }
    if (response.status === 404) {
      await response.body?.cancel().catch(() => {});
      return { kind: "missing" };
    }
    if (response.status === 429) {
      await response.body?.cancel().catch(() => {});
      return { kind: "limited", retryAfter: retryAfterOf(response.headers.get("Retry-After")), byUpstream: true };
    }
    if (response.status !== 200) {
      await response.body?.cancel().catch(() => {});
      return { kind: "failed", reason: "status" };
    }
    const text = await boundedText(response, maxBytes, controller.signal);
    if (text === "too_large" || text === "timeout" || text === "network") return { kind: "failed", reason: text };
    try {
      return { kind: "ok", body: JSON.parse(text) as unknown };
    } catch {
      return { kind: "failed", reason: "bad_json" };
    }
  } finally {
    clearTimeout(timer);
  }
}

function lyricText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  if (value.trim() === "" || value.length > MAX_LYRICS_CHARS) return null;
  return value;
}

/** The lyrics in one LRCLIB record, or null when it holds none we can use. */
export function lyricsOf(record: unknown): Lyrics | null {
  if (typeof record !== "object" || record === null || Array.isArray(record)) return null;
  const r = record as Record<string, unknown>;
  if (r.instrumental === true) return { synced: null, plain: null, instrumental: true };
  const synced = lyricText(r.syncedLyrics);
  const plain = lyricText(r.plainLyrics);
  if (synced == null && plain == null) return null;
  return { synced, plain, instrumental: false };
}

/** Whether two normalised artist names are the same, or one credit holds the other as whole words. */
export function sameArtist(asked: string, found: string): boolean {
  if (asked === "" || found === "") return false;
  if (asked === found) return true;
  // "Artist" against "Artist feat Other", whole words only.
  return ` ${found} `.includes(` ${asked} `) || ` ${asked} `.includes(` ${found} `);
}

/**
 * The best record of a search for [query]: duration within 2 s, the same normalised title and
 * artist. Among those, synced lyrics first, then the closest duration, then the same album.
 */
export function pickBest(records: unknown, query: LyricsQuery): Lyrics | null {
  if (!Array.isArray(records)) return null;
  const title = normalise(query.title);
  const artist = normalise(query.artist);
  const album = normalise(query.album);
  let best: { lyrics: Lyrics; score: number } | null = null;
  for (const record of records.slice(0, 50)) {
    if (typeof record !== "object" || record === null) continue;
    const r = record as Record<string, unknown>;
    if (typeof r.trackName !== "string" || typeof r.artistName !== "string") continue;
    if (typeof r.duration !== "number" || !Number.isFinite(r.duration)) continue;
    const gap = Math.abs(r.duration - query.duration);
    if (gap > DURATION_TOLERANCE) continue;
    if (normalise(r.trackName) !== title) continue;
    if (!sameArtist(artist, normalise(r.artistName))) continue;
    const lyrics = lyricsOf(r);
    if (!lyrics) continue;
    const sameAlbum = album !== "" && typeof r.albumName === "string" && normalise(r.albumName) === album;
    const score = (lyrics.synced ? 100 : 0) + (sameAlbum ? 10 : 0) + (DURATION_TOLERANCE - gap) * 3;
    if (!best || score > best.score) best = { lyrics, score };
  }
  return best?.lyrics ?? null;
}

export function getUrl(query: LyricsQuery): string {
  const params = new URLSearchParams({ track_name: query.title, artist_name: query.artist });
  if (query.album !== "") params.set("album_name", query.album);
  params.set("duration", String(query.duration));
  return `${LRCLIB}/api/get?${params}`;
}

export function searchUrl(query: LyricsQuery): string {
  // No album here: a search is the fallback for when the album or its spelling did not match.
  return `${LRCLIB}/api/search?${new URLSearchParams({ track_name: query.title, artist_name: query.artist })}`;
}

export interface UpstreamOptions {
  userAgent: string;
  timeoutMs?: number;
  /** Asked before each upstream call; false stops the lookup as rate limited. */
  mayCall?: () => boolean;
}

/**
 * Looks [query] up: /api/get first, and /api/search only when /api/get answers 404 (or cannot be
 * asked, for a track longer than LRCLIB's one hour cap).
 */
export async function lookUp(query: LyricsQuery, options: UpstreamOptions): Promise<Outcome> {
  const timeout = options.timeoutMs ?? UPSTREAM_TIMEOUT_MS;
  const mayCall = options.mayCall ?? (() => true);

  if (query.duration <= LRCLIB_MAX_DURATION) {
    if (!mayCall()) return { kind: "limited", retryAfter: 0, byUpstream: false };
    const got = await fetchJson(getUrl(query), MAX_GET_BYTES, options.userAgent, timeout);
    if (got.kind === "ok") {
      const lyrics = lyricsOf(got.body);
      if (lyrics) return { kind: "found", lyrics };
      if (typeof got.body !== "object" || got.body === null || Array.isArray(got.body)) {
        return { kind: "failed", reason: "bad_json" };
      }
      return { kind: "missing" };
    }
    if (got.kind !== "missing") return got;
  }

  if (!mayCall()) return { kind: "limited", retryAfter: 0, byUpstream: false };
  const found = await fetchJson(searchUrl(query), MAX_SEARCH_BYTES, options.userAgent, timeout);
  if (found.kind === "missing") return { kind: "missing" };
  if (found.kind !== "ok") return found;
  if (!Array.isArray(found.body)) return { kind: "failed", reason: "bad_json" };
  const lyrics = pickBest(found.body, query);
  return lyrics ? { kind: "found", lyrics } : { kind: "missing" };
}
