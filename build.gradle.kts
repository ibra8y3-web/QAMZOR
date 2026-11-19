plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
}

// JitPack supplies GROUP and VERSION for the Git repository build.
// Normal builds and Maven Central releases use the explicit Gradle properties.
val sdkGroup = providers.environmentVariable("GROUP")
    .orElse(providers.gradleProperty("qamzor.groupId"))
    .get()
val sdkVersion = providers.environmentVariable("VERSION")
    .orElse(providers.gradleProperty("VERSION_NAME"))
    .get()

allprojects {
    group = sdkGroup
    version = sdkVersion
}
