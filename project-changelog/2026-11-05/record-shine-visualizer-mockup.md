# Record shine: static, pulsing to the beat, with spectrum lines (mockups only)

**Time:** 2026-11-05, ~21:25–21:45 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: asked "the shine on the record disc for both builds. Can it be static? And also act as a sort of visualizer for the current playing track's beat?", then "I want the shine visualizer to mimic noise-lines too, but still keep it's curvature. Like how actual audio visualizers look." Mockups first.

## Details
In both `project-notes/mockups/disc-concept-android.html` and `disc-concept-desktop.html`:
- **Static shine:** the light streak stays still while the record turns under it. It's turned back by the record's own angle (`--spin`), like a lamp reflecting off a turntable.
- **Beat pulse:** while playing, the streak flares on each beat (`--beat`, then a fade of about 0.2 s). At rest it looks as before; a beat makes it up to about 3x brighter. When paused it settles back.
- **Spectrum lines:** fine light lines radiate along the record's curve in the shine's two spots (around 44° and 196° from the top). They jump on each beat, with bass lines highest, flicker slightly, and fall back.
  - They're drawn on a canvas that stays still with the shine.
  - With reduced motion there are no lines and no pulse.
- **Flow and sideways distortion** (project manager: "Can the shine distort horizontally to the beat too? Whilst still keeping the curvature? Also have it flow with the direction the disc is spinning, like how flags blow in the wind."):
  - Each line bends sideways along its own groove, so the band keeps its curve. The tips trail the way the record turns (clockwise), like a flag.
  - A ripple runs along each band in that direction, and the brightness pattern drifts the same way.
  - The beat bends the lines further (trail of 0.8° → about 4° at a peak), and the soft glow is nudged up to 6° clockwise on each beat, then drifts back.
  - The flow moves with the record's speed and stops when the record stops.
  - Prior version (before the flow): `version-control/project-notes/mockups/2026-11-05_2127/`.
- **The glow distorts like the lines** (project manager: "How the noise lines distort is how i need the shine glow to distort."):
  - The soft glow is no longer a CSS gradient that could only rotate. It's drawn on the same canvas as thin slices, with the old brightness profile: the same look at rest and brighter on a beat.
  - Every slice goes through the lines' distortion (`bendAt`): the label part stays straight, and across the grooves it trails clockwise and ripples with the flow.
  - The glow is about 22° wide, so it takes the motion 2.5x larger for its edges to show it.
  - With reduced motion the glow is drawn still and the lines are left out.
  - Shape checked with a temporary 4x-bright copy, outside the mockups.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2133/`.
- **Ripple removed; lines turned sideways to fit the cone** (project manager: "remove the ripple effect from the glow, and turn the noise lines sideways and scale them so they fit the glow's cone size"):
  - The glow keeps only its trailing bend in the spin direction (2° at rest, up to about 10° on a beat). The ripple is gone, from the lines too.
  - The noise lines now lie along the grooves, 22 per cone from the label to the rim, centred on the cone's brightest angle.
  - A line's length is its level: at full level it spans the cone's whole width (34–56° and 178–214°).
  - Bass lines are by the label and jump most; treble lines are by the rim.
  - The lines trail exactly like the glow, so they stay inside it. A slight shimmer drifts outwards with the flow.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2136/`.
- **Bend only while the record turns** (project manager: "have both the glow and the noise lines bend in the direction the wheel is spinning. Only have it bend when the record disc is moving tho, when it's stopped, both should go back to normal"):
  - The bend (clockwise, the way the record turns) is now scaled by the record's own speed (0–1).
  - It eases in as the record spins up and eases out as it coasts down after a pause.
  - It's straight when the record is stopped. Before, a 2° bend stayed while stopped.
  - Checked with the 4x-bright copy: straight before Play, bent clockwise while spinning, straight again about 4 s after Pause.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2137/`.
- **TEMPORARY comparison switch** (project manager asked whether the shine should move with the record or stay still; recommended static, then asked for a switch to compare):
  - "✨ Shine: static / turns with record" sits with the demo buttons (the Android legend; along the top on desktop).
  - It toggles `body.shine-turns`, which lets the shine and lines ride round with the record.
  - Static stays the default. Remove the switch once a choice is made.
  - Checked: static holds at 0° while the record turns; switched on, it turns with the record; switched back, it's still again; no page errors.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2139/`.
