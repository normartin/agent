import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe

/**
 * One-shot mode prints whatever runTask returns and turns null into a
 * non-zero exit, so the return value is the contract worth pinning.
 */
class OneShotTest : FunSpec({

    val workspace = tempdir()

    test("runTask returns the model's final answer") {
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, toolCallBody(command = "echo hi")),
                Reply(200, finalAnswerBody("all done"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl).runTask("say hi") shouldBe "all done"
        }
    }

    test("runTask returns null when the API fails") {
        MockOpenAi().use { mock ->
            mock.script(Reply(400, """{"error":{"message":"bad request"}}"""))
            BashAgentHarness(workspace, "test-key", mock.baseUrl).runTask("anything") shouldBe null
        }
    }
})
