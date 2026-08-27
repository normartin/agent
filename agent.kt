///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.4.10
//DEPS org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3
//DEPS org.jline:jline:3.30.16

import kotlinx.serialization.json.*
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.terminal.TerminalBuilder
import sun.misc.Signal
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

const val MODEL = "gpt-5.3-codex"

// USD per 1M tokens; move with MODEL.
const val INPUT_USD_PER_1M = 1.75
const val CACHED_INPUT_USD_PER_1M = 0.175
const val OUTPUT_USD_PER_1M = 14.00

const val MAX_ITERATIONS = 15
const val TIMEOUT_SECONDS = 120L       // foreground command deadline
const val API_TIMEOUT_SECONDS = 120L   // one API call
const val MAX_JOB_LOG_CHARS = 40_000   // held in memory per background job stream
const val DEFAULT_WAIT_SECONDS = 60L
const val MAX_WAIT_SECONDS = 600L

// Sub-agents are this same program in one-shot mode, started as background jobs. Every job
// inherits AGENT_DEPTH + 1, and the prompt stops offering the pattern at the cap, so a chain
// of children cannot fork without bound.
const val MAX_AGENT_DEPTH = 2
val AGENT_DEPTH = System.getenv("AGENT_DEPTH")?.toIntOrNull() ?: 0

// Sized against tokens-per-minute, not the context window: the whole history is
// resent every iteration and cached tokens still count against TPM.
const val MAX_OUTPUT_CHARS = 6000
const val MAX_HISTORY_CHARS = 120_000

val API_BASE = System.getenv("OPENAI_BASE_URL")?.trimEnd('/') ?: "https://api.openai.com"
const val MAX_RETRIES = 5
const val MAX_RETRY_WAIT_MS = 60_000L

// ---------- 1. Command execution ----------

/**
 * How to launch this very program again, for sub-agents. AGENT_CMD wins; otherwise it is rebuilt
 * from our own argv, which works for both the JBang script and the Gradle-installed launcher.
 * Null when neither is known, in which case the model is simply not offered sub-agents.
 */
fun selfCommand(): String? {
    System.getenv("AGENT_CMD")?.takeIf { it.isNotBlank() }?.let { return it }
    val info = ProcessHandle.current().info()
    val command = info.command().orElse(null) ?: return null
    val args = info.arguments().orElse(null) ?: return null
    return (listOf(command) + args).joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
}

/** Caps output, keeping head and tail: build failures land at the end. */
fun truncate(text: String): String {
    if (text.length <= MAX_OUTPUT_CHARS) return text
    val head = text.take(MAX_OUTPUT_CHARS * 2 / 3)
    val tail = text.takeLast(MAX_OUTPUT_CHARS / 3)
    return "$head\n… [${text.length - head.length - tail.length} chars elided] …\n$tail"
}

/** Keeps the last [cap] chars a job printed; a background job has no deadline to stop it filling memory. */
class BoundedLog(private val cap: Int = MAX_JOB_LOG_CHARS) {
    private val buf = StringBuilder()
    private var elided = 0L

    @Synchronized
    fun append(text: String) {
        buf.append(text)
        val drop = buf.length - cap
        if (drop > 0) { buf.delete(0, drop); elided += drop }
    }

    @Synchronized
    fun snapshot() = if (elided == 0L) buf.toString() else "… [$elided chars elided] …\n$buf"
}

enum class JobState { RUNNING, EXITED, KILLED }

