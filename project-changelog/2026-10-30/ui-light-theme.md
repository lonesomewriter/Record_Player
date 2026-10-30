# Record Disc UI: Light theme and Theme setting (Part 3, final step)

- **Time:** 2026-10-30 14:45 UTC to 15:10 UTC
- **Change:** Settings → Theme now works on both platforms (System / Dark / Light), with a warm-paper light theme built to the approved mockup. The Android Now Playing screen no longer has an "Edit metadata" button under the controls; Edit metadata stays in its ⋮ menu.
- **PM decisions:**
  - The light theme follows the approved "warm paper" mockup (the Theme switch in `disc-concept-android.html` / `disc-concept-desktop.html`): cream surfaces, brown-black text, the disc stays black vinyl.
  - Default theme: **Dark**. Installs that never picked a theme stay Dark.
  - On Linux, **System** reads the desktop's dark/light setting through `gdbus`, falling back to Dark.
  - Remove the Edit metadata button from Android Now Playing, to match the updated mockup. The Lyrics button stays.

## Details

**Setting (`app/AppSettings.kt`, `app/AppModel.kt`):**
- New `ThemeMode` (System, Dark, Light) and an `AppSettings.theme` field, stored as the `theme` row in the existing settings table.
- Missing or unknown values fall back to Dark. `AppModel.setTheme()` saves it.

**Colours (`ui/Theme.kt`):**
- `DiscColors.LIGHT` holds the light palette from the mockup:
  - backgrounds `#ECE3D6`, `#F3ECE1`, `#F7F1E7`, `#FFFAF2`;
  - text `#241A12`, muted `#6B5C4E`.
  - Text is at least 7:1 and muted text at least 4.5:1 on every background (checked by a test).
- New theme colours replace values that were hard-coded for dark:
  - `tint`, for faint overlays: white on dark, warm brown on light;
  - the desktop sidebar, player bar and queue column backgrounds;
  - the dialog scrim (the dimmed background behind dialogs).
- In light, the accent colour used for text is darkened step by step until it reaches 4.5:1 on both light panels. Filled accent buttons keep the accent itself.
- `DiscTheme(accent, light)` builds a light or dark Material colour scheme with the same mapping. The dark theme's colours are unchanged (checked by a test).

**Where the theme comes from:**
- `AppSettings.useLightTheme()`: Light means light, Dark means dark, and System asks the platform.
- **Android:** System follows the phone's dark mode. The status-bar and navigation-bar icons switch between light and dark to stay readable (`SystemBarsEffect`).
- **Linux (`ui/LinuxColorScheme.kt`, new):**
  - Runs `/usr/bin/gdbus` to read `org.freedesktop.appearance color-scheme` from the freedesktop Settings portal (GNOME, KDE and others).
  - **Safety:** fixed arguments (no shell), local D-Bus only, a 2-second timeout and capped output. The process is always ended, and nothing keeps running in the background.
  - **When it reads:** at start and whenever the window gains or loses focus.
  - **Fallback:** if `gdbus` or the portal isn't available, System means Dark.

**Screens:**
- The hard-coded dark colours in `Controls.kt`, `Library.kt`, `PhoneApp.kt`, `DesktopApp.kt` and `Settings.kt` now use the theme colours. The desktop divider lines use the theme's line colour.
- **Unchanged on purpose:**
  - the vinyl disc, label and marquee (`Disc.kt`);
  - the accent picker's colour square and hue bar;
  - the small record logo next to "RECORD DISC" (`FirstRun.kt`), now always black vinyl as in the mockup.
- **Theme chooser (`Settings.kt`):** shows the current mode and lists System, Dark and Light with a tick on the selected one. The SOON tags are gone.
- **Android Now Playing (`PhoneApp.kt`):** the "Edit metadata (SOON)" button under the controls is removed. The ⋮ menu still has Edit lyrics, Edit metadata and Add to playlist.

**Docs:** README notes that `gdbus` (package `libglib2.0-bin`, usually preinstalled) lets System follow the Linux desktop setting.

## Version control

Prior versions are stored as follows:
- `version-control/shared/2026-10-30_1445/`: `AppSettings.kt`, `AppModel.kt`, `Theme.kt`, `Platform.kt`, `Platform.android.kt`, `Platform.jvm.kt`, `PhoneApp.kt`, `DesktopApp.kt`, `Settings.kt`, `Library.kt`, `Controls.kt`, `FirstRun.kt`, `AppModelTest.kt`, `DesktopScreenshotTest.kt`.
- `version-control/androidApp/2026-10-30_1445/`: `MainActivity.kt` (comment only).

New files with no prior version:
- `jvmMain/.../ui/LinuxColorScheme.kt`;
- `jvmTest/.../ui/ThemeTest.kt`.

## Tests

- **Desktop (`:shared:jvmTest`, sandboxed, offline): 73/73 pass** (68 before, plus 5 new), in runs `light1` and `light2` (the second after the logo fix).
  - **Theme setting:** defaults to Dark; Light and System are saved; an unknown value falls back to Dark.
  - **Light contrast:** every preset accent, plus extreme custom colours (yellow, cyan, white, near-black), reads at 4.5:1 or more as text on both light panels. Text and muted text read on every light background.
  - **Dark unchanged:** the dark theme's colours are identical to before.
  - **`gdbus` output parsing:** dark, light, no preference, an error message and empty output.
  - **Screenshots 19–26 (light):**
    - Home, narrow Home, Library and Albums;
    - Settings and narrow Settings;
    - Home with the Mint accent;
    - the custom accent dialog.
    - Reviewed by eye: all text readable, the disc unchanged.
- **Android, on the emulator (sandboxed, no network), run `theme1`, all checks passed:**
  - Settings shows Dark by default. The theme menu lists System, Dark and Light, with no SOON tags.
  - **Light:** picking it turns Settings, Home, Library and Now Playing light, and the status-bar and navigation-bar icons turn dark.
  - **Now Playing:** no Edit metadata button under the controls; the ⋮ menu still has Edit metadata.
  - **Restart:** after a force-stop and restart, Light is kept.
  - **System:** follows the phone's dark mode (`cmd uimode night no/yes`), switching live, with the bar icons to match.
  - No app crashes.
  - **Player tests: `OK (6 tests)`.**
- **Not testable here:** reading the real Linux desktop setting. This Codespace has no desktop session or portal. That path rests on the parsing tests and on the Dark fallback, which applies here.

## Impacts

- **New setting stored locally:** `theme`. No database schema change, since settings are name/value rows.
- **Existing installs look the same** until someone picks Light or System.
- **Linux:** the app may run `/usr/bin/gdbus` (a standard GLib tool) to read the dark/light preference over the local session bus. No network; nothing is sent anywhere.
- **Android permissions unchanged.** No new dependencies, environment variables, secrets or PII. `.gitignore` unchanged.

## Performed By

Claude Code (model: Claude Opus 5.5)

## Approved By

Project Manager (User):
- Approved the light-theme mockup ("Let's keep going") and the implementation plan.
- Chose: Linux System reads the desktop setting; default Dark; remove Edit metadata from Android Now Playing.
