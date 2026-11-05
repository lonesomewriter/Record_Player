# Developing Record Player

Technical notes for building, testing and contributing. For what the app does, see the [README](../README.md).

## Contributing

Read [AGENTS.md](../AGENTS.md) and the files in [project-guidelines/](../project-guidelines/) before changing anything. They apply to people and AI agents alike.

## Project layout

| Module | Contents |
|---|---|
| `shared/` | Code used by both apps. `commonMain`: UI, music library, player rules (queue, shuffle, repeat), Convert to Audio rules. `jvmShared`: tag editing (jaudiotagger + our Opus tag support) and song marks for converted files. `jvmMain`: Linux playback and conversion through GStreamer. |
| `androidApp/` | Android entry point, the background playback service (Media3, lock-screen/notification controls) and the Android converter (MediaCodec). |
| `desktopApp/` | Linux desktop entry point and `.deb` packaging (including the menu entry in `packaging/linux/`). |
| `scripts/` | `build.sh`: builds, checks and files the Android APK and Linux `.deb`. |
| `iosApp/` | An early iOS screen port in Swift (built with Xcode, not part of the Gradle build). |

## Requirements

- **JDK 25.** The Linux player uses Java's FFM API (Java 22+). Gradle won't download JDKs automatically.
- **Android SDK** with platform API 37 (the app compiles against 37 and targets 36; minimum is Android 8.0, API 26). Put its location in a `local.properties` file at the repo root: `sdk.dir=/path/to/android-sdk`. That file is gitignored.
- **GStreamer 1.x on Linux** for playback and conversion, with the base, good and libav plugins. On Debian/Ubuntu:
  ```sh
  sudo apt install gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-libav
  ```
  Without `gstreamer1.0-libav`, M4A conversion is greyed out; everything else works.
- **`gdbus` on Linux** (package `libglib2.0-bin`, usually preinstalled) lets Settings → Theme → System follow the desktop's dark/light setting. Without it, System uses Dark.
- Optional: **`desktop-file-validate`** (package `desktop-file-utils`) lets `scripts/build.sh` validate the Linux menu entry.

## Common commands

```sh
scripts/build.sh dev                         # build, check and file the APK and .deb (asks first)
./gradlew :desktopApp:run                    # run the Linux desktop app
./gradlew :androidApp:assembleDebug          # build the Android debug APK
./gradlew :shared:jvmTest                    # run tests (playback/conversion tests skip if GStreamer is missing)
./gradlew :androidApp:connectedDebugAndroidTest   # Android tests (needs a running emulator or phone)
./gradlew :desktopApp:packageDeb             # build a .deb package
```

Release builds (`scripts/build.sh release`) sign the APK with your own keystore, read from a gitignored `.env`; see [.env_example](../.env_example).

## Playlist files

- **M3U8** exports relative or full paths and optional track information. Import matches songs by path, then by title, artist and duration.
- **`.rdplaylist`** is a standard ZIP containing the playlist, unchanged audio files, optional `.lrc` lyrics and a cover. Import validates archive paths and sizes before extraction, reuses songs already in the library, and copies new songs under `Music/Imported/<playlist>/`.
- Android uses the Storage Access Framework; Linux uses native file dialogs.

## Animation settings (`web-configs.json`)

Timings you can tune without touching the code. They're read when the app is **built** and compiled in, so rebuild after changing them. The app never downloads this file or anything else.

| Key | Default | Allowed | What it does |
|---|---|---|---|
| `localLight.blinkSeconds` | `2.4` | 0.5–10 | One full fade out and back of the glowing dot beside "Local". |
| `disc.spinUpMs` | `300` | 0–10000 | How quickly the disc speeds up when music starts. Bigger is slower; `0` is instant. |
| `disc.spinDownMs` | `600` | 0–10000 | How quickly the disc coasts to a stop when music stops. Bigger is longer; `0` stops at once. |

A missing or out-of-range value stops the build with a message naming the key. When the system asks for less motion (Android with animations off), the dot stays lit and the disc doesn't turn, whatever these say.
