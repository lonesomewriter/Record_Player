# iosApp taken out of the Gradle build

**Time:** 2026-11-05, ~16:50 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "Remove the empty iosApp module".

## Details
- The `iosApp` Gradle module declared no Kotlin targets, so every build printed "e: ❌ No Kotlin Targets Declared". That's harmless but alarming, for example to F-Droid reviewers.
- **Correction:** the module isn't empty. `iosApp/` holds about 300 lines of Swift, the iOS screen port from 2026-11-02, built with Xcode and not with Gradle. To keep that work, only `include(":iosApp")` was removed from `settings.gradle.kts`, with a comment explaining why. The folder stays.
- `docs/DEVELOPMENT.md` lists `iosApp/` in the project layout.
- Prior version: `version-control/root/2026-11-05_1647/settings.gradle.kts`.

## Verified
- `:androidApp:compileDebugKotlin` and `:desktopApp:compileKotlin` pass, and the build log has no "No Kotlin Targets" line.

## Impacts
- Builds are quieter and slightly faster; nothing else changes.
- To build `iosApp` with Gradle again, add the include back and give it real targets.
