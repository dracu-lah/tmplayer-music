# Working on TMPlayer Music

The rules for anyone, person or coding agent, changing this repository. Agents read this file
directly (Codex, Cursor, Copilot, Windsurf) or import it (`CLAUDE.md`, `GEMINI.md`), so it is the
only copy: edit it here, never in those.

TMPlayer Music is a player for the audio files already in your own Telegram chats. It is the sibling
of [TMPlayer](https://github.com/dracu-lah/TMPlayer) (video), built in its own repository on the same
engine. Unofficial, not affiliated with Telegram.

| Topic | File |
| --- | --- |
| What to build, in which order, and where it stopped | [plan/PLAN.md](plan/PLAN.md) (read its Progress section first) |
| The full research and every decision so far | [plan/RESEARCH.md](plan/RESEARCH.md) |
| TV app, Connect (remote) and Cast | [plan/TV-CAST.md](plan/TV-CAST.md) |
| Which TMPlayer files to read, and how they are reused | [plan/REFERENCE.md](plan/REFERENCE.md) |

## The two source repositories we learn from

1. **TMPlayer** (`../TMPlayer`, same author, GPL-3.0). Its rules are ours, and its code is the base:
   TDLib wiring, `TdDataSource`, `DownloadWindow`, sign in, i18n, error handling, release machinery.
   Read the code in `plan/REFERENCE.md` before writing the same thing again. Code taken from it keeps
   the GPL-3.0 header and the OpenSSL exception already in `LICENSE-OPENSSL-EXCEPTION.md`.
2. **PixelPlayer** (github.com/PixelPlayerHQ/PixelPlayer). **Its licence is proprietary** (study and
   personal use only; no derivative work, no redistribution, no store publishing without the author's
   written permission). So the rule is clean room:
   - We may read it to learn what features exist and how they behave, and write that down as a
     **behaviour spec in plain words**, with no code in it.
   - We implement from the spec on our own stack. **Never copy, paste, translate or closely follow its
     code**, never copy its assets, icons, strings or layouts. The look is our own take on the same
     ideas (see `plan/RESEARCH.md` section 3.3).
   - Never vendor its source into this repo (`/reference-src/` is ignored for a reason). Whoever reads
     the source does not also write the implementation of the same feature in the same session.

## Building a feature

Plan first, then build (user, 2026-10-22). Before writing code for a feature, list what it does on each
platform (phone, TV, desktop, web) and its edge cases. Then implement every edge case, or, where one is
too risky to handle in this pass, say so and suggest what to do instead. Never ship the happy path
alone. The kinds of cases that keep coming up:

- **Space and size.** Check free space before anything that writes (the cache, Keep offline, an
  export), say plainly how much is needed and how much is free, and offer what fits. Running totals
  where the user picks several things.
- **Several at once.** Select all (and Ctrl+A on the desktop), a selection that changes while the work
  runs, an empty selection, the same action run twice.
- **Interruptions.** Cancel, a full disk part way, a revoked permission, the app going to the
  background or the screen rotating, a file that is gone, still uploading or not allowed to be saved.
  No partial files left behind, and a summary of what worked, what was skipped and why.
- **Confirm what cannot be undone.** A dialog before a destructive or large step that says what will
  happen, off by default.
- **Each platform's limits.** The TV's remote and focus after the action, Android storage rules
  (Android 6.0 is the floor: guard newer APIs), the desktop's paths on Windows, Linux and macOS, a
  browser without the API needed.
- **Low-end devices.** Anything that runs by itself (paging, refreshes, scans, animations) must stop
  on its own. TMPlayer's 1 GB TV stick held at 150% CPU by a row that kept paging (2026-10-22), so
  measure idle CPU (`/proc/<pid>/stat`) after touching Home, lists or background work.
- **Scale.** Every list or feed is checked against a large account: 3,000 chats with folders and an
  archive, 100,000 tracks, 20,000 in one chat. First content within 3 s (from the disk index), never
  after a whole scan; TDLib calls have a budget; caches are bounded; a FLOOD_WAIT is waited out by the
  call that got it while the rest go on, and retries stop by themselves.
- **Music specifics** (from a mature player's real bug reports, `plan/RESEARCH.md` section 15): a
  missing duration is unknown, never hidden; tags are decoded by detecting the encoding; albums group
  by album artist plus album plus year; leaving a chat or logging out purges its rows and stats; hi-res
  and huge files stream, never load whole; the queue is saved and restored whole; gain lives on the
  track, not the position; nothing autoplays on connect.

Then test on real devices with small files before calling it done (unless the user says otherwise).

New user-visible text goes into `core/src/commonMain/resources/i18n/en.json` (keys sorted; the catalog
test fails otherwise). Other languages are the user's job: **never run LangSync (or any translation
sync) unless the user explicitly asks.**

## Error handling

Nothing the app reads from outside is trusted, and nothing that fails takes the app down:

- **Outside data.** Telegram's answers, files on disk (settings, caches, backups, lyrics, tags, cover
  art) and the network (LRCLIB, the update feed, the Connect and Cast protocols) can be missing, cut
  short, the wrong type or absurdly large. No `!!`, `.first()`, `[0]`, `toInt()`, `require` or `error()`
  on them: use the `OrNull` forms, skip the bad entry and keep the rest.
