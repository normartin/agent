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
                request.json()["model"]!!.jsonPrimitive.content shouldBe MODEL
                request.authorization shouldBe "Bearer test-key"
            }
        }

        test("declares the one bash tool, flat") {
            MockOpenAi().use { mock ->
                mock.call()
                val tools = mock.requests.single().json()["tools"]!!.jsonArray
                tools.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe listOf("bash")

                // Responses puts name/description/parameters on the tool itself.
                // The chat-completions "function" wrapper is a 400 here.
                val tool = tools.single().jsonObject
                tool["function"] shouldBe null
                tool["type"]!!.jsonPrimitive.content shouldBe "function"
            }
        }

        test("the bash tool multiplexes its five actions onto one enum") {
            MockOpenAi().use { mock ->
                mock.call()
                val bash = mock.requests.single().json()["tools"]!!.jsonArray
                    .map { it.jsonObject }.single { it.str("name") == "bash" }

                val parameters = bash["parameters"]!!.jsonObject
                val properties = parameters["properties"]!!.jsonObject
                properties.keys shouldBe setOf("action", "command", "name", "seconds")

                properties["action"]!!.jsonObject["enum"]!!.jsonArray
                    .map { it.jsonPrimitive.content } shouldBe
                    listOf("run", "start", "output", "wait", "stop")
            }
        }

        test("the schema is strict-shaped: every field required, the optional ones nullable") {
            // Strict is the Responses default and the API rewrites the schema that way
            // regardless. Left implicit, the model had no legal way to omit a field and
            // sent "name":"" and "seconds":120 on a plain run. Declaring it ourselves
            // gives it null instead.
            MockOpenAi().use { mock ->
                mock.call()
                val bash = mock.requests.single().json()["tools"]!!.jsonArray
                    .map { it.jsonObject }.single { it.str("name") == "bash" }
                bash["strict"]!!.jsonPrimitive.content shouldBe "true"

                val parameters = bash["parameters"]!!.jsonObject
                parameters["additionalProperties"]!!.jsonPrimitive.content shouldBe "false"
                parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf("action", "command", "name", "seconds")

                val properties = parameters["properties"]!!.jsonObject
                fun types(name: String) = properties[name]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content }
                properties["action"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "string"
                types("command") shouldBe listOf("string", "null")
                types("name") shouldBe listOf("string", "null")
                types("seconds") shouldBe listOf("number", "null")
            }
        }

        test("asks for reasoning, with summaries") {
            // gpt-5.3-codex defaults to effort "none": without this block the reasoning
            // items the loop echoes back are empty and the whole mechanism is inert.
            MockOpenAi().use { mock ->
                mock.call()
                val reasoning = mock.requests.single().json()["reasoning"]!!.jsonObject
                reasoning["effort"]!!.jsonPrimitive.content shouldBe REASONING_EFFORT
                reasoning["summary"]!!.jsonPrimitive.content shouldBe "auto"
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

        test("leaves store at its default so reasoning ids stay resolvable") {
            // Sending store=false would strand the bare reasoning ids the harness
            // echoes back, and gpt-5 would re-derive its thinking every iteration.
            MockOpenAi().use { mock ->
                mock.call()
                mock.requests.single().json()["store"] shouldBe null
            }
        }

        test("pins the prompt cache: one key per process, kept for a day") {
            MockOpenAi().use { mock ->
                mock.call()
                mock.call()
                val keys = mock.requests.map { it.json()["prompt_cache_key"]!!.jsonPrimitive.content }
                keys.toSet().size shouldBe 1
                keys.first() shouldBe PROMPT_CACHE_KEY
                mock.requests.first().json()["prompt_cache_retention"]!!.jsonPrimitive.content shouldBe "24h"
            }
        }

        test("passes the history through as input, unchanged") {
            MockOpenAi().use { mock ->
                mock.call()
                val request = mock.requests.single().json()
                request["messages"] shouldBe null

                val input = request["input"]!!.jsonArray
                input.size shouldBe 2
                input.first().jsonObject["role"]!!.jsonPrimitive.content shouldBe "system"
                input.last().jsonObject["content"]!!.jsonPrimitive.content shouldBe "list the files"
            }
        }
    }

    context("the response the harness parses") {

        test("a function call arrives intact") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, toolCallBody(command = "echo hi")))
                val turn = mock.call()

                val call = turn.output.map { it.jsonObject }
                    .single { it.str("type") == "function_call" }
                // "call_id" is what a function_call_output pairs with, not "id".
                call.str("call_id") shouldBe "call_abc123"
                call.str("id") shouldBe "fc_abc123"

                Json.parseToJsonElement(call.str("arguments")!!)
                    .jsonObject.str("command") shouldBe "echo hi"
            }
        }

        test("reasoning items survive so the loop can echo them back") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, toolCallBody()))
                val turn = mock.call()
                turn.output.map { it.jsonObject.str("type") } shouldBe
                    listOf("reasoning", "function_call")
            }
        }

        test("a final answer arrives as message text with no function call") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, finalAnswerBody("all done")))
                val turn = mock.call()
                assistantText(turn.output) shouldBe "all done"
                turn.output.map { it.jsonObject.str("type") } shouldBe listOf("message")
            }
        }

        test("reasoning summaries are gathered from the reasoning items") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, reasoningSummaryBody("Look at the files", "Then answer")))
                val turn = mock.call()
                reasoningSummary(turn.output) shouldBe "Look at the files\nThen answer"
                // An empty summary array, the common case, is not "".
                reasoningSummary(Json.parseToJsonElement(toolCallBody()).jsonObject["output"]!!.jsonArray) shouldBe null
            }
        }

        test("a turn that only calls a tool has no assistant text") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, toolCallBody()))
                assistantText(mock.call().output) shouldBe null
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
                mock.script(Reply(200, """{"output":[]}"""))
                val turn = mock.call()
                turn.promptTokens shouldBe 0L
                turn.cachedPromptTokens shouldBe 0L
            }
        }

        test("a response with no output array is an error, not a crash") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, """{"id":"resp_1","status":"completed"}"""))
                shouldThrow<Exception> { mock.call() }
                    .message.shouldNotBeNull() shouldContain "did not contain an 'output' array"
            }
        }
    }

    context("the retry loop") {

        test("a rate limit is waited out and the call succeeds") {
            MockOpenAi().use { mock ->
                mock.script(rateLimited(), Reply(200, finalAnswerBody("recovered")))
                assistantText(mock.call().output) shouldBe "recovered"
                mock.requests.size shouldBe 2
            }
        }

        test("a server error is retried too") {
            MockOpenAi().use { mock ->
                mock.script(Reply(503, "upstream boom"), Reply(200, finalAnswerBody("recovered")))
                assistantText(mock.call().output) shouldBe "recovered"
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
                mock.fallback = rateLimited(resetSeconds = "30")
                val started = System.currentTimeMillis()

                shouldThrow<Exception> { mock.call(cancelled = { true }) }
                    .message.shouldNotBeNull() shouldContain "Cancelled"

                (System.currentTimeMillis() - started) shouldBeLessThan 5_000L
                mock.requests.size shouldBe 1
            }
        }
    }
})
