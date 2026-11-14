plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.airsync"
    compileSdk = 34

    defaultConfig {
        // Selvstændig app: installeres uafhængigt af tidligere AirSync/AirTooth-installationer.
        applicationId = "dk.airtooth"
        minSdk = 29
        targetSdk = 34
        versionCode = 23
        versionName = "1.22"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
