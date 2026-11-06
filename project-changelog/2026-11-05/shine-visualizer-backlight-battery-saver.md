# Record shine visualizer, backlight and Battery Saver (apps)

**Time:** 2026-11-05, ~22:00–22:45 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "let's implement the new features into both apps. Then move forward with the builds". The look was settled in the mockups (`record-shine-visualizer-mockup.md`, `battery-saver-and-backlight-mockup.md`). Plan: `project-notes/shine-visualizer-plan.md`.

## What users get (Android and desktop)
- **Static shine:** the light on the record no longer turns with it. It's in the accent colour and only on the vinyl, never on the label.
- **Beat and spectrum:** while music plays, the shine flares on each beat, and 22 lines per shine spot run along the grooves, following the music's real frequencies (bass by the label, treble by the rim).
  - On strong beats the lines reach past the shine and get brighter, capped so they never glare.
  - The shine and lines bend clockwise while the record turns, more on a beat, and go straight when it stops.
- **Label light:** the label's own soft light still turns with the record (final decision).
- **Backlight:** a soft accent glow around the record. It's off on the first-run disc.
- **Settings → Appearance → Battery Saver:** off by default, manual only. "Turns off all motion: the spinning disc, visualizer and transitions."
  - The record stops at once, and there's no visualizer and no audio analysis.
  - No page or dialog transitions, Up next slide or accent fade.
  - No marquee arc or ring, no blinking dot, no lyrics scrolling animation.
- **Where the visualizer shows:** only on records showing the playing song (phone home and Now Playing, desktop Home). The Song details sleeve and first-run disc have just the still glow.

## How it works
- **New `commonMain/playback/Spectrum.kt`**, one analysis for both platforms:
  - `SpectrumAnalyzer`: a 2048-point FFT about 30 times a second, giving 22 log-spaced bands from 40 Hz to 16 kHz (0 at −70 dB to 1 at −10 dB).
  - `SpectrumHub`: lock-free recent frames, with an optional delay to match what's heard. Analysis only runs while a visible record holds it (and the app is started).
- **New `ui/ShineVisualizer.kt`:** beat detection (bass rising 25% above its ~0.9 s average, at least 0.2, 250 ms apart; a flare fading over 170 ms) and line levels (up at once, down slowly).
- **New `ui/DiscLight.kt`:** the still light layer (glow slices and lines, mockup geometry), `LocalSpectrum`, `LocalBatterySaver` and `rememberStill()` (reduced motion **or** Battery Saver).
- **`ui/Disc.kt`:**
  - The record is split into a turning layer and the still `DiscLight`.
  - The old turning white sweep-gradient shine is removed.
  - The backlight is added.
  - `DiscSpin.speed` is exposed for the bend, and Battery Saver stops the spin at once.
  - The marquee arc and ring and the local light use `rememberStill()`.
- **Every motion check** (Motion.kt, PhoneApp, DesktopApp, SongDetails, LyricsPanel, Theme, Playlists) uses `rememberStill()`. `DiscTheme` takes `batterySaver` and `spectrum` and provides them to the whole app.
- **Settings:** `AppSettings.batterySaver` (stored as `batterySaver`) and a Battery Saver row on phone and desktop.
- **Desktop (`GstPlayer`):**
  - playbin's `audio-filter` is a bin: `tee → leaky queue → audioconvert → audioresample → F32 mono 44.1 kHz → appsink (sync, async=false, drop, max 4)`, plus a pass-through branch.
  - A daemon thread pulls samples only while the hub is in use, and stops on close.
  - If the bin or libgstapp is missing there's no visualizer, and playback is unchanged.
  - `DesktopPlayer.spectrum` provides it.
- **Android (new `playback/AudioTap.kt`):**
  - PlaybackService's ExoPlayer uses a `DefaultRenderersFactory` whose audio sink is Media3's default (same float-output and playback-parameter settings) plus `TeeAudioProcessor`.
  - The tap reads a copy of the 16-bit or float PCM without moving the buffer, only while the visualizer shows.
  - Frames are delayed 150 ms to match the audio output.
  - **No permission:** Android's `Visualizer` API, which needs the microphone permission, isn't used.
  - `AndroidPlayer.spectrum` provides it.
- **`MusicPlayer.spectrum`:** a new optional property (null by default).

## Files
- Prior versions: `version-control/shared/2026-11-05_2201/` (MusicPlayer, Disc, AppSettings, AppModel, Settings, PhoneApp, DesktopApp, Motion, SongDetails, LyricsPanel, Theme, Playlists, GstPlayer, DesktopPlayer) and `version-control/androidApp/2026-11-05_2201/` (AndroidPlayer, PlaybackService).
- New files: Spectrum.kt, ShineVisualizer.kt, DiscLight.kt, AudioTap.kt, SpectrumTest.kt.

## Tests
- **New `SpectrumTest` (5):**
  - tones of 100 Hz, 1 kHz and 5 kHz light their own band
  - about 30 frames a second, and silence gives 0
  - bass hits at 120 BPM give one beat each, and the flare fades
  - the lines' attack and decay
  - quiet (Battery Saver) clears everything
  - the hub's delay and release
  - the desktop player delivers tone frames only while used
- **New `DesktopScreenshotTest.recordVisualizerAndBatterySaver`:**
  - the visible record holds the spectrum; synthetic beats draw the lines (`52-record-visualizer.png`)
  - Battery Saver releases the analysis
  - the Settings row shows (`53-battery-saver-settings.png`) and its switch works
- `GstPlayerTest` (6) still passes, so the tap doesn't disturb playback. `FakePlayer` can carry a spectrum.
- `:androidApp:compileDebugKotlin` and `:shared:compileKotlinJvm` pass.

## Impacts
- No new dependencies or permissions, and no network use. The analysis runs only while a record is on screen in a started app, and never with Battery Saver.
- **Phone check needed:** the beat feel with real music, the 150 ms timing, and battery use.
- Not committed.

## Fix found by the full test run (2026-11-06 00:20 UTC)
- **Time:** 2026-11-06 00:20 UTC
- **Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
- **Approved By:** Project manager (part of "implement the new features into both apps")
- **Problem:** `DesktopPlayerTest` failed 4 of 11 tests: end of queue, gapless, without gapless, and every song failing.
  - Cause: the visualizer's appsink defaults to `wait-on-eos=true`, so it held each song's end until its queued samples were read. Nothing reads them while the visualizer is hidden, so songs never "ended" and broken ones never reported their failure.
- **Fix:** add `wait-on-eos=false` to the tap in `GstPlayer.kt`. The backup is the existing `version-control/shared/2026-11-05_2201/` copy.
- **Result:** DesktopPlayerTest 11/11 and SpectrumTest 5/5.
  - Same run before the fix: DesktopScreenshotTest 13/14 with 1 skipped (store screenshots, gated) and AppModelTest 32/32.

## Dev builds (2026-11-06 00:05 UTC, `scripts/build.sh dev`, all checks passed)
- Android: `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-06_0005/RecordPlayer-android-0.1.0-dev.apk`
- Desktop: `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-06_0005/record-player_0.1.0_amd64.deb`
- Logs: `project-builds/logs/RecordPlayer-{android,linux}-0.1.0_2026-11-06_0005.md`
