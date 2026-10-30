# Third-party notices

TMPlayer Music is distributed under the GNU General Public License, version 3 or (at your option) any
later version (GPL-3.0-or-later), with the additional permission for OpenSSL in
[LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md). It also contains or depends on the
components below. Their copyrights remain with their respective authors, and their licence terms
continue to apply.

The versions are the ones pinned in `gradle/libs.versions.toml`; when a pin moves, this file moves
with it.

## Code from TMPlayer

Parts of this app are taken or adapted from [TMPlayer](https://github.com/dracu-lah/TMPlayer), the
sibling video player by the same author, under GPL-3.0-or-later with the same OpenSSL exception:
the TDLib wiring and sign in flow, the streaming data source and its download window, the i18n
catalog and its generator, the log redactor, and error handling. Each such file says so in its first
lines, naming the TMPlayer file it came from. Copyright (c) 2026 dracu-lah.

## Android

### NextLib Media3 Extensions 1.11.1-0.16.0 and FFmpeg

- Author: Anil Kumar Beesetti and contributors
- Licence: GNU General Public License version 3
- Source: <https://github.com/anilbeesetti/nextlib/tree/v1.11.1-0.16.0>
- Used as published on Maven Central (`io.github.anilbeesetti:nextlib-media3ext`), unmodified.

It supplies FFmpeg audio decoders for what a phone's own codecs lack (FLAC and Opus before Android 8,
ALAC). Its native libraries are built by NextLib's `ffmpeg/setup.sh` at that tag, from FFmpeg
(libavcodec, libavutil, libswresample, libswscale), LGPL-3.0-or-later in the configuration NextLib
uses (`--enable-version3`, without `--enable-gpl` or non-free components):
<https://ffmpeg.org/download.html#releases>. The exact FFmpeg version and the other libraries in the
build (such as dav1d, BSD-2-Clause) are listed in that script.

### TDLib with OpenSSL

tdl-coroutines' Android artifact (see "Shared") bundles TDLib's native library
(`libtdjsonjava.so`), which links OpenSSL 1.1.1 statically.

- OpenSSL 1.1.1: OpenSSL License and the original SSLeay License (dual licence):
  <https://www.openssl.org/source/old/1.1.1/>

As the OpenSSL License requires: this product includes software developed by the OpenSSL Project
for use in the OpenSSL Toolkit (<https://www.openssl.org/>). This product includes cryptographic
software written by Eric Young (eay@cryptsoft.com).

Because the OpenSSL License is not compatible with the GPL, this app's own code carries an
additional permission to be combined with it; see
[LICENSE-OPENSSL-EXCEPTION.md](LICENSE-OPENSSL-EXCEPTION.md).

### Android libraries

- AndroidX Core 1.19.1, Activity Compose 1.13.0, Lifecycle 2.11.0: Apache License 2.0:
  <https://github.com/androidx/androidx>
- Jetpack Compose (BOM 2026.09.00): UI, Foundation, Material 3 and Material Icons Core: Apache License
  2.0: <https://github.com/androidx/androidx>
- AndroidX Media3 1.11.1 (ExoPlayer and Session): Apache License 2.0:
  <https://github.com/androidx/media>
- AndroidX SQLite bundled 2.7.1, which carries its own build of SQLite: Apache License 2.0
  (<https://github.com/androidx/androidx>); SQLite itself is in the public domain
  (<https://sqlite.org/copyright.html>)
- Android desugared library (`desugar_jdk_libs_nio`) 2.1.5, which brings newer Java APIs to
  Android 6 and 7: GNU General Public License version 2 with the Classpath Exception:
  <https://github.com/google/desugar_jdk_libs>

## Shared

### tdl-coroutines 15.0.0 and TDLib

- Author: Georgii Ippolitov and contributors
- Licence: Apache License 2.0
- Source: <https://github.com/g000sha256/tdl-coroutines/tree/15.0.0>
- TDLib, as bundled by that release: Boost Software License 1.0: <https://github.com/tdlib/td>

### Kotlin and other libraries

- Kotlin standard library, kotlinx.coroutines 1.11.0 and kotlinx.serialization 1.11.0: Apache
  License 2.0: <https://github.com/JetBrains/kotlin>, <https://github.com/Kotlin/kotlinx.coroutines>
  and <https://github.com/Kotlin/kotlinx.serialization>
- ZXing Core 3.5.4 (the sign in QR code): Apache License 2.0:
  <https://github.com/zxing/zxing/tree/zxing-3.5.4>

The Gradle wrapper scripts are provided under Apache License 2.0. Test-only dependencies (JUnit,
kotlin.test, kotlinx-coroutines-test) are not packaged in any build.

## The lyrics gateway (`gateway/lyrics/`)

The gateway is a small Cloudflare Pages project, not part of the app. Its development tools
(Wrangler, TypeScript, Vitest and the Cloudflare Workers types, see `gateway/lyrics/package.json`)
are not shipped in any build.

## Data from online services

Nothing below ships in a build.

- LRCLIB (<https://lrclib.net>): synced and plain lyrics, asked by the gateway with a track's title,
  artist, album and duration only. The app does not call it yet; when it does, lookups are off until
  the user turns them on.

## Visual assets

### Hugeicons (free Stroke Rounded set)

The app's interface icons (play, pause, next, previous, shuffle, repeat, queue, lyrics, sleep timer,
favourite, search, more, close, arrows, drag handle and the playing indicator) are drawn from the free
Hugeicons icons, <https://hugeicons.com>, taken as SVG path data from
<https://github.com/hugeicons/hugeicons> and kept in
`app/src/main/java/com/tmplayer/music/ui/common/MusicIcons.kt`. Only free icons are used; no Hugeicons
Pro icon is included. Some are adapted: the filled heart is the favourite outline filled in, and the
paused playing indicator is the audio wave with every line cut to the shortest.

```
MIT License

Copyright (c) 2025 Hugeicons

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

<!-- TODO(owner): confirm where the music note mark in `art/logo.svg` and the launcher icon came from
     (its export looks like an SVG Repo file, as TMPlayer's player mark is) and credit it here and in
     the README if it is not original. -->
- The app icon and `art/logo.svg`: source to be confirmed.

## Licence texts

- GPL-3.0: <https://www.gnu.org/licenses/gpl-3.0.html> (also the `LICENSE` file)
- GPL-2.0: <https://www.gnu.org/licenses/old-licenses/gpl-2.0.html>
- LGPL-3.0: <https://www.gnu.org/licenses/lgpl-3.0.html>
- Classpath Exception: <https://openjdk.org/legal/gplv2+ce.html>
- Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>
- MIT: <https://opensource.org/license/mit>
- BSD-2-Clause: <https://opensource.org/license/bsd-2-clause>
- OpenSSL 1.1.1 (OpenSSL and SSLeay): <https://www.openssl.org/source/license-openssl-ssleay.txt>
- Boost-1.0: <https://www.boost.org/LICENSE_1_0.txt>
