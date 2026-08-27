import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

class BoundedLogTest : FunSpec({

    test("short output comes back whole") {
        val log = BoundedLog(cap = 300)
        log.append("hello ")
        log.append("world")
        log.snapshot() shouldBe "hello world"
    }

    test("a flood keeps the head and the tail and counts what went missing") {
        // The cap has to hold as the output arrives: a background job has no
        // deadline, so nothing else would stop it filling memory. The head is
        // pinned too: a build's first error is worth more than its 500th download.
        val log = BoundedLog(cap = 300, head = 50)
        repeat(1000) { log.append("line $it\n") }

        val snapshot = log.snapshot()
        snapshot.length shouldBeLessThan 450
        snapshot shouldContain "line 0\nline 1\n"  // head, from the very first byte
        snapshot shouldContain "line 999"          // tail, still moving
        snapshot shouldContain "chars elided"
        snapshot shouldNotContain "line 500"
        // The marker sits between two real pieces of output, in order.
        snapshot.indexOf("line 1\n") shouldBeLessThan snapshot.indexOf("chars elided")
        snapshot.indexOf("chars elided") shouldBeLessThan snapshot.indexOf("line 999")
    }

    test("a chunk that straddles the head boundary is split, not lost") {
        val log = BoundedLog(cap = 300, head = 8)
        log.append("abcdefghij")
        log.snapshot() shouldBe "abcdefghij"
        log.append("k")
        log.snapshot() shouldBe "abcdefghijk"
    }

    test("the elided count is exactly what was thrown away") {
        val log = BoundedLog(cap = 10, head = 5)
        log.append("x".repeat(100))
        log.snapshot() shouldBe "xxxxx\n… [85 chars elided] …\nxxxxxxxxxx"
    }
})

class CarriageReturnTest : FunSpec({

    test("a redrawn line keeps only its final frame") {
        collapseCarriageReturns("Progress: 1\rProgress: 2\rProgress: 3\ndone\n") shouldBe "Progress: 3\ndone\n"
    }

    test("windows line endings are just line endings") {
        collapseCarriageReturns("x\r\ny") shouldBe "x\ny"
    }

    test("text without a carriage return is untouched") {
        val text = "plain\nlines\n"
        collapseCarriageReturns(text) shouldBe text
    }

    test("a frame that straddles two chunks still collapses at read time") {
        val log = BoundedLog()
        log.append("Progress: 1\rProg")
        log.append("ress: 2\n")
        collapseCarriageReturns(log.snapshot()) shouldBe "Progress: 2\n"
    }
})
