plugins { kotlin("jvm") }

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core"))
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.11.3")
}

tasks.test { useJUnitPlatform() }
