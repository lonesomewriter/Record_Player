# Battery Saver and the record backlight (mockups only)

**Time:** 2026-11-05, ~21:40–21:50 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager:
- Battery Saver: "an option in the Settings Page called 'Battery Saver' that toggles all the transitions off … IF that would extend a device's battery lifetime". When told that transitions alone save almost nothing, they chose **all motion** and a **manual switch only** (off by default).
- Backlight: "add subtle backlight to the record disc too? And have the backlight color be the app's accent color".

## Battery Saver (both mockups)
- **Why "all motion":** transitions only run for about 0.3 s when the screen changes. The battery cost while the app is on screen comes from motion that never stops: the spinning disc redrawing about 60 times a second, the shine visualizer (and, in the app, its audio analysis), and on desktop the ring lights and blinking dot. Stopping those lets the device stop redrawing between taps. With the screen off the app draws nothing either way, and playback costs the same.
- **The switch:** Settings → Appearance → **Battery Saver**: "Turns off all motion: the spinning disc, visualizer and transitions". It's off by default and only changes when you change it.
- **When on:**
  - The record stops at once, with no coasting.
  - No beat and no spectrum lines; the glow stays as a still highlight.
  - Every CSS animation and transition stops: pages, pop-ups, switches, the ring lights, the blinking dot.
  - The disc and visualizer code treats it like reduced motion.
- The Android legend lists it.

## Backlight (both mockups)
- A soft rim of the accent colour around the record, as if lit from behind.
  - Android: home and Now Playing discs.
  - Desktop: the Home disc, next to the existing wide accent glow.
- Two layers: about 0.24 strength close to the edge and 0.10 further out. It follows the accent colour live, including its fade.
- It's static, so it costs nothing while the screen is still.

## Verified (headless Chromium)
- **Battery Saver:** the record was spinning, then held its angle once Battery Saver was on. Beat stayed 0, and 0 CSS animations were running. Both mockups, no page errors.
- **Backlight:** close-ups in dark and light themes.
- A slightly square-cornered label edge seen on the Android mockup predates these changes.

Prior versions: `version-control/project-notes/mockups/2026-11-05_2143/` (before Battery Saver) and `2026-11-05_2144/` (before the backlight).

## Rim light (added ~21:55 UTC)
**Approved By:** Project manager: "Around the rim of the disc should be static lighting, same color as the accent. Let me see that first in the mockups before we proceed".
- **What it is:** a thin ring of the accent colour along the record's outer edge, as if a light catches the rim.
  - It's brightest on the shine's two sides (around 44° and 196°) and softer elsewhere (opacity 0.16 → 0.52), so it matches the lighting.
  - It's drawn on the shine canvas, which stays still.
- **Static:** it never moves, pulses or bends; it's the same while spinning, paused, with Battery Saver and with reduced motion. It follows the accent colour live.
- **Separate from the backlight**, which is the soft glow behind the record.
- **Checked:** both mockups; the shine still holds still, Battery Saver still stops all motion; no page errors.
- Prior version: `version-control/project-notes/mockups/2026-11-05_2154/`.
- **Blurred, then removed (~22:00 UTC):** the project manager asked for the rim light to be blurred like the backlight ("a shine on the rims … to give the backlight some illusion of power"). Once it was blurred, they decided: "Let's remove the rim lighting, doesn't fit like i think it would".
  - Both mockups are restored to the version from just before the rim light (`version-control/project-notes/mockups/2026-11-05_2154/`). The only differences were the rim-light lines.
  - The version with the blurred rim is kept in `version-control/project-notes/mockups/2026-11-05_2158/` in case it's wanted later.
