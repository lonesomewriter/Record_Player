# Build summary: RecordPlayer-linux-0.1.0_2026-11-06_0005

| | |
|---|---|
| Date | 2026-11-06 00:11 UTC |
| Build type | dev |
| Platform | linux |
| App version | 0.1.0 |
| Result | **SUCCESS** |
| Output folder | project-builds/dev-builds/RecordPlayer-linux-0.1.0_2026-11-06_0005 |
| Git commit | e988c36 (with uncommitted changes) |

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
| ✓ | Bundled Java runtime | Java 25.0.4.1 (libjvm.so, modules) |
| ✓ | Packaged app starts | ran from its bundled runtime up to opening its window (no screen here) |
| ✓ | Known error: kotlin-stdlib bundled | kotlin-stdlib-2.4.21-16295f9a23d9d5828a2ac2385e0ded0.jar |
| ✓ | kotlinx-coroutines bundled | kotlinx-coroutines-core-jvm-1.11.0-41a5e9a2f2c9affb9d9ab254be5a984f.jar |
| ✓ | Launcher classpath complete | 61 jars, all present |
| ✓ | Classes load from the package | CoroutineContext$Element, SupervisorKt, MainKt |

## Files

- record-player_0.1.0_amd64.deb (62M, SHA-256 f72185c8612819869b4a320b52a5afacdcd8abc95c518aba3d4b66be0891bb33)

## Mockup changes not yet in the app (confirmed before building)

- None
