# F-Droid store screenshots refreshed

- **Time:** 2026-11-06 14:09 UTC
- **Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
- **Approved By:** Project manager ("Go ahead with the screenshots")

## Changes
- **Re-rendered from the current app** with the screen-test sample library (made-up songs, no personal data):
  `cd project/framework && STORE_SCREENSHOTS=1 ./gradlew :shared:jvmTest --rerun --tests '*storeScreenshots'`.
  - `--rerun` is needed: Gradle doesn't see the environment variable as a change and would otherwise skip the run.
- **Replaced in `fastlane/metadata/android/en-US/images/phoneScreenshots/`:**
  - `1_now-playing.png` and `2_home.png`: the record's still accent shine and backlight.
  - `6_settings.png`: the new Shine brightness and Battery Saver rows.
  - All are 1080×2340.
- **Unchanged** (the new render was identical): `3_library.png`, `4_playlist.png`, `5_convert-to-audio.png`.
- **Prior versions:** `version-control/fastlane/2026-11-06_1409/`.

## Impacts
- Store listing images only.
- The listing text doesn't mention Battery Saver or Shine brightness. That's optional, and left as is.
