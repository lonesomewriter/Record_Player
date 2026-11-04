# Pause queue progression in the lyrics editor

- **Time:** 2026-11-01 02:53 UTC
- **Details:** Added a player mode that pauses at the end of the current track instead of automatically advancing. The shared lyrics editor enables the mode while composed and restores normal progression when disposed. Desktop playback suppresses gapless handoff in this mode; Android forwards the setting through a trusted Media3 session command to `ExoPlayer.setPauseAtEndOfMediaItems`. Added desktop and Android instrumentation regression tests confirming queue position is preserved and manual Next remains available. Prior source and test versions are in `version-control/shared/2026-11-01_0243/` and `version-control/androidApp/2026-11-01_0243/`.
- **Impact:** Automatic queue advancement pauses only while the lyrics editor is open. Playback elsewhere and manual queue navigation keep their existing behavior. Android runtime behavior was not device-tested because `adb` is unavailable.
- **Validation:** The new desktop regression test passed; the complete `DesktopPlayerTest` class passed on rerun. Android main and instrumentation-test sources compiled. `git diff --check` passed. One earlier playback-class run timed out in the existing desktop seek/restart test; that test passed in isolation and the full class passed afterward. The initial new-test assertion was corrected to reflect that manual Next advances while preserving the player's paused state.
- **Approved By:** User (requested pausing queue progression only in the lyrics editor)
- **Performed By:** Copilot
