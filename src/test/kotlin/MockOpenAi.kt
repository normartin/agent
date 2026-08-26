import com.sun.net.httpserver.HttpServer
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
data class Recorded(val body: String, val authorization: String?)

/**
 * An in-process stand-in for the OpenAI responses endpoint, built on the JDK's
 * own HttpServer so the suite needs no extra dependency. Replies are served
 * from a queue; once it drains, [fallback] repeats indefinitely.
 */
class MockOpenAi : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val scripted = ConcurrentLinkedQueue<Reply>()

    val requests = CopyOnWriteArrayList<Recorded>()
    var fallback = Reply(200, toolCallBody())

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
fun rateLimited(resetSeconds: String = "0.05s") = Reply(
    status = 429,
    body = """{"error":{"message":"Rate limit reached","type":"tokens","code":"rate_limit_exceeded"}}""",
    headers = mapOf("x-ratelimit-reset-tokens" to resetSeconds)
)

/**
 * A turn that calls the bash tool. The leading reasoning item is what a real
 * gpt-5 turn looks like and is the thing the harness has to echo back intact,
 * so it belongs in the default fixture rather than in one special-case test.
 */
fun toolCallBody(
    command: String = "ls -la",
    cachedTokens: Long = 0,
    reasoningTokens: Long = 0
) = """
{
  "output": [
    { "type": "reasoning", "id": "rs_abc123", "summary": [] },
    {
      "type": "function_call",
      "id": "fc_abc123",
      "call_id": "call_abc123",
      "name": "bash",
      "arguments": "{\"command\":\"$command\"}"
    }
  ],
  "usage": {
    "input_tokens": 1000,
    "output_tokens": 200,
    "input_tokens_details": { "cached_tokens": $cachedTokens },
    "output_tokens_details": { "reasoning_tokens": $reasoningTokens }
  }
}
"""

fun finalAnswerBody(text: String) = """
{
  "output": [
    {
      "type": "message",
      "id": "msg_abc123",
      "role": "assistant",
      "status": "completed",
      "content": [{ "type": "output_text", "text": "$text" }]
    }
  ],
  "usage": { "input_tokens": 50, "output_tokens": 10 }
}
"""

/**
 * A turn that calls the bash tool with an explicit action — the job verbs, and
 * "run" when a test wants to spell it out. [arguments] is the JSON the model
 * would send, escaped here the way the API carries it: as a string inside the
 * item. [toolCallBody] covers the other shape, a bare command with no action.
 */
fun actionCallBody(arguments: String, callId: String = "call_jobs1") = """
{
  "output": [
    { "type": "reasoning", "id": "rs_jobs", "summary": [] },
    {
      "type": "function_call",
      "id": "fc_jobs",
      "call_id": "$callId",
      "name": "bash",
      "arguments": "${arguments.replace("\"", "\\\"")}"
    }
  ],
  "usage": { "input_tokens": 10, "output_tokens": 5 }
}
"""