/** One command running detached from the turn that started it. Three daemon threads drain and reap it. */
class BackgroundJob(
    val name: String,
    val command: String,
    private val process: Process,
    private val onFinished: (BackgroundJob) -> Unit = {}
) {
    val startedAt = System.currentTimeMillis()

    @Volatile var state = JobState.RUNNING; private set
    @Volatile var exitCode: Int? = null; private set
    @Volatile private var finishedAt: Long? = null
    /** Set once the finished job's output has reached the model. */
    @Volatile var reported = false

    val done get() = state != JobState.RUNNING
    val elapsedSeconds get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt) / 1000

    // Declared before the threads: initialisers run in order.
    private val out = BoundedLog()
    private val err = BoundedLog()

    // Daemons: a live job's pipes never close, and a non-daemon reader would keep the JVM up after main().
    private val outDrain = thread(isDaemon = true) { drain(process.inputStream, out) }
    private val errDrain = thread(isDaemon = true) { drain(process.errorStream, err) }
    private val watcher = thread(isDaemon = true) {
        process.waitFor()
        outDrain.join(2000)
        errDrain.join(2000)
        finish(runCatching { process.exitValue() }.getOrNull())
        onFinished(this)
    }

    private fun drain(stream: InputStream, log: BoundedLog) = runCatching {
        stream.bufferedReader().use { reader ->
            val buffer = CharArray(8192)
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                log.append(String(buffer, 0, read))
            }
        }
    }

    // Synchronised with stop(): otherwise a kill and a natural exit race to name the state.
    @Synchronized
    private fun finish(code: Int?) {
        exitCode = code
        if (state == JobState.RUNNING) state = JobState.EXITED
        finishedAt = System.currentTimeMillis()
    }

    @Synchronized
    fun stop() {
        if (done) return
        state = JobState.KILLED
        // Descendants first, or a grandchild holds the pipes open.
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Waits until the job is over (log included), [seconds] pass, or [cancelled]. Polls so Ctrl+C is felt. */
    fun await(seconds: Long, cancelled: () -> Boolean = { false }): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (!cancelled()) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            if (process.waitFor(minOf(200L, left), TimeUnit.MILLISECONDS)) {
                watcher.join(3000)
                return true
            }
        }
        return false
    }

    /** The output so far. [note] replaces the status line. */
    fun report(note: String? = null) = buildString {
        val stdout = out.snapshot()
        val stderr = err.snapshot()
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) append("ERROR OUTPUT:\n").append(stderr)
        append("\n")
        append(
            note ?: when (state) {
                JobState.RUNNING -> "[Still running after ${elapsedSeconds}s]"
                JobState.KILLED -> "[Killed after ${elapsedSeconds}s]"
                JobState.EXITED -> "[Exit Code: ${exitCode ?: "unknown"} after ${elapsedSeconds}s]"
            }
        )
    }
}

/** Runs every command; only background jobs are registered by name. Finished jobs stay findable. */
class JobRegistry(
    private val workspace: File,
    private val depth: Int = AGENT_DEPTH,
    private val onFinished: (BackgroundJob) -> Unit = {} // last, so callers can pass a trailing lambda
) {
    private val jobs = LinkedHashMap<String, BackgroundJob>()
    private var counter = 0
    @Volatile private var foreground: BackgroundJob? = null

    private fun launch(command: String) = ProcessBuilder("bash", "-c", command)
        .directory(workspace)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))) // no stdin: never block on input
        .apply { environment()["AGENT_DEPTH"] = (depth + 1).toString() } // see MAX_AGENT_DEPTH
        .start()

    /** Starts [command] detached. Throws only if it will not launch. */
    fun start(command: String, requested: String? = null): BackgroundJob {
        val process = launch(command)
        return synchronized(this) {
            BackgroundJob(nameFor(requested), command, process, onFinished).also { jobs[it.name] = it }
        }
    }

    /** Runs [command] and waits, killing it at [seconds]. Not registered and never wakes the console. */
    fun run(command: String, seconds: Long, cancelled: () -> Boolean): BackgroundJob {
        val job = BackgroundJob("foreground", command, launch(command))
        foreground = job
        try {
            if (!job.await(seconds, cancelled)) { job.stop(); job.await(2) }
            return job
        } finally {
            foreground = null
        }
    }

    /** Called from the Ctrl+C handler. */
    fun interruptForeground() { foreground?.stop() }

    /** The model's own name when usable, never reused: a second "build" becomes "build-2". */
    private fun nameFor(requested: String?): String {
        val cleaned = requested.orEmpty().replace(Regex("[^A-Za-z0-9_.-]"), "-").trim('-', '.').take(40)
        val base = cleaned.ifBlank { "job${++counter}" }
        if (base !in jobs) return base
        var suffix = 2
        while ("$base-$suffix" in jobs) suffix++
        return "$base-$suffix"
    }

    @Synchronized fun find(name: String) = jobs[name]
    @Synchronized fun running() = jobs.values.filter { !it.done }
    @Synchronized fun names() = jobs.keys.joinToString(", ").ifEmpty { "none" }
    @Synchronized fun hasUndelivered() = jobs.values.any { it.done && !it.reported }

    /** Finished jobs not yet handed to the model; each exactly once. */
    @Synchronized
    fun drainFinished() = jobs.values.filter { it.done && !it.reported }.onEach { it.reported = true }

    fun killAll(): Int = running().onEach { it.stop() }.size
}

