plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.linewalk.autoad"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.linewalk.autoad"
        minSdk = 30
        targetSdk = 34
        versionCode = 12
        versionName = "1.12.0"
    }

    signingConfigs {
        getByName("debug") {
            val persistentKey = file("persistent.jks")
            if (persistentKey.exists()) {
                storeFile = persistentKey
                storePassword = "linewalk123"
                keyAlias = "linewalk"
                keyPassword = "linewalk123"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // Google ML Kit Text Recognition v2 (Bundled Chinese + Latin on-device OCR)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.0")
}
