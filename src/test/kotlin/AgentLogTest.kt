import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/** agent-log.kt reads what agent.kt writes today; a log produced by the current harness must render. */
class AgentLogTest : FunSpec({

    test("renders a fresh agent.kt log as a transcript") {
        val workspace = tempdir()
        val file = File(workspace, "agent.jsonl")
        MockOpenAi().use { mock ->
            mock.script(turn(reasoning("Looking around"), bash(command = "echo hi")), turn(answer("all done")))
            BashAgentHarness(workspace, "test-key", mock.baseUrl, log = JsonlLog(file)).runTask("say hi") shouldBe "all done"
        }

        val out = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(out, true, Charsets.UTF_8))
        try { renderLog(listOf(file.path)) } finally { System.setOut(stdout) }

        val text = out.toString(Charsets.UTF_8)
        text shouldContain "👤"
        text shouldContain "say hi"
        text shouldContain "🧠 Looking around"
        text shouldContain "$ echo hi"
        text shouldContain "🤖"
        text shouldContain "all done"
        text shouldContain "2 steps · 1 tool calls"
    }
})