/** Drops the oldest turns past the budget. The system prompt and the newest item always survive. */
fun trimHistory(input: MutableList<JsonObject>) {
    var total = input.sumOf { it.toString().length }
    if (total <= MAX_HISTORY_CHARS) return

    val limit = input.size - 1
    var drop = 1
    while (drop < limit && total > MAX_HISTORY_CHARS) total -= input[drop++].toString().length
    // An orphaned function_call_output is a guaranteed 400.
    while (drop < limit && input[drop].str("type") == "function_call_output") drop++

    if (drop > 1) {
        input.subList(1, drop).clear()
        println("🧹 Trimmed ${drop - 1} old item(s) to stay inside the context budget.")
    }
}

/** Cached tokens are a subset of input tokens, so only the remainder bills at the full rate. */
fun turnCost(input: Long, cached: Long, output: Long) =
    (input - cached) / 1_000_000.0 * INPUT_USD_PER_1M +
        cached / 1_000_000.0 * CACHED_INPUT_USD_PER_1M +
        output / 1_000_000.0 * OUTPUT_USD_PER_1M

/** A one-line "still working" indicator for the API call. Real terminals only; tests read stdout as text. */
object Spinner {
    // isTerminal(), not a null check: since JDK 22 System.console() exists even for a pipe.
    private val enabled = System.console()?.isTerminal() == true
    private var worker: Thread? = null

    @Synchronized
    fun start(text: String) {
        if (!enabled || worker != null) return
        val startedAt = System.currentTimeMillis()
        worker = thread(isDaemon = true) {
            var frame = 0
            try {
                while (true) {
                    print("\r\u001B[2K${"⠹⠸⠴⠦⠇⠏"[frame++ % 6]} $text ${(System.currentTimeMillis() - startedAt) / 1000}s")
                    Thread.sleep(90)
                }
            } catch (_: InterruptedException) {
                print("\r\u001B[2K")
            }
        }
    }

    @Synchronized
    fun stop() {
        worker?.apply { interrupt(); join() }
        worker = null
    }
}

// ---------- 2. Core agent harness loop ----------

/** One model turn: the raw Responses output items and what they cost. */
data class Turn(
    val output: JsonArray,
    val promptTokens: Long,
    val cachedPromptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long
)

/** The assistant's prose, gathered from the "message" items' content parts. */
fun assistantText(output: JsonArray): String? = output
    .map { it.jsonObject }
    .filter { it.str("type") == "message" }
    .flatMap { it["content"]?.jsonArray.orEmpty() }
    .mapNotNull { it.jsonObject.str("text") }
    .joinToString("\n")
    .takeUnless { it.isBlank() }

