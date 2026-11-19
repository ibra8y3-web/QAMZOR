plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
}

// JitPack sets JITPACK=true together with GROUP and VERSION.
// Normal builds and Maven Central releases use the explicit Gradle properties.
val isJitPackBuild = providers.environmentVariable("JITPACK").orNull == "true"
val sdkGroup = if (isJitPackBuild) {
    providers.environmentVariable("GROUP").get()
} else {
    providers.gradleProperty("qamzor.groupId").get()
}
val sdkVersion = if (isJitPackBuild) {
    providers.environmentVariable("VERSION").get()
} else {
    providers.gradleProperty("VERSION_NAME").get()
}

allprojects {
    group = sdkGroup
    version = sdkVersion
}
