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
 * An in-process stand-in for the OpenAI chat-completions endpoint, built on the
 * JDK's own HttpServer so the suite needs no extra dependency. Replies are
 * served from a queue; once it drains, [fallback] repeats indefinitely.
 */
class MockOpenAi : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val scripted = ConcurrentLinkedQueue<Reply>()

    val requests = CopyOnWriteArrayList<Recorded>()
    var fallback = Reply(200, toolCallBody())

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/v1/chat/completions") { exchange ->
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

fun toolCallBody(
    command: String = "ls -la",
    cachedTokens: Long = 0,
    reasoningTokens: Long = 0
) = """
{
  "choices": [{
    "finish_reason": "tool_calls",
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_abc123",
        "type": "function",
        "function": { "name": "bash", "arguments": "{\"command\":\"$command\"}" }
      }]
    }
  }],
  "usage": {
    "prompt_tokens": 1000,
    "completion_tokens": 200,
    "prompt_tokens_details": { "cached_tokens": $cachedTokens },
    "completion_tokens_details": { "reasoning_tokens": $reasoningTokens }
  }
}
"""

fun finalAnswerBody(text: String) = """
{
  "choices": [{
    "finish_reason": "stop",
    "message": { "role": "assistant", "content": "$text" }
  }],
  "usage": { "prompt_tokens": 50, "completion_tokens": 10 }
}
"""