class BashAgentHarness(
    private val workspace: File,
    private val apiKey: String,
    private val baseUrl: String = API_BASE,          // tests point this at a mock
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
    depth: Int = AGENT_DEPTH,                         // tests pin it; real runs read the environment
    subAgentCommand: String? = selfCommand(),
    onJobFinished: (BackgroundJob) -> Unit = {}       // rung from a job's watcher thread
) {
    private val jobs = JobRegistry(workspace, depth, onJobFinished)
    private val input = mutableListOf<JsonObject>()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile private var interrupted = false
    /** True while a task runs, so Ctrl+C knows whether to cancel or quit. */
    @Volatile var busy = false; private set

    private var promptTokens = 0L
    private var cachedPromptTokens = 0L
    private var completionTokens = 0L

    private val systemPrompt = """
        You are a coding agent with a local bash shell via the "bash" tool. Working directory: ${workspace.absolutePath}
        You are in an ongoing console conversation; keep earlier turns in mind. When the request is done, answer and stop.

        Chain steps with && or a small script. A foreground command is killed after ${TIMEOUT_SECONDS}s and long output
        is truncated in the middle, so never start a server that way. Slower things go in the background:
          {"command":"ls -la"}                                       foreground (default)
          {"action":"start","command":"./gradlew build","name":"build"}
          {"action":"output","name":"server"}                        printed so far
          {"action":"wait","name":"build","seconds":120}
          {"action":"stop","name":"server"}
        Never poll: a finished job's output is delivered as a [background job "name" finished] message, and each
        user turn is preceded by a [background jobs still running] listing. Use "wait" only when you need the
        result to answer. A finished job may hand you a turn without user input: report it and stop; fix it only
        if it failed. Background jobs survive an interrupted task and die with the session.
    """.trimIndent() + subAgentPrompt(depth, subAgentCommand)

    init { reset() }

    /** Empty at the depth cap, so a child at the bottom of the chain is never shown the idea. */
    private fun subAgentPrompt(depth: Int, command: String?): String {
        if (command == null || depth >= MAX_AGENT_DEPTH) return ""
        return "\n\n" + """
            Sub-agents: for an independent, self-contained subtask, start a copy of yourself as a background job.
            It has no memory of this conversation, so put everything it needs in the prompt. Its answer is
            delivered when it finishes; redirect stderr or its progress log will crowd the answer out:
              {"action":"start","command":"echo 'Count the lines in every .kt file and report the total' | $command 2>/dev/null","name":"count"}
            Run several in parallel only on disjoint files: they share this working directory.
        """.trimIndent()
    }

    fun reset() {
        input.clear()
        input.add(message("system", systemPrompt))
        interrupted = false
    }

    /** Cancels the running task, foreground command included. Background jobs live on. */
    fun interrupt() {
        interrupted = true
        jobs.interruptForeground()
    }

    fun shutdown() {
        val killed = jobs.killAll()
        if (killed > 0) println("🛑 Killed $killed background job(s).")
    }

    /** The model's final answer, or null when the task did not finish (API error, interrupt, iteration cap). */
    fun runTask(taskDescription: String): String? {
        pumpJobs(announceRunning = true) // before the user's message, so their words come last
        input.add(message("user", taskDescription))
        return runLoop()
    }

    /** A turn nobody asked for, on the back of a finished job. False when the result already reached the model. */
    fun resume(): Boolean {
        if (!jobs.hasUndelivered()) return false
        println("\n🔔 A background job finished.")
        runLoop()
        return true
    }

    /**
     * Prints the answer here rather than in the callers: in one-shot mode System.out is already
     * stderr by then, and the caller writes the returned text to the real stdout itself.
     */
    private fun runLoop(): String? {
        interrupted = false
        busy = true
        try {
            repeat(MAX_ITERATIONS) {
                if (interrupted) { println("\n⏹️ Interrupted. Ask again to continue."); return null }
                pumpJobs(announceRunning = false)
                trimHistory(input)

                val turn = try {
                    Spinner.start("Thinking")
                    callOpenAI(httpClient, input, apiKey, baseUrl) { interrupted }
                } catch (e: Exception) {
                    Spinner.stop()
                    if (interrupted) println("\n⏹️ Interrupted. Ask again to continue.")
                    else println("❌ API Error: ${e.message ?: e}")
                    return null
                } finally {
                    Spinner.stop()
                }

                promptTokens += turn.promptTokens
                cachedPromptTokens += turn.cachedPromptTokens
                completionTokens += turn.completionTokens
                println(
                    "📊 %,d in (%,d cached) / %,d out (%,d reasoning) · \$%.4f · session \$%.4f".format(
                        Locale.ROOT, turn.promptTokens, turn.cachedPromptTokens, turn.completionTokens, turn.reasoningTokens,
                        turnCost(turn.promptTokens, turn.cachedPromptTokens, turn.completionTokens), sessionCost()
                    )
                )

                // Echo every output item back verbatim, reasoning included: that keeps gpt-5.x's thinking alive.
                turn.output.forEach { input.add(it.jsonObject) }

                val text = assistantText(turn.output)
                val calls = turn.output.map { it.jsonObject }.filter { it.str("type") == "function_call" }
                if (calls.isEmpty()) {
                    println("\n✅ ${text ?: "(the model returned neither an answer nor a command)"}")
                    return text ?: ""
                }
                if (text != null) println("🤔 Reasoning: $text")

                // Every call needs a reply, even skipped ones: a missing one is a 400.
                calls.forEach { call -> runCall(call)?.let(input::add) }
            }
            println("\n⏹️ Stopped after $MAX_ITERATIONS iterations. Ask again to continue.")
            return null
        } finally {
            busy = false
        }
    }

    /** Runs one function_call and builds its function_call_output. */
    private fun runCall(call: JsonObject): JsonObject? {
        val id = call.str("call_id") ?: return null // "id" names the item; "call_id" is what a reply pairs with
        val rawArgs = call.str("arguments") ?: ""
        val args = runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrNull()

        val result = when {
            interrupted -> "[Skipped: interrupted by the user]"
            args == null -> "Execution Error: the tool call's arguments were not a JSON object (got: $rawArgs)"
            call.str("name") != "bash" -> "Execution Error: there is no tool named '${call.str("name")}'."
            else -> runBashCall(args, rawArgs)
        }
        println("📥 Output:\n$result\n")

        return buildJsonObject {
            put("type", "function_call_output")
            put("call_id", id)
            put("output", result)
        }
    }

    private fun runBashCall(args: JsonObject, rawArgs: String): String {
        val name = args.str("name")
        val action = args.str("action")?.lowercase() ?: "run"
        val command = args.str("command")
        if (action in setOf("run", "start") && command.isNullOrBlank()) {
            return "Execution Error: '$action' needs a 'command' (got: $rawArgs)"
        }

        return when (action) {
            "run" -> {
                println("💻 Executing Bash: $command")
                runCatching { jobs.run(command!!, timeoutSeconds) { interrupted } }.fold(
                    onSuccess = { job ->
                        val note = when {
                            job.state != JobState.KILLED -> null
                            interrupted -> "[Interrupted by the user — process killed]"
                            else -> "[TIMED OUT after ${timeoutSeconds}s — process killed. Output above is partial.]"
                        }
                        truncate(job.report(note))
                    },
                    onFailure = { "Execution Error: ${it.message}" }
                )
            }

            "start" -> runCatching { jobs.start(command!!, name) }.fold(
                onSuccess = { job ->
                    println("🚀 Started background job \"${job.name}\": $command")
                    "Started background job \"${job.name}\". Its output will be delivered to you when it finishes."
                },
                onFailure = { "Execution Error: ${it.message}" }
            )

            "stop" -> withJob(name) { job ->
                job.stop()
                job.await(2)
                job.reported = true // handed over here, so pumpJobs must not repeat it
                println("🛑 Stopped background job \"${job.name}\"")
                "Stopped background job \"${job.name}\".\n${truncate(job.report())}"
            }

            "output" -> withJob(name) { job ->
                if (job.done) job.reported = true
                truncate(job.report())
            }

            "wait" -> withJob(name) { job ->
                val seconds = (args["seconds"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: DEFAULT_WAIT_SECONDS)
                    .coerceIn(1, MAX_WAIT_SECONDS)
                println("⏳ Waiting up to ${seconds}s for background job \"${job.name}\"…")
                if (job.await(seconds) { interrupted }) job.reported = true
                truncate(job.report())
            }

            else -> "Execution Error: unknown action '$action' — use run, start, stop, output or wait."
        }
    }

    private fun withJob(name: String?, block: (BackgroundJob) -> String): String {
        if (name.isNullOrBlank()) return "Execution Error: this action needs a 'name'."
        val job = jobs.find(name)
            ?: return "Execution Error: there is no background job named \"$name\". Jobs: ${jobs.names()}"
        return block(job)
    }

    /**
     * Delivers finished jobs' output and, at a user turn, what is still running. Appended as plain
     * messages, never folded into the system prompt: item 0 changing would forfeit the prompt cache.
     */
    private fun pumpJobs(announceRunning: Boolean) {
        jobs.drainFinished().forEach { job ->
            val notice = "[background job \"${job.name}\" finished] ${job.command}\n${truncate(job.report())}"
            println("🏁 $notice\n")
            input.add(message("user", notice))
        }
        if (!announceRunning) return

        val live = jobs.running()
        if (live.isEmpty()) return
        val listing = live.joinToString("\n", prefix = "[background jobs still running]\n") {
            "- \"${it.name}\" (${it.elapsedSeconds}s): ${it.command}"
        }
        println("$listing\n")
        input.add(message("user", listing))
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    fun sessionCost() = turnCost(promptTokens, cachedPromptTokens, completionTokens)
}

// ---------- 3. HTTP and primitive JSON utils ----------

fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject?.long(key: String) = this?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject

/** Retry-After seconds plus a pad (landing on the window boundary earns another 429), else exponential. */
fun retryDelayMs(response: HttpResponse<String>, attempt: Int): Long {
    val hinted = response.headers().firstValue("retry-after").orElse(null)?.trim()?.toDoubleOrNull()
    val delay = hinted?.let { (it * 1000).toLong() + 250 } ?: (1000L shl attempt)
    return delay.coerceIn(250, MAX_RETRY_WAIT_MS)
}

/** Sleeps in slices so Ctrl+C is felt during a long wait. */
fun sleepUnlessCancelled(totalMs: Long, cancelled: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + totalMs
    while (!cancelled()) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) return true
        Thread.sleep(minOf(200L, left))
    }
    return false
}

