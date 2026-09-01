import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** web_search runs server-side: its call items need no reply, only the verbatim echo. */
@io.kotest.core.annotation.Isolate // swaps System.out, so it must not overlap the concurrent specs
class WebSearchTest : FunSpec({

    val workspace = tempdir()

    test("a web_search_call is echoed back unanswered, and its query shown to the user") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), webSearchCall("kotlin latest release"), bash(command = "echo hi")),
                turn(answer("done"))
            )
            val saved = System.out
            val buffer = java.io.ByteArrayOutputStream()
            System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
            try {
                BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                    harness.runTask("look it up") shouldBe "done"
                }
            } finally {
                System.setOut(saved)
            }
            buffer.toString(Charsets.UTF_8) shouldContain "🔍  kotlin latest release"

            val next = mock.requests[1].input
            next.single { it.str("type") == "web_search_call" } shouldBe webSearchCall("kotlin latest release")
            // Only the bash call gets a reply; answering the search too would be a 400.
            next.count { it.str("type") == "function_call_output" } shouldBe 1
        }
    }
})
