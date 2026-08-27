import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** The prompt states which optional tools exist so the model never spends a call probing for them. */
class AvailableToolsTest : FunSpec({

    test("names what is on PATH and what is not") {
        // `sh` is on every PATH; the other name is not.
        availableTools(listOf("sh", "no-such-tool-xyz")) shouldBe "Available: sh (no no-such-tool-xyz)."
    }

    test("all present ends with a period, none present says so") {
        availableTools(listOf("sh")) shouldBe "Available: sh."
        availableTools(listOf("no-such-tool-xyz")) shouldBe "Available: none of the optional tools (no no-such-tool-xyz)."
    }

    test("the line is in the system prompt and the output cap is stated as a number") {
        val p = systemPrompt(tempdir(), depth = 0, subAgentCommand = null)
        p shouldContain "Available: "
        p shouldContain "\n$MAX_OUTPUT_CHARS chars is truncated in the middle"
    }
})
