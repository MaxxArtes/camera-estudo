pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.7.3"   // 8.6+ exigido pelo CameraX 1.5 (RAW); pede Gradle 8.9, que o CI ja usa
        id("org.jetbrains.kotlin.android") version "2.0.20"
        id("org.jetbrains.kotlin.plugin.compose") version "2.0.20"
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "camera-estudo"
include(":app", ":galeria", ":tradutor")
