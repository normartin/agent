import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The half of the feature the model never asks for: the harness delivers a
 * finished job's output on its own, and tells the model what is still running.
 * These drive the whole loop against the mock and read the requests it recorded.
 */
@io.kotest.core.annotation.Isolate // swaps System.out, so it must not overlap the concurrent specs
class HarnessJobsTest : FunSpec({

    val workspace = tempdir()

    fun captureStdout(block: () -> Unit): String {
        val saved = System.out
        val buffer = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try { block() } finally { System.setOut(saved) }
        return buffer.toString(Charsets.UTF_8)
    }

    /** The tool result the harness sent on its [n]-th request: the last input item, a function_call_output. */
    fun MockOpenAi.toolResult(n: Int) = requests[n].input.last().str("output")!!

    test("strict-mode nulls and filler are harmless on run and wait") {
        // Under strict every field is sent: null when the model follows the schema,
        // "" / 120 when it does not (as the logged gpt-5.3-codex session did).
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "run", command = "echo one")),
                turn(reasoning(), bashRaw("""{"action":"run","command":"echo two","name":"","seconds":120}""")),
                turn(reasoning(), bash(action = "start", command = "echo three")),
                turn(reasoning(), bash(action = "wait", name = "job1")),
                turn(answer("done"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                harness.runTask("exercise the schema") shouldBe "done"
            }
            mock.toolResult(1) shouldStartWith "one\n"
            mock.toolResult(2) shouldStartWith "two\n"
            mock.toolResult(3) shouldStartWith "Started background job \"job1\""
            mock.toolResult(4) shouldStartWith "three\n"
            mock.toolResult(4) shouldContain "[Exit Code: 0"
        }
    }

    test("a reasoning summary is printed and the item is still echoed back") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning("Look at the files"), bash(command = "echo hi")),
                turn(answer("done"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                captureStdout { harness.runTask("go") } shouldContain "🧠  Look at the files"
            }
            mock.requests[1].input.single { it.str("type") == "reasoning" } shouldBe reasoning("Look at the files")
        }
    }

    test("a finished job's output is delivered without the model asking") {
        MockOpenAi().use { mock ->
            mock.script(
                // Start something short, then spend a turn in the foreground so
                // it is certainly over by the time the third request is built.
                turn(reasoning(), bash(action = "start", command = "echo ingested", name = "probe")),
                turn(reasoning(), bash(command = "sleep 1")),
                turn(answer("done"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { it.runTask("run something in the background") }

            mock.requests.size shouldBe 3
            // Nothing is delivered on the request right after the start: the job has not run yet.
            mock.requests[1].input.count { it.str("content")?.startsWith("[background job") == true } shouldBe 0

            val delivered = mock.requests[2].input.last()
            delivered.str("role") shouldBe "user"
            delivered.str("content")!! shouldStartWith "[background job \"probe\" finished] echo ingested\ningested\n\n[Exit Code: 0"
        }
    }

    test("each user turn is preceded by what is still running") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "start", command = "sleep 30", name = "server")),
                turn(answer("started it")),
                turn(answer("still going"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                harness.runTask("start a server")
                harness.runTask("how is it doing?")
            }

            // The listing comes first; the user's own words stay the last thing the model reads.
            val (listing, question) = mock.requests[2].input.takeLast(2).map { it.str("content")!! }
            listing shouldStartWith "[background jobs still running]\n- \"server\" ("
            listing shouldContain "): sleep 30"
            question shouldBe "how is it doing?"
        }
    }

    test("the model can stop a job it started") {
        val marker = "kotest-harness-stop-marker"
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "start", command = "sleep 300 # $marker", name = "hog")),
                turn(reasoning(), bash(action = "stop", name = "hog", callId = "call_2")),
                turn(answer("stopped"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { it.runTask("start it, then stop it") }

            // The stop's own reply carries the result, so it must not also be
            // delivered as a finished job: exactly one copy.
            val items = mock.requests[2].input
            items.last().str("output")!! shouldContain "[Killed after"
            items.count { it.str("content")?.startsWith("[background job") == true } shouldBe 0

            shouldLeaveNoProcess(marker)
        }
    }

    test("an unusable job action is answered rather than left dangling") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "wait", name = "ghost")),
                turn(reasoning(), bash(action = "start", callId = "call_2")),
                turn(answer("gave up"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { it.runTask("wait for a job that does not exist") }

            // An unanswered function_call is a 400 on the very next request, so
            // every branch has to produce an output item.
            mock.toolResult(1) shouldContain "no background job named \"ghost\""
            mock.toolResult(2) shouldContain "'start' needs a 'command'"
        }
    }

    context("a finished job wakes the agent up") {

        test("resume runs a turn the user never asked for and delivers the result") {
            MockOpenAi().use { mock ->
                mock.script(
                    turn(reasoning(), bash(action = "start", command = "echo woke-you-up", name = "alarm")),
                    turn(answer("started it")),
                    turn(answer("the job printed woke-you-up"))
                )
                // The console learns a job is over exactly this way.
                val finished = CountDownLatch(1)
                BashAgentHarness(workspace, "test-key", mock.baseUrl) { finished.countDown() }.use { harness ->
                    harness.runTask("start it")
                    mock.requests.size shouldBe 2

                    finished.await(15, TimeUnit.SECONDS) shouldBe true
                    harness.resume() shouldBe true
                }

                mock.requests.size shouldBe 3
                mock.requests[2].input.last().str("content")!! shouldStartWith "[background job \"alarm\" finished] echo woke-you-up\nwoke-you-up\n\n[Exit Code: 0"
            }
        }

        test("resume spends nothing when the result already reached the model") {
            MockOpenAi().use { mock ->
                mock.script(
                    turn(reasoning(), bash(action = "start", command = "echo quick", name = "quick")),
                    turn(reasoning(), bash(command = "sleep 1")),
                    turn(answer("done"))
                )
                BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                    harness.runTask("start it and hang about")

                    // The task that was running delivered the result itself, so the
                    // notification that follows has nothing left to say.
                    mock.requests.size shouldBe 3
                    harness.resume() shouldBe false
                    mock.requests.size shouldBe 3
                }
            }
        }

        test("a job the model stopped does not wake anyone") {
            MockOpenAi().use { mock ->
                mock.script(
                    turn(reasoning(), bash(action = "start", command = "sleep 300", name = "doomed")),
                    turn(reasoning(), bash(action = "stop", name = "doomed", callId = "call_2")),
                    turn(answer("stopped"))
                )
                BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                    harness.runTask("start it, then stop it")
                    harness.resume() shouldBe false
                }
                mock.requests.size shouldBe 3
            }
        }
    }

    test("shutdown kills what is still running") {
        val marker = "kotest-harness-shutdown-marker"
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "start", command = "sleep 300 & sleep 300 # $marker")),
                turn(answer("running"))
            )
            val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
            harness.runTask("start a long one")

            Thread.sleep(500) // let bash fork the background child before we go after descendants
            harness.shutdown()

            shouldLeaveNoProcess(marker)
        }
    }
})
