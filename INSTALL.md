# Installing TMPlayer Music

TMPlayer Music is not in any store yet. Every build is on the
[Releases](https://github.com/dracu-lah/tmplayer-music/releases) page. It runs on phones and tablets
with **Android 6.0 or newer**.

The first builds are **test versions**, tagged `vX.Y.Z-beta.N` and marked *Pre-release* on GitHub.
They work, but expect rough edges, and please report what you find (see
[Reporting a problem](#reporting-a-problem)).

## On an Android phone

1. **Download the APK.** On the phone, open the
   [Releases](https://github.com/dracu-lah/tmplayer-music/releases) page and, under the newest
   release, tap `TMPlayerMusic-<version>.apk` in its Assets.
2. **Open it and allow the install.** Open the file when it finishes. The first time, Android asks to
   let your browser (or your file manager) install apps: allow it, then tap **Install**. On Android 7
   and older the switch is **Unknown sources** in Settings, Security.
3. **Sign in.** Open TMPlayer Music and enter your phone number, then the code Telegram sends to your
   other devices, and your two-step password if you set one. Or choose **Scan a QR code** and scan it
   from the Telegram app on another phone: in Telegram, Settings, Devices, Link Desktop Device.
4. **Pick the chats** with your audio files. Saved Messages is a good first one. Only the chats you
   pick are read, and you can change them later in Manage chats.

You can check the download against the SHA-256 that GitHub shows beside each file on the release
page.

### Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) can install the APK straight from the releases and
tell you when a new one is out: add `https://github.com/dracu-lah/tmplayer-music`. Turn on its
pre-release option to get the test versions.

## Updating

The app has no updater of its own. Download the newer APK from the Releases page (or let Obtainium do
it) and install it over the old one: your library, playlists and sign in stay. Android refuses an APK
signed with a different key, so only install builds from this repository's Releases page over each
other.

## Storage

Audio streams from Telegram as you play it and is kept in a cache so it does not download twice. Set
how big the cache may grow, and see how much is used and free, in Settings. **Free up space** deletes
the cached audio; your tracks stay in the library and stream again when you play them, and nothing in
Telegram is touched.

Reading the start of each file to find its title, artist and cover runs on Wi-Fi by default, so a big
library does not use up your mobile data.

## Uninstalling

Uninstall it like any app. That removes the library index, the cache and the sign in from the phone.
To end the session on Telegram's side too, log out in the app first, or end it in Telegram under
Settings, Devices.

## Troubleshooting

- **"There is a problem parsing the package"**: the download was cut short, or the phone is older
  than Android 6.0. Download it again.
- **"App not installed"** over an older copy: the old copy was signed with a different key (for
  example a build you made yourself). Uninstall it first.
- **No code arrives**: Telegram sends the code to your other Telegram apps first, not by SMS. If you
  are signed in nowhere else, or Telegram will not send one here, use **Scan a QR code** instead.
- **A track is missing**: only the chats you picked are read, and voice messages and short clips are
  left out on purpose. Check Manage chats, and give the scan time on a large chat; the Library section
  of Settings shows how far it has got.
- **A track does not play**: check your connection, then try it again. If it still fails, open
  **Send logs** from the error or from Settings and attach the file to a bug report.

## Reporting a problem

Open a [bug report](https://github.com/dracu-lah/tmplayer-music/issues/new?template=bug_report.yml)
with the app version (Settings, About), your phone and its Android version, and what happened.
**Send logs** makes a log file with chat names, ids, file names and phone numbers taken out; attach
it if you can.

TMPlayer Music is unofficial and not affiliated with Telegram. See
[tmplayer.org/legal](https://tmplayer.org/legal).
