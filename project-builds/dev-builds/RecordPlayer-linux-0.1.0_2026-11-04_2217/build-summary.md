# Build summary: RecordPlayer-linux-0.1.0_2026-11-04_2217

| | |
|---|---|
| Date | 2026-11-04 22:20 UTC |
| Build type | dev |
| Platform | linux |
| App version | 0.1.0 |
| Result | **SUCCESS** |
| Output folder | project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-04_2217 |
| Git commit | e6add80 (with uncommitted changes) |

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
| ✓ | Menu entry paths | Exec /opt/record-player/bin/record-player, Icon /opt/record-player/lib/record-player.png (both shipped) |
| ✓ | Open with | lists audio types; Exec passes the chosen file (%F) |
| ✓ | desktop-file-validate | no errors |
| ✓ | Bundled Java runtime | Java 25.0.4.1 (libjvm.so, modules) |
| ✓ | Packaged app starts | ran from its bundled runtime up to opening its window (no screen here) |
| ✓ | Known error: kotlin-stdlib bundled | kotlin-stdlib-2.4.21-16295f9a23d9d5828a2ac2385e0ded0.jar |
| ✓ | kotlinx-coroutines bundled | kotlinx-coroutines-core-jvm-1.11.0-41a5e9a2f2c9affb9d9ab254be5a984f.jar |
| ✓ | Launcher classpath complete | 61 jars, all present |
| ✓ | Classes load from the package | CoroutineContext$Element, SupervisorKt, MainKt |

## Files

- record-player_0.1.0_amd64.deb (62M, SHA-256 ca93e4747df965b0989567180bd540e08cca0ec2e0c045248aa3f003bcaa84fd)

## Mockup changes not yet in the app (confirmed before building)

- None
