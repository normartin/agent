import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
}

// The harness is deliberately a single file at the repo root, so that it stays
// runnable as a JBang script (`./agent.kt`). Point Gradle at that one file
// rather than moving it into src/main/kotlin. The filter matters: without it
// Gradle would sweep in build/, src/ and the agent's own scratch workspace.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf(projectDir))
        kotlin.filter.setIncludes(listOf("agent.kt"))
    }
}

// The source-set filter above governs the IDE's view, but the compile task
// pulls **/*.kts straight off the source root — which here is the project
// directory, so it would try to compile the Gradle build scripts themselves.
// Pin the task's source to the single file instead.
tasks.named<KotlinCompile>("compileKotlin") {
    setSource(files("agent.kt"))
}

application {
    // Top-level main() in the root package.
    mainClass = "AgentKt"
}

dependencies {
    // Keep in sync with the //DEPS lines in agent.kt — BuildConsistencyTest
    // fails if these drift apart.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jline:jline:3.30.16")

    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.kotest:kotest-property:6.2.4")
}

kotlin {
    compilerOptions {
        // The local JDK is newer than any target Kotlin 2.4 accepts. This sets
        // the bytecode version only, so sun.misc.Signal stays reachable.
        jvmTarget = JvmTarget.JVM_21
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.test {
    // Required: without it Gradle finds zero Kotest tests and "passes".
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
