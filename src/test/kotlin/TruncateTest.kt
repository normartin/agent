import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldStartWith

class TruncateTest : FunSpec({

    test("output within the cap is returned untouched") {
        truncate("small") shouldBe "small"
        val exact = "x".repeat(MAX_OUTPUT_CHARS)
        truncate(exact) shouldBe exact
    }

    test("oversized output is cut down to the cap") {
        val result = truncate("x".repeat(500_000))
        // The elision marker itself adds a few characters on top of head + tail.
        result.length shouldBeLessThanOrEqualTo MAX_OUTPUT_CHARS + 128
    }

    test("both the head and the tail survive") {
        // Build failures land at the end of a log, so dropping the tail would
        // hide the very thing the agent needs to read.
        val text = "START" + "-".repeat(200_000) + "EXIT CODE 1"
        val result = truncate(text)
        result shouldStartWith "START"
        result shouldEndWith "EXIT CODE 1"
    }

    test("the cut is marked with the exact number of characters dropped") {
        val text = "y".repeat(100_000)
        val result = truncate(text)
        result shouldContain "chars elided"

        val dropped = Regex("\\[(\\d+) chars elided").find(result)!!.groupValues[1].toInt()
        val head = MAX_OUTPUT_CHARS * 2 / 3
        val tail = MAX_OUTPUT_CHARS / 3
        dropped shouldBe text.length - head - tail
    }

    test("head and tail are derived from the cap, so they cannot outgrow it") {
        // Regression: these were once hardcoded at 4000/2000 and silently
        // exceeded the cap when it was lowered to 2500.
        val head = MAX_OUTPUT_CHARS * 2 / 3
        val tail = MAX_OUTPUT_CHARS / 3
        (head + tail) shouldBeLessThanOrEqualTo MAX_OUTPUT_CHARS

        val result = truncate("z".repeat(MAX_OUTPUT_CHARS * 10))
        result.takeWhile { it == 'z' }.length shouldBe head
        result.takeLastWhile { it == 'z' }.length shouldBe tail
    }

    test("the marker names the line range that was cut, so one sed -n can fetch it") {
        val text = (1..1000).joinToString("\n") { "line $it" + "-".repeat(20) }
        val result = truncate(text)
        val (from, to, total) = Regex("lines (\\d+)-(\\d+) of (\\d+)").find(result)!!.groupValues.drop(1).map { it.toInt() }
        total shouldBe 1000
        result shouldContain "sed -n '$from,${to}p'"
        // The head ends inside line `from` and the tail starts inside line `to`: both are partially shown.
        val lines = text.lines()
        result shouldStartWith lines.take(from - 1).joinToString("\n")
        result shouldEndWith lines.drop(to).joinToString("\n")
    }

    test("single-line output gets no line hint") {
        truncate("q".repeat(100_000)) shouldContain "chars elided] …"
    }
})
