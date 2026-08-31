import com.jediterm.terminal.CursorShape
import com.jediterm.terminal.ProcessTtyConnector
import com.jediterm.terminal.TerminalDisplay
import com.jediterm.terminal.TtyBasedArrayDataStream
import com.jediterm.terminal.emulator.JediEmulator
import com.jediterm.terminal.emulator.mouse.MouseFormat
import com.jediterm.terminal.emulator.mouse.MouseMode
import com.jediterm.terminal.model.JediTerminal
import com.jediterm.terminal.model.StyleState
import com.jediterm.terminal.model.TerminalSelection
import com.jediterm.terminal.model.TerminalTextBuffer
import com.pty4j.PtyProcessBuilder
import java.io.File
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/** What the test can do while the agent runs: type a line (once the prompt is up) or wait for the model to be called. */
class Session(
    private val stdin: OutputStream, private val prompts: AtomicInteger,
    private val buffer: TerminalTextBuffer, private val mock: MockOpenAi,
) {
    private var typed = 0
    private fun await(what: String, ready: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!ready() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        check(ready()) { "timed out waiting for $what:\n${snapshot(buffer)}" }
    }
    // Typing before readLine is active is lost: the raw-mode switch flushes the tty's input queue.
    // JLine enters application-cursor mode at every readLine; the prompt text itself is unreliable on a pty.
    fun line(text: String) {
        await("prompt") { prompts.get() > typed }
        typed++
        stdin.write("$text\n".toByteArray()); stdin.flush()
    }
    fun awaitRequests(n: Int) = await("$n requests") { mock.requests.size >= n }
}

private const val COLS = 120
private const val ROWS = 40

/**
 * Runs the agent on a pty4j pseudo-terminal (the console only runs on a tty) and returns what a
 * user would have seen: the pty stream fed live into jediterm's headless VT100 emulator, so
 * cursor movement and erasures are applied instead of stripped. Slow by design: a JVM per call.
 */
fun console(workspace: File, mock: MockOpenAi, type: Session.() -> Unit): String {
    val java = arrayOf(
        ProcessHandle.current().info().command().get(),
        "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "AgentKt"
    )
    // setEnvironment replaces the whole environment, so carry the parent's over.
    val process = PtyProcessBuilder(java)
        .setDirectory(workspace.absolutePath)
        .setEnvironment(System.getenv() + mapOf(
            "OPENAI_API_KEY" to "test-key", "OPENAI_BASE_URL" to mock.baseUrl, "AGENT_LOG" to "", "TERM" to "xterm"))
        .setInitialColumns(COLS).setInitialRows(ROWS)
        .start()
    val style = StyleState()
    val buffer = TerminalTextBuffer(COLS, ROWS, style)
    val prompts = AtomicInteger()
    // JLine enables application-cursor mode at every readLine, so that mode-set is the prompt signal.
    val terminal = object : JediTerminal(NullDisplay, buffer, style) {
        override fun setApplicationArrowKeys(enabled: Boolean) {
            super.setApplicationArrowKeys(enabled)
            if (enabled) prompts.incrementAndGet()
        }
    }
    val connector = object : ProcessTtyConnector(process, Charsets.UTF_8) { override fun getName() = "agent" }
    val emulator = JediEmulator(TtyBasedArrayDataStream(connector), terminal)
    // Drain on a thread so the pty never blocks; ends when the pty closes (EOF).
    val drain = thread { while (emulator.hasNext()) emulator.next() }
    try {
        Session(process.outputStream, prompts, buffer, mock).type()
        if (!process.waitFor(30, TimeUnit.SECONDS)) error("console did not exit:\n${snapshot(buffer)}")
    } finally { process.destroyForcibly() }
    drain.join()
    // jediterm may surface private-use glyphs from styled lines; they are renderer noise.
    return snapshot(buffer).replace(Regex("[\uE000-\uF8FF]"), "")
}

/** History + screen is the full transcript as rendered; locked, the drain thread may still be writing. */
private fun snapshot(buffer: TerminalTextBuffer): String {
    val text = StringBuilder()
    buffer.lock()
    try {
        for (i in -buffer.historyLinesCount until buffer.screenLinesCount) {
            val line = buffer.getLine(i)
            // A line hard-wrapped at the right edge continues on the next row: rejoin it.
            if (line.isWrapped) text.append(line.text) else text.append(line.text.trimEnd()).append('\n')
        }
    } finally { buffer.unlock() }
    return text.toString().trimEnd()
}

/** JediTerminal needs a display; jediterm's own test stub is not published, so: nothing to show. */
private object NullDisplay : TerminalDisplay {
    override fun setCursor(x: Int, y: Int) {}
    override fun setCursorShape(shape: CursorShape?) {}
    override fun beep() {}
    override fun scrollArea(scrollRegionTop: Int, scrollRegionSize: Int, dy: Int) {}
    override fun setCursorVisible(isCursorVisible: Boolean) {}
    override fun useAlternateScreenBuffer(useAlternateScreenBuffer: Boolean) {}
    override fun getWindowTitle(): String = ""
    override fun setWindowTitle(windowTitle: String) {}
    override fun getSelection(): TerminalSelection? = null
    override fun terminalMouseModeSet(mouseMode: MouseMode) {}
    override fun setMouseFormat(mouseFormat: MouseFormat) {}
    override fun ambiguousCharsAreDoubleWidth(): Boolean = false
}
