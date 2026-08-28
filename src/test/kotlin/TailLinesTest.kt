import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

class TailLinesTest : StringSpec({
    fun file(text: String) = File.createTempFile("tail-", ".log").apply { deleteOnExit(); writeText(text) }

    "fewer lines than keep" {
        tailLines(file("a\nb\n"), keep = 5) shouldBe listOf("a", "b")
    }

    "last keep lines, trailing newline ignored" {
        tailLines(file("1\n2\n3\n4\n5\n6\n7\n"), keep = 5) shouldBe listOf("3", "4", "5", "6", "7")
    }

    "carriage-return frames collapse to the last one" {
        tailLines(file("a\n10%\r50%\r99%\nb"), keep = 5) shouldBe listOf("a", "99%", "b")
    }

    "reads only the end of a large file and drops the cut first line" {
        val text = (1..5000).joinToString("\n") { "line $it" }
        tailLines(file(text), keep = 3, window = 40) shouldBe listOf("line 4998", "line 4999", "line 5000")
        // A 40-byte window starting mid-file: the partial first line must not surface as a bogus line.
        tailLines(file(text), keep = 10, window = 40).all { it.startsWith("line ") } shouldBe true
    }
})
