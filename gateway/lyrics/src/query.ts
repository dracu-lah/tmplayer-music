// The values a lookup may carry (lyrics: four, covers: three and a size), checked and bounded. Everything else in the query string is
// ignored, so a client that sends more learns nothing and nothing extra goes upstream.

export interface LyricsQuery {
  title: string;
  artist: string;
  /** Empty when the track has no album. */
  album: string;
  /** Whole seconds, 1 to 86400. */
  duration: number;
}

export const MAX_TITLE = 200;
export const MAX_ARTIST = 200;
export const MAX_ALBUM = 200;
export const MAX_DURATION = 86_400;

/** C0, DEL and C1 control characters, and the Unicode line and paragraph separators. */
const CONTROL = /[\u0000-\u001f\u007f-\u009f\u2028\u2029]/;

/** Trims and folds runs of white space into one space. */
function tidy(value: string): string {
  return value.trim().replace(/\s+/g, " ");
}

function text(params: URLSearchParams, name: string, max: number, required: boolean): string | { error: string } {
  const all = params.getAll(name);
  if (all.length > 1) return { error: `${name} is given more than once.` };
  const raw = all[0] ?? "";
  if (CONTROL.test(raw)) return { error: `${name} has control characters.` };
  const value = tidy(raw);
  if (required && value === "") return { error: `${name} is required.` };
  if (value.length > max) return { error: `${name} is longer than ${max} characters.` };
  return value;
}

/** The lookup in [params], or a short reason it is refused. */
export function parseQuery(params: URLSearchParams): LyricsQuery | { error: string } {
  const title = text(params, "title", MAX_TITLE, true);
  if (typeof title !== "string") return title;
  const artist = text(params, "artist", MAX_ARTIST, true);
  if (typeof artist !== "string") return artist;
  const album = text(params, "album", MAX_ALBUM, false);
  if (typeof album !== "string") return album;

  const durations = params.getAll("duration");
  if (durations.length !== 1) return { error: "duration is required, once, in whole seconds." };
  const raw = durations[0] ?? "";
  if (!/^\d{1,5}$/.test(raw)) return { error: "duration must be a whole number of seconds." };
  const duration = Number(raw);
  if (duration < 1 || duration > MAX_DURATION) return { error: `duration must be 1 to ${MAX_DURATION} seconds.` };

  return { title, artist, album, duration };
}

/** A cover lookup: the same text rules as lyrics, an optional album, and one of three sizes. */
export interface CoverQuery {
  title: string;
  artist: string;
  /** Empty when the track has no album: then only the recording search is made. */
  album: string;
  /** The Cover Art Archive's thumbnail size in pixels. */
  size: CoverSize;
}

export const COVER_SIZES = [250, 500, 1200] as const;
export type CoverSize = (typeof COVER_SIZES)[number];
export const DEFAULT_COVER_SIZE: CoverSize = 500;

/** The cover lookup in [params], or a short reason it is refused. */
export function parseCoverQuery(params: URLSearchParams): CoverQuery | { error: string } {
  const title = text(params, "title", MAX_TITLE, true);
  if (typeof title !== "string") return title;
  const artist = text(params, "artist", MAX_ARTIST, true);
  if (typeof artist !== "string") return artist;
  const album = text(params, "album", MAX_ALBUM, false);
  if (typeof album !== "string") return album;

  const sizes = params.getAll("size");
  if (sizes.length > 1) return { error: "size is given more than once." };
  const raw = sizes[0];
  if (raw === undefined || raw === "") return { title, artist, album, size: DEFAULT_COVER_SIZE };
  const size = COVER_SIZES.find((s) => String(s) === raw);
  if (size === undefined) return { error: `size must be one of ${COVER_SIZES.join(", ")}.` };
  return { title, artist, album, size };
}

/**
 * The form two names are compared in: Unicode compatibility folded, accents dropped, lower case,
 * punctuation gone, single spaces. Letters of every script are kept, so Malayalam, Hindi or Arabic
 * titles still compare.
 */
export function normalise(value: string): string {
  return value
    .normalize("NFKD")
    .replace(/\p{M}+/gu, (marks, offset: number, whole: string) => (isLatinBefore(whole, offset) ? "" : marks))
    .toLowerCase()
    .replace(/[^\p{L}\p{M}\p{N}]+/gu, " ")
    .trim();
}

/** Accents are dropped only after Latin letters: in Indic scripts the marks are the vowels. */
function isLatinBefore(whole: string, offset: number): boolean {
  const before = whole.charCodeAt(offset - 1);
  return before < 0x0250;
}
