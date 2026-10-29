import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
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

        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
        }
    }
}
