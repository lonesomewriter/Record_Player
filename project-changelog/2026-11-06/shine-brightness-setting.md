# Settings → Shine brightness

- **Time:** 2026-11-06 13:20–13:40 UTC
- **Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
- **Approved By:** Project manager.
  - The ask: "Can the accent shine brightness be adjustable in the Settings page?"
  - Their choices: a slider, up to 3×, shine only (not the backlight).
  - Their sign-off: "The brightness mockup looks good too, so we can implement that then rebuild both of them".

## Mockups (gitignored, so not logged in detail)
- Both `disc-concept-android.html` and `disc-concept-desktop.html` have a "Shine brightness" row. It sits in Appearance, before Battery Saver: a slider from Off to Bright with the level shown in the subtitle.
- Prior copies: `version-control/project-notes/mockups/2026-11-06_1323/`.

## App (Android and desktop)
- **`AppSettings`:** `shineLevel` stores 0–300 (% of the default shine), default 100. It's saved as `shineLevel`, and out-of-range values are clamped.
- **`AppModel.setShineLevel`** stores the value.
- **`Theme.kt`:** `DiscTheme(shine =)` provides `LocalShine`.
- **`PhoneApp.kt`, `DesktopApp.kt`:** pass the setting through.
- **`DiscLight.kt`:** scales the shine. At 0 nothing is drawn.
- **`Settings.kt`:** the new `ShineRow` on phone and desktop, in 5% steps.
- **`Controls.kt`:** `ThinSlider` gets an optional `onDrag`, so the percentage updates while dragging. Existing sliders are unchanged.
- **Prior versions:** `version-control/shared/2026-11-06_1330/`.
- **Docs updated:** the feature and programming docs, and `mockup-port-status.md` row 18 (done).

## Tests
- `DesktopScreenshotTest.recordShineAndBatterySaver` now also checks:
  - the row and its 100% subtitle
  - setting 250% shows "250%"
  - 999 is clamped to 300
  - 0 shows "Off"
- DesktopScreenshotTest: 13 passed, 1 skipped (store screenshots, gated).
- AppModelTest: 32/32.
- `:androidApp:compileDebugKotlin` passes.

## Impacts
No new dependencies, permissions or battery cost: the shine is drawn once and doesn't animate. Not committed.

## Dev builds (2026-11-06 13:37 UTC, `scripts/build.sh dev`, all checks passed)
- Android: `project-builds/dev-builds/RecordPlayer-android-0.1.0_2026-11-06_1337/RecordPlayer-android-0.1.0-dev.apk`
- Desktop: `project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-06_1337/record-player_0.1.0_amd64.deb`
