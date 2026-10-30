# Contributing to TMPlayer Music

Thanks for helping! The guiding principle is the same as TMPlayer's: **minimal work that works
properly**. The less code we own, the less breaks.

The full rules for anyone changing this repository, person or coding agent, are in
[AGENTS.md](AGENTS.md). This page is the short version.

## Dev setup

- JDK 21 and the Android SDK with platform 37.
- Your own free Telegram credentials from [my.telegram.org](https://my.telegram.org), in
  `local.properties` (gitignored):

  ```properties
  sdk.dir=/path/to/Android/Sdk
  TG_API_ID=1234567
  TG_API_HASH=your_api_hash
  ```

  Without them the app builds and runs, and says so on the sign in screen. Never use anybody else's
  credentials and never commit yours.

Then:

```bash
./gradlew :core:jvmTest :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                     # a debug APK
```

The library rules, the tag reader, the sign in flow and the UI text live in `:core`, which runs its
tests on a plain JVM, so most work needs no device.

### Release builds and signing

A release build is signed only when `keystore.properties` (gitignored) exists in the repository root:

```properties
storeFile=release.keystore
storePassword=...
keyAlias=...
keyPassword=...
```

To make a keystore of your own and hand it to the release workflow as repository secrets:

```bash
keytool -genkeypair -v -keystore release.keystore -alias tmplayer-music \
  -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.keystore | gh secret set KEYSTORE_BASE64
gh secret set KEYSTORE_PASSWORD
gh secret set KEY_ALIAS
gh secret set KEY_PASSWORD
gh secret set TG_API_ID
gh secret set TG_API_HASH
```

`gh secret set` reads each value from the terminal without echoing it. Keep the keystore somewhere
safe outside the repository: an APK signed with a different key cannot update an installed one.
[.env.example](.env.example) lists every value the build reads.

## Ground rules

- **Existing libraries first.** Before writing a component, check whether TDLib, Media3 or an
  established GPL-compatible library already does it. Custom code needs a reason.
- **Licence hygiene.** This project is GPL-3.0-or-later with an OpenSSL exception. Record the source
  and licence of every dependency or adapted file in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md),
  keep the required notices, and never copy code whose terms are not compatible with GPL-3.0. Code
  taken from TMPlayer keeps a first line saying where it came from.
- **Clean room for proprietary apps.** Reading another app to learn what a feature does is fine.
  Copying, translating or closely following its code, assets, icons, strings or layouts is not.
  Never add another project's source to this repository.
- **Low-end first.** minSdk 23 (Android 6.0): guard newer APIs where they are called. Anything that
  runs by itself (paging, scans, refreshes, animations) must stop on its own; check idle CPU after
  touching lists or background work.
- **Large libraries.** Every list is checked against a big account: thousands of chats, 100,000
  tracks, 20,000 in one chat. First content within 3 seconds, from the disk index.
- **Nothing from outside is trusted.** Telegram's answers, files, tags, lyrics and the network can be
  missing, cut short or absurd. No `!!`, `.first()` or `[0]` on them, and a new parser gets its lines
  in `GarbageInputTest`.
- **Every failure shows a way out:** what happened, Try again and Send logs.
- **Telegram's terms.** Nothing is scanned until the user picks the chat; no public channel search,
  no catalogue, no recommendations; nothing done on the user's behalf without them knowing.

## Style

- Kotlin official style, coroutines and Flow; no DI framework, and no new heavyweight dependency
  without a discussion first.
- Comments only where the code cannot say it (constraints, protocol quirks).
- New user-visible text goes into `core/src/commonMain/resources/i18n/en.json`, keys sorted (a test
  checks). Leave the other languages alone in the same pull request.
- **No em or en dashes anywhere**: code, comments, commit messages, UI text, docs. Use a comma, a
  colon or a full stop. The Style workflow fails on them; check before you push:

  ```bash
  grep -rInP '\x{2014}|\x{2013}' --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules .
  ```

- Screenshots are WebP.
- Commits: a short imperative subject. Keep pull requests small and focused.
- Public text describes the app as a player for the audio files already in your own Telegram chats:
  say "audio" and "tracks", and never use real artist, album or song names, in docs, tests or
  screenshots alike.

## Reporting issues

Use the [issue templates](https://github.com/dracu-lah/tmplayer-music/issues/new/choose). Include
the app version (Settings, About), the phone and its Android version, what you did, what you
expected and what happened. **Send logs** in Settings makes a log file with chat names, ids and phone
numbers taken out; attach it if you can. For a playback problem, say the file's format (MP3, FLAC,
M4A and so on) and roughly its size, but not the track's name.

Security problems go privately, as [SECURITY.md](SECURITY.md) says.
