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
        versionCode = 85 // Compatibility with prior regression suites: versionCode = 84
        versionName = "1.15.52" // Compatibility with prior regression suites: versionName = "1.15.51"
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

