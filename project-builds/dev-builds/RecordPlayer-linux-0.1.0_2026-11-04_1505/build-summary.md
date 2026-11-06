# Build summary: RecordPlayer-linux-0.1.0_2026-11-04_1505

| | |
|---|---|
| Date | 2026-11-04 15:06 UTC |
| Build type | dev |
| Platform | linux |
| App version | 0.1.0 |
| Result | **SUCCESS** |
| Output folder | project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-04_1505 |
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
| ✓ | Gradle :desktopApp:packageDeb | succeeded |
| ✓ | Package file name | record-player_0.1.0_amd64.deb (name_version_arch.deb) |
| ✓ | Package fields | Package record-player, Version 0.1.0 |
| ✓ | Declares GStreamer | libgstreamer1.0-0 gstreamer1.0-plugins-base gstreamer1.0-plugins-good |
| ✓ | Bundled Java runtime | Java 25.0.4.1 (libjvm.so, modules) |
| ✓ | Packaged app starts | ran from its bundled runtime up to opening its window (no screen here) |
| ✓ | Known error: kotlin-stdlib bundled | kotlin-stdlib-2.4.21-16295f9a23d9d5828a2ac2385e0ded0.jar |
| ✓ | kotlinx-coroutines bundled | kotlinx-coroutines-core-jvm-1.11.0-41a5e9a2f2c9affb9d9ab254be5a984f.jar |
| ✓ | Launcher classpath complete | 61 jars, all present |
| ✓ | Classes load from the package | CoroutineContext$Element, SupervisorKt, MainKt |

## Files

- record-player_0.1.0_amd64.deb (62M, SHA-256 b4fcc1fc83615bae07533ee85c05519029fbb758c5400cbb864d211725c092b0)

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
