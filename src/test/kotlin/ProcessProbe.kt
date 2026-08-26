/**
 * Counts processes whose command line carries [marker].
 *
 * The first character is wrapped in a character class so the pattern matches
 * the marker but not the pgrep command line that contains the pattern — without
 * that, this helper counts itself and the test flakes.
 */
fun processesMatching(marker: String): Int {
    val selfExcluding = "[" + marker.first() + "]" + marker.drop(1)
    return ProcessBuilder("bash", "-c", "pgrep -f '$selfExcluding' | wc -l")
        .start().inputStream.bufferedReader().readText().trim().toInt()
}

/**
 * Waits for every process carrying [marker] to be gone. Polls rather than
 * sleeping a fixed spell: a killed process lingers as a zombie until its parent
 * reaps it, and pgrep counts zombies.
 */
fun awaitNoProcesses(marker: String, timeoutMs: Long = 10_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (processesMatching(marker) > 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(100)
    }
}
