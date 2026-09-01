package approvals

import MockOpenAi
import answer
import bash
import console
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestScope
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import org.approvaltests.Approvals
import org.approvaltests.core.Options
import org.approvaltests.namer.NamerWrapper
import org.approvaltests.reporters.QuietReporter
import reasoning
import turn

/**
 * Approval tests to make changes in the UI visible. Failure is expected when we change the UI.
 * Adjust the *.approved.txt so that the changes are visible in the git diff.
 */
class ConsoleApprovalTest : FunSpec({

    val workspace = tempdir()

    /** ApprovalTests' default namer needs a JUnit frame Kotest doesn't have, so the test name names the file. */
    fun TestScope.verify(out: String) {
        // The tempdir varies between runs; everything else is the rendered screen, stable as-is.
        val scrubbed = out.replace(Regex("Workspace: .*"), "Workspace: <workspace>")
        // Only a human in the IDE gets the diff tool on mismatch; terminal and CI runs just fail.
        val options = if (System.getProperty("idea.active", "false").toBoolean()) Options()
                      else Options().withReporter(QuietReporter())
        Approvals.verify(scrubbed, options.forFile().withNamer(
            NamerWrapper(
                { "ConsoleApprovalTest.${testCase.name.name.replace(' ', '_')}" },
                { "src/test/kotlin/approvals" })
        ))
    }

    test("welcome screen") {
        MockOpenAi().use { mock ->
            val out = console(workspace, mock) { line("/foo"); line("/exit") }
            mock.requests.size shouldBe 0 // an unknown /command never reaches the model
            verify(out)
        }
    }

    test("user input and tool call") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning("Just echo it"), bash(command = "echo hello")),
                turn(answer("The command printed hello."))
            )
            val out = console(workspace, mock) { line("say hello"); line("/exit") }
            verify(out)
        }
    }

    test("background job start and finish") {
        MockOpenAi().use { mock ->
            mock.script(
                // sleep 1: the job must outlive the turn (stable ordering) yet finish at a stable "1s".
                turn(reasoning("In the background"), bash(action = "start", command = "sleep 1; echo done")),
                turn(answer("started")),
                turn(answer("saw it"))
            )
            val out = console(workspace, mock) {
                line("run it")
                awaitRequests(3) // nothing typed meanwhile: the third request is the finished-job delivery
                line("/exit")
            }
            verify(out)
        }
    }
})