private fun HttpResponse<String>.toTurn(): Turn {
    val json = Json.parseToJsonElement(body()).jsonObject
    val output = json["output"]?.jsonArray
        ?: throw Exception("API response did not contain an 'output' array: ${body()}")
    val usage = json["usage"]?.jsonObject
    return Turn(
        output = output,
        promptTokens = usage.long("input_tokens"),
        cachedPromptTokens = usage.obj("input_tokens_details").long("cached_tokens"),
        completionTokens = usage.long("output_tokens"),
        reasoningTokens = usage.obj("output_tokens_details").long("reasoning_tokens")
    )
}

// One tool, five actions: a single flat schema (Responses shape, no "function" wrapper) is resent every turn.
val TOOLS = buildJsonArray {
    addJsonObject {
        put("type", "function")
        put("name", "bash")
        put(
            "description",
            "Run a shell command in the workspace (killed after ${TIMEOUT_SECONDS}s), " +
                "or manage a background job that outlives the turn and is referred to by name."
        )
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { add("run"); add("start"); add("output"); add("wait"); add("stop") }
                    put(
                        "description",
                        "Default \"run\": execute 'command' and wait. start: run it in the background. " +
                            "output: what a job has printed so far. wait: block until it finishes. stop: kill it."
                    )
                }
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "Shell command; required for run and start.")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put("description", "Job name; required for stop, output and wait. Optional on start.")
                }
                putJsonObject("seconds") {
                    put("type", "number")
                    put("description", "How long wait may block. Default $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS.")
                }
            }
        }
    }
}

