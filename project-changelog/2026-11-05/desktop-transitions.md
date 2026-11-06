# Desktop transitions (and Up next sliding in and out)

**Time:** 2026-11-05, ~21:00–21:30 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "the Desktop application is missing some transitions", then "the transitions look nice! And yes, the Up next panel should slide in and out".

## Mockup
- The desktop mockup already had transitions for pages, dialogs and toasts; the app never got them. Up next was instant in both.
- `project-notes/mockups/disc-concept-desktop.html`: Up next now slides out to the right while its column closes (0.28 s), and back.
  - The panel keeps its full width as it goes, so its text doesn't squash.
  - It's off with reduced motion.
  - Checked in headless Chromium with no page errors.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2105/`.

## App (desktop only; nothing on the phone; off when the system asks for reduced motion)
- **New `ui/Motion.kt`:** `LocalDesktopMotion`, which only the desktop layout turns on, plus:
  - `pageEnter(key)`: fades in while rising 8 dp over 0.28 s when the page changes
  - `popIn()`: fades in while rising 10 dp and growing from 98.5% over 0.22 s
  - `scrimIn()`: the dimmed background fades in over 0.2 s
- **`DesktopApp.kt`:**
  - The motion switch is on for the whole desktop window.
  - Switching sections (Home, Library, Playlists, Inbox, Settings), a playlist or Song details fades in while rising.
  - Library album and artist pages do the same.
  - New `QueueSlide`: Up next slides in and out (it opens and closes towards the right, with a fade).
- **`Playlists.kt`:** opening a playlist or going back to the list fades in while rising.
- **Dialogs and overlays:**
  - All AlertDialogs (in `Playlists.kt` and `ConvertToAudio.kt`) pop in.
  - The song editor, lyrics editor (and its unsaved-changes prompt) and accent picker: the background fades in and the card pops in.
- **`Controls.kt`:** toasts rise slightly as they fade in, as in both mockups. This is shared, so the phone gets it too, matching the Android mockup.
- Prior versions: `version-control/shared/2026-11-05_2107/` (DesktopApp, Playlists, ConvertToAudio, TagEditor, LyricsEditor, Settings, Controls).
- **Not added:** fading dialogs out on close (the mockup does this over 0.2 s). Closing stays instant.

## Tests
- **New** `DesktopScreenshotTest.desktopTransitionsRunAndSettle` saves midway frames and checks the end states:
  - `49-transition-section-midway.png`: Library half-faded and slightly low.
  - `51-transition-queue-midway.png`: Up next fading and sliding off.
  - It checks that Library arrives, Up next goes away, and Up next comes back.
- `:desktopApp:compileKotlin` and `:androidApp:compileDebugKotlin` pass.


## Build
`scripts/build.sh dev --desktop` passed all checks, including the fixed menu-entry check: `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-05_2128/`.
