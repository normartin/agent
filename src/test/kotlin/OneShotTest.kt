import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

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

/** The depth guard: children learn their depth from the environment and the prompt stops at the cap. */
class SubAgentDepthTest : FunSpec({

    val workspace = tempdir()

    test("a background job sees AGENT_DEPTH one deeper than its parent") {
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, toolCallBody(command = "echo depth=\$AGENT_DEPTH")),
                Reply(200, finalAnswerBody("ok"))
            )
            BashAgentHarness(workspace, "test-key", mock.baseUrl, depth = 1).runTask("what depth?")
            mock.requests[1].body shouldContain "depth=2"
        }
    }

    test("the sub-agent pattern is offered below the cap and withheld at it") {
        MockOpenAi().use { mock ->
            mock.script(Reply(200, finalAnswerBody("ok")), Reply(200, finalAnswerBody("ok")))
            BashAgentHarness(workspace, "test-key", mock.baseUrl, depth = 0, subAgentCommand = "/opt/agent").runTask("hi")
            BashAgentHarness(workspace, "test-key", mock.baseUrl, depth = MAX_AGENT_DEPTH, subAgentCommand = "/opt/agent").runTask("hi")
            mock.requests[0].body shouldContain "/opt/agent 2>/dev/null"
            mock.requests[1].body shouldNotContain "Sub-agents"
        }
    }

    test("a background job inherits the parent's log file") {
        MockOpenAi().use { mock ->
            mock.script(
                Reply(200, toolCallBody(command = "echo log=\$AGENT_LOG")),
                Reply(200, finalAnswerBody("ok"))
            )
            val log = JsonlLog(java.io.File(workspace, "session.jsonl"))
            BashAgentHarness(workspace, "test-key", mock.baseUrl, log = log).runTask("what log?")
            mock.requests[1].body shouldContain "log=${log.path}"
        }
    }

    test("no launch command means no sub-agent offer") {
        MockOpenAi().use { mock ->
            mock.script(Reply(200, finalAnswerBody("ok")))
            BashAgentHarness(workspace, "test-key", mock.baseUrl, depth = 0, subAgentCommand = null).runTask("hi")
            mock.requests[0].body shouldNotContain "Sub-agents"
        }
    }
})
