///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 25+
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.4.10
//DEPS org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0
//DEPS org.jline:jline:4.4.0

import kotlinx.serialization.json.*
import org.jline.keymap.KeyMap
import org.jline.reader.EndOfFileException
import org.jline.reader.LineReader
import org.jline.reader.LineReaderBuilder
import org.jline.reader.UserInterruptException
import org.jline.reader.Widget
import org.jline.reader.impl.DefaultParser
import org.jline.reader.impl.LineReaderImpl
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.utils.InfoCmp
import sun.misc.Signal
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter.ofPattern
import java.util.Locale
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.system.exitProcess

const val MODEL = "gpt-5.3-codex"
// The model defaults to no reasoning; without this the echoed reasoning items are empty.
const val REASONING_EFFORT = "medium"

// USD per 1M tokens; move with MODEL.
const val INPUT_USD_PER_1M = 1.75
const val CACHED_INPUT_USD_PER_1M = 0.175
const val OUTPUT_USD_PER_1M = 14.00
const val CONTEXT_WINDOW_TOKENS = 400_000 // moves with MODEL too

const val MAX_ITERATIONS = 25
const val TIMEOUT_SECONDS = 120L       // foreground command deadline
// One API call. A think over a big tool result runs minutes; Ctrl+C cancels the in-flight request.
const val API_TIMEOUT_SECONDS = 600L
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
const val MAX_OUTPUT_CHARS = 12_000
const val MAX_HISTORY_TOKENS = 30_000
/** Live tail under the spinner only; the model and the log always get the whole result. */
const val SHOWN_OUTPUT_LINES = 5
// Trimming forfeits the prompt cache behind the cut, so cut deep and rarely.
const val TRIM_TARGET_TOKENS = MAX_HISTORY_TOKENS * 6 / 10
// Tool results dominate the dropped span, and the summary only needs their gist.
const val SUMMARY_RESULT_CHARS = 2_000

val API_BASE = System.getenv("OPENAI_BASE_URL")?.trimEnd('/') ?: "https://api.openai.com"
const val MAX_RETRIES = 5
const val MAX_RETRY_WAIT_MS = 60_000L

// One key per process, so parallel sub-agents with the same prompt do not compete for one cache node.
val PROMPT_CACHE_KEY = "agent-" + java.util.UUID.randomUUID()

// isTerminal(), not a null check: System.console() exists even for a pipe.
val IS_TTY = System.console()?.isTerminal() == true

// ---------- 1. What the model sees: system prompt and tool schema ----------

/** Item 0 of every request. Stable by design: the prompt cache keys on it. */
fun systemPrompt(workspace: File, depth: Int = AGENT_DEPTH, subAgentCommand: String? = selfCommand()): String = """
    You are a coding agent with a local bash shell via the "bash" tool. Working directory: ${workspace.absolutePath}
    Commands already run there; no need to cd into it. ${availableTools()} There is no apply_patch: edit with sed, python3 or a heredoc.
    You are in an ongoing console conversation; keep earlier turns in mind. When the request is done, answer and stop.

    Chain steps with && or a small script (after a heredoc, start the next command on its own
    line, never with &&). A foreground command is killed after ${TIMEOUT_SECONDS}s and output over
    $MAX_OUTPUT_CHARS chars is truncated in the middle (so read at most ~300 lines per call); the marker names a
    file holding the full output, so sed -n or grep -n that instead of re-running. Never start a server in the
    foreground. Slower things go in the background:
      {"action":"run","command":"ls -la"}                        foreground
      {"action":"start","command":"./gradlew build","name":"build"}
      {"action":"list"}                                          known jobs
      {"action":"output","name":"server"}                        printed so far
      {"action":"wait","name":"build","seconds":120}
      {"action":"stop","name":"server"}
    Never poll: a finished job's output is delivered as a [background job "name" finished] message, and each
    user turn is preceded by a [background jobs still running] listing. Use "output" or "wait" only when the
    user asks for a job's status or you need its result to answer. A finished job may hand you a turn without
    user input: report it and stop; fix it only if it failed. Background jobs survive an interrupted task and die with the session.

    Keep command count low: batch related reads, make the smallest correct edit, then run the smallest validation
    that proves correctness. Prefer grep/sed one-liners over writing a script. Web pages: never print raw HTML;
    docs sites usually serve markdown at the URL with .md appended (or list pages in /llms.txt), otherwise strip
    tags (sed 's/<[^>]*>//g') and grep -C for what you need.
""".trimIndent() + subAgentPrompt(depth, subAgentCommand) +
    // Instructions last, so the harness text ahead of them is identical in every project.
    projectInstructions(workspace).takeIf { it.isNotBlank() }
        ?.let { "\n\nProject instructions, read from the working directory at startup. Follow them:\n\n$it" }.orEmpty()

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

