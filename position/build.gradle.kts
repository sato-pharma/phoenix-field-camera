plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The same release key as the camera, written by CI from the repository secrets into a temporary file. It never lives
// in this repository and never in the workspace.
val releaseStore = System.getenv("PHOENIX_CAMERA_STORE_FILE")

android {
    namespace = "com.satopharma.phoenixposition"
    compileSdk = 35

    defaultConfig {
        // Its own package: the tablet agent is installed beside the camera, never instead of it.
        applicationId = "com.satopharma.phoenixposition"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
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
    implementation("androidx.core:core-ktx:1.15.0")
    // The tablet's combined position (Wi-Fi, mobile network, satellites): the only one that answers indoors at once.
    implementation("com.google.android.gms:play-services-location:21.3.0")
    // Android's own scheduler: the rounds keep running with no app open and after a restart.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
