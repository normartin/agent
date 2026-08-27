import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/** One scripted reply. [headers] carries things like retry-after. */
data class Reply(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
)

/** What the server saw, so tests can assert on the request the harness built. */
data class Recorded(val body: String, val authorization: String?) {
    val json: JsonObject by lazy { Json.parseToJsonElement(body).jsonObject }
    /** The conversation the harness sent: item 0 is the system prompt, the last item is the newest. */
    val input: List<JsonObject> get() = json["input"]!!.jsonArray.map { it.jsonObject }
}

/**
 * An in-process stand-in for the OpenAI responses endpoint, built on the JDK's
 * own HttpServer so the suite needs no extra dependency. Replies are served
 * from a queue; once it drains, [fallback] repeats indefinitely.
 */
class MockOpenAi : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val scripted = ConcurrentLinkedQueue<Reply>()

    val requests = CopyOnWriteArrayList<Recorded>()
    // A 400, so a test that scripts too few replies fails at once instead of looping to MAX_ITERATIONS.
    var fallback = Reply(400, """{"error":{"message":"unscripted request"}}""")

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/v1/responses") { exchange ->
            requests.add(
                Recorded(
                    body = exchange.requestBody.readBytes().decodeToString(),
                    authorization = exchange.requestHeaders.getFirst("Authorization")
                )
            )
            val reply = scripted.poll() ?: fallback
            reply.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            val bytes = reply.body.toByteArray()
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    fun script(vararg replies: Reply) = apply { scripted.addAll(replies) }

    override fun close() = server.stop(0)
}

/** A 429 whose reset header is short enough to keep the suite fast. */
fun rateLimited(resetSeconds: String = "0.05") = Reply(
    status = 429,
    body = """{"error":{"message":"Rate limit reached","type":"tokens","code":"rate_limit_exceeded"}}""",
    headers = mapOf("retry-after" to resetSeconds)
)

// ---- Output items, so a scripted conversation reads top to bottom: turn(reasoning(), bash(command = "ls")) ----

/** A reasoning item, empty-summaried as real gpt-5 turns mostly are; the harness must echo it back intact. */
fun reasoning(vararg summary: String) = buildJsonObject {
    put("type", "reasoning")
    put("id", "rs_1")
    putJsonArray("summary") { summary.forEach { addJsonObject { put("type", "summary_text"); put("text", it) } } }
}

/** The model's final answer. */
fun answer(text: String) = buildJsonObject {
    put("type", "message")
    put("id", "msg_1")
    put("role", "assistant")
    put("status", "completed")
    putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", text) } }
}

/** A bash tool call as strict mode sends it: every field present, null when unset. */
fun bash(
    action: String? = null,
    command: String? = null,
    name: String? = null,
    seconds: Number? = null,
    callId: String = "call_1"
) = bashRaw(
    buildJsonObject {
        put("action", action); put("command", command); put("name", name); put("seconds", seconds)
    }.toString(),
    callId
)

/** A bash tool call with [arguments] verbatim, for shapes strict mode would not produce. */
fun bashRaw(arguments: String, callId: String = "call_1") = buildJsonObject {
    put("type", "function_call")
    put("id", "fc_$callId")
    put("call_id", callId)
    put("name", "bash")
    put("arguments", arguments)
}

/** A 200 whose output is [items], with a usage block. */
fun turn(
    vararg items: JsonObject,
    input: Long = 1000,
    cached: Long = 0,
    output: Long = 200,
    reasoningTokens: Long = 0
) = Reply(
    200,
    buildJsonObject {
        putJsonArray("output") { items.forEach { add(it) } }
        putJsonObject("usage") {
            put("input_tokens", input)
            put("output_tokens", output)
            putJsonObject("input_tokens_details") { put("cached_tokens", cached) }
            putJsonObject("output_tokens_details") { put("reasoning_tokens", reasoningTokens) }
        }
    }.toString()
)
