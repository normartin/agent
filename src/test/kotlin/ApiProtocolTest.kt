import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.http.HttpClient

private val client: HttpClient = HttpClient.newHttpClient()

private val history = listOf<JsonObject>(
    buildJsonObject { put("role", "system"); put("content", "sys") },
    buildJsonObject { put("role", "user"); put("content", "list the files") }
)

private fun MockOpenAi.call(cancelled: () -> Boolean = { false }) =
    callOpenAI(client, history, "test-key", baseUrl, cancelled)

private fun Recorded.json() = Json.parseToJsonElement(body).jsonObject

class ApiProtocolTest : FunSpec({

    context("the request the harness builds") {

        test("names the configured model and carries the key") {
            MockOpenAi().use { mock ->
                mock.call()
                val request = mock.requests.single()
                request.json()["model"]!!.jsonPrimitive.content shouldBe "gpt-5"
                request.authorization shouldBe "Bearer test-key"
            }
        }

        test("declares exactly one bash function tool") {
            MockOpenAi().use { mock ->
                mock.call()
                val tools = mock.requests.single().json()["tools"]!!.jsonArray
                tools.size shouldBe 1

                val function = tools.single().jsonObject["function"]!!.jsonObject
                function["name"]!!.jsonPrimitive.content shouldBe "bash"

                val parameters = function["parameters"]!!.jsonObject
                val command = parameters["properties"]!!.jsonObject["command"]!!.jsonObject
                command["type"]!!.jsonPrimitive.content shouldBe "string"
                parameters["required"]!!.jsonArray
                    .map { it.jsonPrimitive.content } shouldBe listOf("command")
            }
        }

        test("sends no temperature") {
            // gpt-5 is a reasoning model and rejects one — this would 400 on the
            // first real call, which no other test in the suite would catch.
            MockOpenAi().use { mock ->
                mock.call()
                mock.requests.single().json()["temperature"] shouldBe null
            }
        }

        test("passes the message history through unchanged") {
            MockOpenAi().use { mock ->
                mock.call()
                val messages = mock.requests.single().json()["messages"]!!.jsonArray
                messages.size shouldBe 2
                messages.last().jsonObject["content"]!!.jsonPrimitive.content shouldBe "list the files"
            }
        }
    }

    context("the response the harness parses") {

        test("a tool call arrives intact") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, toolCallBody(command = "echo hi")))
                val turn = mock.call()

                val call = turn.message["tool_calls"]!!.jsonArray.single().jsonObject
                call["id"]!!.jsonPrimitive.content shouldBe "call_abc123"

                val arguments = call["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content
                Json.parseToJsonElement(arguments).jsonObject["command"]!!
                    .jsonPrimitive.content shouldBe "echo hi"
            }
        }

        test("a final answer arrives as content with no tool calls") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, finalAnswerBody("all done")))
                val turn = mock.call()
                turn.message["content"]!!.jsonPrimitive.content shouldBe "all done"
                turn.message["tool_calls"] shouldBe null
            }
        }

        test("nested usage details are picked up") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, toolCallBody(cachedTokens = 768, reasoningTokens = 128)))
                val turn = mock.call()
                turn.promptTokens shouldBe 1000L
                turn.cachedPromptTokens shouldBe 768L
                turn.completionTokens shouldBe 200L
                turn.reasoningTokens shouldBe 128L
            }
        }

        test("missing usage counts as zero rather than throwing") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, """{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""))
                val turn = mock.call()
                turn.promptTokens shouldBe 0L
                turn.cachedPromptTokens shouldBe 0L
            }
        }

        test("a response with no message is an error, not a crash") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, """{"choices":[]}"""))
                shouldThrow<Exception> { mock.call() }
                    .message.shouldNotBeNull() shouldContain "did not contain choices[0].message"
            }
        }
    }

    context("the retry loop") {

        test("a rate limit is waited out and the call succeeds") {
            MockOpenAi().use { mock ->
                mock.script(rateLimited(), Reply(200, finalAnswerBody("recovered")))
                mock.call().message["content"]!!.jsonPrimitive.content shouldBe "recovered"
                mock.requests.size shouldBe 2
            }
        }

        test("a server error is retried too") {
            MockOpenAi().use { mock ->
                mock.script(Reply(503, "upstream boom"), Reply(200, finalAnswerBody("recovered")))
                mock.call().message["content"]!!.jsonPrimitive.content shouldBe "recovered"
                mock.requests.size shouldBe 2
            }
        }

        test("a persistent rate limit gives up after MAX_RETRIES") {
            MockOpenAi().use { mock ->
                mock.fallback = rateLimited()
                shouldThrow<Exception> { mock.call() }
                    .message.shouldNotBeNull() shouldContain "after $MAX_RETRIES retries"
                // The first attempt plus one per retry.
                mock.requests.size shouldBe MAX_RETRIES + 1
            }
        }

        test("a client error is not retried") {
            MockOpenAi().use { mock ->
                mock.fallback = Reply(400, """{"error":{"message":"bad request"}}""")
                shouldThrow<Exception> { mock.call() }
                    .message.shouldNotBeNull() shouldContain "Status 400"
                mock.requests.size shouldBe 1
            }
        }

        test("cancelling during the wait aborts instead of sleeping it out") {
            MockOpenAi().use { mock ->
                mock.fallback = rateLimited(resetSeconds = "30s")
                val started = System.currentTimeMillis()

                shouldThrow<Exception> { mock.call(cancelled = { true }) }
                    .message.shouldNotBeNull() shouldContain "Cancelled"

                (System.currentTimeMillis() - started) shouldBeLessThan 5_000L
                mock.requests.size shouldBe 1
            }
        }
    }
})
