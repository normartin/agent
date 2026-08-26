///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.4.10
//DEPS org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

import kotlinx.serialization.json.*
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
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

const val MODEL = "gpt-5"

// USD per 1M tokens for MODEL — these move with the model. Cached input is an
// order of magnitude cheaper and this loop resends the same growing prefix every
// iteration, so it is worth pricing separately.
const val INPUT_USD_PER_1M = 1.25
const val CACHED_INPUT_USD_PER_1M = 0.125
const val OUTPUT_USD_PER_1M = 10.00

const val MAX_ITERATIONS = 15
const val TIMEOUT_SECONDS = 120L

// A background job has no deadline — a server is meant to outlive the turn that
// started it — so its output is capped as it arrives instead. This bounds what
// the harness holds in memory; MAX_OUTPUT_CHARS below bounds what reaches the
// model, and the two are deliberately different sizes.
const val MAX_JOB_LOG_CHARS = 40_000

// How long the jobs tool's "wait" action may block: what it defaults to, and
// what it will not exceed however large a number the model asks for.
const val DEFAULT_WAIT_SECONDS = 60L
const val MAX_WAIT_SECONDS = 600L

// Distinct from the bash timeout above: how long one API call may take.
const val API_TIMEOUT_SECONDS = 120L

// Every iteration resends the whole history, so tokens per minute grow with the
// square of the turn count. Both budgets are sized against a tokens-per-minute
// limit rather than the model's context window: at ~4 chars per token a full
// history is ~30k tokens, so a MAX_ITERATIONS task stays inside gpt-5's 500k TPM.
// Caching makes the resend cheap but not free — cached tokens still count in full
// against TPM. Retune these if you change model or tier.
const val MAX_OUTPUT_CHARS = 6000
const val MAX_HISTORY_CHARS = 120_000

// Point this at a proxy or a local OpenAI-compatible server if you want one;
// the tests use it to stand up a mock in-process.
val API_BASE = System.getenv("OPENAI_BASE_URL")?.trimEnd('/') ?: "https://api.openai.com"

const val MAX_RETRIES = 5
const val MAX_RETRY_WAIT_MS = 60_000L

// ---------- 1. Bash execution ----------

/** Reads a stream to EOF; a failed read counts as no output. */
private fun InputStream.readTextOrEmpty() =
    runCatching { bufferedReader().readText() }.getOrDefault("")

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
                // No console input: an interactive command would otherwise steal
                // the chat input or block forever.
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            current = process

            // Drain both streams on their own threads: reading them one after the
            // other deadlocks as soon as the unread one fills its pipe buffer.
            // The joins below are what publish these captured vars back to here.
            var out = ""
            var err = ""
            val outDrain = thread { out = process.inputStream.readTextOrEmpty() }
            val errDrain = thread { err = process.errorStream.readTextOrEmpty() }

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                // Descendants first: a surviving grandchild keeps the pipes open
                // and the drain threads never see EOF.
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
 * Caps a command's output before it enters the history. Keeps head and tail:
 * build failures land at the end, so dropping the tail hides the answer.
 */
fun truncate(text: String): String {
    if (text.length <= MAX_OUTPUT_CHARS) return text
    // Derived from the cap so the two can never add up to more than it allows.
    val head = text.take(MAX_OUTPUT_CHARS * 2 / 3)
    val tail = text.takeLast(MAX_OUTPUT_CHARS / 3)
    return "$head\n… [${text.length - head.length - tail.length} chars elided] …\n$tail"
}

// ---------- 1b. Background jobs ----------

/**
 * A capped buffer that a drain thread can append to for hours. It keeps the same
 * head and tail [truncate] does — a build failure lands at the end, so dropping
 * the tail would hide the answer — but enforces the cap as the output arrives,
 * since a background job has no deadline to stop it filling memory.
 */
class BoundedLog(cap: Int = MAX_JOB_LOG_CHARS) {
    private val headCap = cap * 2 / 3
    private val tailCap = cap - headCap
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var elided = 0L

