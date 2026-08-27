///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.4.10
//DEPS org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

// Renders agent.kt JSONL logs as a readable transcript: user turns, reasoning, commands, clipped output.

import kotlinx.serialization.json.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private data class Config(
    val files: List<String> = emptyList(),
    val maxChars: Int = 120,
    val maxLines: Int = 8,
    val showRaw: Boolean = false
)

private val clockFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun main(args: Array<String>) = renderLog(args.toList())

/** Entry point split from main so tests can call it: agent.kt's main() shadows this file's in the same module. */
fun renderLog(args: List<String>) {
    val cfg = parseArgs(args) ?: return
    val files = resolveFiles(cfg.files)
    if (files.isEmpty()) {
        System.err.println("No agent log files found. Pass file paths or keep agent-*.jsonl in the current directory.")
        return
    }
    files.forEachIndexed { i, file ->
        if (i > 0) println()
        val r = Renderer(cfg, file.name)
        file.forEachLine { line ->
            val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            if (obj == null) println("⚠  invalid JSON line") else r.event(obj)
        }
        r.footer()
    }
}

private fun parseArgs(args: List<String>): Config? {
    var maxChars = 120
    var maxLines = 8
    var showRaw = false
    val files = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "-h", "--help" -> {
                println(
                    """
                    Usage: ./agent-log.kt [options] [file1.jsonl file2.jsonl ...]

                    Readable transcript of agent.kt JSONL logs.

                    Options:
                      --max-chars N   line width before clipping (default: 120)
                      --max-lines N   lines shown per command/output block (default: 8)
                      --all           no clipping at all
                      --raw           also show request/response/http meta lines
                      -h, --help      show this help

                    If no files are given, the newest ./agent-*.jsonl is used.
                    """.trimIndent()
                )
                return null
            }
            "--raw" -> showRaw = true
            "--all" -> { maxChars = Int.MAX_VALUE; maxLines = Int.MAX_VALUE }
            "--max-chars", "--max-lines" -> {
                val n = args.getOrNull(++i)?.toIntOrNull()
                if (n == null || n <= 0) { System.err.println("Expected a positive integer after $a"); return null }
                if (a == "--max-chars") maxChars = n else maxLines = n
            }
            else -> files += a
        }
        i++
    }
    return Config(files, maxChars, maxLines, showRaw)
}

private fun resolveFiles(explicit: List<String>): List<File> {
    if (explicit.isNotEmpty()) return explicit.map(::File).filter { it.isFile }
    return File(".").listFiles { f -> f.isFile && f.name.startsWith("agent-") && f.name.endsWith(".jsonl") }
        ?.maxByOrNull { it.lastModified() }?.let { listOf(it) } ?: emptyList()
}

private class Renderer(val cfg: Config, val fileName: String) {
    private var inTurn = false
    private var steps = 0
    private var calls = 0
    private var inTok = 0L
    private var cachedTok = 0L
    private var outTok = 0L
    private var first: Instant? = null
    private var last: Instant? = null