fun callOpenAI(
    client: HttpClient,
    input: List<JsonObject>,
    apiKey: String,
    baseUrl: String = API_BASE,
    cancelled: () -> Boolean = { false }
): Turn {
    // No temperature: gpt-5.x is a reasoning model. "store" stays at its default of true so the bare
    // reasoning ids we echo back stay resolvable; store=false needs include=["reasoning.encrypted_content"].
    val payload = buildJsonObject {
        put("model", MODEL)
        put("input", JsonArray(input))
        put("tools", TOOLS)
    }.toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/v1/responses"))
        .timeout(Duration.ofSeconds(API_TIMEOUT_SECONDS))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()

    // Rate limits are normal weather for a loop that resends its history: wait them out.
    var attempt = 0
    while (true) {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status == 200) return response.toTurn()
        if (status != 429 && status < 500) throw Exception("API Error [Status $status]: ${response.body()}")
        if (attempt >= MAX_RETRIES) throw Exception("API Error [Status $status] after $MAX_RETRIES retries: ${response.body()}")

        val waitMs = retryDelayMs(response, attempt++)
        Spinner.stop()
        println("⏳ %d from the API — retrying in %.1fs (attempt %d/%d)".format(Locale.ROOT, status, waitMs / 1000.0, attempt, MAX_RETRIES))
        Spinner.start("Waiting")
        if (!sleepUnlessCancelled(waitMs, cancelled)) throw Exception("Cancelled while waiting out a rate limit.")
    }
}

// ---------- 4. Main entry point (interactive console, or one-shot on a pipe) ----------

/** The console waits on a queue, not stdin: a finishing job must be able to start a turn too. */
private sealed interface Event {
    data class Typed(val line: String) : Event
    data object EndOfInput : Event
    data object JobFinished : Event
}

fun printHelp() = println(
    """
    Commands:
      /help    Show this help
      /reset   Clear the conversation history
      /exit    Quit (or Ctrl+D)
    Arrow keys edit the line; Up/Down recall earlier prompts (kept in ~/.agent_history).
    Ctrl+C cancels the running task; at the prompt it quits.
    Background jobs survive /reset and a cancelled task; they die with the session.
    Note: requests use store=true, so OpenAI retains this session for about 30 days.
    Piped stdin (echo "…" | ./agent.kt) runs that one prompt: answer on stdout, log on stderr, then exit.
    """.trimIndent()
)

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
    if (apiKey.isBlank()) {
        System.err.println("❌ Please set the 'OPENAI_API_KEY' environment variable.")
        exitProcess(2)
    }
    if (AGENT_DEPTH > MAX_AGENT_DEPTH) {
        System.err.println("❌ AGENT_DEPTH=$AGENT_DEPTH exceeds MAX_AGENT_DEPTH=$MAX_AGENT_DEPTH; refusing to nest deeper.")
        exitProcess(2)
    }
    val workspace = File(".").apply { mkdirs() }.canonicalFile

    // isTerminal(), not a null check: since JDK 22 System.console() exists even for a pipe.
    if (System.console()?.isTerminal() == true) runConsole(workspace, apiKey) else runOneShot(workspace, apiKey)
}

