plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "br.maxymus.cameraestudo"
    compileSdk = 35   // exigido pelo CameraX 1.5
    defaultConfig {
        applicationId = "br.maxymus.cameraestudo"
        minSdk = 26
        targetSdk = 34
        versionCode = 74
        versionName = "0.74"
        // Só arm64: todo celular Android de 2017 em diante. Sem isto o ML Kit traz 20 MB de
        // biblioteca nativa por arquitetura (x86, x86_64, armeabi-v7a) que ninguém usa no aparelho.
        ndk { abiFilters += listOf("arm64-v8a") }
        buildConfigField("String", "MELHORAR_TOKEN", "\"" + (System.getenv("MELHORAR_TOKEN") ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"")
        // token da telemetria vem do CI (secret TELEMETRIA_TOKEN); sem ele o app não envia nada
        buildConfigField("String", "TELEMETRIA_TOKEN", "\"" + (System.getenv("TELEMETRIA_TOKEN") ?: "") + "\"")
    }
    // Assinatura fixa (secrets do repo): sem ela cada build teria chave aleatória e o celular
    // recusaria atualizar por cima. Localmente, sem as variáveis, cai na chave de debug.
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
            isMinifyEnabled = true          // R8: tira código não usado
            isShrinkResources = true        // e recursos não usados
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksCaminho != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += "tflite" }   // o TFLite mapeia o modelo direto do APK; comprimido não abre
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.camera:camera-core:1.5.3")          // 1.5 trouxe OUTPUT_FORMAT_RAW_JPEG (captura RAW+JPEG)
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("androidx.camera:camera-video:1.5.3")
    implementation("androidx.camera:camera-extensions:1.5.3")           // bokeh/HDR nativos, quando o aparelho tem
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6") // retrato por software (pessoa x fundo)
    implementation("com.google.mlkit:face-detection:16.1.7")            // rostos: olhos abertos na rajada, cadastro de pessoas
    implementation("org.tensorflow:tensorflow-lite:2.16.1")             // embedding facial (MobileFaceNet em assets, BSD-3)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
