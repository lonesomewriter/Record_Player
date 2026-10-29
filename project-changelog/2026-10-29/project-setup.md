# Project Setup: Compose Multiplatform Skeleton + Tag/Playback Modules

- **Time:** 2026-10-29 18:13:11 UTC
- **Branch:** `project-setup`
- **Change:** Created the Kotlin / Compose Multiplatform project for Android and Linux desktop, moved the tested Opus tag and GStreamer playback prototypes in with automated tests, and replaced the `.gitignore` entries.

## Details

**Structure** (JetBrains' recommended layout for Android Gradle Plugin 9+):
- `shared/`: a Kotlin Multiplatform library.
  - `commonMain`: placeholder `App()` screen.
  - `jvmShared` (Android + desktop): `tags/OpusTags.kt`.
  - `jvmMain` (desktop): `playback/GstPlayer.kt`.
  - `jvmTest`: tests plus 2-second generated test tones (Opus, OGG, FLAC, MP3; 164 KB total, no copyrighted audio).
- `androidApp/`: `MainActivity`; manifest with **no INTERNET permission** and `allowBackup="false"`.
- `desktopApp/`: `main.kt`; `.deb`/`.rpm` packaging; `--enable-native-access` for the FFM-based GStreamer binding.
- Root: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties` (JDK auto-download disabled), `gradle/libs.versions.toml`.
- Gradle wrapper 9.7.1, with the distribution SHA-256 pinned and the wrapper jar checked against Gradle's published checksum.
- `README.md`: requirements and build commands.

**Versions** (latest stable): Kotlin 2.4.21, Compose Multiplatform 1.12.2, Android Gradle Plugin 9.4.0, Material 3 1.9.0, AndroidX lifecycle 2.11.0, activity-compose 1.13.0, jaudiotagger 3.0.1.

**Android SDK levels:** minSdk 26, **compileSdk 37**, targetSdk 36. The PM approved compiling against 37 because Compose 1.12 / lifecycle 2.11 require it; runtime behaviour stays on API 36.

**App ID:** `dev.recorddisc.player` (temporary placeholder).

**`.gitignore`:** Next.js entries replaced with Gradle, Kotlin, Android, signing-key (`*.jks`, `*.keystore`, `keystore.properties`) and IDE entries. The `/project-notes`, `.env`, `.env*.local`, `*.pem` and `.DS_Store` entries were kept. The PM classes `.gitignore` as configuration, so no prior copy was stored in `/version-control/`.

**Not in the repo (developer machine only):**
- the Android SDK in `~/android-sdk`; the command-line tools were checked against Google's published SHA-256;
- `local.properties`, which is gitignored.

## Verification

- `:androidApp:assembleDebug`, `:desktopApp:compileKotlin`, `:desktopApp:createDistributable` and the test compilation all succeed.
- The merged Android manifest has no network permissions. Its only `uses-permission` is AndroidX's app-private `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- `:shared:jvmTest` ran in a Bubblewrap sandbox: read-only system, repo hidden, no network.
  - **12/12 tests pass:** 7 OpusTags tests and 5 real-time GstPlayer tests (MP3, FLAC, OGG, Opus, and a non-audio error case).
- The packaged desktop app launched in the sandbox on a virtual display and was still running after 12 seconds. Without a GPU, Compose falls back to software rendering, which is expected.

## Impacts

- The repo now builds both apps. The UI is a placeholder until the approved Record Disc mockups are implemented.
- No environment variables were introduced, so there is no `.env_example` yet.
- Every file is new, so nothing was stored in `/version-control/`.
- No secrets, API keys or PII were introduced.
- jaudiotagger is LGPL; distribution builds must honour that licence.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User)
