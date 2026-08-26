import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * agent.kt declares its dependencies twice: once as JBang //DEPS directives so
 * `./agent.kt` runs standalone, and once in build.gradle.kts so Gradle can
 * compile and test it. Nothing keeps those in step, and the failure mode is
 * nasty — the script and the build quietly resolve different versions.
 */
class BuildConsistencyTest : FunSpec({

    val agent = File("agent.kt").readText()
    val build = File("build.gradle.kts").readText()

    // kotlin-stdlib is implicit under the Gradle Kotlin plugin, so JBang has to
    // name it but build.gradle.kts must not.
    val jbangDeps = Regex("""^//DEPS\s+(\S+)""", RegexOption.MULTILINE)
        .findAll(agent).map { it.groupValues[1] }
        .filterNot { it.startsWith("org.jetbrains.kotlin:kotlin-stdlib:") }
        .toList()

    val gradleDeps = Regex("""^\s*implementation\("([^"]+)"\)""", RegexOption.MULTILINE)
        .findAll(build).map { it.groupValues[1] }.toList()

    test("JBang and Gradle declare the same dependencies") {
        gradleDeps shouldContainExactlyInAnyOrder jbangDeps
    }

    test("both agree on the Kotlin version") {
        val fromJbang = Regex("""^//KOTLIN\s+(\S+)""", RegexOption.MULTILINE)
            .find(agent)!!.groupValues[1]
        val fromStdlib = Regex("""org\.jetbrains\.kotlin:kotlin-stdlib:(\S+)""")
            .find(agent)!!.groupValues[1]
        val fromGradle = Regex("""kotlin\("jvm"\)\s+version\s+"([^"]+)"""")
            .find(build)!!.groupValues[1]

        fromStdlib shouldBe fromJbang
        fromGradle shouldBe fromJbang
    }

    test("the script is still executable and still shebanged for JBang") {
        agent.lineSequence().first() shouldBe "///usr/bin/env jbang \"\$0\" \"\$@\" ; exit \$?"
        File("agent.kt").canExecute() shouldBe true
    }

    test("the entry point Gradle is told to run actually exists") {
        // A top-level main() in the root package compiles to class AgentKt.
        val hasTopLevelMain = Regex("""^fun main\(\)""", RegexOption.MULTILINE).containsMatchIn(agent)
        hasTopLevelMain shouldBe true

        val declaredMainClass = Regex("""mainClass\s*=\s*"([^"]+)"""").find(build)!!.groupValues[1]
        declaredMainClass shouldBe "AgentKt"
    }

    test("the harness is still a single file") {
        // The founding constraint: everything Gradle compiles as main source is
        // this one file. Tests may multiply; the harness may not.
        val mainSources = File("src/main").takeIf { it.exists() }?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" }?.toList().orEmpty()
        mainSources shouldBe emptyList()
        File("agent.kt").exists() shouldBe true
    }
})
