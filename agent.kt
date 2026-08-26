///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.4.10
//DEPS org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import sun.misc.Signal
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

const val MODEL = "gpt-5"

// USD per 1M tokens for MODEL. These move with the model — change them together.
// Cached input is an order of magnitude cheaper, and this loop resends the same
// growing prefix on every iteration, so it is worth pricing separately.
const val INPUT_USD_PER_1M = 1.25
const val CACHED_INPUT_USD_PER_1M = 0.125
const val OUTPUT_USD_PER_1M = 10.00

const val MAX_ITERATIONS = 15
const val TIMEOUT_SECONDS = 120L

// Every iteration resends the whole history, so tokens per minute grow with the
// square of the turn count. Both budgets are sized against a tokens-per-minute
// limit rather than the model's context window: at roughly 4 chars per token a
// full history is ~30k tokens, so a MAX_ITERATIONS task stays inside gpt-5's
// 500k TPM. Caching makes the resend cheap but not free — cached tokens are
// still counted in full against TPM. Retune these if you change model or tier.
const val MAX_OUTPUT_CHARS = 6000
const val MAX_HISTORY_CHARS = 120_000

// Point this at a proxy or a local OpenAI-compatible server if you want one;
// the tests use it to stand up a mock in-process.
val API_BASE = System.getenv("OPENAI_BASE_URL")?.trimEnd('/') ?: "https://api.openai.com"

const val MAX_RETRIES = 5
const val MAX_RETRY_WAIT_MS = 60_000L

// ==========================================
// 1. BASH EXECUTION
// ==========================================

class BashTool(
    private val workspace: File,
    private val timeoutSeconds: Long = TIMEOUT_SECONDS
) {
    @Volatile
    private var current: Process? = null

    @Volatile
    private var killedByUser = false

    /** Kills the running command, if any. Called from the Ctrl+C handler thread. */
    fun kill() {
        current?.let { process ->
            killedByUser = true
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }

    fun execute(command: String): String {
        killedByUser = false
        return try {
            val process = ProcessBuilder("bash", "-c", command)
                .directory(workspace)
                // Do not pass console input through to the command, otherwise an
                // interactive command steals the chat input or blocks forever.
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            current = process

            // Drain both streams on their own threads: reading them one after the
            // other deadlocks as soon as the unread one fills its pipe buffer, and
            // a blocking read on this thread would outlive the timeout below.
            val out = StringBuilder()
            val err = StringBuilder()
            val outDrain = thread { runCatching { out.append(process.inputStream.bufferedReader().readText()) } }
            val errDrain = thread { runCatching { err.append(process.errorStream.bufferedReader().readText()) } }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                // Kill descendants first: a surviving grandchild keeps the pipes
                // open and the drain threads never see EOF.
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
                process.waitFor()
            }
            // The drains end on their own once the pipes close; the grace period
            // only bounds the pathological case.
            outDrain.join(2000)
            errDrain.join(2000)
            current = null

            buildString {
                if (out.isNotBlank()) append(out)
                if (err.isNotBlank()) append("ERROR OUTPUT:\n").append(err)
                when {
                    killedByUser -> append("\n[Interrupted by the user — process killed]")
                    !finished -> append("\n[TIMED OUT after ${timeoutSeconds}s — process killed. Output above is partial.]")
                    else -> append("\n[Exit Code: ${process.exitValue()}]")
                }
            }
        } catch (e: Exception) {
            current = null
            "Execution Error: ${e.message}"
        }
    }
}

/**
 * Caps a command's output before it enters the history. Keeps the head and the
 * tail: build failures land at the end, so dropping the tail hides the answer.
 */
fun truncate(text: String): String {
    if (text.length <= MAX_OUTPUT_CHARS) return text
    // Derived from the cap so the two can never add up to more than it allows.
    val head = text.take(MAX_OUTPUT_CHARS * 2 / 3)
    val tail = text.takeLast(MAX_OUTPUT_CHARS / 3)
    return "$head\n… [${text.length - head.length - tail.length} chars elided] …\n$tail"
}

/**
 * Drops the oldest turns once the history outgrows its budget. The system
 * prompt and the most recent message always survive.
 */
