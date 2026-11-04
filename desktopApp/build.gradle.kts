import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
    }
}

dependencies {
    implementation(projects.shared)

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)
    implementation("org.jetbrains.kotlin:kotlin-stdlib")
    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "dev.recorddisc.player.MainKt"
        // Playback calls the system GStreamer through Java's FFM API.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            // .deb only: .rpm was dropped (project manager, 2026-11-04).
            targetFormats(TargetFormat.Deb)
            packageName = "record-player"
            packageVersion = "0.1.0"
            linux {
                iconFile.set(project.file("src/main/resources/record-player.png"))
            }
        }
    }
}

// Playback uses the system's GStreamer (it isn't bundled), so the .deb must declare it.
// Without this, the package installs fine but can't play anything on a machine that lacks GStreamer.
// The Compose DSL has no setting for this, so pass jpackage's own option to the .deb tasks.
// No spaces: Compose writes jpackage's arguments to a file unquoted, so a space would split the list.
val gstreamerDebDepends = "libgstreamer1.0-0,gstreamer1.0-plugins-base,gstreamer1.0-plugins-good"
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
    .matching { it.name == "packageDeb" || it.name == "packageReleaseDeb" }
    .configureEach { freeArgs.addAll("--linux-package-deps", gstreamerDebDepends) }
