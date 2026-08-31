import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * When history is trimmed, the dropped span is summarized by one tool-less call and the
 * summary takes its place as a user item behind the untouched system prompt.
 */
@io.kotest.core.annotation.Isolate // the harness prints to System.out
class SummaryOnTrimTest : FunSpec({

    val workspace = tempdir()
    val big = "x".repeat(50_000)
    // The third reply measures the history over budget; keeping the TRIM_TARGET_TOKENS share (~45%
    // of the chars) drops the first two turns and keeps the third.
    val overBudget = MAX_HISTORY_TOKENS * 4L / 3

    test("the dropped turn is summarized and the summary sits behind an unchanged system prompt") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(answer("one")),
                turn(answer("two")),
                turn(answer("three"), input = overBudget),
                turn(answer("Goal: keep going")),   // the summary call
                turn(answer("four"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                harness.runTask("first $big") shouldBe "one"
                harness.runTask("second $big") shouldBe "two"
                harness.runTask("third $big") shouldBe "three"
                harness.runTask("fourth") shouldBe "four"
            }
            val summaryCall = mock.requests[3]
            summaryCall.json["tools"] shouldBe null
            summaryCall.input[0].str("content") shouldBe SUMMARY_PROMPT
            summaryCall.input[1].str("content")!! shouldContain "user: first x"
            summaryCall.input[1].str("content")!! shouldContain "assistant: two"
            summaryCall.input[1].str("content")!! shouldNotContain "third"

            val next = mock.requests[4]
            next.input[0] shouldBe mock.requests[0].input[0]
            next.input[1].str("role") shouldBe "user"
            next.input[1].str("content")!! shouldContain "Goal: keep going"
            next.input[2].str("content")!! shouldContain "third"
            next.body shouldNotContain "first x"
            next.body shouldNotContain "second x"
        }
    }

    test("a failed summary call falls back to the plain trim") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(answer("one")),
                turn(answer("two")),
                turn(answer("three"), input = overBudget),
                Reply(400, """{"error":{"message":"no summary for you"}}"""),
                turn(answer("four"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                harness.runTask("first $big")
                harness.runTask("second $big")
                harness.runTask("third $big")
                harness.runTask("fourth") shouldBe "four"
            }
            val next = mock.requests[4]
            next.input[1].str("content")!! shouldContain "third"
            next.body shouldNotContain "first x"
            next.body shouldNotContain "summary of earlier"
        }
    }
})