fun trimHistory(messages: MutableList<JsonObject>) {
    var total = messages.sumOf { it.toString().length }
    if (total <= MAX_HISTORY_CHARS) return

    val limit = messages.size - 1
    var drop = 1
    while (drop < limit && total > MAX_HISTORY_CHARS) {
        total -= messages[drop].toString().length
        drop++
    }
    // A tool reply whose assistant tool_calls message was dropped is an
    // orphan and a guaranteed 400, so keep dropping until history resumes
    // at a message that stands on its own.
    while (drop < limit && messages[drop]["role"]?.jsonPrimitive?.contentOrNull == "tool") {
        drop++
    }

    if (drop > 1) {
        messages.subList(1, drop).clear()
        println("🧹 Trimmed ${drop - 1} old message(s) to stay inside the context budget.")
    }
}

/**
 * What one turn's token usage costs, in USD. The API reports cached tokens as a
 * subset of the prompt total, so the uncached remainder is what gets charged at
 * the full input rate — counting both would overstate the bill tenfold.
 */
fun turnCost(input: Long, cached: Long, output: Long) =
    (input - cached) / 1_000_000.0 * INPUT_USD_PER_1M +
        cached / 1_000_000.0 * CACHED_INPUT_USD_PER_1M +
        output / 1_000_000.0 * OUTPUT_USD_PER_1M

// ==========================================
// 2. CORE AGENT HARNESS LOOP
// ==========================================

/**
 * One model turn. Cached prompt tokens are a subset of [promptTokens], and
 * reasoning tokens a subset of [completionTokens] — both are broken out because
 * they are priced or spent differently from the rest.
 */
data class Turn(
    val message: JsonObject,
    val promptTokens: Long,
    val cachedPromptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long
)

class BashAgentHarness(private val workspace: File, private val apiKey: String) {
    private val bash = BashTool(workspace)
    private val messages = mutableListOf<JsonObject>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    @Volatile
    private var interrupted = false

    /** True while a task is running, so Ctrl+C knows whether to cancel or quit. */
    @Volatile
    var busy = false
        private set

    private var promptTokens = 0L
    private var cachedPromptTokens = 0L
    private var completionTokens = 0L

    private val systemPrompt = """
        You are an autonomous coding agent with full access to a local bash shell,
        which you drive through the "bash" tool. Your working directory is:
        ${workspace.absolutePath}

        You are in an ongoing conversation with a user at a console. Keep the context
        of earlier turns in mind. When the current request is done, stop and answer
        the user instead of inventing extra work.

        Chain related steps with && or write a small script when one turn should do
        several things. Commands are killed after ${TIMEOUT_SECONDS}s, so never start a
        server or another long-running process in the foreground. Long output is
        truncated in the middle before you see it.
    """.trimIndent()

    init {
        reset()
    }

    /** Drops the conversation history and starts over with the system prompt. */
    fun reset() {
        messages.clear()
        messages.add(message("system", systemPrompt))
        interrupted = false
    }

    /** Cancels the running task. Called from the Ctrl+C handler thread. */
    fun interrupt() {
        interrupted = true
        bash.kill()
    }