- **Every async job has a handler.** App-wide scopes and view models go through `JobFailures`,
  activities through `guardedLifecycleScope`. A screen's `LaunchedEffect` or `scope.launch` that calls
  TDLib, the disk or the network catches its own failure and shows it.
- **A failure shows a way out.** Say what happened with Try again and Send logs; never restart in a loop.
- **Parsers have garbage tests.** A new parser of outside data (tags, LRC, TTML, Connect messages)
  gets its line in `GarbageInputTest`: truncated, wrong-typed, huge and random input.

## Writing

No em dashes or en dashes, anywhere: comments, KDoc, commit messages, UI strings, site copy. Write the
sentence with a colon, a comma or a full stop instead. Before committing:

```sh
grep -rInP '\x{2014}|\x{2013}' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules .
```

Public copy (posts, store text, release notes, the site) describes the app as a player for the audio
files already in your own Telegram chats: "audio" and "tracks", never "free music", "download any song"
or "music channels"; no real artist, album or song names; no AI-assistance note unless a site requires it.

Commits: do **not** add a `Co-Authored-By: Claude` (or any Claude or Anthropic) trailer.

## Telegram's terms and the stores

- The app's own `api_id` from secrets, never a sample one. No "Telegram" in any app or store title and
  no paper plane logo; "unofficial, not affiliated with Telegram" in every description. Sponsored
  messages stay shown in channels. Nothing is done on the user's behalf without them knowing; read
  state is never faked or hidden.
- Never set TDLib's `ignore_platform_restrictions`, and keep the media mapper skipping messages with a
  restriction reason.
- A store build has no updater and no `REQUEST_INSTALL_PACKAGES`; only the GitHub APK updates itself.
  The local HTTP server and the Cast SDK belong in the regular APK only.
- Music in chats is often pirated, so: nothing is scanned until the user picks the chat, no public
  channel search, no catalogue, no recommendations of what to listen to (`plan/RESEARCH.md` section 11).
- Lyrics lookups are opt in and go through our own gateway, sending only title, artist, album and
  duration, never chat names.

## Process

- **Token-efficient multi-phase work.** Keep the Progress / handoff section of `plan/PLAN.md` current: after
  each phase say what is done (files, commit), what is next, and decisions or gotchas. A fresh session
  must be able to resume from that file alone. After a phase, tell the user it is a clean point to
  `/clear`, with what is next, the model and effort to use, and the exact prompt to paste.
- **No phone testing** unless the user lifts it (2026-10-28): compile, unit tests and render shots; list
  phone checks as due. Emulators only for TV; never the real TV stick without asking.
- **Release cadence guard.** Before any release (version bump, tag, `gh release create`), run
  `gh release list --limit 10`. If the last release is under 7 days old, or there were 2 or more in the
  past 7 days, stop and tell the user first. Every release is a tested pre-release (`vX.Y.Z-beta.N`)
  first. Releases only when the user says so.
- **Images.** Screenshots are WebP (quality 88); do not compress or re-encode images unless asked.
- **Browser checks** use Chromium, not Google Chrome.
- **Branches.** `main` is the only long lived branch; tidy worktrees and side branches as soon as they
  are merged.
