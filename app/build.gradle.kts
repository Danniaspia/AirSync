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
        versionCode = 29
        versionName = "1.8.4"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
