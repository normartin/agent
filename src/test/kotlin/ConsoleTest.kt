import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith

/**
 * The console only runs on a tty, so these drive the compiled agent on a pty4j pseudo-terminal.
 * Slow by design (a JVM per test): keep it to smoke tests of what nothing else can reach.
 * Experimental: if it turns flaky or costly to maintain, delete it rather than nurse it.
 */
class ConsoleTest : FunSpec({

    val workspace = tempdir()

    test("a job finishing while the user is idle at the prompt starts a turn by itself") {
        MockOpenAi().use { mock ->
            val out = console(workspace, mock) {
                user("run it")
                model(reasoning(), bash(action = "start", command = "sleep 1; echo done"))
                model(answer("started"))
                model(answer("saw it"))
                awaitRequests(3) // nothing typed meanwhile: the third request comes from the finished job
                user("/exit")
            }
            out shouldContain "saw it"
            mock.requests.size shouldBe 3
            mock.requests[2].input.last().str("content")!! shouldStartWith "[background job"
        }
    }

    test("Ctrl+C during a foreground command backgrounds it instead of quitting") {
        MockOpenAi().use { mock ->
            val out = console(workspace, mock) {
                user("run it")
                // The marker is split so the echoed command line cannot satisfy awaitScreen: only real output can.
                model(reasoning(), bash(command = "echo trap''-armed; sleep 2; echo survived"))
                // Via the spinner's live tail. Not "Running": SIGINT before bash runs `trap '' INT` kills the job.
                awaitScreen("trap-armed")
                interrupt()
                awaitScreen("Moved to background job")
                model(answer("saw it"))
                awaitRequests(2) // the moved job finishing starts a turn on its own
                user("/exit")
            }
            out shouldContain "Interrupted"
            out shouldContain "saw it"
            val delivered = mock.requests[1].input
            delivered.first { it.str("type") == "function_call_output" }
                .str("output")!! shouldContain "continues as background job"
            delivered.last().str("content")!! shouldContain "survived"
        }
    }
})
