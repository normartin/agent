import io.kotest.matchers.shouldBe

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
 * Asserts that every process carrying [marker] is gone. Polls first rather than
 * sleeping a fixed spell: a killed process lingers as a zombie until its parent
 * reaps it, and pgrep counts zombies.
 */
fun shouldLeaveNoProcess(marker: String, timeoutMs: Long = 10_000) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (processesMatching(marker) > 0 && System.currentTimeMillis() < deadline) {
        Thread.sleep(100)
    }
    processesMatching(marker) shouldBe 0
}
