# Music_Player

An offline music player for Android and Linux desktop, built with Kotlin and Compose Multiplatform. Working name: "Record Disc".

No accounts, ads, analytics or network access.

## Project layout

| Module | Contents |
|---|---|
| `shared/` | Code used by both apps. `commonMain`: UI, music library, player rules (queue, shuffle, repeat). `jvmShared`: tag editing (jaudiotagger + our Opus tag support). `jvmMain`: Linux playback through GStreamer. |
| `androidApp/` | Android entry point and the background playback service (Media3, lock-screen/notification controls). |
| `desktopApp/` | Linux desktop entry point and `.deb`/`.rpm` packaging. |

## Requirements

- **JDK 25.** The Linux player uses Java's FFM API (Java 22+). Gradle won't download JDKs automatically.
- **Android SDK** with platform API 37 (the app compiles against 37 and targets 36). Put its location in a `local.properties` file at the repo root: `sdk.dir=/path/to/android-sdk`. That file is gitignored.
- **GStreamer 1.x on Linux** for playback, with the base, good and libav plugins. On Debian/Ubuntu:
  ```sh
  sudo apt install gstreamer1.0-plugins-base gstreamer1.0-plugins-good gstreamer1.0-libav
  ```

## Common commands

```sh
./gradlew :desktopApp:run                    # run the Linux desktop app
./gradlew :androidApp:assembleDebug          # build the Android debug APK
./gradlew :shared:jvmTest                    # run tests (playback tests skip if GStreamer is missing)
./gradlew :androidApp:connectedDebugAndroidTest   # Android player tests (needs a running emulator or phone)
./gradlew :desktopApp:packageDeb             # build a .deb package
```

## Contributing

Read [AGENTS.md](AGENTS.md) and the files in [project-guidelines/](project-guidelines/) before changing anything. They apply to people and AI agents alike.
