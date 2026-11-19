plugins { `java-library` }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

// Generate the SDK version from the actual Gradle project version. This keeps
// Qamzor.SDK_VERSION aligned with JitPack tags and Maven Central release tags.
val generatedVersionSourceDir = layout.buildDirectory.dir("generated/sources/qamzorVersion/java/main")
val generateQamzorVersion = tasks.register("generateQamzorVersion") {
    val outputFile = generatedVersionSourceDir.map {
        it.file("org/qamzor/javasdk/QamzorVersion.java")
    }
    inputs.property("version", project.version.toString())
    outputs.file(outputFile)
    doLast {
        val version = project.version.toString()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """package org.qamzor.javasdk;

final class QamzorVersion {
    static final String VALUE = "$version";

    private QamzorVersion() { }
}
"""
        )
    }
}

sourceSets {
    main {
        java.srcDir(generatedVersionSourceDir)
    }
}

tasks.named<JavaCompile>("compileJava") {
    dependsOn(generateQamzorVersion)
}

tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(generateQamzorVersion)
}

dependencies {
    api(project(":core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("com.google.code.gson:gson:2.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.3")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("qamzor.compat.dir", rootProject.file("compat").absolutePath)
    systemProperty("qamzor.version", project.version.toString())
}

extra["qamzorArtifactId"] = "qamzor-java"
extra["qamzorDescription"] = "QAMZOR Java SDK: create, verify and extract QZRA archives from Java 17+ applications"
apply(from = rootProject.file("gradle/publishing.gradle"))
