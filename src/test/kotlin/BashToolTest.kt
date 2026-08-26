import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.concurrent.thread

// processesMatching lives in ProcessProbe.kt — BackgroundJobsTest needs it too.

class BashToolTest : FunSpec({

    val workspace = tempdir()

    test("stdout, stderr and the exit code all come back") {
        val result = BashTool(workspace).execute("echo hello; echo boom >&2; exit 3")
        result shouldContain "hello"
        result shouldContain "ERROR OUTPUT:"
        result shouldContain "boom"
        result shouldContain "[Exit Code: 3]"
    }

    test("commands run in the workspace") {
        BashTool(workspace).execute("pwd") shouldContain workspace.canonicalPath
    }

    test("a flood of output drains without deadlocking") {
        // Reading the two streams in sequence deadlocks once the unread one
        // fills its pipe buffer, so both are drained on their own threads.
        val result = BashTool(workspace).execute("seq 1 200000")
        result shouldContain "\n200000"
        result shouldContain "[Exit Code: 0]"
    }

    test("stderr alone does not stall the drain") {
        val result = BashTool(workspace).execute("seq 1 100000 >&2")
        result shouldContain "ERROR OUTPUT:"
        result shouldContain "[Exit Code: 0]"
    }

    test("a kill from another thread returns promptly and leaves no orphan") {
        val marker = "kotest-kill-marker"
        val tool = BashTool(workspace)
        var result = ""
        val runner = thread { result = tool.execute("sleep 300 # $marker") }

        Thread.sleep(1_500)
        val killedAt = System.currentTimeMillis()
        tool.kill()
        runner.join(15_000)

        (System.currentTimeMillis() - killedAt) shouldBeLessThan 5_000L
        runner.isAlive shouldBe false
        result shouldContain "Interrupted by the user"

        Thread.sleep(500)
        processesMatching(marker) shouldBe 0
    }

    test("a hung command is killed at the deadline, descendants and all") {
        // The background child holds the pipes open. Killing only the bash -c
        // parent would leave the drain threads waiting for an EOF that never
        // comes, so descendants have to go first.
        val marker = "kotest-timeout-marker"
        val started = System.currentTimeMillis()
        val result = BashTool(workspace, timeoutSeconds = 2).execute("sleep 300 & sleep 300 # $marker")
        val elapsedMs = System.currentTimeMillis() - started

        elapsedMs shouldBeGreaterThanOrEqual 1_900L
        elapsedMs shouldBeLessThan 15_000L
        result shouldContain "TIMED OUT after 2s"

        Thread.sleep(500)
        processesMatching(marker) shouldBe 0
    }

    test("a command that finishes inside the deadline is not disturbed") {
        val result = BashTool(workspace, timeoutSeconds = 30).execute("echo quick")
        result shouldContain "quick"
        result shouldContain "[Exit Code: 0]"
    }

    test("a failure to launch is reported rather than thrown") {
        val result = BashTool(java.io.File("/no/such/directory")).execute("echo hi")
        result shouldContain "Execution Error"
    }
})
