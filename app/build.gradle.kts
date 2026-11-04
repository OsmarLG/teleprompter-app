plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.osmarlg.teleprompter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.osmarlg.teleprompter"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
    }

    // La llave de firma NO está en el repo: GitHub Actions la arma desde sus secretos (TELEPROMPTER_KEYSTORE_B64 y
    // contraseñas) para que cada APK nuevo se instale encima del anterior. En una compu sin esa llave se usa la
    // llave de depuración de Android Studio.
    val keystorePath = System.getenv("TELEPROMPTER_KEYSTORE_FILE")
    signingConfigs {
        if (!keystorePath.isNullOrBlank()) {
            create("ci") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TELEPROMPTER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TELEPROMPTER_KEY_ALIAS")
                keyPassword = System.getenv("TELEPROMPTER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (!keystorePath.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val camerax = "1.4.1"

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Lector de QR de Google (para vincular la app con el CRM); no pide permiso de cámara.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
