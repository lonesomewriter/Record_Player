rootProject.name = "MusicPlayer"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
    // The version catalog lives in dependencies/ (project folder rule, 2026-11-06), not Gradle's default gradle/.
    versionCatalogs {
        create("libs") { from(files("dependencies/libs.versions.toml")) }
    }
}

include(":shared")
include(":androidApp")
include(":desktopApp")
// iosApp/ (Swift UI port, built with Xcode, not Gradle) is left out of the Gradle build: its
// Gradle module declares no Kotlin targets, which printed "No Kotlin Targets Declared" on every build.
