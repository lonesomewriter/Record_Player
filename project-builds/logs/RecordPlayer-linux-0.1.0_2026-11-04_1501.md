# Build summary: RecordPlayer-linux-0.1.0_2026-11-04_1501

| | |
|---|---|
| Date | 2026-11-04 15:01 UTC |
| Build type | dev |
| Platform | linux |
| App version | 0.1.0 |
| Result | **FAILED** |
| Output folder | none (failed builds are not filed) |
| Git commit | 1bc9682 (with uncommitted changes) |

## Tools

- Gradle 9.7.1
- JDK: openjdk version "25.0.4.1" 2026-08-18 LTS
- Kotlin 2.4.21
- Compose Multiplatform 1.12.2
- Android Gradle Plugin 9.4.0

## Checks

| | Check | Detail |
|---|---|---|
| ✗ | Gradle :desktopApp:packageDeb | failed; last lines: BUILD FAILED in 9s 16 actionable tasks: 3 executed, 13 up-to-date Configuration cache entry stored.  |
| ✗ | Package produced | no .deb at desktopApp/build/compose/binaries/main/deb/record-player_0.1.0_amd64.deb |

## Mockup changes not yet in the app (confirmed before building)

- #1 Page slides go opposite to the finger; the old page slides out; system Back slides like a left swipe [Android]
- #2 Tapping a song only plays it [Android]
- #3 ⋮ → View Details: Now Playing for the playing song, otherwise the new Song details screen (record-sleeve art) [Android, Desktop]
- #3b Album, artist and playlist Play / Shuffle buttons only play [Android]
- #4 One synced ⋮ song menu everywhere; playlist rows add a divider + Remove from playlist [Android, Desktop]
- #5 Hold-and-drag playlist reordering (Move up / Move down removed) [Android, Desktop]
- #6 Now Playing returns to where the song was last chosen (playlist, album, artist, Library) [Android]
- #7 Accent colour fades instead of snapping (and no Dynamic colour flash between songs) [Android, Desktop]
- #8 Pause when muted (on) / Resume when unmuted (off, only if muting paused it); desktop slider at 0 counts [Android, Desktop]
- #9 Desktop "Use system accent" (off by default) follows the desktop's accent colour [Desktop]
