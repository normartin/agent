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

const val MODEL = "gpt-4o"

// USD per 1M tokens for MODEL. These move with the model — change them together.
const val INPUT_USD_PER_1M = 2.50
const val OUTPUT_USD_PER_1M = 10.00

const val MAX_ITERATIONS = 15
const val TIMEOUT_SECONDS = 120L
const val MAX_OUTPUT_CHARS = 6000
const val MAX_HISTORY_CHARS = 200_000

// ==========================================
// 1. BASH EXECUTION
// ==========================================

class BashTool(private val workspace: File) {
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

            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
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
                    !finished -> append("\n[TIMED OUT after ${TIMEOUT_SECONDS}s — process killed. Output above is partial.]")
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
    val head = text.take(4000)
    val tail = text.takeLast(2000)
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

// ==========================================
// 2. CORE AGENT HARNESS LOOP
// ==========================================

data class Turn(val message: JsonObject, val promptTokens: Long, val completionTokens: Long)

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
                    callOpenAI(httpClient, messages, apiKey)
                } catch (e: Exception) {
                    println("❌ API Error: ${e.message}")
                    return
                }

                promptTokens += turn.promptTokens
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
        val cost = cost(turn.promptTokens, turn.completionTokens)
        println(
            "📊 %,d in / %,d out · \$%.4f · session \$%.4f"
                // ROOT: a dollar figure keeps its dot whatever the console locale is.
                .format(Locale.ROOT, turn.promptTokens, turn.completionTokens, cost, sessionCost())
        )
    }

    fun sessionCost() = cost(promptTokens, completionTokens)

    private fun cost(input: Long, output: Long) =
        input / 1_000_000.0 * INPUT_USD_PER_1M + output / 1_000_000.0 * OUTPUT_USD_PER_1M
}

// ==========================================
// 3. MODERN HTTP & PRIMITIVE JSON UTILS
// ==========================================

fun callOpenAI(client: HttpClient, messages: List<JsonObject>, apiKey: String): Turn {
    val payload = buildJsonObject {
        put("model", MODEL)
        put("messages", JsonArray(messages))
        put("temperature", 0.1)
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
        .uri(URI.create("https://api.openai.com/v1/chat/completions"))
        .timeout(Duration.ofSeconds(120))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

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
    return Turn(
        message = message,
        promptTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.longOrNull ?: 0L,
        completionTokens = usage?.get("completion_tokens")?.jsonPrimitive?.longOrNull ?: 0L
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
