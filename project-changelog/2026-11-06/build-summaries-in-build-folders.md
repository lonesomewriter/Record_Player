# Build summaries now go in each build's folder

- **Time:** 2026-11-06 ~14:06–14:20 UTC
- **Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
- **Approved By:** Project manager.
  - Their guideline change in `project-guidelines/linux_packaging.md`: "A build summary must be logged in the same folder the application build went into".
  - Their choices: move the old logs too, and give a failed build its folder anyway.

## Changes
- **`scripts/build.sh`:** every build writes `build-summary.md` into its own build folder.
  - A failed build still gets a folder holding its summary; the "Output folder" row says no package was filed.
  - The `project-builds/logs/` folder is no longer used.
  - Prior version: `version-control/scripts/2026-11-06_1406/build.sh`.
- **Existing summaries:** moved with `git mv` (history kept). Each `project-builds/logs/RecordPlayer-<platform>-<version>_<stamp>.md` became `project-builds/dev-builds/RecordPlayer-<platform>-<version>_<stamp>/build-summary.md` (32 files, all dev builds).
- **The two 2026-11-02 summaries** from before `build.sh` existed (`android_build_summary.txt`, `desktop_build_summary.txt`) moved to `dev-builds/RecordPlayer-android-stable-dev-01_2026-11-02/` and `dev-builds/RecordPlayer-linux-0.1.0_2026-11-02/` as `build-summary.txt`. Their loose package files (gitignored, local only) moved in beside them.
- **`.gitignore`:** comment updated; no rule change. Packages stay local and summaries are tracked.
- **Notes:** `project-notes/commands/building.md` updated, including a fix: the guideline file it named was the old `build_packaging.md`, now `linux_packaging.md`.

## Check
- `scripts/build.sh dev --android` passed every check and wrote `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-06_1406/build-summary.md` beside the APK.
- The failed-build path (folder plus summary only) wasn't triggered here; it uses the same folder function as successful builds.

## Impacts
Older changelogs still name `project-builds/logs/...` paths; those files are now in the build folders, as listed above.
