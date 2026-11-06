plugins {
    alias(libs.plugins.kotlinMultiplatform) apply true
    alias(libs.plugins.composeCompiler) apply true
}

repositories {
    mavenCentral()
    google()
}

sourceSets {
    val iosX64Main by creating
    val iosArm64Main by creating
}