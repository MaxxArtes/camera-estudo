// Build próprio do app do Coordenador: não entra no settings.gradle.kts da raiz, para que mudar este app não
// recompile nem republique a câmera, a galeria ou o tradutor. Mesmas versões de plugin da raiz.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.0.20"
    }
}
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "coordenador"
