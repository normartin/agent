import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * The console only runs on a tty, so these drive the compiled agent under `script`'s pseudo-terminal.
 * Slow by design (a JVM per test): keep it to smoke tests of what nothing else can reach.
 * Experimental: if it turns flaky or costly to maintain, delete it rather than nurse it.
 */
class ConsoleTest : FunSpec({

    val workspace = tempdir()

    test("an unknown /command is rejected with help and never reaches the model") {
        MockOpenAi().use { mock ->
            val out = console(workspace, mock) { line("/foo"); line("/exit") }
            out shouldContain "Bash Agent — Workspace"
            out shouldContain "Unknown command"
            out shouldContain "Bye!"
            mock.requests.size shouldBe 0
        }
    }

    test("a job finishing while the user is idle at the prompt starts a turn by itself") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "start", command = "sleep 1; echo done")),
                turn(answer("started")),
                turn(answer("saw it"))
            )
            val out = console(workspace, mock) {
                line("run it")
                awaitRequests(3) // nothing typed meanwhile: the third request comes from the finished job
                line("/exit")
            }
            out shouldContain "saw it"
            mock.requests.size shouldBe 3
            mock.requests[2].input.last().str("content")!! shouldStartWith "[background job"
        }
    }
})
