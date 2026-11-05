# README cleanup: summary README, technical notes moved to docs/DEVELOPMENT.md

**Time:** 2026-11-05 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: asked for a less technical README and chose "Move to docs/DEVELOPMENT.md".

## Details
- **`README.md`** is now a short summary:
  - what the app is, its features, privacy, platforms
  - a one-line link for building from source
  - the License section
  - Title "Record Player" (was "Music_Player").
  - Every feature claim was checked against the code: gapless setting, cover editing, widget, the six playable formats.
- **New `docs/DEVELOPMENT.md`** holds the technical sections that were in the README, updated:
  - Contributing, project layout, requirements, common commands, playlist file details, animation settings.
  - **Removed (out of date):**
    - `.rpm` packaging (dropped 2026-11-04)
    - "converting a playlist… out of scope" (now built)
    - the note about the Android import/export walkthrough
  - **Added:**
    - `scripts/build.sh` and release signing through `.env`
    - M4A conversion needing `gstreamer1.0-libav`
    - the optional `desktop-file-validate`
    - the minimum Android version (8.0)

## Impacts
Documentation only. Not committed.
