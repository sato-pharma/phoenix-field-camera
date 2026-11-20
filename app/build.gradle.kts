plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The release key never lives in this repository: CI writes it from the repository secrets into a temporary file.
val releaseStore = System.getenv("PHOENIX_CAMERA_STORE_FILE")

android {
    namespace = "com.satopharma.phoenixcamera"
    compileSdk = 35

    defaultConfig {
        // The package name and the release certificate are what Phoenix checks inside every seal.
        applicationId = "com.satopharma.phoenixcamera"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "1.2.0"
    }

    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storeType = "PKCS12"
                storePassword = System.getenv("PHOENIX_CAMERA_STORE_PASSWORD")
                keyAlias = System.getenv("PHOENIX_CAMERA_KEY_ALIAS") ?: "phoenixcamera"
                keyPassword = System.getenv("PHOENIX_CAMERA_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // The phone's combined position (Wi-Fi, mobile network, satellites): the only one that answers indoors at once.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // play-services-location brings Fragment 1.0, too old for the permission request (lint InvalidFragmentVersionForActivityResult).
    implementation("androidx.fragment:fragment-ktx:1.8.5")
}