    fun event(obj: JsonObject) {
        val ts = obj.text("ts")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (ts != null) { if (first == null) first = ts; last = ts }
        // Sub-agents append to the same file; a bar per depth level keeps them visually nested.
        val depth = (obj["depth"] as? JsonPrimitive)?.intOrNull ?: 0
        val bar = "│ ".repeat(depth)
        val body = bar + if (inTurn) "  " else ""
        when (obj.text("type")) {
            "session" -> {
                val files = (obj["instruction_files"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                val extra = (if (depth > 0) " · pid ${obj.strNum("pid")}" else "") +
                    (if (files.isNotEmpty()) " · instructions: ${files.joinToString(", ")}" else "")
                println("$bar━━ $fileName ━━ ${obj.text("model")} · ${obj.text("workspace")} · ${clock(obj)}$extra")
                inTurn = false
            }
            "user" -> { println(); block("$bar👤  ${clock(obj)}  ", "$bar             ", obj.text("text").orEmpty(), prose = true); inTurn = true }
            "job_notice" -> block("$body📣  ", "$body   ", obj.text("text").orEmpty())
            "tool_call" -> { calls++; toolCall(body, obj.text("arguments").orEmpty()) }
            "tool_result" -> block("$body    ", "$body    ", obj.text("output").orEmpty().ifBlank { "(no output)" })
            "response" -> response(body, bar, obj)
            "trim" -> println("$body⚠  trim: dropped ${obj.strNum("dropped")} items (${k(obj.long("chars_before"))} → ${k(obj.long("chars_after"))} chars)")
            "retry" -> println("$body⚠  retry: status ${obj.strNum("status")}, wait ${obj.strNum("wait_ms")}ms, attempt ${obj.strNum("attempt")}")
            "error" -> block("$body⚠  error: ", "$body   ", obj.text("message").orEmpty())
            "request" -> if (cfg.showRaw) println("$body· request attempt ${obj.strNum("attempt")} ${obj.text("url")}")
        }
    }

    private fun response(body: String, bar: String, obj: JsonObject) {
        if (cfg.showRaw) println("$body· response status ${obj.strNum("status")} ${obj.strNum("elapsed_ms")}ms")
        val resp = obj["body"] as? JsonObject ?: return
        val output = (resp["output"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        steps++
        val usage = resp["usage"] as? JsonObject
        val cached = ((usage?.get("input_tokens_details") as? JsonObject)?.long("cached_tokens")) ?: 0
        inTok += usage?.long("input_tokens") ?: 0; cachedTok += cached; outTok += usage?.long("output_tokens") ?: 0
        val stats = "%.1fs · %s in (%s cached) · %s out".format(Locale.ROOT,
            obj.long("elapsed_ms") / 1000.0, k(usage?.long("input_tokens") ?: 0), k(cached), k(usage?.long("output_tokens") ?: 0)
        )
        val thoughts = output.filter { it.text("type") == "reasoning" }
            .flatMap { (it["summary"] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.text("text")?.replace("**", "")?.trim() }
            .filter { it.isNotEmpty() }
        println()
        thoughts.ifEmpty { listOf("(no reasoning summary)") }.forEachIndexed { i, t ->
            val line = "$body🧠  $t"
            println(if (i == 0) line.padEnd(maxOf(line.length + 3, 70)) + stats else line)
        }
        output.filter { it.text("type") == "message" }
            .flatMap { (it["content"] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.text("text") }
            .filter { it.isNotBlank() }
            .forEach { println(); block("$bar🤖  ${clock(obj)}  ", "$bar             ", it, prose = true) }
    }

    private fun toolCall(body: String, raw: String) {
        val args = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        if (args == null) { block("$body\$ ", "$body  ", raw); return }
        val cmd = args.text("command")
        if ((args.text("action") ?: "run") == "run") { block("$body\$ ", "$body  ", cmd.orEmpty()); return }
        val head = listOfNotNull(args.text("action"), args.text("name"), args.text("seconds")?.let { "${it}s" }).joinToString(" ")
        block("$body⚙  $head" + if (cmd != null) ": " else "", "$body  ", cmd.orEmpty())
    }

    /** Line-based clipping: whole lines survive, long ones get a trailing ellipsis. */
    private fun block(firstPrefix: String, prefix: String, text: String, prose: Boolean = false) {
        val lines = text.replace("\r", "").trimEnd().lines()
        val shown = lines.take(if (prose) minOf(cfg.maxLines * 4L, Int.MAX_VALUE.toLong()).toInt() else cfg.maxLines)
            .map { if (!prose && it.length > cfg.maxChars) it.take(cfg.maxChars - 1) + "…" else it }
        shown.forEachIndexed { i, l -> println((if (i == 0) firstPrefix else prefix) + l) }
        if (lines.size > shown.size) println("$prefix… ${lines.size - shown.size} more lines")
    }

    fun footer() {
        val secs = if (first != null && last != null) java.time.Duration.between(first, last).seconds else 0
        println()
        println("── $steps steps · $calls tool calls · ${secs}s · ${k(inTok)} in (${k(cachedTok)} cached) · ${k(outTok)} out ──")
    }
}

private fun k(n: Long) = if (n < 1000) "$n" else "%.1fk".format(Locale.ROOT, n / 1000.0)
private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.long(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0
private fun JsonObject.strNum(key: String): String = this[key]?.toString() ?: "?"
private fun clock(obj: JsonObject): String =
    obj.text("ts")?.let { runCatching { clockFmt.format(Instant.parse(it)) }.getOrNull() } ?: "--:--:--"
