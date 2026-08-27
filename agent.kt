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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter.ofPattern
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

const val MODEL = "gpt-5.3-codex"
// The model defaults to no reasoning; without this the echoed reasoning items are empty.
const val REASONING_EFFORT = "medium"

// USD per 1M tokens; move with MODEL.
const val INPUT_USD_PER_1M = 1.75
const val CACHED_INPUT_USD_PER_1M = 0.175
const val OUTPUT_USD_PER_1M = 14.00

const val MAX_ITERATIONS = 25
const val TIMEOUT_SECONDS = 120L       // foreground command deadline
// One API call. A think over a big tool result runs minutes, and an in-flight request cannot be cancelled.
const val API_TIMEOUT_SECONDS = 600L
const val MAX_JOB_LOG_CHARS = 40_000   // per background job stream
const val DEFAULT_WAIT_SECONDS = 60L
const val MAX_WAIT_SECONDS = 600L

// Sub-agents are this program in one-shot mode. Jobs inherit AGENT_DEPTH + 1; at the cap the prompt stops offering them.
const val MAX_AGENT_DEPTH = 2
val AGENT_DEPTH = System.getenv("AGENT_DEPTH")?.toIntOrNull() ?: 0

// Read once at startup into the system prompt: stable across turns, so the prompt cache covers them.
val INSTRUCTION_FILES = listOf("CLAUDE.md", "AGENTS.md")
// Per-file cap: the prompt is resent every iteration and counts against TPM even when cached.
const val MAX_INSTRUCTIONS_CHARS = 20_000

// Sized against TPM, not the context window: the whole history is resent every iteration.
const val MAX_OUTPUT_CHARS = 6000
const val MAX_HISTORY_CHARS = 120_000
// Trimming forfeits the prompt cache behind the cut, so cut deep and rarely.
const val TRIM_TARGET_CHARS = MAX_HISTORY_CHARS * 6 / 10

val API_BASE = System.getenv("OPENAI_BASE_URL")?.trimEnd('/') ?: "https://api.openai.com"
const val MAX_RETRIES = 5
const val MAX_RETRY_WAIT_MS = 60_000L

// One key per process, so parallel sub-agents with the same prompt do not compete for one cache node.
val PROMPT_CACHE_KEY = "agent-" + java.util.UUID.randomUUID()

// ---------- 1. What the model sees: system prompt and tool schema ----------

/** Item 0 of every request. Stable by design: the prompt cache keys on it. */
fun systemPrompt(
    workspace: File,
    depth: Int = AGENT_DEPTH,
    subAgentCommand: String? = selfCommand(),
    instructions: String = projectInstructions(workspace),
    tools: String = availableTools()
): String = """
    You are a coding agent with a local bash shell via the "bash" tool. Working directory: ${workspace.absolutePath}
    Commands already run there; no need to cd into it. $tools
    You are in an ongoing console conversation; keep earlier turns in mind. When the request is done, answer and stop.

    Chain steps with && or a small script. A foreground command is killed after ${TIMEOUT_SECONDS}s and output over
    $MAX_OUTPUT_CHARS chars is truncated in the middle (so read at most ~150 lines per call); never start a server
    that way. Slower things go in the background:
      {"command":"ls -la"}                                       foreground (default)
      {"action":"start","command":"./gradlew build","name":"build"}
      {"action":"output","name":"server"}                        printed so far
      {"action":"wait","name":"build","seconds":120}
      {"action":"stop","name":"server"}
    Never poll: a finished job's output is delivered as a [background job "name" finished] message, and each
    user turn is preceded by a [background jobs still running] listing. Use "wait" only when you need the
    result to answer. A finished job may hand you a turn without user input: report it and stop; fix it only
    if it failed. Background jobs survive an interrupted task and die with the session.

    Keep command count low: batch related reads, make the smallest correct edit, then run the smallest validation
    that proves correctness. Prefer grep/sed one-liners over writing a script. Web pages: never print raw HTML;
    docs sites usually serve markdown at the URL with .md appended (or list pages in /llms.txt), otherwise strip
    tags (sed 's/<[^>]*>//g') and grep -C for what you need.
""".trimIndent() + subAgentPrompt(depth, subAgentCommand) + instructionsPrompt(instructions)

