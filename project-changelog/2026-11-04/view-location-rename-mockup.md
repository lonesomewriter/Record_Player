# Song menu: "View file location" → "View Location", moved below View Details (mockup only)

**Time:** 2026-11-04, 21:42 UTC
**Performed By:** AI-Agent (Anthropic Claude Opus 5.5, via Claude Code)
**Approved By:** Project manager: "can 'View File Location' be renamed to 'View Location' then moved up to be below 'View Details'"

## Details
- **Project manager's result first:** on the 21:39 dev APK, View file location now opens the song's folder (`android-view-file-location-fix.md` is confirmed working).
- `project-notes/mockups/disc-concept-android.html` and `disc-concept-desktop.html`: the song ⋮ menu item is renamed **View Location** and moved to second place.
  - New order: View Details, View Location, Edit lyrics, Edit metadata, Add to playlist, then (in a playlist) Remove from playlist.
  - The desktop mockup's toast text was renamed to match.
- Where View Details is hidden (on Now Playing / the song's details), View Location becomes the first item.
- Prior versions: `version-control/project-notes/mockups/2026-11-04_2142/`.

## Impacts
- Mockups only. The app keeps the old name and order until the change is ported (`ui/Library.kt` `SongMenu`, shared by Android and desktop).
