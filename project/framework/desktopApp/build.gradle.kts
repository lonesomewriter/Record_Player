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
// Our own menu entry replaces jpackage's generated one (which fails desktop-file-validate:
// "Categories=Unknown"): a real name and category, and the audio types, so file managers list
// Record Player under "Open with" (Exec ... %F passes the chosen file). jpackage's own
// file-association option isn't used: it installs its own MIME definitions for these standard
// types. The Compose task gives jpackage a resource folder it clears first, so the entry can't be
// supplied there; instead the finished .deb is unpacked, the entry swapped (same file name, which
// the install script registers), and the package rebuilt root-owned, as jpackage built it.
val linuxDesktopEntry = layout.projectDirectory.file("packaging/linux/record-player.desktop")
tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>()
    .matching { it.name == "packageDeb" || it.name == "packageReleaseDeb" }
    .configureEach {
        freeArgs.addAll("--linux-package-deps", gstreamerDebDepends)
        inputs.file(linuxDesktopEntry)
        val output = destinationDir
        val entry = linuxDesktopEntry.asFile
        val work = temporaryDir.resolve("repack")
        doLast {
            fun run(vararg command: String) {
                val process = ProcessBuilder(*command).redirectErrorStream(true).start()
                val log = process.inputStream.bufferedReader().readText()
                check(process.waitFor() == 0) { "${command.joinToString(" ")} failed:\n$log" }
            }
            val deb = output.get().asFile.listFiles { f -> f.extension == "deb" }?.singleOrNull()
                ?: error("Expected one .deb in ${output.get().asFile}")
            work.deleteRecursively()
            run("dpkg-deb", "--raw-extract", deb.absolutePath, work.absolutePath)
            val generated = work.resolve("opt/record-player/lib").listFiles { f -> f.extension == "desktop" }?.singleOrNull()
                ?: error("jpackage's menu entry wasn't found in ${deb.name}")
            entry.copyTo(generated, overwrite = true)
            // As jpackage ships it (the source file's own mode depends on the machine that built it).
            generated.setWritable(false, false) // rw-r--r--: owner writes, everyone reads
            generated.setWritable(true, true)
            generated.setReadable(true, false)
            generated.setExecutable(false, false)
            run("dpkg-deb", "--root-owner-group", "--build", work.absolutePath, deb.absolutePath)
            work.deleteRecursively()
        }
    }
