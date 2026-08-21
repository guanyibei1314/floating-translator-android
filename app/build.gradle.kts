plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.floatingtranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.floatingtranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.0"

        // The target device is an ARM64 Honor 200 Pro. Shipping only ARM64
        // removes the unused ARM32 ML Kit native libraries without changing
        // the OCR or translation behavior on the target phone.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    // Bundled Latin OCR: available immediately after installation.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // On-device English -> Simplified Chinese translation model.
    implementation("com.google.mlkit:translate:17.0.3")
}
