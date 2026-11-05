# Record Player

An offline music player for Android and Linux desktop. Point it at your music folder and it plays your songs on a spinning record.

## Features

- **Your music, from your folder.** Picks up new and changed songs on its own. Plays MP3, FLAC, M4A, OGG, Opus and WAV.
- **Now Playing on a spinning record**, with shuffle, repeat and gapless playback.
- **Synced lyrics** from `.lrc` files or the song itself, with a built-in lyrics editor.
- **Edit song details**: title, artist, album, cover art and more.
- **Playlists** with drag-to-reorder and custom covers. Export and import as M3U8 or as a `.rdplaylist` bundle (songs, lyrics and cover in one file).
- **Convert to Audio**: turn a playlist into one audio file (FLAC, M4A, Opus, WAV and more), with optional even volume and song markers you can skip between.
- **Open with**: play audio files straight from your file manager.
- **Make it yours**: light, dark or system theme, accent colours (or your desktop's accent), and pause when the device is muted.
- **Controls everywhere**: lock screen, notification and home-screen widget on Android.

## Privacy

No accounts, no ads, no analytics, and no internet access. Everything stays on your device.

## Platforms

- **Android** 8.0 or later.
- **Linux desktop** as a `.deb` package (Debian, Ubuntu and similar). It uses the system's GStreamer for audio, which the package installs.

## Building from source

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for requirements, commands and how to contribute.

## License

Record Player is free software, released under the [GNU General Public License](LICENSE), version 3 or (at your option) any later version (GPL-3.0-or-later).

You may use, study, share and change it. If you distribute it or a modified version, you must do so under the same license and make the full source code available. It comes with no warranty; see [LICENSE](LICENSE) for the full terms.

It builds on open-source libraries under their own licenses, all compatible with GPL-3.0:
- AndroidX, Media3, Room, Jetpack Compose, Kotlin: Apache-2.0
- jaudiotagger: LGPL-2.1
- GStreamer, used on desktop from the system and not bundled: LGPL-2.1
