# Audio seek matrix

The definition of done for streaming audio from Telegram, the music version of TMPlayer's seek matrix
(`docs/ARCHITECTURE.md` there). Each row is played on a real phone from a chat, not from a local file.
Mark each cell with the date and the device. Small test files only, from a test account.

| File | Start | Seek to 50% | Seek near the end | Seek back to the start | Next track starts (gapless) | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| MP3 CBR 320 kbps, ID3v2.3 with a cover | | | | | | |
| MP3 VBR with a Xing header | | | | | | Seek uses the Xing table |
| MP3 VBR without a Xing or VBRI header | | | | | | Seek is an estimate; must not hang |
| FLAC 16 bit 44.1 kHz with a SEEKTABLE, sent as a document | | | | | | |
| FLAC 24 bit 96 kHz without a SEEKTABLE | | | | | | Binary search seek over the stream |
| M4A AAC, `moov` at the start | | | | | | |
| M4A AAC, `moov` at the end | | | | | | Reads the tail first |
| M4A ALAC | | | | | | FFmpeg decoder |
| Ogg Opus | | | | | | |
| Ogg Vorbis | | | | | | |
| WAV 16 bit | | | | | | Large file, streamed |
| A 3 hour mix (MP3) | | | | | | Seek to 2:30:00 starts within a few seconds |
| A 1 GB WAV or FLAC | | | | | | Never loaded whole; memory stays flat |

Also on each device pass:

- Lock the phone while playing: playback goes on, the notification and lock screen show the cover,
  previous, play or pause, next and a seek bar (Android 13 and later).
- Unplug wired headphones or switch off Bluetooth: playback pauses.
- A phone call: playback pauses and resumes after it (audio focus).
- Kill the app from recents while paused: the next open shows the same queue at the same position,
  paused. Nothing plays on its own, also not when Bluetooth headphones connect.
- A track whose message was deleted: it is skipped with its reason in the mini-player; three such
  tracks in a row stop the skipping.
- Notification permission refused on Android 13 and later: playback still works.
- Idle CPU after the queue ends and after a long pause (`/proc/<pid>/stat`, AGENTS.md).