- **Shine only on the vinyl** (project manager: "make it so the shine is only on the rim of the disc and not the album cover section of it"):
  - The glow slices now start at the label's edge instead of the centre, so the album art has no streak across it. The noise lines were already outside the label.
  - The label keeps its own soft corner light (`.label::before`), which is part of the label's shading, not the shine.
  - Checked with the 4x-bright copy; no page errors.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2140/`.
- **Decision: static** (project manager: "it's official. We'll keep it static"). The temporary switch was removed from both mockups. Prior version: `version-control/project-notes/mockups/2026-11-05_2142/`.
- **Accent-coloured shine and lines** (project manager: "Can the disc shine and noise lines be the same color as the Accent Color too?"):
  - The glow and the spectrum lines are drawn in the accent colour (`--accent-rgb`, read each frame) instead of white. They follow accent changes live, like the backlight and ring.
  - Checked in amber (default) and teal; no page errors.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2146/`.
- **Lines brighten with the beat** (project manager: "have the noise lines get brighter (but not too bright) the higher the beat too"):
  - Line brightness is multiplied by 0.75 between beats, rising to 1.3 on a full beat, and capped at 0.55 opacity so they never glare.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2147/`.
- **Lines spread farther** (project manager: "Can the noise lines be able to spread out a little farther too?"):
  - A full-level line now reaches 35% past the cone's edges along the groove (`SPREAD = 1.35`); quieter lines stay short.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2148/`.
- **Fix: the label's light turned with the record** (project manager: "a section of the glow effect for the disc that moves with it instead of being static … can you fix it?"):
  - The album label has its own soft light (top-left) and shade (bottom), drawn on `.label::before`. As part of the label, it turned with the record.
  - It's now held still with the same counter-rotation as the shine (`--spin`), while the label art and its "SIDE A" print keep turning.
  - Checked: the record turned 19° → 118° while the label light's net turn stayed 0.00° in both mockups; no page errors.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2149/`.
- **Reverted: the label's light turns with the record again** (project manager: "Nevermind, have the album label's soft light move with the disc"). `.label::before` is back to its previous version in both mockups (same as `version-control/project-notes/mockups/2026-11-05_2149/`). Checked: it turns with the record; no page errors. The shine, lines and backlight stay still.
- **Final: all label light static** (project manager: "can any light associated with the album label be static?"):
  - `.label::before` (the label's soft light and bottom shade) is held still again with the `--spin` counter-rotation. The cover art and "SIDE A" keep turning.
  - The label's inner shadow and border are perfect circles, so they look the same at any angle and need no change.
  - Checked: net turn 0.00° while the record turned up to 118°, in both mockups; no page errors.
  - Prior version: `version-control/project-notes/mockups/2026-11-05_2151/`.
- **Final decision: the label's light turns with the record** (project manager: "keep the album label's light moving with the disc"). `.label::before` is back to its original version in both mockups. The shine, spectrum lines and backlight stay still. Checked: it turns with the record; no page errors.
- **Stand-in beat:** the mockups can't hear the audio, so each sample song gets a tempo of 92–128 BPM from its title. The app would follow the real song.
- Prior versions: `version-control/project-notes/mockups/2026-11-05_2122/`.

## Verified (headless Chromium, both mockups)
- The record turned about 45° while the shine's net turn was 0°.
- The beat pulsed between 0.02 and 0.7, and returned to 0 when paused.
- No page errors.

## For building it into the app (not done; needs the project manager's OK on the mockups)
- **Android:** read the playing audio inside Media3 (a pass-through audio processor) and compute levels and an FFT. Android's `Visualizer` API needs the microphone permission, so it isn't used. No new permission.
- **Desktop:** GStreamer's `spectrum` and `level` elements in the playback pipeline.
- **Both:** draw the lines on the existing disc. Respect reduced motion; possibly a Settings switch.