val SUMMARY_PROMPT = """
    Summarize the conversation below for a coding agent that will continue it. Headings: Goal; Progress (done /
    in progress / blocked); Key decisions; Files read or modified; Next steps. Be concrete: keep paths, commands,
    error messages and numbers. Plain text, no preamble.
""".trimIndent()

// Appended to the user's message, never item 0: the prompt cache keys on item 0.
val PLAN_NOTE = "\n\n[plan mode] Read-only: explore with bash (cat, grep, git log …) but do not create, modify or " +
    "delete files, and do not start background jobs. End with a concrete step-by-step plan (files, edits, " +
    "validation) and stop; the user will approve it and ask you to execute it."

// One tool, six actions, resent every turn. We mark all fields required, so optionals are nullable:
// that gives the model a legal way to omit a field as null instead of inventing filler.
val TOOLS = Json.parseToJsonElement("""[{
    "type": "function", "name": "bash", "strict": true,
    "description": "Run a shell command in the workspace (killed after ${TIMEOUT_SECONDS}s), or manage a background job that outlives the turn and is referred to by name.",
    "parameters": { "type": "object", "additionalProperties": false,
        "required": ["action", "command", "name", "seconds"],
        "properties": {
            "action": { "type": "string", "enum": ["run", "start", "list", "output", "wait", "stop"],
                "description": "run: execute 'command' and wait. start: run it in the background. list: show known jobs. output: what a job has printed so far. wait: block until it finishes. stop: kill it." },
            "command": { "type": ["string", "null"], "description": "Shell command for run and start; null otherwise." },
            "name":    { "type": ["string", "null"], "description": "Job name for stop, output and wait; optional on start; null for run and list." },
            "seconds": { "type": ["number", "null"], "description": "wait only: how long it may block (default $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS). Null otherwise." }
        } } }]""").jsonArray

// ---------- 2. Agent loop ----------

/** One model turn: the raw output items and what they cost. */
data class Turn(val output: JsonArray, val usage: Usage)

data class Usage(val input: Long = 0, val cached: Long = 0, val output: Long = 0, val reasoning: Long = 0) {
    operator fun plus(o: Usage) = Usage(input + o.input, cached + o.cached, output + o.output, reasoning + o.reasoning)
    val cost get() = turnCost(input, cached, output)
}

/** The "text" of every [parts] entry of the [type] items, or null when there is none. */
fun itemTexts(output: List<JsonElement>, type: String, parts: String): String? = output
    .map { it.jsonObject }
    .filter { it.str("type") == type }
    .flatMap { it[parts]?.jsonArray.orEmpty() }
    .mapNotNull { it.jsonObject.str("text") }
    .joinToString("\n")
    .takeUnless { it.isBlank() }

fun reasoningSummary(output: JsonArray) = itemTexts(output, "reasoning", "summary")
fun assistantText(output: JsonArray) = itemTexts(output, "message", "content")

