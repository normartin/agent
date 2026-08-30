import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The console only runs on a tty, so these drive the compiled agent under `script`'s pseudo-terminal.
 * Slow by design (a JVM per test): keep it to smoke tests of what nothing else can reach.
 * Experimental: if it turns flaky or costly to maintain, delete it rather than nurse it.
 */
class ConsoleTest : FunSpec({

    val workspace = tempdir()

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

    /** Runs the agent under a pty and returns everything it printed. */
    fun console(mock: MockOpenAi, type: Session.() -> Unit): String {
        val java = listOf(
            ProcessHandle.current().info().command().get(),
            "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "AgentKt"
        )
        // BSD script takes the command as arguments; util-linux wants a single -c string.
        val cmd = if (System.getProperty("os.name").startsWith("Mac")) listOf("script", "-q", "/dev/null") + java
        else listOf("script", "-q", "-c", java.joinToString(" "), "/dev/null")
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
        return output.toString()
    }

    test("an unknown /command is rejected with help and never reaches the model") {
        MockOpenAi().use { mock ->
            val out = console(mock) { line("/foo"); line("/exit") }
            out shouldContain "Bash Agent — Workspace"
            out shouldContain "Unknown command"
            out shouldContain "Bye!"
            mock.requests.size shouldBe 0
        }
    }

    test("a job finishing while the user is idle at the prompt starts a turn by itself") {
        MockOpenAi().use { mock ->
            mock.script(
                turn(reasoning(), bash(action = "start", command = "sleep 1; echo done")),
                turn(answer("started")),
                turn(answer("saw it"))
            )
            val out = console(mock) {
                line("run it")
                awaitRequests(3) // nothing typed meanwhile: the third request comes from the finished job
                line("/exit")
            }
            out shouldContain "saw it"
            mock.requests.size shouldBe 3
            mock.requests[2].input.last().str("content")!! shouldStartWith "[background job"
        }
    }
})