    /** Appends a chunk. Called from the drain thread. */
    @Synchronized
    fun append(text: String) {
        var rest = text
        if (head.length < headCap) {
            val take = minOf(headCap - head.length, rest.length)
            head.append(rest, 0, take)
            rest = rest.substring(take)
        }
        if (rest.isEmpty()) return

        tail.append(rest)
        if (tail.length > tailCap) {
            val drop = tail.length - tailCap
            tail.delete(0, drop)
            elided += drop
        }
    }

    /** The log as it stands. Called from the agent thread while [append] runs. */
    @Synchronized
    fun snapshot() =
        if (elided == 0L) head.toString() + tail
        else "$head\n… [$elided chars elided] …\n$tail"
}

enum class JobState { RUNNING, EXITED, KILLED }

/**
 * One command running detached from the turn that started it. Two threads drain
 * its streams and a third reaps it, so nothing here blocks the agent loop; what
 * the loop reads back is either @Volatile or guarded.
 */
class BackgroundJob(
    val name: String,
    val command: String,
    private val process: Process,
    // Fired from the watcher thread the moment the job is over, so the console
    // can wake up instead of waiting for the user to type something. It has to
    // be non-blocking: the watcher is what publishes the job's final state.
    private val onFinished: (BackgroundJob) -> Unit = {}
) {

    val startedAt = System.currentTimeMillis()

    @Volatile
    var state = JobState.RUNNING
        private set

    @Volatile
    var exitCode: Int? = null
        private set

    @Volatile
    private var finishedAt: Long? = null

    /** Set once the finished job's output has been handed to the model. */
    @Volatile
    var reported = false

    // Declared before the threads below on purpose: property initialisers run in
    // order, and the drains start writing to these immediately.
    private val out = BoundedLog()
    private val err = BoundedLog()

    // Chunk by chunk rather than readText(): a job's log has to be readable
    // while it is still being written.
    //
    // All three threads below are daemons. A live job blocks its drains on a
    // pipe that will not close, and a non-daemon thread there keeps the JVM up
    // after main() returns — which also means the shutdown hook that would have
    // killed the job never runs, and /exit hangs instead of quitting.
    private val outDrain = thread(isDaemon = true, name = "job-$name-out") { drain(process.inputStream, out) }
    private val errDrain = thread(isDaemon = true, name = "job-$name-err") { drain(process.errorStream, err) }

    // A watcher thread rather than a poll from the agent loop: the drains have
    // to be joined before the log is complete, and only something that already
    // waited on the process can do that without stalling the caller.
    private val watcher = thread(isDaemon = true, name = "job-$name") {
        process.waitFor()
        outDrain.join(2000)
        errDrain.join(2000)
        finish(runCatching { process.exitValue() }.getOrNull())
        onFinished(this)
    }

    /** Seconds spent so far, or in total once the job is over. */
    val elapsedSeconds: Long
        get() = ((finishedAt ?: System.currentTimeMillis()) - startedAt) / 1000

    private fun drain(stream: InputStream, log: BoundedLog) {
        runCatching {
            stream.bufferedReader().use { reader ->
                val buffer = CharArray(8192)
                while (true) {
                    val read = reader.read(buffer)
                    if (read < 0) break
                    log.append(String(buffer, 0, read))
                }
            }
        }
    }

    // Synchronised against stop(): a kill and a natural exit otherwise race to
    // name the state, and a killed job would report itself as having exited.
    @Synchronized
    private fun finish(code: Int?) {
        exitCode = code
        if (state == JobState.RUNNING) state = JobState.EXITED
        finishedAt = System.currentTimeMillis()
    }

    /** Kills the job. Marking the state first is what makes the race above safe. */
    @Synchronized
    fun stop() {
        if (state != JobState.RUNNING) return
        state = JobState.KILLED
        // Descendants first: a surviving grandchild holds the pipes open and the
        // drain threads never see EOF.
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    /** Waits up to [ms] for the job to be over, log included. */
    fun awaitFor(ms: Long): Boolean {
        if (!process.waitFor(ms, TimeUnit.MILLISECONDS)) return false
        // The log is only complete once the watcher has joined the drains.
        watcher.join(3000)
        return true
    }

    /**
     * Blocks until the job is over, [seconds] elapse, or [cancelled] goes true.
     * Polls in slices instead of joining outright so Ctrl+C is felt during the
     * wait, the same way [sleepUnlessCancelled] handles a rate-limit pause.
     */
    fun await(seconds: Long, cancelled: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + seconds * 1000
        while (true) {
            if (cancelled()) return false
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return false
            if (awaitFor(minOf(200L, left))) return true
        }
    }

    /** The output so far, in the same shape the foreground tool returns. */
    fun report() = buildString {
        val stdout = out.snapshot()
        val stderr = err.snapshot()
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) append("ERROR OUTPUT:\n").append(stderr)
        append("\n")
        append(
            when (state) {
                JobState.RUNNING -> "[Still running after ${elapsedSeconds}s]"
                JobState.KILLED -> "[Killed after ${elapsedSeconds}s]"
                JobState.EXITED -> "[Exit Code: ${exitCode ?: "unknown"} after ${elapsedSeconds}s]"
            }
        )
    }
}

