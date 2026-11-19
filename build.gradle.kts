plugins {
    id("com.android.application") version "8.7.3" apply false
    kotlin("android") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.serialization") version "2.0.21" apply false
    id("com.android.library") version "8.7.3" apply false
}

val sdkVersion = providers.gradleProperty("VERSION_NAME").get()
val sdkGroup = providers.gradleProperty("qamzor.groupId").get()
allprojects {
    group = sdkGroup
    version = sdkVersion
}
