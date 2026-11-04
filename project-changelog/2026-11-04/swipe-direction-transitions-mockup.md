# Swipe transitions follow swipe direction (mockup only)

**Time:** 2026-11-04, 13:24 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager

## Details
- Problem: every page change slid in from the right and moved left, whichever way the user swiped.
- Decision (project manager): transitions follow the finger, and the old page slides out while the new one slides in.
- `project-notes/mockups/disc-concept-android.html`:
  - Added `slideScreens()`. `show(name, direction)` now takes an optional `"left"`/`"right"` direction.
  - Finger right (forward): the new page enters from the left and the old page leaves to the right. Finger left (back): the reverse.
  - The Now Playing left swipe now slides the panel off to the left instead of to the right.
  - Tap navigation (arc buttons, close buttons) passes no direction, so it keeps the original slide-in.
  - Reduced-motion users still get instant switches (the existing `prefers-reduced-motion` rule still applies).
- Prior version stored at `version-control/project-notes/mockups/2026-11-04_1324/disc-concept-android.html`.
- Verified with a headless Chromium swipe simulation. Mid-animation offsets matched the intended directions, and there were no page errors.

## Impacts
- Mockup only. The app (`shared/.../ui/PhoneApp.kt`, `AnimatedContent` `transitionSpec`) has **not** been changed yet and waits on mockup approval.
- No dependencies, environment variables, secrets, or PII involved.

## Revision: slide opposite to the finger
- **Time:** 2026-11-04, 13:27 UTC. **Performed By:** AI-Agent (Anthropic Claude Opus 5.5). **Approved By:** Project manager
- The project manager changed the decision: pages now slide **opposite** to the finger.
  - Finger right (forward): the new page enters from the right and the old page leaves to the left.
  - Finger left (back): the new page enters from the left and the old page leaves to the right.
  - Now Playing finger-left close: the panel slides off to the right again.
- Only the direction values passed by the swipe handler changed; `slideScreens()` is unchanged.
- Prior (finger-following) version stored at `version-control/project-notes/mockups/2026-11-04_1327/disc-concept-android.html`.
- Re-verified with the headless Chromium swipe simulation: directions correct, no page errors.
