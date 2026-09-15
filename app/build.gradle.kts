plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "br.maxymus.cameraestudo"
    compileSdk = 34
    defaultConfig {
        applicationId = "br.maxymus.cameraestudo"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.4"
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
    buildFeatures { compose = true }
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
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
