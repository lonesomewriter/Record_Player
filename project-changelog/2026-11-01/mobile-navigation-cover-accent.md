# Mobile navigation and cover-art accent

- **Time:** 2026-11-01 01:52 UTC
- **Details:** Implemented ordered Android horizontal swipe navigation (Home → Library → Playlists → Settings, with no wrap); retained Now Playing's left-swipe-to-Home and kept editor swipes inert. Dynamic Color now samples a representative chromatic color from the current track's cover art on all Android versions, falls back to the selected accent if art is unavailable, and updates when the current artwork cache is invalidated. Updated the Android setting copy and removed obsolete wallpaper-accent platform APIs. Added focused JVM tests for navigation destinations and accent selection. AppModel JVM test cleanup now waits for scoped coroutines to finish before closing their databases.
- **Impact:** Android phone navigation and Dynamic Color only; desktop accent behavior and the Edit Lyrics appearance remain unchanged.
- **Validation:** Focused navigation/accent JVM tests passed; desktop compilation and Android debug APK assembly passed. After the test-cleanup fix, all 28 `AppModelTest` cases passed, but the full JVM run still ended when Gradle Test Executor 4 exited with code 143. The initial full run had crashed in bundled SQLite native code (`unixShmLock`, exit 134). `git diff --check` passed.
- **Performed By:** Copilot
- **Approved By:** User (approved the swipe-navigation and cover-art accent mockups)