/**
 * Non-interactive mode: all of stdin is the prompt, the answer is all that lands on stdout.
 * Exit code 0 = answered, 1 = did not finish, 2 = usage.
 */
private fun runOneShot(workspace: File, apiKey: String) {
    // The harness prints progress with println throughout. Swapping System.out for stderr turns all
    // of it into diagnostics without threading a logger through a single-file harness; the real
    // stdout is kept aside for the one thing a caller pipes us for.
    val stdout = System.out
    System.setOut(System.err)

    val prompt = System.`in`.readBytes().decodeToString().trim()
    if (prompt.isEmpty()) {
        System.err.println("❌ No prompt on stdin.")
        exitProcess(2)
    }

    val harness = BashAgentHarness(workspace, apiKey)
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.shutdown() })
    Signal.handle(Signal("INT")) { harness.interrupt() }

    val answer = harness.runTask(prompt)
    System.err.println("Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
    if (answer == null) exitProcess(1)
    stdout.println(answer)
    stdout.flush()
    exitProcess(0) // explicit: a background job's watcher thread would otherwise keep the JVM alive
}

private fun runConsole(workspace: File, apiKey: String) {
    val events = LinkedBlockingQueue<Event>() // unbounded: put() runs on a job's watcher thread
    val harness = BashAgentHarness(workspace, apiKey) { events.put(Event.JobFinished) }

    // JLine puts the tty in raw mode so arrow keys reach us as editing commands instead of
    // escape sequences. It must be closed on every exit path or the shell inherits a broken tty.
    val terminal = TerminalBuilder.builder().system(true).build()
    val reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .variable(LineReader.HISTORY_FILE, File(System.getProperty("user.home"), ".agent_history"))
        .build()
    fun farewell() {
        println("\n👋 Bye! Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
        terminal.close()
    }

    // Covers /exit, Ctrl+D and the exitProcess below.
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.shutdown() })

    Signal.handle(Signal("INT")) {
        if (harness.busy) harness.interrupt() else { farewell(); exitProcess(0) }
    }

    println("🤖 Bash Agent — Workspace: ${workspace.absolutePath}")
    printHelp()

    // readLine() paints the prompt while it runs, so it may only be active while we are actually
    // waiting for the user — not while a task is streaming output. The main loop hands out one
    // permit per prompt it wants; type-ahead during a task is dropped, as in most REPLs.
    val wantLine = Semaphore(0)
    thread(isDaemon = true) {
        while (true) {
            wantLine.acquire()
            val event = try {
                Event.Typed(reader.readLine("\n👤 You: "))
            } catch (_: EndOfFileException) {
                Event.EndOfInput
            } catch (_: UserInterruptException) { // Ctrl+C, if JLine intercepts it before our handler
                Event.EndOfInput
            }
            events.put(event)
            if (event == Event.EndOfInput) break
        }
    }

    var prompted = false // a wake-up with nothing to say leaves the prompt standing
    while (true) {
        if (!prompted) {
            wantLine.release()
            prompted = true
        }
        when (val event = events.take()) {
            Event.EndOfInput -> break
            Event.JobFinished -> {
                // resume() prints under a prompt that readLine is still holding on the other
                // thread; redrawing is the only cross-thread call JLine supports for that.
                if (harness.resume()) prompted = false
                else runCatching { reader.callWidget(LineReader.REDRAW_LINE); reader.callWidget(LineReader.REDISPLAY) }
            }
            is Event.Typed -> {
                prompted = false
                val line = event.line.trim()
                when (line.lowercase()) {
                    "" -> {}
                    "/exit", "/quit" -> break
                    "/help" -> printHelp()
                    "/reset" -> { harness.reset(); println("🧹 History cleared.") }
                    else -> { println(); harness.runTask(line) }
                }
            }
        }
    }
    farewell()
}