    fun runTask(taskDescription: String) {
        interrupted = false
        busy = true
        try {
            messages.add(message("user", taskDescription))

            var iterations = 0
            while (iterations < MAX_ITERATIONS) {
                iterations++

                if (interrupted) {
                    println("\n⏹️ Interrupted. Ask again to continue.")
                    return
                }
                trimHistory(messages)

                val turn = try {
                    callOpenAI(httpClient, messages, apiKey) { interrupted }
                } catch (e: Exception) {
                    if (interrupted) println("\n⏹️ Interrupted. Ask again to continue.")
                    // Fall back to the exception itself: a connection failure
                    // carries no message, and "API Error: null" says nothing.
                    else println("❌ API Error: ${e.message ?: e}")
                    return
                }

                promptTokens += turn.promptTokens
                cachedPromptTokens += turn.cachedPromptTokens
                completionTokens += turn.completionTokens
                printUsage(turn)

                val content = turn.message["content"]?.jsonPrimitive?.contentOrNull?.takeUnless { it.isBlank() }
                // An empty tool_calls array is rejected on the next request, so
                // treat it as no call at all.
                val toolCalls = turn.message["tool_calls"]?.jsonArray?.takeUnless { it.isEmpty() }

                // Echo back only the fields the API expects. The raw message also
                // carries refusal/annotations, which can come back as a 400.
                messages.add(
                    buildJsonObject {
                        put("role", "assistant")
                        if (content != null) put("content", content)
                        if (toolCalls != null) put("tool_calls", toolCalls)
                    }
                )

                if (toolCalls == null) {
                    println("\n✅ ${content ?: "(the model returned neither an answer nor a command)"}")
                    return
                }

                if (content != null) println("🤔 Reasoning: $content")

                // Every tool call needs a reply, even the ones we skip: omitting
                // one is a 400 on the next request.
                for (call in toolCalls) {
                    val obj = call.jsonObject
                    val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                    // "arguments" is a JSON document carried as a string.
                    val rawArgs = obj["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                    val command = runCatching {
                        Json.parseToJsonElement(rawArgs).jsonObject["command"]?.jsonPrimitive?.contentOrNull
                    }.getOrNull()

                    val output = when {
                        interrupted -> "[Skipped: interrupted by the user]"
                        command.isNullOrBlank() -> "Execution Error: the tool call carried no 'command' argument (got: $rawArgs)"
                        else -> {
                            println("💻 Executing Bash: $command")
                            truncate(bash.execute(command))
                        }
                    }
                    println("📥 Shell Output:\n$output\n")

                    messages.add(
                        buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", id)
                            put("content", output)
                        }
                    )
                }
            }

            println("\n⏹️ Stopped after $MAX_ITERATIONS iterations. Ask again to continue.")
        } finally {
            busy = false
        }
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    private fun printUsage(turn: Turn) {
        val cached = if (turn.cachedPromptTokens > 0) " (%,d cached)".format(Locale.ROOT, turn.cachedPromptTokens) else ""
        val reasoning = if (turn.reasoningTokens > 0) " (%,d reasoning)".format(Locale.ROOT, turn.reasoningTokens) else ""
        println(
            "📊 %,d in$cached / %,d out$reasoning · \$%.4f · session \$%.4f"
                // ROOT: a dollar figure keeps its dot whatever the console locale is.
                .format(
                    Locale.ROOT,
                    turn.promptTokens,
                    turn.completionTokens,
                    turnCost(turn.promptTokens, turn.cachedPromptTokens, turn.completionTokens),
                    sessionCost()
                )
        )
    }

    fun sessionCost() = turnCost(promptTokens, cachedPromptTokens, completionTokens)
}

// ==========================================
// 3. MODERN HTTP & PRIMITIVE JSON UTILS
// ==========================================

/**
 * Reads the duration formats OpenAI uses in its rate-limit headers: "8.134s",
 * "1m30s", "500ms", or a bare number of seconds. Returns null if it is none of
 * those, so the caller can fall back to its own backoff.
 */
fun parseDelayMs(raw: String?): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toDoubleOrNull()?.let { return (it * 1000).toLong() }

    // "ms" has to be tried before "s", or "500ms" reads as 500 seconds.
    val parts = Regex("([0-9]*\\.?[0-9]+)(ms|s|m|h)").findAll(text).toList()
    if (parts.isEmpty()) return null
    return parts.sumOf { part ->
        val amount = part.groupValues[1].toDouble()
        when (part.groupValues[2]) {
            "ms" -> amount
            "s" -> amount * 1_000
            "m" -> amount * 60_000
            else -> amount * 3_600_000
        }.toLong()
    }
}

/**
 * How long to wait before retrying. The server tells us exactly when the window
 * reopens, so prefer its answer and only fall back to exponential backoff.
 */
fun retryDelayMs(response: HttpResponse<String>, attempt: Int): Long {
    fun header(name: String) = response.headers().firstValue(name).orElse(null)

    val hinted = header("retry-after-ms")?.trim()?.toDoubleOrNull()?.toLong()
        ?: parseDelayMs(header("retry-after"))
        ?: parseDelayMs(header("x-ratelimit-reset-tokens"))
        ?: parseDelayMs(header("x-ratelimit-reset-requests"))

    // A little past the stated reset: the window boundary is exact, and landing
    // on it earns a second 429.
    val delay = hinted?.plus(250) ?: (1000L shl attempt)
    return delay.coerceIn(250, MAX_RETRY_WAIT_MS)
}

/** Sleeps in slices so Ctrl+C is felt during a long rate-limit wait. */
fun sleepUnlessCancelled(totalMs: Long, cancelled: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + totalMs
    while (true) {
        if (cancelled()) return false
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) return true
        Thread.sleep(minOf(200L, left))
    }
}