/**
 * The live background jobs, by name. Finished ones are kept so their output can
 * still be asked for after it has been delivered.
 *
 * Every method is synchronised because the names have to be allocated and the
 * map written under one lock; the waiting happens on [BackgroundJob] itself, so
 * nothing here is ever held for longer than a map operation.
 */
class JobRegistry(
    private val workspace: File,
    private val onFinished: (BackgroundJob) -> Unit = {}
) {
    // Insertion-ordered, so a listing reads in the order the jobs were started.
    private val jobs = LinkedHashMap<String, BackgroundJob>()
    private var counter = 0

    /** Starts [command] detached. Throws only if the process will not launch. */
    fun start(command: String, requested: String? = null): BackgroundJob {
        val process = ProcessBuilder("bash", "-c", command)
            .directory(workspace)
            // Same as the foreground tool: an interactive command would
            // otherwise steal the chat input or block forever.
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            .start()
        return synchronized(this) {
            BackgroundJob(nameFor(requested), command, process, onFinished).also { jobs[it.name] = it }
        }
    }

    /**
     * A name the model can refer to later. Its own choice is kept when it is
     * usable, but never reused: a second "build" becomes "build-2", so a
     * delivered result can never be attributed to the wrong process.
     */
    private fun nameFor(requested: String?): String {
        // Trimmed of leading and trailing punctuation too: "./gradlew build"
        // should read back as "gradlew-build", not ".-gradlew-build".
        val cleaned = requested.orEmpty().replace(Regex("[^A-Za-z0-9_.-]"), "-").trim('-', '.').take(40)
        val base = cleaned.ifBlank { "job${++counter}" }
        if (base !in jobs) return base

        var suffix = 2
        while ("$base-$suffix" in jobs) suffix++
        return "$base-$suffix"
    }

    @Synchronized
    fun find(name: String): BackgroundJob? = jobs[name]

    @Synchronized
    fun running() = jobs.values.filter { it.state == JobState.RUNNING }

    /** Every job's name, for the "no such job" message. */
    @Synchronized
    fun names() = jobs.keys.joinToString(", ").ifEmpty { "none" }

    /**
     * Jobs that have finished since the last call. Each is handed over exactly
     * once — the caller feeds them into the conversation, and a repeat would
     * read as the job having run twice.
     */
    @Synchronized
    fun drainFinished(): List<BackgroundJob> = jobs.values
        .filter { it.state != JobState.RUNNING && !it.reported }
        .onEach { it.reported = true }

    /**
     * Whether [drainFinished] would hand anything over. Asked before waking the
     * model on a finished job: by the time the notification is acted on, the
     * result may already have reached the model through a turn that was running
     * anyway, or through the tool call that stopped the job.
     */
    @Synchronized
    fun hasUndelivered() = jobs.values.any { it.state != JobState.RUNNING && !it.reported }

    /** Kills every live job and returns how many there were. */
    fun killAll(): Int {
        val live = running()
        live.forEach { it.stop() }
        return live.size
    }
}

/**
 * Drops the oldest turns once the history outgrows its budget. The system
 * prompt and the most recent item always survive.
 */
