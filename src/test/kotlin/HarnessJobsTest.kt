import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The half of the feature the model never asks for: the harness delivers a
 * finished job's output on its own, and tells the model what is still running.
 * These drive the whole loop against the mock and read the requests it recorded.
 */
class HarnessJobsTest : FunSpec({

    val workspace = tempdir()

    /** What the harness sent on its [n]-th call, as one searchable string. */
    fun MockOpenAi.request(n: Int) = requests[n].body

    test("a finished job's output is delivered without the model asking") {
        MockOpenAi().use { mock ->
            mock.script(
                // Start something short, then spend a turn in the foreground so
                // it is certainly over by the time the third request is built.
                Reply(200, jobsCallBody("""{"action":"start","command":"echo ingested","name":"probe"}""")),
                Reply(200, toolCallBody(command = "sleep 2")),
                Reply(200, finalAnswerBody("done"))
            )
            val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
            harness.runTask("run something in the background")

            mock.requests.size shouldBe 3
            // Nothing has been delivered on the turn that started it: no
            // command has produced any output at all yet.
            mock.request(1) shouldNotContain "[Exit Code"

            val delivered = mock.request(2)
            delivered shouldContain "[background job "
            delivered shouldContain "probe"
            delivered shouldContain "ingested"
            delivered shouldContain "[Exit Code: 0"

            harness.shutdown()
        }
    }

    test("each user turn is preceded by what is still running") {
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, jobsCallBody("""{"action":"start","command":"sleep 30","name":"server"}""")),
                Reply(200, finalAnswerBody("started it")),
                Reply(200, finalAnswerBody("still going"))
            )
            val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
            harness.runTask("start a server")
            harness.runTask("how is it doing?")

            val second = mock.request(2)
            second shouldContain "[background jobs still running]"
            second shouldContain "\\\"server\\\""
            second shouldContain "sleep 30"
            // The user's own words stay the last thing the model reads.
            second.indexOf("how is it doing?") shouldBe second.lastIndexOf("how is it doing?")

            harness.shutdown()
        }
    }

    test("the model can stop a job it started") {
        val marker = "kotest-harness-stop-marker"
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, jobsCallBody("""{"action":"start","command":"sleep 300 # $marker","name":"hog"}""")),
                Reply(200, jobsCallBody("""{"action":"stop","name":"hog"}""", callId = "call_jobs2")),
                Reply(200, finalAnswerBody("stopped"))
            )
            val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
            harness.runTask("start it, then stop it")

            // The stop's own reply carries the result, so it must not also be
            // delivered as a finished job on the next turn — exactly one copy.
            mock.request(2).split("[Killed after").size shouldBe 2

            awaitNoProcesses(marker)
            processesMatching(marker) shouldBe 0
            harness.shutdown()
        }
    }

    test("an unusable jobs call is answered rather than left dangling") {
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, jobsCallBody("""{"action":"wait","name":"ghost"}""")),
                Reply(200, jobsCallBody("""{"action":"start"}""", callId = "call_jobs2")),
                Reply(200, finalAnswerBody("gave up"))
            )
            val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
            harness.runTask("wait for a job that does not exist")

            // An unanswered function_call is a 400 on the very next request, so
            // every branch has to produce an output item.
            mock.request(1) shouldContain "no background job named"
            mock.request(2) shouldContain "'start' needs a 'command'"

            harness.shutdown()
        }
    }

    context("a finished job wakes the agent up") {

        test("resume runs a turn the user never asked for and delivers the result") {
            MockOpenAi().use { mock ->
                mock.script(
                    Reply(200, jobsCallBody("""{"action":"start","command":"echo woke-you-up","name":"alarm"}""")),
                    Reply(200, finalAnswerBody("started it")),
                    Reply(200, finalAnswerBody("the job printed woke-you-up"))
                )
                // The console learns a job is over exactly this way.
                val finished = CountDownLatch(1)
                val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl) { finished.countDown() }
                harness.runTask("start it")
                mock.requests.size shouldBe 2

                finished.await(15, TimeUnit.SECONDS) shouldBe true
                harness.resume() shouldBe true

                mock.requests.size shouldBe 3
                val unprompted = mock.request(2)
                unprompted shouldContain "[background job "
                unprompted shouldContain "woke-you-up"

                harness.shutdown()
            }
        }

        test("resume spends nothing when the result already reached the model") {
            MockOpenAi().use { mock ->
                mock.script(
                    Reply(200, jobsCallBody("""{"action":"start","command":"echo quick","name":"quick"}""")),
                    Reply(200, toolCallBody(command = "sleep 2")),
                    Reply(200, finalAnswerBody("done"))
                )
                val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
                harness.runTask("start it and hang about")

                // The task that was running delivered the result itself, so the
                // notification that follows has nothing left to say.
                mock.requests.size shouldBe 3
                harness.resume() shouldBe false
                mock.requests.size shouldBe 3

                harness.shutdown()
            }
        }

        test("a job the model stopped does not wake anyone") {
            MockOpenAi().use { mock ->
                mock.script(
                    Reply(200, jobsCallBody("""{"action":"start","command":"sleep 300","name":"doomed"}""")),
                    Reply(200, jobsCallBody("""{"action":"stop","name":"doomed"}""", callId = "call_jobs2")),
                    Reply(200, finalAnswerBody("stopped"))
                )
                val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
                harness.runTask("start it, then stop it")

                harness.resume() shouldBe false
                mock.requests.size shouldBe 3
            }
        }

        test("the notification fires once per job, whatever ended it") {
            val fired = java.util.concurrent.CopyOnWriteArrayList<String>()
            val jobs = JobRegistry(workspace) { fired.add(it.name) }

            val quick = jobs.start("echo done", "quick")
            val killed = jobs.start("sleep 300", "killed")
            quick.awaitFor(15_000) shouldBe true

            killed.stop()
            killed.awaitFor(15_000) shouldBe true
            // A moment for the watcher, which fires the callback after it has
            // published the state.
            Thread.sleep(500)

            fired.sorted() shouldBe listOf("killed", "quick")
        }
    }

    test("shutdown kills what is still running") {
        val marker = "kotest-harness-shutdown-marker"
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, jobsCallBody("""{"action":"start","command":"sleep 300 & sleep 300 # $marker"}""")),
                Reply(200, finalAnswerBody("running"))
            )
            val harness = BashAgentHarness(workspace, "test-key", mock.baseUrl)
            harness.runTask("start a long one")

            Thread.sleep(500)
            harness.shutdown()

            awaitNoProcesses(marker)
            processesMatching(marker) shouldBe 0
        }
    }
})
