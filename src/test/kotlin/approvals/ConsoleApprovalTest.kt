package approvals

import MockOpenAi
import answer
import bash
import console
import reasoning
import turn
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import org.approvaltests.Approvals
import org.approvaltests.core.Options
import org.approvaltests.namer.NamerWrapper
import org.approvaltests.reporters.QuietReporter

/**
 * Approval tests to make changes in the UI visible. Failure is expected when we change the UI.
 * Adjust the *.approved.txt so that the changes are visible in the git diff.
 */
class ConsoleApprovalTest : FunSpec({

    val workspace = tempdir()

    /** ApprovalTests' default namer needs a JUnit frame Kotest doesn't have, so name the file explicitly. */
    fun verify(name: String, text: String) {
        // Only a human in the IDE gets the diff tool on mismatch; terminal and CI runs just fail.
        val options = if (System.getProperty("idea.active").toBoolean()) Options()
                      else Options().withReporter(QuietReporter())
        Approvals.verify(text, options.forFile().withNamer(
            NamerWrapper({ "ConsoleApprovalTest.$name" }, { "src/test/kotlin/approvals" })))
    }

    /** The tempdir varies between runs, and spinner frames plus their re-painted tail depend on timing. */
    fun scrub(out: String) = out
        .replace(Regex("Workspace: .*"), "Workspace: <workspace>")
        .replace(Regex("[⠹⠸⠴⠦⠇⠏] \\S+ \\d+s"), "")
        .lines().filterNot { it.isBlank() }
        .fold(mutableListOf<String>()) { acc, l -> if (acc.lastOrNull() != l) acc += l; acc }
        .joinToString("\n")

    test("check welcome screen") {
        MockOpenAi().use { mock ->
            val out = console(workspace, mock) { line("/exit") }
            verify("check_welcome_screen", scrub(out))
        }
    }

    test("check user input and tool call") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning("Just echo it"), bash(command = "echo hello")),
                turn(answer("The command printed hello."))
            )
            val out = console(workspace, mock) { line("say hello"); line("/exit") }
            verify("check_tool_call", scrub(out))
        }
    }
})