fun trimHistory(input: MutableList<JsonObject>) {
    var total = input.sumOf { it.toString().length }
    if (total <= MAX_HISTORY_CHARS) return

    val limit = input.size - 1
    var drop = 1
    while (drop < limit && total > MAX_HISTORY_CHARS) {
        total -= input[drop].toString().length
        drop++
    }
    // A function_call_output whose function_call was dropped is an orphan and a
    // guaranteed 400, so keep dropping until history resumes at an item that
    // stands on its own. Landing on a reasoning item is fine: the API only asks
    // that the items it belongs to *follow* it.
    while (drop < limit && input[drop].str("type") == "function_call_output") {
        drop++
    }

    if (drop > 1) {
        input.subList(1, drop).clear()
        println("🧹 Trimmed ${drop - 1} old item(s) to stay inside the context budget.")
    }
}

/**
 * What one turn's token usage costs, in USD. The API reports cached tokens as a
 * subset of the prompt total, so only the uncached remainder bills at the full
 * input rate — counting both would overstate the bill tenfold.
 */
fun turnCost(input: Long, cached: Long, output: Long) =
    (input - cached) / 1_000_000.0 * INPUT_USD_PER_1M +
        cached / 1_000_000.0 * CACHED_INPUT_USD_PER_1M +
        output / 1_000_000.0 * OUTPUT_USD_PER_1M

/**
 * A one-line "still working" indicator for the API call, the one stretch where
 * the agent has nothing to say. It paints from a daemon thread with a carriage
 * return, so it overwrites itself and never scrolls anything away.
 *
 * Only a real terminal gets it: piped output and the tests read stdout as text,
 * where the escapes would be noise every caller has to strip.
 */
object Spinner {
    private const val FRAMES = "⠹⠸⠴⠦⠇⠏"
    private const val HIDE_CURSOR = "\u001B[?25l"
    private const val SHOW_CURSOR = "\u001B[?25h"
    private const val CLEAR_LINE = "\r\u001B[2K"

    // isTerminal(), not a null check: since JDK 22 System.console() hands back a
    // Console even when stdout is a pipe, so a null check would let the escapes
    // through to a redirected log or `./gradlew run`.
    private val enabled = System.console()?.isTerminal() == true

    // Doubles as the running flag: null means "stop", which the worker loop
    // reads on every frame.
    @Volatile
    private var label: String? = null
    private var worker: Thread? = null

    /** Starts painting [text] until [stop]. A second start just retitles. */
    @Synchronized
    fun start(text: String) {
        label = text
        if (!enabled || worker != null) return
        val startedAt = System.currentTimeMillis()
        worker = thread(isDaemon = true, name = "spinner") {
            print(HIDE_CURSOR)
            var frame = 0
            while (true) {
                val current = label ?: break
                // The elapsed count is the point: a frozen frame and a slow call
                // look alike, a climbing clock does not.
                val seconds = (System.currentTimeMillis() - startedAt) / 1000
                print("$CLEAR_LINE${FRAMES[frame % FRAMES.length]} $current ${seconds}s")
                System.out.flush()
                frame++
                Thread.sleep(90)
            }
        }
    }

    /** Erases the spinner line and gives the cursor back. Safe to call twice. */
    @Synchronized
    fun stop() {
        label = null
        val running = worker ?: return
        running.join()
        worker = null
        print("$CLEAR_LINE$SHOW_CURSOR")
        System.out.flush()
    }

    /**
     * Prints a line the spinner will not smear across. Anything that has to
     * speak mid-call — the retry notice — goes through here instead of println.
     */
    @Synchronized
    fun log(line: String) {
        if (enabled) print(CLEAR_LINE)
        println(line)
    }
}

// ---------- 2. Core agent harness loop ----------

/**
 * One model turn: the raw [output] items the Responses API produced, plus what
 * they cost. Cached prompt tokens are a subset of [promptTokens] and reasoning
 * tokens a subset of [completionTokens] — both are broken out because they are
 * priced or spent differently from the rest.
 */
data class Turn(
    val output: JsonArray,
    val promptTokens: Long,
    val cachedPromptTokens: Long,
    val completionTokens: Long,
    val reasoningTokens: Long
)

