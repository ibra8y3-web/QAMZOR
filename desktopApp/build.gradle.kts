plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(17) }

dependencies { implementation(project(":core")) }

application { mainClass.set("org.qamzor.desktop.MainKt") }
