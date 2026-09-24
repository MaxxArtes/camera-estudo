plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Tradutor de tela: bolha flutuante que lê a tela, acha o texto, traduz e desenha por cima cobrindo só o
// formato das letras. Terceiro app do repositório, para herdar assinatura, publicação no R2 e atualização.
// O processo foi medido na bancada antes deste módulo existir (galeria/medicao não; ver docs/ do módulo).
android {
    namespace = "br.maxymus.tradutor"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.maxymus.tradutor"
        minSdk = 30            // takeScreenshot da acessibilidade exige API 30
        targetSdk = 34
        versionCode = 12
        versionName = "0.12"
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "TELEMETRIA_TOKEN", "\"" + (System.getenv("TELEMETRIA_TOKEN") ?: "") + "\"")
        // token do NOSSO servico de tradução por modelo de linguagem; a chave do provedor fica no servidor
        buildConfigField("String", "TRADUTOR_TOKEN", "\"" + (System.getenv("TRADUTOR_TOKEN") ?: "") + "\"")
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksCaminho != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("com.google.mlkit:text-recognition:16.0.1")   // OCR latino no aparelho
    implementation("com.google.mlkit:translate:17.0.3")          // tradução offline, pacote baixado sob demanda
    implementation("com.google.mlkit:language-id:17.0.6")        // detecta o idioma da fala, para servir a qualquer língua
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
