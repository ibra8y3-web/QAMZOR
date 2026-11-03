plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    namespace = "org.qamzor.android"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.qamzor.android"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0-dev"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("androidx.documentfile:documentfile:1.0.1")
}
