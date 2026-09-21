plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Galeria Estudo: app irmão do Camera Estudo, no mesmo repo. v1 (19/09/2026): fotos por data e pessoas por rosto,
// tudo no aparelho. Reaproveita o detector (ML Kit) e o embedding (MobileFaceNet) da câmera; a extração para um
// módulo comum fica para quando a edição entrar aqui (o dono decide a sequência).
android {
    namespace = "br.maxymus.galeriaestudo"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.maxymus.galeriaestudo"
        minSdk = 26
        targetSdk = 34
        versionCode = 21
        versionName = "0.21"
        ndk { abiFilters += listOf("arm64-v8a") }
        // token da telemetria vem do CI (secret TELEMETRIA_TOKEN); sem ele o app não envia nada
        buildConfigField("String", "TELEMETRIA_TOKEN", "\"" + (System.getenv("TELEMETRIA_TOKEN") ?: "") + "\"")
    }
    // Mesma chave da câmera (apps diferentes podem compartilhar a assinatura); sem as variáveis, chave de debug.
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
    androidResources { noCompress += "tflite" }
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
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:face-detection:16.1.7")     // detecção de rostos no aparelho
    implementation("org.tensorflow:tensorflow-lite:2.16.1")      // embedding facial (MobileFaceNet em assets, BSD-3)
    implementation("com.google.android.gms:play-services-mlkit-subject-segmentation:16.0.0-beta1")   // recorte Padrão: módulo do Play (meta-data no manifesto)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")                          // recorte Alta: ISNet int8 baixado do R2 (IsnetOnnx.kt)
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")            // miniatura de vídeo na grade
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
