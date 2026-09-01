import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant

/** The logfile is the only window into what the harness actually sends, so its shape is a contract. */
class JsonlLogTest : FunSpec({

    val workspace = tempdir()

    fun lines(file: File) = file.readLines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    test("a tool-call turn logs the whole conversation in order, with the request body as JSON") {
        MockOpenAi().use { mock ->
            mock.script(turn(reasoning(), bash(command = "echo hi")), turn(answer("all done")))
            val file = File(workspace, "log/agent.jsonl")
            BashAgentHarness(workspace, "test-key", mock.baseUrl, log = JsonlLog(file)).runTask("say hi") shouldBe "all done"

            val events = lines(file)
            events.map { it.str("type") } shouldBe listOf(
                "session", "user", "request", "response", "tool_call", "tool_result", "request", "response"
            )
            events.forEach { it.str("ts")!!.contains("T") shouldBe true; it["pid"]?.jsonPrimitive?.longOrNull shouldBe ProcessHandle.current().pid() }

            val secondRequest = events.filter { it.str("type") == "request" }[1]
            val inputTypes = secondRequest["body"]!!.jsonObject["input"]!!.jsonArray.map { it.jsonObject.str("type") }
            inputTypes shouldContain "function_call_output"
            secondRequest["body"]!!.jsonObject.str("model") shouldBe MODEL

            events.first { it.str("type") == "tool_result" }.str("output")!!.contains("hi") shouldBe true
            events.none { it.toString().contains("test-key") } shouldBe true
        }
    }

    test("a 429 leaves a retry line between two request/response pairs") {
        MockOpenAi().use { mock ->
            mock.script(rateLimited(), turn(answer("ok")))
            val file = File(workspace, "retry.jsonl")
            BashAgentHarness(workspace, "test-key", mock.baseUrl, log = JsonlLog(file)).runTask("hello") shouldBe "ok"

            lines(file).map { it.str("type") } shouldBe listOf(
                "session", "user", "request", "response", "retry", "request", "response"
            )
        }
    }

    test("resolveLogPath uses a session timestamp when AGENT_LOG is unset") {
        val zone = java.time.ZoneId.of("Europe/Copenhagen")
        resolveLogPath(null, Instant.parse("2026-01-02T03:04:05Z"), zone) shouldBe ".agent/agent-20260102-040405.jsonl"
    }

    test("resolveLogPath keeps explicit values and treats blank as off") {
        resolveLogPath("custom/path.jsonl", Instant.EPOCH) shouldBe "custom/path.jsonl"
        resolveLogPath("", Instant.EPOCH) shouldBe null
        resolveLogPath("   ", Instant.EPOCH) shouldBe null
    }
})
