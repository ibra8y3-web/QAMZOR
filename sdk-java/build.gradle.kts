plugins { `java-library` }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
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
