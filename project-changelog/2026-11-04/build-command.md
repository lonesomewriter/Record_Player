# Build command: scripts/build.sh, plus packaging fixes

**Time:** 2026-11-04, 14:57–15:06 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager. Plan approved 2026-11-04 (`project-notes/mockup-port-and-build-plan.md`, part B).

## Details
- **New `scripts/build.sh [dev|release] [--android] [--desktop]`.** It is a terminal command, as required by `project-notes/prompts/application_build.md`.
  - **Asks for dev or release** if no type is given.
  - **Preflight:** JDK, Android SDK + build-tools, `dpkg-deb` / `fakeroot`; the Android and desktop versions must match.
  - **Lists pending mockup changes** from `project-notes/mockup-port-status.md` and asks "Build anyway?" before building.
  - **Android:** builds the APK, then checks the package name, version, `classes.dex` and the `apksigner` signature. A release build must not use the debug key.
  - **Desktop:** builds the `.deb`, then checks:
    - the file name and package fields
    - that **GStreamer is declared**
    - the bundled runtime (libjvm.so, modules, version)
    - the **known kotlin-stdlib error**: the jar is bundled, every launcher classpath jar is present, and the failing classes load from the package
    - a **headless start of the real packaged launcher** (it must reach its UI)
  - **Files results** in `project-builds/<dev|release>-builds/RecordPlayer-<platform>-<version>_<YYYY-MM-DD_HHMM>/`. It never overwrites or deletes old builds, and failed platforms aren't filed.
  - **Writes a summary** to `project-builds/logs/RecordPlayer-<platform>-<version>_<stamp>.md`: tools, every check, size + SHA-256, git commit, and the confirmed pending-mockup list.
  - **Release signing** reads `RECORDPLAYER_*` from `.env` as plain text (never sourced, never printed). It refuses a keystore inside the repo.
- **`desktopApp/build.gradle.kts`:**
  - `.rpm` removed (project manager decision).
  - The `.deb` now declares `libgstreamer1.0-0, gstreamer1.0-plugins-base, gstreamer1.0-plugins-good`, via jpackage `--linux-package-deps` on the `.deb` tasks.
  - The list has no spaces, because Compose writes jpackage arguments unquoted.
- **`androidApp/build.gradle.kts`:** an optional `release` signing config from the `RECORDPLAYER_*` environment variables.
- **New `.env_example`:** the four empty, annotated `RECORDPLAYER_*` keys, plus keystore setup steps. `.env` was already gitignored.
- **New docs** (gitignored notes): `project-notes/commands/building.md`, and `project-notes/mockup-port-status.md` (the tracking table the build reads).
- **Prior versions stored:** `version-control/desktopApp/2026-11-04_1457/build.gradle.kts`, `version-control/androidApp/2026-11-04_1457/build.gradle.kts`.

## Verification
- **First runs** found three problems, each fixed and logged:
  - The script's own checks failed falsely: `head` / `grep -q` cut pipes short under `pipefail`.
  - The dependency list was split by its spaces.
  - jpackage runtimes have no `bin/java`, so that check was replaced by the headless launcher start.
  - The two failed runs left FAILED logs (`..._1501.md`), kept as a record.
- **Final run `scripts/build.sh dev`:** every check passed on both platforms.
  - `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-04_1502/`
  - `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-04_1505/`
- **Negative test:** with `kotlin-stdlib` removed from the classpath, the class-load check fails with exactly the known `ClassNotFoundException: kotlin.coroutines.CoroutineContext$Element`.
- **Release refusal tested:** without `.env`, `release` stops with setup instructions.

## Impacts
- The `.deb` now pulls in GStreamer on install, so playback works on machines that lacked it.
- No `.rpm` is produced.
- Release APKs need the project manager's local keystore (`.env`); dev builds are unchanged.
- No secrets, PII or new dependencies were added. Package binaries stay gitignored; only logs are tracked.