/** Last, so the harness text ahead of it is identical in every project. */
fun instructionsPrompt(instructions: String): String =
    if (instructions.isBlank()) ""
    else "\n\nProject instructions, read from the working directory at startup. Follow them:\n\n$instructions"

/** The instruction files present in [workspace], in [INSTRUCTION_FILES] order. */
fun instructionFiles(workspace: File): List<File> =
    INSTRUCTION_FILES.map { File(workspace, it) }.filter { it.isFile }

/** One block, a "## name" heading per file, capped per file. Unreadable files are skipped, not fatal. */
fun projectInstructions(workspace: File): String =
    instructionFiles(workspace).mapNotNull { file ->
        val text = runCatching { file.readText() }.getOrNull()?.trim() ?: return@mapNotNull null
        if (text.isEmpty()) null else "## ${file.name}\n" + truncate(text, MAX_INSTRUCTIONS_CHARS)
    }.joinToString("\n\n")

/** Empty at the depth cap, so the bottom child is never shown the idea. */
fun subAgentPrompt(depth: Int, command: String?): String {
    if (command == null || depth >= MAX_AGENT_DEPTH) return ""
    return "\n\n" + """
        Sub-agents: for an independent, self-contained subtask, start a copy of yourself as a background job.
        It has no memory of this conversation, so put everything it needs in the prompt. Its answer is
        delivered when it finishes; redirect stderr or its progress log will crowd the answer out:
          {"action":"start","command":"echo 'Count the lines in every .kt file and report the total' | $command 2>/dev/null","name":"count"}
        Run several in parallel only on disjoint files: they share this working directory.
    """.trimIndent()
}

// One tool, five actions, resent every turn. Strict requires every property, so optionals are nullable:
// that gives the model a legal way to omit a field instead of inventing filler.
val TOOLS = buildJsonArray {
    addJsonObject {
        put("type", "function")
        put("name", "bash")
        put("strict", true)
        put(
            "description",
            "Run a shell command in the workspace (killed after ${TIMEOUT_SECONDS}s), " +
                "or manage a background job that outlives the turn and is referred to by name."
        )
        putJsonObject("parameters") {
            put("type", "object")
            put("additionalProperties", false)
            putJsonArray("required") { add("action"); add("command"); add("name"); add("seconds") }
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { add("run"); add("start"); add("output"); add("wait"); add("stop") }
                    put(
                        "description",
                        "run: execute 'command' and wait. start: run it in the background. " +
                            "output: what a job has printed so far. wait: block until it finishes. stop: kill it."
                    )
                }
                putJsonObject("command") {
                    putJsonArray("type") { add("string"); add("null") }
                    put("description", "Shell command for run and start; null otherwise.")
                }
                putJsonObject("name") {
                    putJsonArray("type") { add("string"); add("null") }
                    put("description", "Job name for stop, output and wait; optional on start; null for run.")
                }
                putJsonObject("seconds") {
                    putJsonArray("type") { add("number"); add("null") }
                    put("description", "wait only: how long it may block (default $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS). Null otherwise.")
                }
            }
        }
    }
}

// ---------- 2. Agent loop ----------

/** One model turn: the raw output items and what they cost. */
data class Turn(
    val output: JsonArray,
    val promptTokens: Long,
    val cachedPromptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long
) {
    /** Mid-session this should sit high; a sudden 0 means the prefix changed. */
    val cacheHitPercent: Int get() = if (promptTokens == 0L) 0 else (cachedPromptTokens * 100 / promptTokens).toInt()
}

/** The "reasoning" items' summary parts. */
fun reasoningSummary(output: JsonArray): String? = output
    .map { it.jsonObject }
    .filter { it.str("type") == "reasoning" }
    .flatMap { it["summary"]?.jsonArray.orEmpty() }
    .mapNotNull { it.jsonObject.str("text") }
    .joinToString("\n")
    .takeUnless { it.isBlank() }

