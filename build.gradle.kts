import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.10"
    application
}

repositories {
    mavenCentral()
    // jediterm-core is published here, not on Maven Central. Test scope only.
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
}

// The harness is deliberately a single file at the repo root, so that it stays
// runnable as a JBang script (`./agent.kt`). Point Gradle at that one file
// rather than moving it into src/main/kotlin. The filter matters: without it
// Gradle would sweep in build/, src/ and the agent's own scratch workspace.
sourceSets {
    main {
        kotlin.setSrcDirs(listOf(projectDir))
        kotlin.filter.setIncludes(listOf("agent.kt", "agent-log.kt"))
    }
}

// The source-set filter above governs the IDE's view, but the compile task
// pulls **/*.kts straight off the source root — which here is the project
// directory, so it would try to compile the Gradle build scripts themselves.
// Pin the task's source to the single file instead.
tasks.named<KotlinCompile>("compileKotlin") {
    setSource(files("agent.kt", "agent-log.kt"))
}

application {
    // Top-level main() in the root package.
    mainClass = "AgentKt"
    // JLine's FFM terminal calls restricted native methods; silence the JDK warning.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
    // Keep in sync with the //DEPS lines in agent.kt — BuildConsistencyTest
    // fails if these drift apart.
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jline:jline:4.4.0")

    testImplementation("io.kotest:kotest-runner-junit5:6.2.4")
    testImplementation("io.kotest:kotest-assertions-core:6.2.4")
    testImplementation("io.kotest:kotest-property:6.2.4")
    testImplementation("com.approvaltests:approvaltests:31.0.0")
    // Headless terminal emulation for the console tests: pty4j spawns the agent
    // on a real pty, jediterm renders its output into a text screen.
    testImplementation("org.jetbrains.jediterm:jediterm-core:3.74")
    testImplementation("org.jetbrains.pty4j:pty4j:0.13.12")
}

kotlin {
    compilerOptions {
        // Fail the build on any Kotlin warning in main and test compilations.
        allWarningsAsErrors = true
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
    // IntelliJ sets idea.active on every Gradle call it launches; hand it to the test JVM
    // so approval mismatches only open a diff tool for a human in the IDE.
    systemProperty("idea.active", providers.systemProperty("idea.active").getOrElse("false"))
    testLogging {
        events("passed", "skipped", "failed")
    }
}