class BashAgentHarness(
    private val workspace: File,
    private val apiKey: String,
    private val baseUrl: String = API_BASE,
    private val timeoutSeconds: Long = TIMEOUT_SECONDS,
    depth: Int = AGENT_DEPTH,
    subAgentCommand: String? = selfCommand(),
    private val log: JsonlLog? = null,
    private val echoAnswer: Boolean = true,           // false in one-shot: stdout gets the answer itself
    onJobFinished: (BackgroundJob) -> Unit = {}       // last, for trailing-lambda callers
) : AutoCloseable {
    private val jobs = JobRegistry(workspace, depth, log, onJobFinished)
    private val input = mutableListOf<JsonObject>()
    private val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Volatile private var interrupted = false
    /** Tells Ctrl+C whether to cancel or quit. */
    @Volatile var busy = false; private set

    private var session = Usage()
    /** Last call's input+output: what the next request carries. Measured, so one call stale. */
    private var contextTokens = 0L

    private val systemPrompt = systemPrompt(workspace, depth, subAgentCommand)

    init { reset() }

    fun reset() {
        input.clear()
        contextTokens = 0
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

    /** Cancels the running task; with [moveForeground] a foreground command survives as a background job. */
    fun interrupt(moveForeground: Boolean = true) {
        if (moveForeground) jobs.backgroundForeground() // before the flag: run() must never see it and kill the moved job
        interrupted = true
    }

    override fun close() {
        val killed = jobs.killAll()
        if (killed > 0) println("🛑  Killed $killed background job(s).")
    }

    /** The model's final answer, or null when the task did not finish (API error, interrupt, iteration cap). */
    fun runTask(taskDescription: String, plan: Boolean = false): String? {
        pumpJobs(announceRunning = true) // first, so the user's words come last
        val text = if (plan) taskDescription + PLAN_NOTE else taskDescription
        input.add(message("user", text))
        log?.event("user") { put("text", text) }
        return runLoop()
    }

    /** A turn triggered by a finished job. False when its result already reached the model. */
    fun resume(): Boolean {
        if (!jobs.hasUndelivered()) return false
        println("\n🔔  A background job finished.")
        runLoop()
        return true
    }

    /** Prints the answer here: in one-shot mode System.out is stderr, and the caller owns the real stdout. */
    private fun runLoop(): String? {
        interrupted = false
        busy = true
        var used = Usage()
        fun stopInterrupted(): Nothing? { println("\n⏹️ Interrupted. Ask again to continue."); return null }
        try {
            repeat(MAX_ITERATIONS) {
                if (interrupted) return stopInterrupted()
                pumpJobs(announceRunning = false)
                // Logged after the trim, so chars_after is real; agent-log.kt reads all three keys.
                val (itemsBefore, charsBefore) = input.size to input.sumOf { it.toString().length }
                var summary: String? = null
                trimHistory(input, contextTokens) { dropped -> summarize(dropped).also { summary = it } }
                if (input.size < itemsBefore) log?.event("trim") {
                    put("dropped", itemsBefore - input.size)
                    put("context_tokens", contextTokens)
                    put("chars_before", charsBefore)
                    put("chars_after", input.sumOf { it.toString().length })
                    put("summary", summary)
                }

                Spinner.start("Thinking")
                val turn = runCatching { callOpenAI(httpClient, input, apiKey, baseUrl, { interrupted }, log) }
                    .also { Spinner.stop() }
                    .getOrElse { e ->
                        log?.event("error") { put("message", e.message ?: e.toString()) }
                        if (interrupted) return stopInterrupted()
                        println("❌  API Error: ${e.message ?: e}")
                        return null
                    }
                used += turn.usage
                session += turn.usage
                contextTokens = turn.usage.input + turn.usage.output

                // Echoed back verbatim, reasoning included: that keeps the model's thinking alive.
                turn.output.forEach { input.add(it.jsonObject) }

                reasoningSummary(turn.output)?.let { println("🧠  $it") }
                val text = assistantText(turn.output)
                val calls = turn.output.map { it.jsonObject }.filter { it.str("type") == "function_call" }
                if (calls.isEmpty()) {
                    if (text == null) println("\n✅  (the model returned neither an answer nor a command)")
                    else if (echoAnswer) println("\n✅  $text")
                    return text ?: ""
                }
                if (text != null) println("🤔  Reasoning: $text")

                // Every call needs a reply, even skipped ones, or the next request is a 400.
                calls.forEach { call -> runCall(call)?.let(input::add) }
            }
            println("\n⏹️ Stopped after $MAX_ITERATIONS iterations. Ask again to continue.")
            return null
        } finally {
            // Once per turn, not per call: the tool loop is the noisy part. A low hit % mid-session means the prefix changed.
            if (used.input > 0) println(
                "📊  %,d in (%,d cached, %d%% hit) / %,d out (%,d reasoning) · \$%.4f · session \$%.4f · ctx %,d (%d%%)".format(
                    Locale.ROOT, used.input, used.cached, used.cached * 100 / used.input, used.output, used.reasoning,
                    used.cost, session.cost, contextTokens, contextTokens * 100 / CONTEXT_WINDOW_TOKENS
                )
            )
            busy = false // last: Ctrl+C reads it, and the turn is not over until its output has printed
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
        // Job branches echo their own summary; errors are one-liners worth showing whole.
        if (result.startsWith("Execution Error") || result.startsWith("[Skipped")) println("📥  $result\n")
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
        val action = args.str("action")?.lowercase() ?: "run" // lowercase: a proxy behind OPENAI_BASE_URL may not enforce the strict schema
        val command = args.str("command")
        if ((action == "run" || action == "start") && command.isNullOrBlank()) {
            return "Execution Error: '$action' needs a 'command' (got: $rawArgs)"
        }

        return runCatching { when (action) {
            "run" -> {
                println("💻  Bash: $command")
                val job = jobs.run(command!!, timeoutSeconds) { interrupted }
                if (job.name != "foreground") { // renamed only when Ctrl+C moved it to the background
                    println("📦  Moved to background job \"${job.name}\"")
                    "Interrupted by the user — the command continues as background job \"${job.name}\". " +
                        "Its output will be delivered to you when it finishes."
                } else {
                    val note = when {
                        job.state != JobState.KILLED -> null
                        interrupted -> "[Interrupted by the user — process killed]"
                        else -> "[TIMED OUT after ${timeoutSeconds}s — process killed. Output above is partial.]"
                    }
                    println("📥  ${job.summary(note)}\n")
                    job.report(note)
                }
            }

            "start" -> {
                val job = jobs.start(command!!, name)
                println("🚀  Started background job \"${job.name}\": $command")
                "Started background job \"${job.name}\". Its output will be delivered to you when it finishes."
            }

            "stop" -> withJob(name) { job ->
                job.stop()
                job.await(2)
                job.reported = true // so pumpJobs does not repeat it
                println("🛑  Stopped background job \"${job.name}\" — ${job.summary()}\n")
                "Stopped background job \"${job.name}\".\n${job.report()}"
            }

            "list" -> {
                val known = jobs.all()
                val listing = if (known.isEmpty()) "No background jobs."
                else known.joinToString(prefix = "[background jobs]\n", separator = "\n") {
                    "- \"${it.name}\" (${it.state.name.lowercase()}, ${it.elapsedSeconds}s): ${it.command}"
                }
                println("📥  $listing\n")
                listing
            }

            "output" -> withJob(name) { job ->
                if (job.done) job.reported = true
                println("📥  ${job.summary()}\n")
                job.report()
            }

            "wait" -> withJob(name) { job ->
                val seconds = (args["seconds"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: DEFAULT_WAIT_SECONDS)
                    .coerceIn(1, MAX_WAIT_SECONDS)
                println("⏳ Waiting up to ${seconds}s for background job \"${job.name}\"…")
                if (job.await(seconds) { interrupted }) job.reported = true
                println("📥  ${job.summary()}\n")
                job.report()
            }

            else -> "Execution Error: unknown action '$action' — use run, start, list, stop, output or wait."
        } }.getOrElse { "Execution Error: ${it.message}" }
    }

    private fun withJob(name: String?, block: (BackgroundJob) -> String): String {
        if (name.isNullOrBlank()) return "Execution Error: this action needs a 'name'."
        val job = jobs.find(name)
            ?: return "Execution Error: there is no background job named \"$name\". Jobs: ${jobs.names()}"
        return block(job)
    }

    /** Delivers finished jobs and, at a user turn, what still runs. As messages, never in item 0: the cache keys on it. */
    private fun pumpJobs(announceRunning: Boolean) {
        fun notice(text: String, icon: String = "", display: String = text) {
            println("$icon$display\n")
            log?.event("job_notice") { put("text", text) }
            input.add(message("user", text))
        }
        jobs.drainFinished().forEach {
            val head = "[background job \"${it.name}\" finished] ${it.command}"
            notice("$head\n${it.report()}", "🏁  ", display = "$head — ${it.summary()}")
        }
        val live = if (announceRunning) jobs.running() else return
        if (live.isNotEmpty()) notice(live.joinToString("\n", prefix = "[background jobs still running]\n") {
            "- \"${it.name}\" (${it.elapsedSeconds}s): ${it.command}"
        })
    }

    /** One tool-less call over the dropped span as plain text (so the model summarizes rather than continues it). Null on failure. */
    private fun summarize(dropped: List<JsonObject>): String? {
        val text = dropped.mapNotNull { item ->
            when (item.str("type")) {
                // Our messages carry a string; the model's carry output_text parts.
                null -> "${item.str("role")}: ${item.str("content")}"
                "message" -> "${item.str("role")}: ${itemTexts(listOf(item), "message", "content")}"
                "function_call" -> "call: ${item.str("arguments")}"
                "function_call_output" -> "result: ${item.str("output").orEmpty().take(SUMMARY_RESULT_CHARS)}"
                else -> null
            }
        }.joinToString("\n\n")
        val request = listOf(message("system", SUMMARY_PROMPT), message("user", text))
        Spinner.start("Summarizing")
        return runCatching { callOpenAI(httpClient, request, apiKey, baseUrl, { interrupted }, log, tools = null) }
            .also { Spinner.stop() }
            .onSuccess { session += it.usage }
            .onFailure { log?.event("error") { put("message", "summary: ${it.message ?: it}") } }
            .getOrNull()?.let { assistantText(it.output) }
    }

    fun sessionCost() = session.cost
}

fun message(role: String, content: String) = buildJsonObject {
    put("role", role)
    put("content", content)
}

/**
 * Past the budget, drops the oldest turns down to TRIM_TARGET_TOKENS. Item 0 and the newest item survive.
 * [contextTokens] is the last call's measured size — one call stale, so an oversized tool result is sent
 * once before triggering (bounded by MAX_OUTPUT_CHARS); chars apportion only where the cut lands.
 * [summarize] sees the dropped items; its text replaces them as one user item, so the cut still lands on a user turn.
 */
fun trimHistory(input: MutableList<JsonObject>, contextTokens: Long, summarize: (List<JsonObject>) -> String? = { null }) {
    if (contextTokens <= MAX_HISTORY_TOKENS) return
    var total = input.sumOf { it.toString().length }
    val keepChars = total.toLong() * TRIM_TARGET_TOKENS / contextTokens

    val limit = input.size - 1
    var drop = 1
    while (drop < limit && total > keepChars) total -= input[drop++].toString().length
    // Resume on a user message: anything else is orphaned from its call or reasoning item (400).
    while (drop < limit && input[drop].str("role") != "user") drop++
    if (drop >= limit) drop = 1

    if (drop > 1) {
        val summary = summarize(input.subList(1, drop).toList())
        input.subList(1, drop).clear()
        if (summary != null) input.add(1, message("user", "[summary of earlier conversation]\n$summary"))
        println("🧹  Trimmed ${drop - 1} old item(s) to stay inside the context budget" + (if (summary != null) ", summarized." else "."))
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
    data object Interrupted : Event // Ctrl+C during a resumed turn: re-prompt, don't quit
}

fun printHelp() = println(
    """
    Commands:
      /help    Show this help
      /plan    Toggle plan mode (or Shift-Tab): the model explores read-only and answers with a plan
      /reset   Clear the conversation history (background jobs survive)
      /exit    Quit (or Ctrl+D)
    Ctrl+C stops the turn; its running command goes to background.
    Note: prompt cache retention is requested at 24h for this session.
    Piped stdin (echo "…" | ./agent.kt) runs that one prompt: answer on stdout, log on stderr, then exit.
    """.trimIndent()
)

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
    if (apiKey.isBlank()) {
        System.err.println("❌  Please set the 'OPENAI_API_KEY' environment variable.")
        exitProcess(2)
    }
    if (AGENT_DEPTH > MAX_AGENT_DEPTH) {
        System.err.println("❌  AGENT_DEPTH=$AGENT_DEPTH exceeds MAX_AGENT_DEPTH=$MAX_AGENT_DEPTH; refusing to nest deeper.")
        exitProcess(2)
    }
    val workspace = File(".").canonicalFile
    val log = resolveLogPath(System.getenv("AGENT_LOG"))?.let { JsonlLog(File(it)) }

    if (IS_TTY) runConsole(workspace, apiKey, log) else runOneShot(workspace, apiKey, log)
}

/** AGENT_LOG: unset -> one file per session (local time), blank -> off, otherwise the given path. */
// Under .agent/ so the model's own greps over the workspace don't match the log of this conversation.
fun resolveLogPath(env: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? = when {
    env == null -> ".agent/agent-${ofPattern("yyyyMMdd-HHmmss").withZone(zone).format(now)}.jsonl"
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
        System.err.println("❌  No prompt on stdin.")
        exitProcess(2)
    }

    val harness = BashAgentHarness(workspace, apiKey, log = log, echoAnswer = false)
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.close() })
    // No next turn exists to deliver a backgrounded job: kill it and return the partial output.
    Signal.handle(Signal("INT")) { harness.interrupt(moveForeground = false) }
    instructionsNotice(workspace)?.let { System.err.println(it) }

    val answer = harness.runTask(prompt) // the 📊 line already reports the session cost
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
        // Trailing \ + Enter continues on the next line; the buffer then edits as real multi-line.
        .parser(DefaultParser().eofOnEscapedNewLine(true))
        .variable(LineReader.SECONDARY_PROMPT_PATTERN, "    ...  ")
        // Without this, JLine "expands" the accepted line: strips \x to x, drops \-newlines, runs !-history.
        .option(LineReader.Option.DISABLE_EVENT_EXPANSION, true)
        .build()
    fun farewell() {
        println("\n👋  Bye! Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))
        terminal.close()
    }

    // Covers every exit path.
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.close() })

    // Quit takes a second Ctrl+C: busy flips false only after a turn's last output, but a press aimed
    // at a turn can still land just after that, and one stray keypress must not kill the session's jobs.
    val lastIdleInt = AtomicLong(0)
    fun quitConfirmed(): Boolean {
        if (System.currentTimeMillis() - lastIdleInt.getAndSet(System.currentTimeMillis()) < 2000) return true
        println("\nPress Ctrl+C again to quit")
        return false
    }

    // Via the terminal, not sun.misc: readLine saves/restores the terminal's INT handler around
    // every prompt, so a raw sun.misc registration is clobbered back to SIG_DFL after the first one.
    terminal.handle(Terminal.Signal.INT) {
        if (harness.busy) harness.interrupt() else if (quitConfirmed()) { farewell(); exitProcess(0) }
    }

    println("🤖  Bash Agent — Workspace: ${workspace.absolutePath}")
    instructionsNotice(workspace)?.let { println(it) }
    printHelp()

    // readLine() paints the prompt, so it must not overlap a task's output: one permit per prompt wanted.
    val wantLine = Semaphore(0)
    var plan = false // the reader thread reads it only after the main loop releases wantLine
    fun prompt() = if (plan) "\n📋  Plan: " else "\n👤  You: "
    // Shift-Tab (backtab) flips plan mode mid-line; setPrompt is only on the impl; a diffed redisplay garbles the emoji, so redraw fully.
    reader.keyMaps[LineReader.MAIN]!!.bind(Widget {
        plan = !plan
        (reader as LineReaderImpl).setPrompt(prompt())
        reader.callWidget(LineReader.REDRAW_LINE); reader.callWidget(LineReader.REDISPLAY)
        true
    }, KeyMap.key(terminal, InfoCmp.Capability.key_btab), "\u001b[Z")
    thread(isDaemon = true) {
        while (true) {
            wantLine.acquire()
            val event = try {
                // The \ before each continuation newline is a marker, not content.
                Event.Typed(reader.readLine(prompt()).replace("\\\n", "\n"))
            } catch (_: EndOfFileException) {
                Event.EndOfInput
            } catch (_: UserInterruptException) { // Ctrl+C caught by JLine before our handler
                // Interrupt here, not in the main loop: it is inside resume() and would see the event too late.
                if (harness.busy) { harness.interrupt(); Event.Interrupted }
                else if (quitConfirmed()) Event.EndOfInput else Event.Interrupted
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
            Event.Interrupted -> prompted = false // the aborted readLine consumed the permit; paint a fresh prompt
            Event.JobFinished -> {
                // readLine is still active on the reader thread (it cannot be cancelled), so a turn's output
                // scrolls its prompt away; redraw is the only cross-thread call JLine allows.
                harness.resume()
                runCatching { reader.callWidget(LineReader.REDRAW_LINE); reader.callWidget(LineReader.REDISPLAY) }
            }
            is Event.Typed -> {
                prompted = false
                val line = event.line.trim()
                when (line.lowercase()) {
                    "" -> {}
                    "/exit", "/quit" -> break
                    "/help" -> printHelp()
                    "/reset" -> { harness.reset(); println("🧹  History cleared.") }
                    "/plan" -> { plan = !plan; println(if (plan) "📋  Plan mode on: read-only, answers with a plan. /plan again to leave." else "🔧  Plan mode off.") }
                    else -> if (line.startsWith("/")) {
                        println("❌  Unknown command")
                        printHelp()
                    } else { println(); harness.runTask(line, plan) }
                }
            }
        }
    }
    farewell()
}

// ---------- 4. Command execution and background jobs ----------

/** How sub-agents launch this program. AGENT_CMD wins. */
fun selfCommand(): String =
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
    instructionFiles(workspace).takeIf { it.isNotEmpty() }?.let { "📄  Instructions: " + it.joinToString(", ") { f -> f.name } }

/** Last [keep] lines of a growing log, reading only its end: cheap enough to poll while a job runs. */
fun tailLines(file: File, keep: Int = SHOWN_OUTPUT_LINES, window: Int = 8192): List<String> {
    val from = maxOf(0L, file.length() - window)
    val buf = ByteArray(window)
    val n = java.io.RandomAccessFile(file, "r").use { it.seek(from); it.read(buf) }
    val text = if (n > 0) String(buf, 0, n) else ""
    val lines = collapseCarriageReturns(text).lines().dropLastWhile { it.isEmpty() }
    return lines.drop(if (from > 0) 1 else 0).takeLast(keep) // a mid-file start cuts the first line
}

/** Caps output, keeping head and tail: build failures land at the end. */
fun truncate(text: String, limit: Int = MAX_OUTPUT_CHARS): String {
    if (text.length <= limit) return text
    val head = text.take(limit * 2 / 3)
    val tail = text.takeLast(limit / 3)
    val middle = text.substring(head.length, text.length - tail.length)
    return head + elisionMarker(middle.length.toLong(), head.count { it == '\n' }, middle.count { it == '\n' }, tail.count { it == '\n' }, null) + tail
}

/** Names the missing lines (and the file, if any), so one sed -n fetches exactly the gap instead of the model re-reading everything. */
fun elisionMarker(elided: Long, headLines: Int, middleLines: Int, tailLines: Int, path: String?): String {
    val from = headLines + 1
    val to = from + middleLines
    val total = headLines + middleLines + tailLines + 1
    val hint = when {
        total > 1 -> "; lines $from-$to of $total, sed -n '$from,${to}p'${path?.let { " $it" } ?: ""} shows them"
        path != null -> "; full output in $path"
        else -> ""
    }
    return "\n… [$elided chars elided$hint] …\n"
}

/** [truncate] for a job's log: one sequential pass, so a gigabyte of output never sits in memory. Bytes, not chars. */
fun readTruncated(file: File, limit: Int = MAX_OUTPUT_CHARS): String {
    val size = file.length()
    if (size <= limit) return file.readText()
    val headLen = limit * 2 / 3
    val tailLen = limit / 3
    val newline = '\n'.code.toByte()
    file.inputStream().buffered().use { stream ->
        val head = stream.readNBytes(headLen)
        var middleLines = 0
        var left = size - headLen - tailLen
        val buf = ByteArray(1 shl 16)
        while (left > 0) {
            val n = stream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            for (i in 0 until n) if (buf[i] == newline) middleLines++
            left -= n
        }
        // Exactly tailLen: a running job's file may have grown since size was read.
        val tail = stream.readNBytes(tailLen)
        val marker = elisionMarker(size - headLen - tailLen, head.count { it == newline }, middleLines, tail.count { it == newline }, file.path)
        return String(head) + marker + String(tail)
    }
}

/** What a terminal would show: progress bars redraw with \r, and every kept frame is wasted budget. */
fun collapseCarriageReturns(text: String): String {
    if ('\r' !in text) return text
    return text.replace("\r\n", "\n").split('\n').joinToString("\n") { it.substringAfterLast('\r') }
}

enum class JobState { RUNNING, EXITED, KILLED }

/** One command detached from the turn that started it. Its output goes straight to [logFile]; a daemon thread reaps it. */
class BackgroundJob(
    name: String,
    val command: String,
    private val process: Process,
    val logFile: File,
    private var onFinished: (BackgroundJob) -> Unit = {}
) {
    @Volatile var name = name; private set
    val startedAt = System.currentTimeMillis()

    @Volatile var state = JobState.RUNNING; private set
    @Volatile var exitCode: Int? = null; private set
    @Volatile private var finishedAt: Long? = null
    /** Output has reached the model. */
    @Volatile var reported = false

    val done get() = state != JobState.RUNNING
    val elapsedSeconds get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt) / 1000

    // Daemon: a live job would otherwise keep the JVM up after main().
    private val watcher = thread(isDaemon = true) {
        process.waitFor()
        finish(runCatching { process.exitValue() }.getOrNull())
        onFinished(this)
    }

    // Synchronised with stop(): a kill and a natural exit race to name the state.
    @Synchronized
    private fun finish(code: Int?) {
        exitCode = code
        if (state == JobState.RUNNING) state = JobState.EXITED
        finishedAt = System.currentTimeMillis()
    }

    /** Adopts a new name and callback; refused once done or dead (the watcher fires the old one). */
    @Synchronized
    fun handOff(newName: String, callback: (BackgroundJob) -> Unit): Boolean {
        // isAlive too: Ctrl+C can kill bash before its trap arms, ahead of the watcher's finish().
        if (done || !process.isAlive) return false
        name = newName
        onFinished = callback
        return true
    }

    @Synchronized
    fun stop() {
        if (done) return
        state = JobState.KILLED
        // Descendants too, or a grandchild outlives the job.
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

    fun tail() = tailLines(logFile)

    /** The output so far. [note] replaces the status line. */
    fun report(note: String? = null) = buildString {
        // At read time: a \r frame can straddle two writes.
        val output = collapseCarriageReturns(readTruncated(logFile))
        if (output.isNotBlank()) append(output)
        append(status(note))
    }

    private fun status(note: String?) = note ?: when (state) {
        JobState.RUNNING -> "[Still running after ${elapsedSeconds}s]"
        JobState.KILLED -> "[Killed after ${elapsedSeconds}s]"
        JobState.EXITED -> "[Exit Code: ${exitCode ?: "unknown"} after ${elapsedSeconds}s]"
    }

    fun lineCount() = logFile.useLines { it.count() }

    /** Console one-liner; the model and the log always get the whole result. */
    fun summary(note: String? = null) = "${status(note)} (${lineCount()} lines)"
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

    private fun launch(name: String, command: String, onFinished: (BackgroundJob) -> Unit = {}): BackgroundJob {
        // stdout and stderr merged like a terminal, straight to a file: the log is exactly what the model
        // sees, so the marker's line numbers are exact and nothing is lost however much a job prints.
        val logFile = File.createTempFile("agent-", ".log")
        // A NUL cannot cross execve, and the model occasionally emits one; stripping beats failing the call.
        // trap '' INT, console only: the tty's Ctrl+C signals the whole process group, and a job (descendants
        // included, they inherit the ignore) must survive the very interrupt that backgrounds it. Without a
        // tty there is no group signal, and the ignore would break commands whose contract is SIGINT.
        val prefix = if (IS_TTY) "trap '' INT; " else ""
        val process = ProcessBuilder("bash", "-c", prefix + command.replace("\u0000", ""))
            .directory(workspace)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))) // never block on input
            .redirectErrorStream(true)
            .redirectOutput(logFile)
            .apply {
                environment()["AGENT_DEPTH"] = (depth + 1).toString()
                environment()["AGENT_LOG"] = log?.path ?: "" // absolute, so a child that cd's still finds it
            }
            .start()
        return BackgroundJob(name, command, process, logFile, onFinished)
    }

    /** Throws only if [command] will not launch. */
    fun start(command: String, requested: String? = null): BackgroundJob = synchronized(this) {
        launch(nameFor(requested), command, onFinished).also { jobs[it.name] = it }
    }

    /** Runs [command] and waits, killing it at [seconds]. Not registered. */
    fun run(command: String, seconds: Long, cancelled: () -> Boolean): BackgroundJob {
        // Launched and exposed under the lock: an interrupt in the launch window must find the job.
        val job = synchronized(this) { launch("foreground", command).also { foreground = it } }
        Spinner.start("Running") { job.tail() }
        try {
            if (!job.await(seconds, cancelled)) {
                // Decided under the lock: either we kill it, or backgroundForeground() adopted it and it lives on.
                val kill = synchronized(this) { (foreground === job).also { if (it) foreground = null } }
                if (kill) { job.stop(); job.await(2) }
            }
            return job
        } finally {
            Spinner.stop()
            synchronized(this) { if (foreground === job) foreground = null }
        }
    }

    /** Ctrl+C: the foreground command becomes a named background job instead of dying. */
    @Synchronized
    fun backgroundForeground() {
        val job = foreground ?: return
        if (!job.handOff(nameFor(null), onFinished)) return
        jobs[job.name] = job
        foreground = null
    }

    /** Never reused: a second "build" becomes "build-2". */
    private fun nameFor(requested: String?): String {
        val cleaned = requested.orEmpty().replace(Regex("[^A-Za-z0-9_.-]"), "-").trim('-', '.').take(40)
        val base = cleaned.ifBlank { "job${++counter}" }
        if (base !in jobs) return base
        return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in jobs }
    }

    @Synchronized fun find(name: String) = jobs[name]
    @Synchronized fun running() = jobs.values.filter { !it.done }
    @Synchronized fun all() = jobs.values.toList()
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
            put("ts", Instant.now().toString())
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
    log: JsonlLog? = null,
    tools: JsonArray? = TOOLS // null for the summary call: it must answer, not act
): Turn {
    // "store" stays true so the echoed reasoning ids stay resolvable (store=false needs
    // include=["reasoning.encrypted_content"]). "summary" feeds the 🧠 line.
    val payload = buildJsonObject {
        put("model", MODEL)
        put("input", JsonArray(input))
        if (tools != null) put("tools", tools)
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
        val response = sendCancellable(client, request, cancelled)
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

/** send() cannot be cancelled; poll the future in slices so Ctrl+C is felt. A response beats a pending cancel. */
private fun sendCancellable(client: HttpClient, request: HttpRequest, cancelled: () -> Boolean): HttpResponse<String> {
    val future = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
    while (true) {
        try { return future.get(200, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { if (cancelled()) { future.cancel(true); throw Exception("Cancelled by user.") } }
        catch (e: ExecutionException) { throw e.cause ?: e } // unwrap: the IOException message is the useful one
    }
}

private fun HttpResponse<String>.toTurn(): Turn {
    val json = Json.parseToJsonElement(body()).jsonObject
    val output = json["output"]?.jsonArray
        ?: throw Exception("API response did not contain an 'output' array: ${body()}")
    val usage = json["usage"]?.jsonObject
    return Turn(output, Usage(
        input = usage.long("input_tokens"),
        cached = usage.obj("input_tokens_details").long("cached_tokens"),
        output = usage.long("output_tokens"),
        reasoning = usage.obj("output_tokens_details").long("reasoning_tokens")
    ))
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
    private var worker: Thread? = null

    /** [tail] lines are painted under the spinner and redrawn each frame. */
    @Synchronized
    fun start(text: String, tail: () -> List<String> = { emptyList() }) {
        if (!IS_TTY || worker != null) return
        val startedAt = System.currentTimeMillis()
        worker = thread(isDaemon = true) {
            var frame = 0
            var painted = 0
            try {
                while (true) {
                    val lines = tail()
                    // Autowrap off: a long line must stay one row, or the cursor-up count is wrong.
                    print("\u001B[?7l\r\u001B[2K${"⠹⠸⠴⠦⠇⠏"[frame++ % 6]} $text ${(System.currentTimeMillis() - startedAt) / 1000}s")
                    // Clear as many rows as the previous frame used, so a shrinking tail leaves nothing behind.
                    repeat(maxOf(lines.size, painted)) { print("\n\u001B[2K" + (lines.getOrNull(it)?.let { l -> "   $l" } ?: "")) }
                    painted = maxOf(lines.size, painted)
                    if (painted > 0) print("\u001B[${painted}A")
                    print("\u001B[?7h")
                    Thread.sleep(90)
                }
            } catch (_: InterruptedException) {
                print("\r\u001B[2K" + "\n\u001B[2K".repeat(painted) + (if (painted > 0) "\u001B[${painted}A" else ""))
            }
        }
    }

    @Synchronized
    fun stop() {
        worker?.apply { interrupt(); join() }
        worker = null
    }
}
