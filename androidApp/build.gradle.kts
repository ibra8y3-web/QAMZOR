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
        versionCode = 10
        versionName = "0.9.0"
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
}
