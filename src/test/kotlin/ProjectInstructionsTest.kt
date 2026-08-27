import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File

/**
 * CLAUDE.md / AGENTS.md in the working directory ride along in the system prompt: read once at
 * startup, so they sit in the cached prefix rather than in a per-turn message.
 */
class ProjectInstructionsTest : FunSpec({

    fun prompt(dir: File) = systemPrompt(dir, depth = 0, subAgentCommand = null)

    test("no files: nothing is appended") {
        val dir = tempdir()
        projectInstructions(dir) shouldBe ""
        prompt(dir) shouldNotContain "Project instructions"
        instructionsNotice(dir) shouldBe null
    }

    test("CLAUDE.md alone lands under its own heading") {
        val dir = tempdir()
        File(dir, "CLAUDE.md").writeText("Always run the tests.\n")
        val p = prompt(dir)
        p shouldContain "Project instructions, read from the working directory at startup"
        p shouldContain "## CLAUDE.md\nAlways run the tests."
        p shouldNotContain "## AGENTS.md"
        instructionsNotice(dir) shouldBe "📄  Instructions: CLAUDE.md"
    }

    test("both files, CLAUDE.md first, after the harness text") {
        val dir = tempdir()
        File(dir, "AGENTS.md").writeText("agents rules")
        File(dir, "CLAUDE.md").writeText("claude rules")
        val p = prompt(dir)
        p shouldStartWith "You are a coding agent"
        p.indexOf("## CLAUDE.md\nclaude rules") shouldBeLessThan p.indexOf("## AGENTS.md\nagents rules")
        instructionsNotice(dir) shouldBe "📄  Instructions: CLAUDE.md, AGENTS.md"
    }

    test("an empty file is ignored") {
        val dir = tempdir()
        File(dir, "AGENTS.md").writeText("  \n")
        projectInstructions(dir) shouldBe ""
    }

    test("an oversized file is cut in the middle, not dropped") {
        val dir = tempdir()
        File(dir, "CLAUDE.md").writeText("START" + "x".repeat(MAX_INSTRUCTIONS_CHARS * 2) + "END")
        val text = projectInstructions(dir)
        text.length shouldBeLessThan MAX_INSTRUCTIONS_CHARS + 200
        text shouldContain "chars elided"
        text shouldContain "## CLAUDE.md\nSTART"
        text shouldContain "END"
    }

    test("the harness sends them as part of item 0") {
        val dir = tempdir()
        File(dir, "CLAUDE.md").writeText("Prefer fish shell syntax.")
        MockOpenAi().use { mock ->
            mock.script(turn(answer("ok")))
            BashAgentHarness(dir, "test-key", mock.baseUrl, depth = 0, subAgentCommand = null).runTask("hi")

            // In the system prompt, not in a later message.
            val input = mock.requests.single().input
            input[0].str("role") shouldBe "system"
            input[0].str("content")!! shouldContain "Prefer fish shell syntax."
            input[1].str("content") shouldBe "hi"
        }
    }
})
