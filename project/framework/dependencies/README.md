# Dependencies

Gradle downloads every library when the app is built; no library files are kept in the repo. This folder holds the **version catalog**, [`libs.versions.toml`](libs.versions.toml), where every dependency and its version is listed once. `../settings.gradle.kts` points Gradle at it.

To add or update a dependency, follow the project rules (`project-guidelines/implementation.md`):
- check that it's free with no card needed
- read its code for telemetry or tracking
- back up the old catalog in `version-control/`
- add a row below

## What the apps use

| Dependency (catalog name) | What for | Licence | Telemetry / network |
|---|---|---|---|
| Kotlin, kotlinx-coroutines (`kotlin`, `kotlinx-coroutines`) | the language and background work | Apache 2.0 | none |
| Compose Multiplatform, Material3 (`composeMultiplatform`, `material3`) | all screens, phone and desktop | Apache 2.0 | none |
| AndroidX Activity, Lifecycle (`androidx-activity`, `androidx-lifecycle`) | Android app plumbing | Apache 2.0 | none |
| AndroidX Media3 ExoPlayer and Session (`androidx-media3`) | Android playback, media notification | Apache 2.0 | none: its analytics hooks only report inside the app |
| AndroidX Room, SQLite bundled (`room`, `sqlite`) | the library database | Apache 2.0 (SQLite itself: public domain) | none |
| jaudiotagger (`jaudiotagger`) | reading and writing song tags | LGPL 2.1 (compatible with our GPL-3.0-or-later) | none |

The Android app asks for **no internet permission**, so nothing in it can reach the network.

## Only for building and testing (not shipped)

| Dependency | What for | Licence |
|---|---|---|
| Android Gradle Plugin (`agp`), KSP (`ksp`) | building the Android app, Room's code generation | Apache 2.0 |
| JUnit 4 (`junit`), AndroidX Test (`androidx-test`, `androidx-testExtJunit`), kotlin-test | tests | EPL 1.0 (JUnit), Apache 2.0 |

## Not in this catalog
- **GStreamer:** desktop playback and Convert to Audio. It's installed by the system (the `.deb` declares it) and isn't bundled. LGPL.
- **Java runtime:** bundled into the desktop `.deb` by Compose's packaging. GPL 2.0 with Classpath Exception.
