plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// App do painel do coordenador (https://coordenador.maxymus.dev.br): casca que abre o painel numa Trusted Web Activity
// do Chrome. O login do Google é recusado dentro de WebView; no Chrome funciona como no navegador, e o painel inteiro
// vem junto, sem refazer tela. Mesma chave de assinatura e mesma publicação no R2 dos outros apps do repositório.
android {
    namespace = "br.maxymus.coordenador"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.maxymus.coordenador"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }
    val ksCaminho = System.getenv("CAMERA_KEYSTORE")
    val ksSenha = System.getenv("CAMERA_KEYSTORE_SENHA")
    signingConfigs {
        create("release") {
            if (ksCaminho != null && ksSenha != null) {
                storeFile = file(ksCaminho); storePassword = ksSenha; keyAlias = "camera"; keyPassword = ksSenha
            }
        }
    }
    buildTypes {
        release {
            // Casca pequena: sem R8, para não precisar de regras de manutenção para a biblioteca da TWA.
            isMinifyEnabled = false
            signingConfig = if (ksCaminho != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // A 2.6+ puxa androidx.browser 1.9/1.10, que exigem compileSdk 36 e AGP 8.9.1; a 2.5.0 com a browser 1.8.0 (a mais
    // nova que aceita compileSdk 35) mantém as versões de plugin do resto do repositório.
    implementation("com.google.androidbrowserhelper:androidbrowserhelper:2.5.0")
    implementation("androidx.browser:browser:1.8.0")
}
