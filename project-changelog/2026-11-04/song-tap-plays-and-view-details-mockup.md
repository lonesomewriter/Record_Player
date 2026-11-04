# Song tap only plays; ⋮ → View Details and song preview screen (mockup only)

**Time:** 2026-11-04, 13:33 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager

## Details
Project manager decisions: tapping a song in any list should only play it, not open Now Playing. A new first ⋮ menu item, **View Details**, opens Now Playing for the song that's playing. For any other song, it opens a separate **preview** screen that shows the art as a vinyl sleeve instead of the record disc.

`project-notes/mockups/disc-concept-android.html`:
- **Song tap:** Library, album, artist and playlist rows now play the song and stay on the list. The mini player shows the new song.
- **⋮ menus:** "View Details" is first in the song menu (with a new `i-info` icon) and in the playlist song menu. It is hidden when the menu is opened from Now Playing or the preview, because you're already on that song's details.
- **New `preview` screen (Song details):**
  - Album art as a square record sleeve with the black record sliding out to the right, a centre label in the album colours, and paper-sheen/texture overlays.
  - Title, artist and album, plus a Year / Genre / Track / Length row.
  - **Play** starts the song and opens Now Playing (swipe-back from there returns to the list the preview was opened from). **Add to playlist** opens the existing picker.
  - A note says which song keeps playing (or stays paused) until Play is pressed.
  - ✕, a ⋮ menu, and swipe-left all return to the screen the preview was opened from, sliding opposite to the finger like other pages.
- Album/artist rows now carry their track index, so their ⋮ menu acts on the right song.
- Legend updated with the new tap and View Details behaviour.
- Prior version stored at `version-control/project-notes/mockups/2026-11-04_1333/disc-concept-android.html`.
- Verified with a headless Chromium run of every flow (tap stays on list, View Details on playing vs. other song, playlist menu, preview Play, swipe back) and screenshots in light and dark themes. No page errors.

## Impacts
- Mockup only. The app (`Library.kt` `onPlayed`, `PhoneApp.kt` song menu) has **not** been changed and waits on mockup approval.
- Open design question: the black record has low contrast against the dark-theme background.
- No dependencies, environment variables, secrets, or PII involved.
