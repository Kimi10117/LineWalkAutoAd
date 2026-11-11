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
        versionCode = 111 // Compatibility with prior regression suites: versionCode = 110, versionCode = 109, versionCode = 108, versionCode = 107, versionCode = 106, versionCode = 105, versionCode = 104, versionCode = 103, versionCode = 102, versionCode = 101, versionCode = 100, versionCode = 99, versionCode = 98, versionCode = 97, versionCode = 96, versionCode = 95, versionCode = 94, versionCode = 93, versionCode = 91, versionCode = 90, versionCode = 89, versionCode = 88, versionCode = 87, versionCode = 86, versionCode = 85, versionCode = 84, versionCode = 83, versionCode = 82
        versionName = "1.15.78" // Compatibility with prior regression suites: versionName = "1.15.77", versionName = "1.15.76", versionName = "1.15.75", versionName = "1.15.74", versionName = "1.15.73", versionName = "1.15.72", versionName = "1.15.71", versionName = "1.15.70", versionName = "1.15.69", versionName = "1.15.68", versionName = "1.15.67", versionName = "1.15.66", versionName = "1.15.65", versionName = "1.15.64", versionName = "1.15.63", versionName = "1.15.62", versionName = "1.15.61", versionName = "1.15.60", versionName = "1.15.58", versionName = "1.15.57", versionName = "1.15.56", versionName = "1.15.55", versionName = "1.15.54", versionName = "1.15.53", versionName = "1.15.52", versionName = "1.15.51", versionName = "1.15.50", versionName = "1.15.49", versionName = "1.15.48", versionName = "1.15.47", versionName = "1.15.46", versionName = "1.15.45"
    }

    signingConfigs {
        create("release") {
            val persistentKey = file("persistent.jks")
            if (persistentKey.exists()) {
                storeFile = persistentKey
                storePassword = "linewalk123"
                keyAlias = "linewalk"
                keyPassword = "linewalk123"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
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
        buildConfig = true
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
        }
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

    // E2E & Unit Test Infrastructure
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.11.1")
    testImplementation("io.mockk:mockk:1.13.9")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("androidx.test.ext:junit:1.1.5")
}