/**
 * The assistant's prose for a turn. Responses spreads it over "message" items
 * whose content is a list of parts, so it has to be gathered rather than read
 * off a single field.
 */
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
    // Injected so a test can point the harness at a stand-in server; main()
    // leaves it at the environment-derived default.
    private val baseUrl: String = API_BASE,
    // Rung from a job's watcher thread when it finishes. The console turns this
    // into an event; [resume] is what acts on it.
    onJobFinished: (BackgroundJob) -> Unit = {}
) {
    private val bash = BashTool(workspace)
    private val jobs = JobRegistry(workspace, onJobFinished)
    private val input = mutableListOf<JsonObject>()
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

        Anything slower than that goes in the background, through the "jobs" tool:
          {"action":"start","command":"./gradlew build","name":"build"}
          {"action":"output","name":"server"}   what it has printed so far
          {"action":"wait","name":"build","seconds":120}
          {"action":"stop","name":"server"}
        The name is yours to choose and is how you refer to the job later; omit it
        and one is assigned.

        You never have to poll a background job. When one finishes, its output is
        delivered to you as a [background job "name" finished] message, and each
        turn the user takes is preceded by a [background jobs still running]
        listing. Reach for "wait" only when you need the result before you can
        answer — otherwise say what you started and stop.

        A finished job can hand you a turn when the user has said nothing at all.
        Report what the job did and stop there; if it failed, you may fix what it
        was for, but do not go looking for unrelated work.

        Background jobs survive an interrupted task; they are killed when the
        session ends.
    """.trimIndent()

    init {
        reset()
    }

    /** Drops the conversation history and starts over with the system prompt. */
    fun reset() {
        input.clear()
        input.add(message("system", systemPrompt))
        interrupted = false
    }

    /**
     * Cancels the running task. Called from the Ctrl+C handler thread.
     *
     * The foreground command only: background jobs outliving an interrupted task
     * is the point of them, and [shutdown] is what ends those.
     */
    fun interrupt() {
        interrupted = true
        bash.kill()
    }

    /** Kills every background job. The last thing the session does. */
    fun shutdown() {
        val killed = jobs.killAll()
        if (killed > 0) println("🛑 Killed $killed background job(s).")
    }

    fun runTask(taskDescription: String) {
        // Before the user's message: whatever landed while they were typing is
        // context for what they are about to ask, and their own words stay the
        // last thing the model reads.
        pumpJobs(announceRunning = true)
        input.add(message("user", taskDescription))
        runLoop()
    }

    /**
     * Runs a turn nobody asked for, on the back of a finished background job:
     * the loop's own pumpJobs delivers the result, and the model answers with
     * the user still idle at the prompt.
     *
     * Returns whether it actually ran. A notification can easily arrive with
     * nothing left to say — the task that was running when the job finished will
     * have delivered the result itself — and a turn there would be an API call
     * spent on repeating what the model already read.
     */
    fun resume(): Boolean {
        if (!jobs.hasUndelivered()) return false
        println("\n🔔 A background job finished.")
        runLoop()
        return true
    }

    private fun runLoop() {
        interrupted = false
        busy = true
        try {
            var iterations = 0
            while (iterations < MAX_ITERATIONS) {
                iterations++

                if (interrupted) {
                    println("\n⏹️ Interrupted. Ask again to continue.")
                    return
                }
                // A job that finished mid-task is delivered here, on the first
                // turn after it happened. Before the trim, so an oversized
                // result is subject to the same budget as everything else.
                pumpJobs(announceRunning = false)
                trimHistory(input)

                val turn = try {
                    // The API call is the one stretch with nothing to show, and
                    // gpt-5 can think for a minute. Say so rather than look hung.
                    Spinner.start("Thinking")
                    callOpenAI(httpClient, input, apiKey, baseUrl) { interrupted }
                } catch (e: Exception) {
                    // Before printing, not just in the finally below: otherwise
                    // the next spinner frame lands on the same line as the error.
                    Spinner.stop()
                    if (interrupted) println("\n⏹️ Interrupted. Ask again to continue.")
                    // Fall back to the exception itself: a connection failure
                    // carries no message, and "API Error: null" says nothing.
                    else println("❌ API Error: ${e.message ?: e}")
                    return
                } finally {
                    Spinner.stop()
                }

                promptTokens += turn.promptTokens
                cachedPromptTokens += turn.cachedPromptTokens
                completionTokens += turn.completionTokens
                printUsage(turn)

                // Responses takes its own output straight back as input, so every
                // item is echoed verbatim — assistant messages, function calls, and
                // the reasoning items that keep gpt-5's thinking alive between tool
                // calls. Nothing here needs the sanitising chat completions did.
                turn.output.forEach { input.add(it.jsonObject) }

                val text = assistantText(turn.output)
                val calls = turn.output.map { it.jsonObject }.filter { it.str("type") == "function_call" }

                if (calls.isEmpty()) {
                    println("\n✅ ${text ?: "(the model returned neither an answer nor a command)"}")
                    return
                }

                if (text != null) println("🤔 Reasoning: $text")

                // Every call needs a reply, even the ones we skip: omitting one is
                // a 400 on the next request.
                calls.forEach { call -> runCall(call)?.let(input::add) }
            }

            println("\n⏹️ Stopped after $MAX_ITERATIONS iterations. Ask again to continue.")
        } finally {
            busy = false
        }
    }

    /** Runs one function_call and builds the output item that answers it. */
    private fun runCall(call: JsonObject): JsonObject? {
        // "call_id" is what a reply pairs with; "id" names the item itself and
        // the two are not interchangeable.
        val id = call.str("call_id") ?: return null
        // "arguments" is a JSON document carried as a string.
        val rawArgs = call.str("arguments") ?: ""
        val args = runCatching { Json.parseToJsonElement(rawArgs).jsonObject }.getOrNull()

        val result = when {
            interrupted -> "[Skipped: interrupted by the user]"
            args == null -> "Execution Error: the tool call's arguments were not a JSON object (got: $rawArgs)"
            else -> when (val tool = call.str("name")) {
                "bash" -> runBashCall(args, rawArgs)
                "jobs" -> runJobsCall(args)
                else -> "Execution Error: there is no tool named '$tool'."
            }
        }
        println("📥 Output:\n$result\n")

        return buildJsonObject {
            put("type", "function_call_output")
            put("call_id", id)
            put("output", result)
        }
    }

    private fun runBashCall(args: JsonObject, rawArgs: String): String {
        val command = args.str("command")
        if (command.isNullOrBlank()) {
            return "Execution Error: the tool call carried no 'command' argument (got: $rawArgs)"
        }
        println("💻 Executing Bash: $command")
        return truncate(bash.execute(command))
    }

    /** The jobs tool: one action per call, dispatched here. */
    private fun runJobsCall(args: JsonObject): String {
        val name = args.str("name")

        return when (val action = args.str("action")?.lowercase()) {
            "start" -> {
                val command = args.str("command")
                if (command.isNullOrBlank()) "Execution Error: 'start' needs a 'command'."
                else runCatching { jobs.start(command, name) }.fold(
                    onSuccess = { job ->
                        println("🚀 Started background job \"${job.name}\": $command")
                        "Started background job \"${job.name}\". Its output will be delivered to you when it finishes."
                    },
                    onFailure = { "Execution Error: ${it.message}" }
                )
            }

            "stop" -> withJob(name) { job ->
                job.stop()
                // Give the watcher its moment, or the report below still says
                // "running" about a process that is already gone.
                job.awaitFor(2000)
                // Handed over right here, so pumpJobs must not deliver it again.
                job.reported = true
                println("🛑 Stopped background job \"${job.name}\"")
                "Stopped background job \"${job.name}\".\n${truncate(job.report())}"
            }

            "output" -> withJob(name) { job ->
                if (job.state != JobState.RUNNING) job.reported = true
                truncate(job.report())
            }

            "wait" -> withJob(name) { job ->
                // A double: the model writes 60 as often as 60.0, and longOrNull
                // reads only the first of those.
                val seconds = (args["seconds"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: DEFAULT_WAIT_SECONDS)
                    .coerceIn(1, MAX_WAIT_SECONDS)
                println("⏳ Waiting up to ${seconds}s for background job \"${job.name}\"…")
                if (job.await(seconds) { interrupted }) job.reported = true
                truncate(job.report())
            }

            else -> "Execution Error: unknown action '$action' — use start, stop, output or wait."
        }
    }

    /** Resolves the job an action names, or explains why it could not. */
    private fun withJob(name: String?, block: (BackgroundJob) -> String): String {
        if (name.isNullOrBlank()) return "Execution Error: this action needs a 'name'."
        val job = jobs.find(name)
            ?: return "Execution Error: there is no background job named \"$name\". Jobs: ${jobs.names()}"
        return block(job)
    }

    /**
     * Feeds the conversation what only the harness can see: the output of jobs
     * that finished on their own, and — at the start of a user turn — what is
     * still running.
     *
     * Both go in as ordinary messages appended at the end rather than folded
     * into the system prompt: the prefix is what the prompt cache keys on, and
     * this loop resends its whole history every iteration, so rewriting item 0
     * would forfeit the cached discount on all of it. Being plain messages, they
     * are also safe for trimHistory to drop.
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

// ---------- 3. HTTP and primitive JSON utils ----------

/** Reads a string field, or null if it is absent or is not a string. */
fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull

/** Usage counters: an absent object or field reads as zero rather than throwing. */
fun JsonObject?.long(key: String) = this?.get(key)?.jsonPrimitive?.longOrNull ?: 0L

fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject

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

/** Pulls the output items and the usage counters out of a Responses reply. */
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

/**
 * The tools the model may call. Responses flattens the schema: name, description
 * and parameters sit on the tool itself, with no "function" wrapper — the
 * chat-completions shape is a 400 here.
 *
 * "jobs" multiplexes four actions onto one tool rather than adding four: the
 * whole set is one concept, and one schema keeps the request — resent in full
 * every iteration — that much smaller.
 */
val TOOLS = buildJsonArray {
    addJsonObject {
        put("type", "function")
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
    addJsonObject {
        put("type", "function")
        put("name", "jobs")
        put(
            "description",
            "Manage background commands that outlive the turn that started them: builds, " +
                "test suites, servers. A finished job's output is delivered to you " +
                "automatically, so there is no need to poll one."
        )
        putJsonObject("parameters") {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("action") {
                    put("type", "string")
                    putJsonArray("enum") { add("start"); add("stop"); add("output"); add("wait") }
                    put(
                        "description",
                        "start: run 'command' in the background. stop: kill a job. " +
                            "output: what a job has printed so far, running or not. " +
                            "wait: block until a job finishes."
                    )
                }
                putJsonObject("command") {
                    put("type", "string")
                    put("description", "The shell command to run. Required for \"start\".")
                }
                putJsonObject("name") {
                    put("type", "string")
                    put(
                        "description",
                        "Which job to act on — required for \"stop\", \"output\" and \"wait\". " +
                            "On \"start\" it is an optional name for the new job; one is assigned if you omit it."
                    )
                }
                putJsonObject("seconds") {
                    put("type", "number")
                    put("description", "How long \"wait\" may block. Defaults to $DEFAULT_WAIT_SECONDS, capped at $MAX_WAIT_SECONDS.")
                }
            }
            putJsonArray("required") { add("action") }
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
    val payload = buildJsonObject {
        put("model", MODEL)
        put("input", JsonArray(input))
        // No "temperature": GPT-5 is a reasoning model and does not take one.
        // Use "reasoning_effort" (low/medium/high) to trade quality for tokens.
        //
        // "store" is left at its default of true on purpose: reasoning items come
        // back as bare ids the server rehydrates, so echoing them costs a few dozen
        // bytes instead of the encrypted blobs store=false would force us to carry
        // — and without them gpt-5 re-derives its thinking every iteration. The
        // trade is that OpenAI retains the session; /help says so.
        put("tools", TOOLS)
    }.toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create("$baseUrl/v1/responses"))
        .timeout(Duration.ofSeconds(API_TIMEOUT_SECONDS))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()

    // A rate limit is the normal weather for an agent loop, not an error: the
    // loop resends its whole history every iteration, so a busy task can spend a
    // minute's token allowance on itself. Wait it out rather than lose the task.
    var attempt = 0
    while (true) {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        val status = response.statusCode()
        if (status == 200) return response.toTurn()
        if (status != 429 && status < 500) {
            throw Exception("API Error [Status $status]: ${response.body()}")
        }
        if (attempt >= MAX_RETRIES) {
            throw Exception("API Error [Status $status] after $MAX_RETRIES retries: ${response.body()}")
        }

        val waitMs = retryDelayMs(response, attempt++)
        Spinner.log(
            "⏳ %d from the API — retrying in %.1fs (attempt %d/%d)"
                .format(Locale.ROOT, status, waitMs / 1000.0, attempt, MAX_RETRIES)
        )
        if (!sleepUnlessCancelled(waitMs, cancelled)) throw Exception("Cancelled while waiting out a rate limit.")
    }
}

// ---------- 4. Main entry point (interactive console) ----------

/**
 * What the console waits on. The user is no longer the only thing that can start
 * a turn, so the loop takes events from a queue instead of reading stdin
 * directly — [readlnOrNull] would hold the thread hostage while a background job
 * finished with nobody to tell.
 */
private sealed interface Event {
    data class Typed(val line: String) : Event
    data object EndOfInput : Event
    data object JobFinished : Event
}

fun printHelp() {
    println(
        """
        Commands:
          /help    Show this help
          /reset   Clear the conversation history
          /exit    Quit (or Ctrl+D)
        Ctrl+C cancels the running task; at the prompt it quits.
        Anything else is sent to the agent as a task.

        The agent can run commands in the background. Those survive /reset and a
        cancelled task, and are killed when the session ends. When one finishes
        the agent wakes up and reports on it, even if you have not asked.

        Note: requests are sent with store=true, so OpenAI retains this session
        — the commands run and their output included — for about 30 days.
        """.trimIndent()
    )
}

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY").orEmpty()
    if (apiKey.isBlank()) {
        println("❌ Please set the 'OPENAI_API_KEY' environment variable.")
        return
    }

    val workspace = File(".").apply { mkdirs() }.canonicalFile

    // Unbounded on purpose: put() is called from a job's watcher thread, which
    // must not block — it is what publishes the job's final state.
    val events = LinkedBlockingQueue<Event>()
    val harness = BashAgentHarness(workspace, apiKey) { events.put(Event.JobFinished) }
    fun farewell() = println("\n👋 Bye! Session cost: \$%.4f".format(Locale.ROOT, harness.sessionCost()))

    // Background jobs outlive the task that started them by design, so something
    // has to end them. A shutdown hook covers all three ways out at once: /exit,
    // Ctrl+D, and the exitProcess in the Ctrl+C handler below.
    Runtime.getRuntime().addShutdownHook(thread(start = false) { harness.shutdown() })

    // Ctrl+C cancels the running task instead of killing the JVM. At the idle
    // prompt it still quits, which is what a console user expects.
    Signal.handle(Signal("INT")) {
        if (harness.busy) {
            harness.interrupt()
        } else {
            farewell()
            exitProcess(0)
        }
    }

    println("🤖 Bash Agent — Workspace: ${workspace.absolutePath}")
    printHelp()

    // Stdin gets its own thread so the loop below can wait on the user and on a
    // finishing job at the same time. A daemon: at exit there is no line to
    // finish reading, and a live read would keep the JVM up.
    thread(isDaemon = true, name = "stdin") {
        while (true) {
            val line = readlnOrNull()
            events.put(line?.let { Event.Typed(it) } ?: Event.EndOfInput)
            if (line == null) break // Ctrl+D; nothing more will ever arrive.
        }
    }

    // Tracked rather than printed every pass: a wake-up that turns out to have
    // nothing to report leaves the prompt already on screen and standing.
    var prompted = false
    loop@ while (true) {
        if (!prompted) {
            print("\n👤 You: ")
            System.out.flush()
            prompted = true
        }

        when (val event = events.take()) {
            Event.EndOfInput -> break@loop
            Event.JobFinished -> if (harness.resume()) prompted = false
            is Event.Typed -> {
                prompted = false
                val input = event.line.trim()
                if (input.isEmpty()) continue@loop

                when (input.lowercase()) {
                    "/exit", "/quit" -> break@loop
                    "/help" -> printHelp()
                    "/reset" -> {
                        harness.reset()
                        println("🧹 History cleared.")
                    }
                    else -> {
                        println()
                        harness.runTask(input)
                    }
                }
            }
        }
    }

    farewell()
}
