# Repo restructure for the new Folder Structure rule

**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager. Their answers: keep "Project manager" in logs; move the whole Gradle project; version catalog in dependencies/; mockups private. Then: "Plan is good to go, you can commit and ignore the rest of the mockups". Plan: `project-notes/restructure-plan.md`.

## 1. Before the move (2026-11-06 ~13:45 UTC)
- **implementation.md line 6:** back to "Project manager" for Performed By / Approved By, plus "Never write the user's name, username or email". The project manager's update had changed it to "write down your name", which conflicted with security.md → Anonymity; they chose the old wording. The rest of their update (the Folder Structure section, the removed version-control layout line, the wording fixes) is kept as they wrote it.
- **`.gitignore`:** mockups and their backups are private (`/project/mockups/`, `/version-control/project/mockups/`, `/version-control/project-notes/mockups/`).
  - Mockup backups already committed earlier stay in the public history.
  - The new, uncommitted ones from 2026-11-05/06 are not committed.
- **Commit of the finished work, not pushed:**
  - still accent shine, backlight, Battery Saver, Shine brightness
  - desktop transitions and the build-script fix
  - the changelogs, backups and build logs
  - A privacy scan found no username, email or phone model in the committed files.

## 2. The move (2026-11-06 ~13:46–14:00 UTC)
- **Moved with `git mv` (history follows each file) to `project/framework/`:**
  - the modules `shared/`, `androidApp/`, `desktopApp/`, `iosApp/`
  - Gradle's own files: `gradle/` (wrapper), `gradlew`, `gradlew.bat`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`
  - `web-configs.json`
- `local.properties` (gitignored, this machine's SDK path) moved along.
- **Version catalog:** `gradle/libs.versions.toml` → `project/framework/dependencies/libs.versions.toml`. `settings.gradle.kts` now loads it from there.
  - New `project/framework/dependencies/README.md` lists every dependency with its use, licence and telemetry check.
- **Mockups:** `project-notes/mockups/` → `project/mockups/` (gitignored).
- **Old build caches** at the root (`.gradle/`, `.kotlin/`, `build/`, all gitignored) were deleted.
- **References updated:**
  - **`scripts/build.sh`:** still runs from the repo root; Gradle runs through `project/framework`. The version, APK, .deb, catalog and `local.properties` paths changed. One miss (the `local.properties` read) was found by the first build run and fixed.
  - **Docs:** `docs/DEVELOPMENT.md` (layout, commands, `local.properties`, `web-configs.json`).
  - **Working notes (gitignored):** the programming doc, handoff and HANDOUT (a layout note), the previewing command, and the F-Droid recipe draft (`subdir: project/framework/androidApp`).
  - **Unchanged and still working:** paths that move together (the `androidApp` → `../shared` test assets, `web-configs.json` from the Gradle root, the desktop packaging files).
- **Backups:** `version-control/root/2026-11-06_1346/settings.gradle.kts` and `version-control/scripts/2026-11-06_1346/build.sh`. Moved-only files are kept by git's rename history.

## 3. Checks
- From `project/framework`: `:androidApp:compileDebugKotlin`, `:desktopApp:compileKotlin` and the full `:shared:jvmTest` all pass. That's 30 suites and 185 tests: 0 failed, 1 skipped (store screenshots, gated).
- `scripts/build.sh dev` from the repo root passed all checks:
  - `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-06_1353/`
  - `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-06_1353/`

## Impacts
- **Gradle commands now run from `project/framework`** (`cd project/framework && ./gradlew …`). `scripts/build.sh` is unchanged for whoever runs it.
- **Editors:** open `project/framework` (or the repo root) as the Gradle project.
- **The F-Droid recipe** uses `subdir: project/framework/androidApp`.

## 4. F-Droid-style check after the move (2026-11-06 14:04 UTC)
- **Setup:** a fresh clone of commit `c8ebb60` with JDK 21, no `local.properties`, no `.env` and no signing variables. The SDK came from `ANDROID_HOME`.
- **Command:** `cd project/framework && ./gradlew :androidApp:assembleRelease`. It gives `androidApp-release-unsigned.apk`: `dev.recorddisc.player`, versionCode 1, versionName 0.1.0.
- **Permissions:** foreground service (playback, data sync), notifications, wake lock. **No internet.**
- The recipe draft's `subdir: project/framework/androidApp` matches.
