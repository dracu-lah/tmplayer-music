# Security policy

## Supported versions

Only the latest release, and the latest test version (`vX.Y.Z-beta.N`) while one is out, get fixes.
Please check that the problem still happens on the newest build from the
[Releases](https://github.com/dracu-lah/tmplayer-music/releases) page before reporting.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately instead:

- through GitHub: the **Report a vulnerability** button on the
  [Security tab](https://github.com/dracu-lah/tmplayer-music/security/advisories/new), or
- by email to hello@tmplayer.org.

Include the app version, the phone and its Android version, what an attacker could do, and the steps
to reproduce it. You will get a reply within a week. Once a fix is released, the report can be
credited to you if you like.

## Scope

TMPlayer Music talks to Telegram through TDLib and keeps your library on your device. The one service
of its own is the lyrics gateway in `gateway/lyrics/`, which only ever receives a track's title,
artist, album and duration; problems in it are in scope. Problems in Telegram itself, in TDLib or in
LRCLIB belong upstream, though a heads up is welcome when this app is affected.
