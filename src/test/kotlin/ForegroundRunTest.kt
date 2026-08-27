import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlin.concurrent.thread

/**
 * The foreground half of the one tool. A foreground command is an unregistered
 * [BackgroundJob] that the calling turn waits on, so the log file, the kill and the
 * truncation are all covered by BackgroundJobsTest already; what is left here
 * is the waiting itself and the wording that comes out the other side.
 */
class ForegroundRunTest : FunSpec({

    val workspace = tempdir()

    test("a command that finishes inside its deadline is not disturbed") {
        val job = JobRegistry(workspace).run("echo hello; echo boom >&2; exit 3", 30) { false }

        job.state shouldBe JobState.EXITED
        val report = job.report()
        // stderr sits where a terminal would show it, not in a section of its own.
        report shouldStartWith "hello\nboom\n"
        report shouldContain "[Exit Code: 3"
    }

    test("a flood of output reaches the model with its real start and its real end") {
        // seq 1 20000 is ~110k chars, far past the model's budget. What survives
        // must be the first line and the last, and the marker must lead to the rest.
        val job = JobRegistry(workspace).run("echo first; seq 1 20000; echo last", 30) { false }
        val report = job.report()
        report.length.toLong() shouldBeLessThan MAX_OUTPUT_CHARS + 200L
        report shouldStartWith "first\n1\n2\n"
        report shouldContain "\n19999\n20000\nlast\n"
        report shouldContain "[Exit Code: 0"
        report shouldNotContain "\n10000\n"

        // The file holds everything, and the sed the marker suggests fetches exactly the gap.
        job.logFile.readText() shouldBe "first\n" + (1..20000).joinToString("\n") + "\nlast\n"
        val (from, to) = Regex("lines (\\d+)-(\\d+) of 20003, sed -n '\\d+,\\d+p' ${Regex.escape(job.logFile.path)}")
            .find(report)!!.groupValues.drop(1).map { it.toInt() }
        val gap = job.logFile.readLines().subList(from - 1, to)
        gap.first() shouldBe (from - 1).toString()  // "first" is line 1, so line n holds n-1
        gap.last() shouldBe (to - 1).toString()
        report shouldNotContain "\n${gap[1]}\n"
    }

    test("progress-bar redraws are collapsed before the model sees them") {
        val report = JobRegistry(workspace).run("printf 'Progress: 1\\rProgress: 2\\rProgress: 3\\ndone\\n'", 30) { false }.report()
        report shouldStartWith "Progress: 3\ndone\n"

        collapseCarriageReturns("Progress: 1\rProgress: 2\rProgress: 3\ndone\n") shouldBe "Progress: 3\ndone\n"
        collapseCarriageReturns("x\r\ny") shouldBe "x\ny"       // windows line endings are just line endings
        collapseCarriageReturns("plain\nlines\n") shouldBe "plain\nlines\n"
    }

    test("a foreground command runs in the workspace") {
        JobRegistry(workspace).run("pwd", 30) { false }
            .report() shouldContain workspace.canonicalPath
    }

    test("a hung command is killed at the deadline, descendants and all") {
        // Killing only the bash -c parent would leave the background child
        // running on, so descendants have to go too.
        val marker = "kotest-foreground-timeout-marker"
        val started = System.currentTimeMillis()
        val job = JobRegistry(workspace).run("sleep 300 & sleep 300 # $marker", 2) { false }
        val elapsedMs = System.currentTimeMillis() - started

        elapsedMs shouldBeGreaterThanOrEqual 1_900L
        elapsedMs shouldBeLessThan 15_000L
        job.state shouldBe JobState.KILLED

        shouldLeaveNoProcess(marker)
    }

    test("an interrupt is felt during the command, not after it") {
        val marker = "kotest-foreground-interrupt-marker"
        val jobs = JobRegistry(workspace)
        var interrupted = false
        var job: BackgroundJob? = null
        // The deadline is 600s: only the interrupt can end this in time.
        val runner = thread { job = jobs.run("sleep 300 # $marker", 600) { interrupted } }

        Thread.sleep(1_500)
        val killedAt = System.currentTimeMillis()
        interrupted = true
        jobs.interruptForeground()
        runner.join(15_000)

        (System.currentTimeMillis() - killedAt) shouldBeLessThan 5_000L
        runner.isAlive shouldBe false
        job!!.state shouldBe JobState.KILLED

        shouldLeaveNoProcess(marker)
    }

    test("a foreground command is never registered as a job") {
        // It has no name to be referred to by later and nothing to deliver once
        // the call returns, so it must not show up in either listing.
        val jobs = JobRegistry(workspace)
        jobs.run("echo transient", 30) { false }

        jobs.running() shouldBe emptyList()
        jobs.drainFinished() shouldBe emptyList()
        jobs.names() shouldBe "none"
    }

    test("a failure to launch is reported rather than thrown") {
        // Answered by the harness, which turns it into an error string: an
        // unanswered function_call is a 400 on the very next request.
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(command = "echo hi")),
                turn(answer("gave up"))
            )
            BashAgentHarness(java.io.File("/no/such/directory"), "test-key", mock.baseUrl).use { it.runTask("run something") }

            mock.requests[1].input.last().str("output")!! shouldStartWith "Execution Error"
        }
    }

    test("the timeout wording reaches the model") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(command = "sleep 300")),
                turn(answer("that took too long"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl, timeoutSeconds = 2).use { it.runTask("hang") }

            // "[Killed after 2s]" is what the job itself would say, which does
            // not tell the model whether it was the deadline or the user.
            mock.requests[1].input.last().str("output")!! shouldContain "TIMED OUT after 2s"
        }
    }
})
