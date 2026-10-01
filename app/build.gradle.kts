plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.airsync"
    compileSdk = 34

    defaultConfig {
        applicationId = "dk.airsync"
        minSdk = 29
        targetSdk = 34
        versionCode = 6
        versionName = "0.6"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
