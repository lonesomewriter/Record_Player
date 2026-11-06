import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

room {
    // Exported schemas are committed so future database migrations can be checked.
    schemaDirectory("$projectDir/schemas")
}

/**
 * Turns the animation timings in the repo's web-configs.json into Kotlin constants when the app
 * is built. Nothing is read at run time; the app has no network access.
 */
abstract class GenerateWebConfigs : DefaultTask() {
    @get:InputFile
    abstract val configFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val json = groovy.json.JsonSlurper().parse(configFile.get().asFile) as? Map<*, *>
            ?: throw GradleException("web-configs.json must be a JSON object")
        fun number(group: String, key: String, min: Double, max: Double): Double {
            val value = ((json[group] as? Map<*, *>)?.get(key) as? Number)?.toDouble()
                ?: throw GradleException("web-configs.json: $group.$key must be a number")
            if (value < min || value > max) throw GradleException("web-configs.json: $group.$key must be between $min and $max")
            return value
        }
        val blinkMs = Math.round(number("localLight", "blinkSeconds", 0.5, 10.0) * 1000)
        val spinUpMs = Math.round(number("disc", "spinUpMs", 0.0, 10_000.0))
        val spinDownMs = Math.round(number("disc", "spinDownMs", 0.0, 10_000.0))
        val file = outputDir.get().file("dev/recorddisc/player/ui/WebConfigs.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package dev.recorddisc.player.ui
            |
            |/** Generated from web-configs.json when the app is built; edit that file, not this one. */
            |internal object WebConfigs {
            |    /** One full fade out and back of the "Local" light. */
            |    const val LOCAL_BLINK_MS = $blinkMs
            |    /** How quickly the disc speeds up when playback starts (0 = at once). */
            |    const val DISC_SPIN_UP_MS = ${spinUpMs}f
            |    /** How quickly the disc coasts to a stop when playback stops (0 = at once). */
            |    const val DISC_SPIN_DOWN_MS = ${spinDownMs}f
            |}
            |""".trimMargin(),
        )
    }
}

val generateWebConfigs = tasks.register<GenerateWebConfigs>("generateWebConfigs") {
    configFile.set(rootProject.layout.projectDirectory.file("web-configs.json"))
    outputDir.set(layout.buildDirectory.dir("generated/webConfigs"))
}

kotlin {
    android {
        namespace = "dev.recorddisc.player.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        androidResources {
            enable = true
        }
    }
    jvm {
        compilerOptions {
            // The GStreamer binding uses Java's FFM API (final in Java 22).
            jvmTarget = JvmTarget.JVM_25
        }
    }

    sourceSets {
        // Code shared by the Android and desktop JVMs (tag editing), but not by non-JVM targets.
        val jvmShared by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.jaudiotagger)
            }
        }
        androidMain.get().dependsOn(jvmShared)
        jvmMain.get().dependsOn(jvmShared)
        androidMain.dependencies {
            // System Back handling for the phone layout.
            implementation(libs.androidx.activity.compose)
        }

        commonMain {
            kotlin.srcDir(generateWebConfigs)
        }
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutinesCore)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.kotlinx.coroutinesTest)
            // Native Skia for rendering the desktop UI to images in tests.
            implementation(compose.desktop.currentOs)
        }
    }
}

dependencies {
    // Room generates the database code for each target.
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspJvm", libs.androidx.room.compiler)
}