fun callOpenAI(
    client: HttpClient,
    messages: List<JsonObject>,
    apiKey: String,
    baseUrl: String = API_BASE,
    cancelled: () -> Boolean = { false }
): Turn {
    val payload = buildJsonObject {
        put("model", MODEL)
        put("messages", JsonArray(messages))
        // No "temperature": GPT-5 is a reasoning model and does not take one.
        // Use "reasoning_effort" (low/medium/high) to trade quality for tokens.
        putJsonArray("tools") {
            addJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "bash")
                    put("description", "Run a shell command in the workspace and return its stdout, stderr and exit code.")
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("command") {
                                put("type", "string")
                                put("description", "The exact shell command to run.")
                            }
                        }
                        putJsonArray("required") { add("command") }
                    }
                }
            }
        }
    }.toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/v1/chat/completions"))
        .timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()

    // A rate limit is the normal weather for an agent loop, not an error: the
    // loop resends its whole history every iteration, so a busy task can spend a
    // minute's token allowance on itself. Wait it out rather than lose the task.
    var attempt = 0
    var response = client.send(request, HttpResponse.BodyHandlers.ofString())
    while (response.statusCode() == 429 || response.statusCode() >= 500) {
        if (attempt >= MAX_RETRIES) {
            throw Exception("API Error [Status ${response.statusCode()}] after $MAX_RETRIES retries: ${response.body()}")
        }
        val waitMs = retryDelayMs(response, attempt)
        attempt++
        println(
            "⏳ %d from the API — retrying in %.1fs (attempt %d/%d)"
                .format(Locale.ROOT, response.statusCode(), waitMs / 1000.0, attempt, MAX_RETRIES)
        )
        if (!sleepUnlessCancelled(waitMs, cancelled)) throw Exception("Cancelled while waiting out a rate limit.")
        response = client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    if (response.statusCode() != 200) {
        throw Exception("API Error [Status ${response.statusCode()}]: ${response.body()}")
    }

    val body = Json.parseToJsonElement(response.body()).jsonObject
    val message = body["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?: throw Exception("API response did not contain choices[0].message: ${response.body()}")

    val usage = body["usage"]?.jsonObject
    fun count(vararg path: String): Long {
        var node: JsonObject? = usage
        for (key in path.dropLast(1)) node = node?.get(key) as? JsonObject
        return node?.get(path.last())?.jsonPrimitive?.longOrNull ?: 0L
    }

    return Turn(
        message = message,
        promptTokens = count("prompt_tokens"),
        cachedPromptTokens = count("prompt_tokens_details", "cached_tokens"),
        completionTokens = count("completion_tokens"),
        reasoningTokens = count("completion_tokens_details", "reasoning_tokens")
    )
}

// ==========================================
// 4. MAIN ENTRY POINT (INTERACTIVE CONSOLE)
// ==========================================

fun printHelp() {
    println(
        """
        Commands:
          /help    Show this help
          /reset   Clear the conversation history
          /exit    Quit (or Ctrl+D)
        Ctrl+C cancels the running task; at the prompt it quits.
        Anything else is sent to the agent as a task.
        """.trimIndent()
    )
}

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
    if (apiKey.isBlank()) {
        println("❌ Please set the 'OPENAI_API_KEY' environment variable.")
        return
    }

    val workspace = File("./agent_workspace").apply { mkdirs() }.canonicalFile
    val harness = BashAgentHarness(workspace, apiKey)

    // Ctrl+C cancels the running task instead of killing the JVM. At the idle
    // prompt it still quits, which is what a console user expects.
    Signal.handle(Signal("INT")) {
        if (harness.busy) {
            harness.interrupt()
        } else {
            println("\n👋 Bye! Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
            exitProcess(0)
        }
    }

    println("🤖 Bash Agent — Workspace: ${workspace.absolutePath}")
    printHelp()

    while (true) {
        print("\n👤 You: ")
        System.out.flush()

        val input = readlnOrNull()?.trim() ?: break // Ctrl+D
        if (input.isEmpty()) continue

        when (input.lowercase()) {
            "/exit", "/quit" -> break
            "/help" -> {
                printHelp()
                continue
            }
            "/reset" -> {
                harness.reset()
                println("🧹 History cleared.")
                continue
            }
        }

        println()
        harness.runTask(input)
    }

    println("\n👋 Bye! Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
}
