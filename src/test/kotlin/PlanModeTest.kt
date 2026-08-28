import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

/** Plan mode is a note on the user's message: nothing else in the request may change, or the cache prefix is lost. */
class PlanModeTest : FunSpec({

    val workspace = tempdir()

    test("plan mode appends the note to the user's words, and only then") {
        MockOpenAi().use { mock ->
            mock.script(turn(answer("a plan")), turn(answer("done")))
            BashAgentHarness(workspace, "test-key", mock.baseUrl).use { harness ->
                harness.runTask("add a flag", plan = true) shouldBe "a plan"
                harness.runTask("do it") shouldBe "done"
            }
            val planned = mock.requests[0].input.last().str("content")!!
            planned shouldStartWith "add a flag\n\n[plan mode]"
            planned shouldEndWith PLAN_NOTE
            mock.requests[1].input.last().str("content") shouldBe "do it"

            // Item 0 is the cache key; a mode toggle must not touch it.
            mock.requests[1].input[0] shouldBe mock.requests[0].input[0]
        }
    }
})
