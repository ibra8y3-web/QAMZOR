plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "org.qamzor.sdk.android"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":sdk-java"))
    implementation("androidx.documentfile:documentfile:1.0.1")
}

extra["qamzorArtifactId"] = "qamzor-android"
extra["qamzorDescription"] = "QAMZOR Android SDK (AAR): open, verify and extract QZRA archives via the Storage Access Framework"
apply(from = rootProject.file("gradle/publishing.gradle"))
