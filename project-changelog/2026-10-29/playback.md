# Playback (Part 2)

- **Time:** 2026-10-29 19:45:09 UTC
- **Change:** Added the player for both platforms: shared queue/shuffle/repeat rules, the Linux player on GStreamer, and the Android background playback service on Media3 with lock-screen/notification controls. Fixed a lost-end-of-song bug in `GstPlayer`. No UI yet; verified by tests on Linux and on an Android emulator.

## Details

**Shared (`commonMain/playback`):**
- `MusicPlayer`: the interface both platforms implement. It covers queue, play/pause, next/previous, seek, repeat off/one/all, shuffle and release, and publishes `PlaybackState` as a flow.
- `QueueItem`: built from a library track with `toQueueItem()`.
- `PlaybackQueue`: the order rules.
  - Previous restarts the song if more than 3 s in.
  - Repeat-one repeats only when a song ends; the Next button still skips.
  - Wrapping around happens only with repeat-all, the same rule as Media3.
  - Turning shuffle on keeps the current song first; turning it off restores the original order.

**Linux (`DesktopPlayer`):**
- wraps `GstPlayer` on a single dedicated thread;
- moves to the next song when one ends, and picks up duration updates while playing (needed for header-less VBR MP3s);
- skips unplayable files and reports the error, stopping if every song fails;
- at the end of the queue, stops at the start of the last song.

**Android (`androidApp/playback`):**
- **`PlaybackService`** (Media3 `MediaSessionService`, ExoPlayer) handles background playback, the media notification and lock-screen controls, audio focus, and pausing when headphones are unplugged. It keeps the CPU awake while playing (wake lock).
- **Privacy and security hardening:**
  - **Platform diagnostics are turned off.** `setUsePlatformDiagnostics(false)` is on by default in Media3; it sends playback statistics to Android's MediaMetrics service, which Google documents it may collect.
  - **Only trusted callers may connect:** this app, the system (notification, lock screen, Bluetooth) and apps the user allowed as media controllers. All others are rejected.
  - **Songs sent to the service are accepted only as local `content://` or `file://` addresses.**
  - **The `ACCESS_NETWORK_STATE` permission that Media3 adds is removed.** We never stream, and Media3's bytecode catches the resulting `SecurityException`.
- `AndroidPlayer`: the app-side `MusicPlayer`, a Media3 controller.
  - A seek made while a song is still loading is held and applied once seeking is possible; Media3 would otherwise silently drop it.
  - Shuffle uses the same current-song-first order as desktop.

**Manifest:** added the `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `WAKE_LOCK` permissions, plus the playback service. The service is exported so the system's media controls can reach it; the service itself filters callers. **Still no `INTERNET` permission.**

**`GstPlayer` fix:** GStreamer's filtered bus read discards non-matching messages, so the old error check could throw away the end-of-song message. End and error messages are now read together with one filter. The message type is read at struct offset 64, which was checked against the installed GStreamer headers with a compiled probe run in the sandbox. Stale flags are cleared on load and seek.

**Dependencies** (new, vetted):
- **`androidx.media3` 1.11.1** (`media3-exoplayer`, `media3-session`): the latest stable (2026-09-10), Apache-2.0, with no published security advisories.
  - **Static scan of all 8 Media3 archives plus Guava 33.3.1-android** (nothing executed):
    - no telemetry or analytics SDKs;
    - network classes exist only for HTTP/UDP streaming and live-stream time sync, which this app never uses and couldn't use without the internet permission;
    - all hard-coded web addresses are documentation links or format identifiers.
- **Test only, not shipped:** `androidx.test:runner` 1.7.0, `androidx.test.ext:junit` 1.3.0.
- No existing dependency versions changed.

**Version control:** prior copies stored before editing:
- `version-control/root/2026-10-29_1906/libs.versions.toml`
- `version-control/androidApp/2026-10-29_1906/build.gradle.kts`
- `version-control/androidApp/2026-10-29_1906/AndroidManifest.xml`
- `version-control/shared/2026-10-29_1919/GstPlayer.kt`

**Docs:** README layout and commands updated.

## Verification

- All modules compile, including both Android APKs. The merged manifest has no `INTERNET` or `ACCESS_NETWORK_STATE` permission.
- **`:shared:jvmTest`: 36/36 pass** in the Bubblewrap sandbox (read-only system, repo hidden, no network). The 13 new tests are:
  - **6 queue-rule tests;**
  - **7 desktop player tests** with real GStreamer (silent `fakesink`):
    - plays through and stops at the end;
    - pause, seek, next and previous;
    - repeat-one and repeat-all;
    - broken files skipped;
    - every song broken;
    - shuffle.
- **Android: 5/5 instrumented tests pass, 3 full runs in a row,** on an Android 16 (API 36) emulator, plain AOSP image with no Google services:
  - every format (MP3, FLAC, OGG, Opus) plays through, with correct durations and auto-advance;
  - pause, seek, next and previous, including a seek sent while a song is still loading;
  - repeat-one and repeat-all;
  - broken file skipped;
  - shuffle.
- **The emulator ran inside the same kind of sandbox:** repo and home hidden, no network (the virtual phone had no internet either), with only `/dev/kvm` passed in.
  - Tests were installed and run with `adb` and `am instrument`.
  - The system Bluetooth service connected to the session and was accepted as a trusted controller.

## Impacts

- **Machine setup (outside the repo; PM approved):**
  - `acl` package installed, with read/write on `/dev/kvm` granted only to the `codespace` user (resets when the Codespace restarts);
  - Android emulator 37.2.12 and the `android-36;default;x86_64` system image installed into `~/android-sdk`;
  - virtual phone `recorddisc36` created in `~/android-avd`.
- Nothing calls the player yet. The Now Playing / Home UI comes in part 3.
- On Android 13 and newer, the media notification doesn't need the notification permission (Android exempts media sessions). Asking for that permission, if ever needed, is left to the UI work.
- `./gradlew :androidApp:connectedDebugAndroidTest` (README) is the standard Gradle route, but the tests here were run with `adb`/`am instrument` inside the sandbox, so that exact command is untested.
- No environment variables, secrets or PII. No network access added.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User)
