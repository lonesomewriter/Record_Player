# "Open with" from the file explorer (mockup only)

**Time:** 2026-11-04, 21:47–22:05 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: asked for Record Player to appear in the file explorer's "Open With" list, chose the behaviour (below), and said "start on the Mockups"

## Decisions (project manager, 2026-11-04)
- **Platforms:** both Android and the Linux desktop.
- **A file that's in the library** plays as a library song, as if it had been tapped in Library: the library is the queue and every ⋮ action works.
- **Any other file plays once and isn't added.**
  - It plays on its own and is marked **"Opened from Files · not in your library"**.
  - Edit lyrics, Edit metadata, Add to playlist and View Location are off, with a note explaining why.
  - It isn't restored by Resume on start.
- Record Player is **listed** in Open With and never made the default player.

## Details
Prior versions: `version-control/project-notes/mockups/2026-11-04_2147/`.

`project-notes/mockups/disc-concept-android.html`:
- New legend buttons, "Open a library song from Files" and "Open an outside file from Files", show Android's chooser (system UI, for context) with Record Player in it.
- A library song plays from Library, so leaving Now Playing returns to Library.
- An outside file ("Demo Take 3.m4a", tags read from the file):
  - Now Playing shows the "Opened from Files" label, with no album line when the file has none.
  - Lyrics "Edit" links are hidden.
  - In the ⋮ menu, View Location, Edit lyrics, Edit metadata and Add to playlist are off, with a note.
  - It never appears in the Library or playlist lists.

`project-notes/mockups/disc-concept-desktop.html`:
- New top buttons, "Open library song" and "Open outside file", show the file manager's Open With dialog (system UI).
- An outside file on Home:
  - an "Opened from Files · not in your library" chip and an M4A chip
  - Edit metadata is off, with a note
  - Track details → Source shows "Opened from Files"
  - Up next shows only that file ("1 track · opened from Files")
- The toast "Opened in this window" shows the hand-over to an already open window.
- Also added the missing `i-folder` icon. Up next rows no longer end in " · " when a song has no album.

## Verification
- Headless Chromium, both mockups, no page errors:
  - Outside file: the label shows, the four menu items are off, and the library lists stay at 4 songs.
  - Library song: no label, and Now Playing returns to Library (Android) / the queue shows 4 tracks (desktop).

## Impacts
- Mockups only. The app is unchanged until the project manager approves the mockups.
