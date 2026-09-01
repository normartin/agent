import com.sun.net.httpserver.HttpServer
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
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

private val client: HttpClient = HttpClient.newHttpClient()

private val history = listOf<JsonObject>(
    buildJsonObject { put("role", "system"); put("content", "sys") },
    buildJsonObject { put("role", "user"); put("content", "list the files") }
)

private fun MockOpenAi.call(cancelled: () -> Boolean = { false }) =
    callOpenAI(client, history, "test-key", baseUrl, cancelled)

class ApiProtocolTest : FunSpec({

    context("the request the harness builds") {

        test("names the configured model and carries the key") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                val request = mock.requests.single()
                request.json["model"]!!.jsonPrimitive.content shouldBe MODEL
                request.authorization shouldBe "Bearer test-key"
            }
        }

        test("declares the bash tool flat, and the built-in web_search") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                val tools = mock.requests.single().json["tools"]!!.jsonArray.map { it.jsonObject }
                tools.map { it.str("name") ?: it.str("type") } shouldBe listOf("bash", "web_search")

                // Responses puts name/description/parameters on the tool itself.
                // The chat-completions "function" wrapper is a 400 here.
                val bash = tools.single { it.str("name") == "bash" }
                bash["function"] shouldBe null
                bash["type"]!!.jsonPrimitive.content shouldBe "function"

                // web_search is just a type: the API runs it server-side, nothing to configure.
                tools.single { it.str("type") == "web_search" }.keys shouldBe setOf("type")
            }
        }

        test("the bash tool multiplexes its six actions onto one enum") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                val bash = mock.requests.single().json["tools"]!!.jsonArray
                    .map { it.jsonObject }.single { it.str("name") == "bash" }

                val parameters = bash["parameters"]!!.jsonObject
                val properties = parameters["properties"]!!.jsonObject
                properties.keys shouldBe setOf("action", "command", "stdin", "name", "seconds")

                properties["action"]!!.jsonObject["enum"]!!.jsonArray
                    .map { it.jsonPrimitive.content } shouldBe
                    listOf("run", "start", "list", "output", "wait", "stop")
            }
        }

        test("the schema is strict-shaped: every field required, the optional ones nullable") {
            // Strict is the Responses default and the API rewrites the schema that way
            // regardless. Left implicit, the model had no legal way to omit a field and
            // sent "name":"" and "seconds":120 on a plain run. Declaring it ourselves
            // gives it null instead.
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                val bash = mock.requests.single().json["tools"]!!.jsonArray
                    .map { it.jsonObject }.single { it.str("name") == "bash" }
                bash["strict"]!!.jsonPrimitive.content shouldBe "true"

                val parameters = bash["parameters"]!!.jsonObject
                parameters["additionalProperties"]!!.jsonPrimitive.content shouldBe "false"
                parameters["required"]!!.jsonArray.map { it.jsonPrimitive.content } shouldBe
                    listOf("action", "command", "stdin", "name", "seconds")

                val properties = parameters["properties"]!!.jsonObject
                fun types(name: String) = properties[name]!!.jsonObject["type"]!!.jsonArray.map { it.jsonPrimitive.content }
                properties["action"]!!.jsonObject["type"]!!.jsonPrimitive.content shouldBe "string"
                types("command") shouldBe listOf("string", "null")
                types("stdin") shouldBe listOf("string", "null")
                types("name") shouldBe listOf("string", "null")
                types("seconds") shouldBe listOf("number", "null")
            }
        }

        test("asks for reasoning, with summaries") {
            // gpt-5.3-codex defaults to effort "none": without this block the reasoning
            // items the loop echoes back are empty and the whole mechanism is inert.
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                val reasoning = mock.requests.single().json["reasoning"]!!.jsonObject
                reasoning["effort"]!!.jsonPrimitive.content shouldBe REASONING_EFFORT
                reasoning["summary"]!!.jsonPrimitive.content shouldBe "auto"
            }
        }

        test("sends no temperature") {
            // gpt-5 is a reasoning model and rejects one — this would 400 on the
            // first real call, which no other test in the suite would catch.
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                mock.requests.single().json["temperature"] shouldBe null
            }
        }

        test("leaves store at its default so reasoning ids stay resolvable") {
            // Sending store=false would strand the bare reasoning ids the harness
            // echoes back, and gpt-5 would re-derive its thinking every iteration.
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                mock.requests.single().json["store"] shouldBe null
            }
        }

        test("pins the prompt cache: one key per process, kept for a day") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")), turn(answer("ok")))
                mock.call()
                mock.call()
                val keys = mock.requests.map { it.json["prompt_cache_key"]!!.jsonPrimitive.content }
                keys.toSet().size shouldBe 1
                keys.first() shouldBe PROMPT_CACHE_KEY
                mock.requests.first().json["prompt_cache_retention"]!!.jsonPrimitive.content shouldBe "24h"
            }
        }

        test("passes the history through as input, unchanged") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok")))
                mock.call()
                val request = mock.requests.single()
                request.json["messages"] shouldBe null
                request.input shouldBe history
            }
        }
    }

    context("the response the harness parses") {

        test("a function call arrives intact") {
            MockOpenAi().use { mock ->
                mock.script(turn(reasoning(), bash(command = "echo hi", callId = "call_7")))
                val turn = mock.call()

                val call = turn.output.map { it.jsonObject }
                    .single { it.str("type") == "function_call" }
                // "call_id" is what a function_call_output pairs with, not "id".
                call.str("call_id") shouldBe "call_7"
                call.str("id") shouldBe "fc_call_7"

                Json.parseToJsonElement(call.str("arguments")!!)
                    .jsonObject.str("command") shouldBe "echo hi"
            }
        }

        test("reasoning items survive so the loop can echo them back") {
            MockOpenAi().use { mock ->
                mock.script(turn(reasoning(), bash(command = "ls")))
                val turn = mock.call()
                turn.output.map { it.jsonObject.str("type") } shouldBe
                    listOf("reasoning", "function_call")
            }
        }

        test("a final answer arrives as message text with no function call") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("all done")))
                val turn = mock.call()
                assistantText(turn.output) shouldBe "all done"
                turn.output.map { it.jsonObject.str("type") } shouldBe listOf("message")
            }
        }

        test("reasoning summaries are gathered from the reasoning items") {
            MockOpenAi().use { mock ->
                mock.script(
                    turn(reasoning("Look at the files", "Then answer"), bash(command = "ls")),
                    turn(reasoning(), bash(command = "ls"))
                )
                reasoningSummary(mock.call().output) shouldBe "Look at the files\nThen answer"
                // An empty summary array, the common case, is not "".
                reasoningSummary(mock.call().output) shouldBe null
            }
        }

        test("a turn that only calls a tool has no assistant text") {
            MockOpenAi().use { mock ->
                mock.script(turn(reasoning(), bash(command = "ls")))
                assistantText(mock.call().output) shouldBe null
            }
        }

        test("nested usage details are picked up") {
            MockOpenAi().use { mock ->
                mock.script(turn(answer("ok"), input = 1000, cached = 768, output = 200, reasoningTokens = 128))
                val turn = mock.call()
                turn.usage.input shouldBe 1000L
                turn.usage.cached shouldBe 768L
                turn.usage.output shouldBe 200L
                turn.usage.reasoning shouldBe 128L
            }
        }

        test("missing usage counts as zero rather than throwing") {
            MockOpenAi().use { mock ->
                mock.script(Reply(200, """{"output":[]}"""))
                val turn = mock.call()
                turn.usage.input shouldBe 0L
                turn.usage.cached shouldBe 0L
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
                mock.script(rateLimited(), turn(answer("recovered")))
                assistantText(mock.call().output) shouldBe "recovered"
                mock.requests.size shouldBe 2
            }
        }

        test("a server error is retried too") {
            MockOpenAi().use { mock ->
                mock.script(Reply(503, "upstream boom", headers = mapOf("retry-after" to "0")), turn(answer("recovered")))
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

        test("cancelling an in-flight request abandons it instead of awaiting the response") {
            // The server never answers: only the cancel can end the call in time. A latch, not a sleep:
            // server.stop() joins the dispatcher thread, so a sleeping handler would stall the suite.
            val hang = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/responses") { hang.await() }
            server.start()
            try {
                val cancelled = AtomicBoolean(false)
                thread { Thread.sleep(150); cancelled.set(true) }
                val started = System.currentTimeMillis()

                shouldThrow<Exception> {
                    callOpenAI(client, history, "test-key", "http://127.0.0.1:${server.address.port}", cancelled = { cancelled.get() })
                }.message.shouldNotBeNull() shouldContain "Cancelled"

                (System.currentTimeMillis() - started) shouldBeLessThan 5_000L
            } finally {
                hang.countDown()
                server.stop(0)
            }
        }
    }
})
