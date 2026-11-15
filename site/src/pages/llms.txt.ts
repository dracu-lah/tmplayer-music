// /llms.txt: a plain Markdown summary for language models (llmstxt.org): what the app is, the page
// list with one line each, how to download it and the constraints that matter when describing it.
// The page list comes from src/data/lastmod.json, so a new page appears here once it is listed
// there; the one-line descriptions are below and a path without one is listed bare. The download
// facts come from src/data/release.json. Like robots.txt and the sitemap it is empty-handed while
// INDEXABLE is false.
import type { APIRoute } from 'astro';
import pages from '../data/lastmod.json';
import release from '../data/release.json';
import { INDEXABLE, ORIGIN } from '../../site.config.mjs';
import { REPO_URL } from '../lib/support';

const DESCRIPTIONS: Record<string, string> = {
  '/': 'What TMPlayer Music is, with screenshots and the download button',
  '/download/': 'The latest APK, its size and SHA-256, and how to check it',
  '/install/': 'Installing the APK, signing in, picking chats and updating',
  '/features/': 'Everything the player does: library, versions, streaming, lyrics, queue, cache, offline',
  '/faq/': 'Short answers about what it reads, what it keeps and what it does not do',
  '/changelog/': 'Release notes for every version',
  '/changelog/v0.1.0/': 'Release notes for version 0.1.0',
  '/troubleshooting/': 'Fixes for the problems people hit most',
  '/troubleshooting/telegram-blocked/': 'When Telegram is blocked on the network',
  '/troubleshooting/no-tracks-found/': 'When a chat shows no tracks',
  '/troubleshooting/track-will-not-play/': 'When a track will not play',
  '/troubleshooting/storage-full/': 'When the phone runs out of space',
  '/troubleshooting/lyrics-missing/': 'When lyrics or covers do not show',
  '/privacy/': 'What stays on the device, what goes to Telegram and to the lyrics and cover lookup, and what the site does',
  '/legal/': 'Lawful use, rights holders, the GPL-3.0 licence with the OpenSSL exception, third-party notices',
  '/security/': 'How to report a security problem privately, and how to verify an APK',
  '/support/': 'Ways to support the project, all voluntary',
};

interface Entry { path: string; lastmod?: string }

const NOT_LIVE = `# TMPlayer Music

music.tmplayer.org is not open to search engines or crawlers yet.
`;

function live(): string {
  const list = (pages as Entry[]).map((e) => {
    const d = DESCRIPTIONS[e.path];
    return `- [${ORIGIN + e.path}](${ORIGIN + e.path})${d ? `: ${d}` : ''}${e.lastmod ? ` (updated ${e.lastmod})` : ''}`;
  });
  const mb = (release.apk.size / (1024 * 1024)).toFixed(1);
  return [
    '# TMPlayer Music',
    '',
    '> TMPlayer Music is a free, open source player for Android for the audio files already in your own Telegram chats. You sign in with your own Telegram account, pick the chats, and it plays the tracks in them while the file is still downloading. Unofficial, not affiliated with Telegram.',
    '',
    '## What it is, and what it is not',
    '',
    '- It plays audio files (MP3, FLAC, M4A, Ogg, Opus, WAV, AIFF) that are already in chats you are in. It reads only the chats you pick, and nothing is scanned until you pick one.',
    '- It has a library of songs, albums and artists, playlists and favourites, a queue that is saved and restored, synced lyrics, an equalizer, a sleep timer and offline copies.',
    '- It keeps the library on the device. It has no ads, no analytics, no account of ours and no server between you and Telegram. The one service of its own looks up lyrics and covers from a track\'s title, artist, album and length, and one switch in Settings, Lyrics turns it off.',
    '- It is not a catalogue and not a source of music: no search of public channels, no recommendations of what to listen to, no audio provided. Use it only with audio you own or have the right to play.',
    '- It is unofficial and not affiliated with Telegram. "Telegram" is not part of the app\'s name.',
    '- Android only for now (Android 6.0 or newer). Licence: GPL-3.0-or-later with an additional permission for OpenSSL.',
    '',
    '## Download',
    '',
    `- Latest release: ${release.version}, released ${release.date}, for Android ${release.minAndroid} or newer.`,
    `- APK: ${release.apk.url} (${mb} MB)`,
    `- SHA-256: ${release.apk.sha256}`,
    `- All releases: ${REPO_URL}/releases`,
    `- Source code: ${REPO_URL}`,
    `- Short address for a phone: ${ORIGIN}/apk`,
    '',
    '## Pages',
    '',
    ...list,
    '',
    '## Related',
    '',
    '- [TMPlayer](https://tmplayer.org/): the sibling app, a player for the videos in your own Telegram chats.',
    '',
  ].join('\n');
}

export const GET: APIRoute = () =>
  new Response(INDEXABLE ? live() : NOT_LIVE, { headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