/** The "message" items' text parts. */
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
    private val baseUrl: String = API_BASE,
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
    depth: Int = AGENT_DEPTH,
    subAgentCommand: String? = selfCommand(),
    private val log: JsonlLog? = null,
    onJobFinished: (BackgroundJob) -> Unit = {}       // last, for trailing-lambda callers
) : AutoCloseable {
    private val jobs = JobRegistry(workspace, depth, log, onJobFinished)
    private val input = mutableListOf<JsonObject>()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile private var interrupted = false
    /** Tells Ctrl+C whether to cancel or quit. */
    @Volatile var busy = false; private set

    private var promptTokens = 0L
    private var cachedPromptTokens = 0L
    private var completionTokens = 0L

    private val systemPrompt = systemPrompt(workspace, depth, subAgentCommand)

    init { reset() }

    fun reset() {
        input.clear()
        input.add(message("system", systemPrompt))
        interrupted = false
        log?.event("session") {
            put("model", MODEL)
            put("workspace", workspace.absolutePath)
            put("prompt_cache_key", PROMPT_CACHE_KEY)
            put("system_prompt", systemPrompt)
            putJsonArray("instruction_files") { instructionFiles(workspace).forEach { add(it.name) } }
        }
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

    override fun close() = shutdown()

    /** The model's final answer, or null when the task did not finish (API error, interrupt, iteration cap). */
    fun runTask(taskDescription: String): String? {
        pumpJobs(announceRunning = true) // first, so the user's words come last
        input.add(message("user", taskDescription))
        log?.event("user") { put("text", taskDescription) }
        return runLoop()
    }

    /** A turn triggered by a finished job. False when its result already reached the model. */
    fun resume(): Boolean {
        if (!jobs.hasUndelivered()) return false
        println("\n🔔 A background job finished.")
        runLoop()
        return true
    }

    /** Prints the answer here: in one-shot mode System.out is stderr, and the caller owns the real stdout. */
    private fun runLoop(): String? {
        interrupted = false
        busy = true
        try {
            repeat(MAX_ITERATIONS) {
                if (interrupted) { println("\n⏹️ Interrupted. Ask again to continue."); return null }
                pumpJobs(announceRunning = false)
                val (itemsBefore, charsBefore) = input.size to input.sumOf { it.toString().length }
                trimHistory(input)
                if (input.size < itemsBefore) log?.event("trim") {
                    put("dropped", itemsBefore - input.size)
                    put("chars_before", charsBefore)
                    put("chars_after", input.sumOf { it.toString().length })
                }

                val turn = try {
                    Spinner.start("Thinking")
                    callOpenAI(httpClient, input, apiKey, baseUrl, { interrupted }, log)
                } catch (e: Exception) {
                    Spinner.stop()
                    log?.event("error") { put("message", e.message ?: e.toString()) }
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
                    "📊 %,d in (%,d cached, %d%% hit) / %,d out (%,d reasoning) · \$%.4f · session \$%.4f".format(
                        Locale.ROOT, turn.promptTokens, turn.cachedPromptTokens, turn.cacheHitPercent,
                        turn.completionTokens, turn.reasoningTokens,
                        turnCost(turn.promptTokens, turn.cachedPromptTokens, turn.completionTokens), sessionCost()
                    )
                )

                // Echoed back verbatim, reasoning included: that keeps the model's thinking alive.
                turn.output.forEach { input.add(it.jsonObject) }

                reasoningSummary(turn.output)?.let { println("🧠 $it") }
                val text = assistantText(turn.output)
                val calls = turn.output.map { it.jsonObject }.filter { it.str("type") == "function_call" }
                if (calls.isEmpty()) {
                    println("\n✅ ${text ?: "(the model returned neither an answer nor a command)"}")
                    return text ?: ""
                }
                if (text != null) println("🤔 Reasoning: $text")

                // Every call needs a reply, even skipped ones, or the next request is a 400.
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
        val id = call.str("call_id") ?: return null // not "id": a reply pairs on call_id
        val rawArgs = call.str("arguments") ?: ""
        val args = runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrNull()
        log?.event("tool_call") {
            put("call_id", id)
            put("name", call.str("name"))
            put("arguments", rawArgs)
        }

        val result = when {
            interrupted -> "[Skipped: interrupted by the user]"
            args == null -> "Execution Error: the tool call's arguments were not a JSON object (got: $rawArgs)"
            call.str("name") != "bash" -> "Execution Error: there is no tool named '${call.str("name")}'."
            else -> runBashCall(args, rawArgs)
        }
        println("📥 Output:\n$result\n")
        log?.event("tool_result") {
            put("call_id", id)
            put("output", result)
        }

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
                job.reported = true // so pumpJobs does not repeat it
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

    /** Delivers finished jobs and, at a user turn, what still runs. As messages, never in item 0: the cache keys on it. */
    private fun pumpJobs(announceRunning: Boolean) {
        jobs.drainFinished().forEach { job ->
            val notice = "[background job \"${job.name}\" finished] ${job.command}\n${truncate(job.report())}"
            println("🏁 $notice\n")
            log?.event("job_notice") { put("text", notice) }
            input.add(message("user", notice))
        }
        if (!announceRunning) return

        val live = jobs.running()
        if (live.isEmpty()) return
        val listing = live.joinToString("\n", prefix = "[background jobs still running]\n") {
            "- \"${it.name}\" (${it.elapsedSeconds}s): ${it.command}"
        }
        println("$listing\n")
        log?.event("job_notice") { put("text", listing) }
        input.add(message("user", listing))
    }

    private fun message(role: String, content: String) = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    fun sessionCost() = turnCost(promptTokens, cachedPromptTokens, completionTokens)
}

/** Past the budget, drops the oldest turns down to TRIM_TARGET_CHARS. Item 0 and the newest item survive. */
fun trimHistory(input: MutableList<JsonObject>) {
    var total = input.sumOf { it.toString().length }
    if (total <= MAX_HISTORY_CHARS) return

    val limit = input.size - 1
    var drop = 1
    while (drop < limit && total > TRIM_TARGET_CHARS) total -= input[drop++].toString().length
    // Resume on a user message: anything else is orphaned from its call or reasoning item (400).
    while (drop < limit && input[drop].str("role") != "user") drop++
    if (drop >= limit) drop = 1

    if (drop > 1) {
        input.subList(1, drop).clear()
        println("🧹 Trimmed ${drop - 1} old item(s) to stay inside the context budget.")
    }
}

/** Cached tokens are a subset of input tokens. */
fun turnCost(input: Long, cached: Long, output: Long) =
    (input - cached) / 1_000_000.0 * INPUT_USD_PER_1M +
        cached / 1_000_000.0 * CACHED_INPUT_USD_PER_1M +
        output / 1_000_000.0 * OUTPUT_USD_PER_1M

// ---------- 3. Entry points: interactive console, or one-shot on a pipe ----------

/** A queue, not stdin: a finishing job must be able to start a turn too. */
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
    val log = resolveLogPath(System.getenv("AGENT_LOG"))?.let { JsonlLog(File(it)) }

    // isTerminal(), not a null check: System.console() exists even for a pipe.
    if (System.console()?.isTerminal() == true) runConsole(workspace, apiKey, log) else runOneShot(workspace, apiKey, log)
}

/** AGENT_LOG: unset -> one file per session (local time), blank -> off, otherwise the given path. */
fun resolveLogPath(env: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? = when {
    env == null -> "agent-${ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(now)}.jsonl"
    env.isBlank() -> null
    else -> env
}

/** All of stdin is the prompt, only the answer lands on stdout. Exit 0 = answered, 1 = did not finish, 2 = usage. */
private fun runOneShot(workspace: File, apiKey: String, log: JsonlLog?) {
    // Swapping System.out for stderr turns every println into diagnostics without plumbing a logger.
    val stdout = System.out
    System.setOut(System.err)

    val prompt = System.`in`.readBytes().decodeToString().trim()
    if (prompt.isEmpty()) {
        System.err.println("❌ No prompt on stdin.")
        exitProcess(2)
    }

    val harness = BashAgentHarness(workspace, apiKey, log = log)
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.shutdown() })
    Signal.handle(Signal("INT")) { harness.interrupt() }
    instructionsNotice(workspace)?.let { System.err.println(it) }

    val answer = harness.runTask(prompt)
    System.err.println("Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
    if (answer == null) exitProcess(1)
    stdout.println(answer)
    stdout.flush()
    exitProcess(0) // a job's watcher thread would otherwise keep the JVM alive
}

private fun runConsole(workspace: File, apiKey: String, log: JsonlLog?) {
    val events = LinkedBlockingQueue<Event>() // unbounded: put() runs on job threads
    val harness = BashAgentHarness(workspace, apiKey, log = log) { events.put(Event.JobFinished) }

    // Raw tty mode; must be closed on every exit path or the shell inherits a broken tty.
    val terminal = TerminalBuilder.builder().system(true).build()
    val reader = LineReaderBuilder.builder()
        .terminal(terminal)
        .variable(LineReader.HISTORY_FILE, File(System.getProperty("user.home"), ".agent_history"))
        .build()
    fun farewell() {
        println("\n👋 Bye! Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
        terminal.close()
    }

    // Covers every exit path.
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.shutdown() })

    Signal.handle(Signal("INT")) {
        if (harness.busy) harness.interrupt() else { farewell(); exitProcess(0) }
    }

    println("🤖 Bash Agent — Workspace: ${workspace.absolutePath}")
    instructionsNotice(workspace)?.let { println(it) }
    printHelp()

    // readLine() paints the prompt, so it must not overlap a task's output: one permit per prompt wanted.
    val wantLine = Semaphore(0)
    thread(isDaemon = true) {
        while (true) {
            wantLine.acquire()
            val event = try {
                Event.Typed(reader.readLine("\n👤 You: "))
            } catch (_: EndOfFileException) {
                Event.EndOfInput
            } catch (_: UserInterruptException) { // Ctrl+C caught by JLine before our handler
                Event.EndOfInput
            }
            events.put(event)
            if (event == Event.EndOfInput) break
        }
    }

    var prompted = false
    while (true) {
        if (!prompted) {
            wantLine.release()
            prompted = true
        }
        when (val event = events.take()) {
            Event.EndOfInput -> break
            Event.JobFinished -> {
                // The prompt is still held by readLine on the other thread; redraw is the only cross-thread call JLine allows.
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

// ---------- 4. Command execution and background jobs ----------

/** How sub-agents launch this program. AGENT_CMD wins. */
fun selfCommand(): String? =
    System.getenv("AGENT_CMD")?.takeIf { it.isNotBlank() } ?: "./agent.kt"

/** Optional tools on PATH, probed once at startup so the prompt stays cache-stable. */
fun availableTools(names: List<String> = listOf("rg", "jq", "python3", "curl", "git", "gh")): String {
    val path = System.getenv("PATH").orEmpty().split(File.pathSeparator)
    val (have, missing) = names.partition { n -> path.any { File(it, n).canExecute() } }
    return "Available: ${have.joinToString(", ").ifEmpty { "none of the optional tools" }}" +
        (if (missing.isEmpty()) "." else " (no ${missing.joinToString(", no ")}).")
}

/** Names the loaded instruction files, or null when there are none. */
fun instructionsNotice(workspace: File): String? =
    instructionFiles(workspace).takeIf { it.isNotEmpty() }?.let { "📄 Instructions: " + it.joinToString(", ") { f -> f.name } }

/** Caps output, keeping head and tail: build failures land at the end. */
fun truncate(text: String, limit: Int = MAX_OUTPUT_CHARS): String {
    if (text.length <= limit) return text
    val head = text.take(limit * 2 / 3)
    val tail = text.takeLast(limit / 3)
    return "$head\n… [${text.length - head.length - tail.length} chars elided] …\n$tail"
}

/**
 * Keeps the first [head] and last [cap] chars a job printed: a build's first error and its summary sit at
 * opposite ends. [head] matches what [truncate] keeps, so the model sees the real start of the output.
 */
class BoundedLog(private val cap: Int = MAX_JOB_LOG_CHARS, private val head: Int = MAX_OUTPUT_CHARS * 2 / 3) {
    private val first = StringBuilder()
    private val buf = StringBuilder()
    private var elided = 0L

    @Synchronized
    fun append(text: String) {
        val room = head - first.length
        if (room >= text.length) { first.append(text); return }
        if (room > 0) { first.append(text, 0, room); buf.append(text, room, text.length) } else buf.append(text)
        val drop = buf.length - cap
        if (drop > 0) { buf.delete(0, drop); elided += drop }
    }

    /** The marker sits mid-output, so [truncate] leaves both ends intact. */
    @Synchronized
    fun snapshot() = if (elided == 0L) "$first$buf" else "$first\n… [$elided chars elided] …\n$buf"
}

/** What a terminal would show: progress bars redraw with \r, and every kept frame is wasted budget. */
fun collapseCarriageReturns(text: String): String {
    if ('\r' !in text) return text
    return text.replace("\r\n", "\n").split('\n').joinToString("\n") { it.substringAfterLast('\r') }
}

enum class JobState { RUNNING, EXITED, KILLED }

/** One command detached from the turn that started it. Three daemon threads drain and reap it. */
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
    /** Output has reached the model. */
    @Volatile var reported = false

    val done get() = state != JobState.RUNNING
    val elapsedSeconds get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt) / 1000

    // Before the threads: initialisers run in order.
    private val out = BoundedLog()
    private val err = BoundedLog()

    // Daemons: a live job's pipes never close and would keep the JVM up after main().
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

    // Synchronised with stop(): a kill and a natural exit race to name the state.
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

    /** Waits until the job is over (log included), [seconds] pass, or [cancelled]. */
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
        // At read time: a \r frame can straddle two drain chunks.
        val stdout = collapseCarriageReturns(out.snapshot())
        val stderr = collapseCarriageReturns(err.snapshot())
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

/** Runs every command; only background jobs are registered by name, and stay findable when done. */
class JobRegistry(
    private val workspace: File,
    private val depth: Int = AGENT_DEPTH,
    private val log: JsonlLog? = null,
    private val onFinished: (BackgroundJob) -> Unit = {} // last, for trailing-lambda callers
) {
    private val jobs = LinkedHashMap<String, BackgroundJob>()
    private var counter = 0
    @Volatile private var foreground: BackgroundJob? = null

    private fun launch(command: String) = ProcessBuilder("bash", "-c", command)
        .directory(workspace)
        .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))) // never block on input
        .apply {
            environment()["AGENT_DEPTH"] = (depth + 1).toString()
            environment()["AGENT_LOG"] = log?.path ?: "" // absolute, so a child that cd's still finds it
        }
        .start()

    /** Throws only if [command] will not launch. */
    fun start(command: String, requested: String? = null): BackgroundJob {
        val process = launch(command)
        return synchronized(this) {
            BackgroundJob(nameFor(requested), command, process, onFinished).also { jobs[it.name] = it }
        }
    }

    /** Runs [command] and waits, killing it at [seconds]. Not registered. */
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

    fun interruptForeground() { foreground?.stop() }

    /** Never reused: a second "build" becomes "build-2". */
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

    /** Each finished job exactly once. */
    @Synchronized
    fun drainFinished() = jobs.values.filter { it.done && !it.reported }.onEach { it.reported = true }

    fun killAll(): Int = running().onEach { it.stop() }.size
}

// ---------- 5. HTTP, retry and small utilities ----------

/** Flushed per line: a crash mid-turn must not lose the request that caused it. Job threads log too. */
class JsonlLog(file: File) {
    val path: String = file.absolutePath
    private val writer = java.io.FileWriter(File(path).apply { parentFile?.mkdirs() }, Charsets.UTF_8, true).buffered()

    @Synchronized
    fun event(type: String, build: JsonObjectBuilder.() -> Unit) {
        val line = buildJsonObject {
            put("ts", java.time.Instant.now().toString())
            put("pid", ProcessHandle.current().pid())
            put("depth", AGENT_DEPTH)
            put("type", type)
            build()
        }
        writer.write(line.toString())
        writer.newLine()
        writer.flush()
    }
}

fun callOpenAI(
    client: HttpClient,
    input: List<JsonObject>,
    apiKey: String,
    baseUrl: String = API_BASE,
    cancelled: () -> Boolean = { false },
    log: JsonlLog? = null
): Turn {
    // "store" stays true so the echoed reasoning ids stay resolvable (store=false needs
    // include=["reasoning.encrypted_content"]). "summary" feeds the 🧠 line.
    val payload = buildJsonObject {
        put("model", MODEL)
        put("input", JsonArray(input))
        put("tools", TOOLS)
        putJsonObject("reasoning") { put("effort", REASONING_EFFORT); put("summary", "auto") }
        put("prompt_cache_key", PROMPT_CACHE_KEY)
        // The default retention is minutes; a user idle at the prompt outlasts that.
        put("prompt_cache_retention", "24h")
    }
    val url = "$baseUrl/v1/responses"

    val request = HttpRequest.newBuilder()
        .uri(URI.create(url))
        .timeout(Duration.ofSeconds(API_TIMEOUT_SECONDS))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
        .build()

    // Rate limits are routine for a loop that resends its history: wait them out.
    var attempt = 0
    while (true) {
        // JSON, not a string, so jq can dig into it; the key lives in the header only.
        log?.event("request") { put("attempt", attempt); put("url", url); put("body", payload) }
        val started = System.nanoTime()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        log?.event("response") {
            put("attempt", attempt)
            put("status", status)
            put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
            put("body", runCatching { Json.parseToJsonElement(response.body()) }.getOrElse { JsonPrimitive(response.body()) })
        }
        if (status == 200) return response.toTurn()
        if (status != 429 && status < 500) throw Exception("API Error [Status $status]: ${response.body()}")
        if (attempt >= MAX_RETRIES) throw Exception("API Error [Status $status] after $MAX_RETRIES retries: ${response.body()}")

        val waitMs = retryDelayMs(response.headers().firstValue("retry-after").orElse(null), attempt++)
        log?.event("retry") { put("status", status); put("wait_ms", waitMs); put("attempt", attempt) }
        Spinner.stop()
        println("⏳ %d from the API — retrying in %.1fs (attempt %d/%d)".format(Locale.ROOT, status, waitMs / 1000.0, attempt, MAX_RETRIES))
        Spinner.start("Waiting")
        if (!sleepUnlessCancelled(waitMs, cancelled)) throw Exception("Cancelled while waiting out a rate limit.")
    }
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

/** Retry-After plus a pad (the window boundary earns another 429), else exponential. */
fun retryDelayMs(retryAfter: String?, attempt: Int): Long {
    val hinted = retryAfter?.trim()?.toDoubleOrNull()
    val delay = hinted?.let { (it * 1000).toLong() + 250 } ?: (1000L shl attempt)
    return delay.coerceIn(250, MAX_RETRY_WAIT_MS)
}

/** Sliced so Ctrl+C is felt. */
fun sleepUnlessCancelled(totalMs: Long, cancelled: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + totalMs
    while (!cancelled()) {
        val left = deadline - System.currentTimeMillis()
        if (left <= 0) return true
        Thread.sleep(minOf(200L, left))
    }
    return false
}

fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
fun JsonObject?.long(key: String) = this?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject

/** Real terminals only; tests read stdout as text. */
object Spinner {
    // isTerminal(), not a null check: System.console() exists even for a pipe.
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
