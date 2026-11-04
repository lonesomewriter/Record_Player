# Playlist Shuffle Play mockup and lyrics-selection issue

- **Time:** 2026-11-01 09:51 UTC
- **Details:** Updated the existing Android and desktop HTML concept mockups with a secondary “Shuffle Play” action next to “Play” on playlist detail. Wired each prototype action to start a random track from that playlist and enable the player shuffle state. Began investigating the reported Android touch long-press selection issue in timed lyric lines; no application source has been changed for either request.
- **Impact:** Mockup-only visual/prototype change pending user approval before implementing the app UI. Lyrics editor selection behavior remains unchanged pending reproduction and diagnosis.
- **Approved By:** User (requested the existing HTML mockup be used and selected both Android and desktop playlist detail mockups)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Implement playlist Shuffle Play and restore lyric text selection

- **Time:** 2026-11-01 10:04 UTC
- **Details:** Backed up `Playlists.kt`, `LyricsEditor.kt`, the desktop Compose UI test, and the previous debug APK under `version-control/shared/2026-11-01_0952/` and `version-control/androidApp/2026-11-01_0952/`. Added “Shuffle Play” to playlist details on Android and desktop, wired to the existing `AppModel.playPlaylist(id, shuffle = true)` behavior, and kept it disabled for empty playlists. For timed lyric lines, removed the clickable gesture handler from the entire row and instead updates the selected line when its text field receives focus, leaving long-press gestures available to Android text selection.
- **Impact:** Users can start a playlist in shuffle mode from its detail page. Lyric line text fields should support Android's native long-press selection actions without the row's click gesture interfering.
- **Verification:** Focused Compose tests for playlist Shuffle Play and lyrics-editor playback/repeat restoration passed. Android app and instrumentation Kotlin compilation passed, `:androidApp:assembleDebug` succeeded, and `git diff --check` passed. The new APK SHA-256 is `f82e3299d6266396a07171f22e487a591b1e4450a6b4d07702c60462118fdcdd`; the prior APK backup SHA-256 is `8515404a1f2500426134dc8ec26e4f6a44dae431c6ce7ce4d10f9e8cbe1da797`. Touch selection is not runtime-verified because no Android device/emulator is connected.
- **Approved By:** User (approved the Android and desktop mockups and asked to proceed with the Android lyrics-selection fix)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Mock up expandable lyric line

- **Time:** 2026-11-01 10:07 UTC
- **Details:** Updated the Android HTML mockup's timed lyric editor to use a one-line textarea that wraps and grows vertically as text exceeds the visible line width. Added a long sample lyric to make the expanded state visible. The mockup prevents newline insertion so the text remains one timed lyric line.
- **Impact:** Mockup only. Application UI remains unchanged pending approval of the expanded-line presentation.
- **Approved By:** User (requested the lyric-line height expansion idea be shown in the mockup before app implementation)
- **Performed By:** Copilot (AI assistant using Copilot SDK)

## Implement expandable lyric line and restore row selection

- **Time:** 2026-11-01 10:08 UTC
- **Details:** Backed up the pre-change lyrics editor and debug APK under `version-control/shared/2026-11-01_1008/` and `version-control/androidApp/2026-11-01_1008/`. Changed timed lyric text fields to wrap and expand vertically as their text exceeds the available width, while converting typed line breaks to spaces so each timed row remains one line. Restored the row-level click handler so tapping a row selects it; timestamp taps still select the row and jump playback to that time. The already-approved Shuffle Play action remains part of this app build and starts the playlist through `playPlaylist(..., shuffle = true)`.
- **Impact:** Long lyric text can wrap within an expanding field; the user can again select a lyric by tapping anywhere in its row. Playlist details retain the Shuffle Play option.
- **Verification:** Focused Compose tests for playlist Shuffle Play and lyrics-editor repeat/playback behavior passed. Android app and instrumentation Kotlin compilation passed, `:androidApp:assembleDebug` succeeded, and `git diff --check` passed. The rebuilt APK SHA-256 is `995bf2407fe938511750d1a4fac34bcea7d47deb79ef26ed8a8c79fe7e137b0f`; the prior APK backup SHA-256 is `f82e3299d6266396a07171f22e487a591b1e4450a6b4d07702c60462118fdcdd`. Android touch-selection behavior requires device confirmation; no device/emulator is connected.
- **Approved By:** User (approved the expanded-line mockup and requested restoring the row click handler; Shuffle Play was separately approved)
- **Performed By:** Copilot (AI assistant using Copilot SDK)
