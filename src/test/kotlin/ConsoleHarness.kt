import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** What the test can do while the agent runs: type a line (once the prompt is up) or wait for the model to be called. */
class Session(private val stdin: OutputStream, private val output: StringBuilder, private val mock: MockOpenAi) {
    private var typed = 0
    private fun await(what: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        check(ready()) { "timed out waiting for $what:\n$output" }
    }
    // Typing before readLine is active is lost: the raw-mode switch flushes the tty's input queue.
    // JLine enters application-cursor mode at every readLine; the prompt text itself is unreliable on a pty.
    fun line(text: String) {
        await("prompt") { synchronized(output) { output.split("\u001b[?1h").size - 1 } > typed }
        typed++
        stdin.write("$text\n".toByteArray()); stdin.flush()
    }
    fun awaitRequests(n: Int) = await("$n requests") { mock.requests.size >= n }
}

/**
 * Runs the agent under `script`'s pseudo-terminal (the console only runs on a tty) and returns
 * everything it printed. Slow by design: a JVM per call.
 */
fun console(workspace: File, mock: MockOpenAi, type: Session.() -> Unit): String {
    val java = listOf(
        ProcessHandle.current().info().command().get(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "AgentKt"
    )
    // script leaves the pty at 0x0 and JLine then draws a truncated ">...." instead of prompt and echo,
    // so stty gives it a real size first. BSD script takes the command as arguments; util-linux wants a single -c string.
    val sized = "stty rows 40 cols 120; exec " + java.joinToString(" ") { "'$it'" }
    val cmd = if (System.getProperty("os.name").startsWith("Mac")) listOf("script", "-q", "/dev/null", "sh", "-c", sized)
    else listOf("script", "-q", "-c", sized, "/dev/null")
    val process = ProcessBuilder(cmd).directory(workspace).redirectErrorStream(true).apply {
        environment() += mapOf("OPENAI_API_KEY" to "test-key", "OPENAI_BASE_URL" to mock.baseUrl, "AGENT_LOG" to "", "TERM" to "xterm")
    }.start()
    // Drain on a thread so the pty never blocks; by char, since the prompt ends without a newline.
    val output = StringBuilder()
    val drain = thread {
        val reader = process.inputStream.reader()
        val buf = CharArray(4096)
        while (true) { val n = reader.read(buf); if (n < 0) break; synchronized(output) { output.appendRange(buf, 0, n) } }
    }
    try {
        Session(process.outputStream, output, mock).type()
        if (!process.waitFor(30, TimeUnit.SECONDS)) error("console did not exit:\n$output")
    } finally { process.destroyForcibly() }
    drain.join()
    // The pty is full of JLine control sequences and CRLF; return plain text (Session syncs on the raw buffer).
    return output.toString().replace(Regex("\u001b(\\[[0-?]*[ -/]*[@-~]|[=>])"), "").replace("\r", "")
}
