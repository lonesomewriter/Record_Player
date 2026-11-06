# Visualizer removed; still accent shine kept

- **Time:** 2026-11-06 13:00 UTC
- **Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
- **Approved By:** Project manager. Their words: "let's get rid of the visualizer as a whole, and just add the shine back". Their answers to the follow-up questions:
  - shine: the still accent shine (as in the mockups)
  - backlight and Battery Saver: keep both

## What changed for the user (both apps)
- **Gone:** the beat flare, the spectrum lines, the bend while turning, and the audio analysis behind them.
- **Kept:**
  - the still accent shine on the vinyl only, at its resting brightness; the record turns under it
  - the label light that turns with the record
  - the accent backlight
  - Settings → Battery Saver, whose subtitle is now "Turns off all motion: the spinning disc and transitions"

## Details
- **Restored to the last commit (e988c36):** each held only visualizer changes.
  - `MusicPlayer.kt`
  - `GstPlayer.kt` (the GStreamer tap is gone, so no `audio-filter`)
  - `DesktopPlayer.kt`
  - `AndroidPlayer.kt`
  - `PlaybackService.kt` (ExoPlayer's default renderers again)
  - `TestFakes.kt`
- **Deleted:** `Spectrum.kt`, `ShineVisualizer.kt`, `AudioTap.kt`, `SpectrumTest.kt`.
- **`DiscLight.kt`:** only draws the two still accent spots. It no longer needs an animation loop, a lifecycle check or a player.
- **`Disc.kt`:** the `visualizer` parameter and `DiscSpin.speed` are removed.
- **`Theme.kt` / `PhoneApp.kt` / `DesktopApp.kt`:** no spectrum is passed down.
- **`SongDetails.kt`:** the call is simplified.
- **`Settings.kt` / `AppSettings.kt`:** the wording is updated.
- **Screen test:** `recordVisualizerAndBatterySaver` became `recordShineAndBatterySaver`.
  - The record turns while playing.
  - With Battery Saver on, two frames 0.5 s apart are identical.
  - The Settings row and switch still work.
  - It saves `52-record-shine.png` and `53-battery-saver-settings.png`.
- **Prior versions:** `version-control/shared/2026-11-06_1300/` and `version-control/androidApp/2026-11-06_1300/` (including the deleted files).
- **Docs updated:**
  - the feature and programming docs
  - `mockup-port-status.md` row 15
  - `shine-visualizer-plan.md` status

## Impacts
- No playback code differs from the last release state. The desktop end-of-song problem found yesterday can't occur, because the tap no longer exists.
- No dependencies or permissions change.
- Not committed.

## Checks (2026-11-06)
- `:androidApp:compileDebugKotlin` and `:shared` compile.
- DesktopScreenshotTest: 13 passed, 1 skipped (store screenshots, gated). This includes `recordShineAndBatterySaver`.
- GstPlayerTest: 6/6.
- DesktopPlayerTest: 10/11 in the combined run.
  - `stopsWhenEverySongFails` timed out while the screen tests were loading the machine.
  - Rerun alone it passed 11/11 twice, so it's timing noise; the player code is unchanged from the last commit.
- Both mockups were updated to the still shine and render with no script errors. Prior copies are in `version-control/project-notes/mockups/2026-11-06_1303/`.

## Dev builds (2026-11-06 13:14 UTC, `scripts/build.sh dev`, all checks passed)
Approved By: Project manager ("Build the android and desktop builds with the 'still-shine'"). The beat-matched visualizer is shelved; notes are in `project-notes/shine-visualizer-plan.md`.
- Android: `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-06_1314/RecordPlayer-android-0.1.0-dev.apk`
- Desktop: `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-06_1314/record-player_0.1.0_amd64.deb`
- Logs: `project-builds/logs/RecordPlayer-{android,linux}-0.1.0_2026-11-06_1314.md`
