import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class RetryDelayTest : FunSpec({

    test("the server's Retry-After wins, plus a pad") {
        retryDelayMs(retryAfter = "8.134", attempt = 0) shouldBe 8_384L
        // Landing exactly on the window boundary earns a second 429.
        retryDelayMs(retryAfter = "8.134", attempt = 0) shouldBeGreaterThan 8_134L
    }

    context("without a header it backs off exponentially") {
        withData(
            nameFn = { (attempt, _) -> "attempt $attempt" },
            0 to 1_000L, 1 to 2_000L, 2 to 4_000L, 3 to 8_000L, 4 to 16_000L
        ) { (attempt, expected) -> retryDelayMs(retryAfter = null, attempt) shouldBe expected }
    }

    test("waits are clamped at both ends") {
        retryDelayMs(retryAfter = null, attempt = 20) shouldBe MAX_RETRY_WAIT_MS
        retryDelayMs(retryAfter = "600", attempt = 0) shouldBe MAX_RETRY_WAIT_MS
        // A zero hint must not turn the retry loop into a spin.
        retryDelayMs(retryAfter = "0", attempt = 0) shouldBeGreaterThanOrEqual 250L
    }

    test("an uninterrupted wait runs to completion") {
        val started = System.currentTimeMillis()
        sleepUnlessCancelled(250) { false } shouldBe true
        (System.currentTimeMillis() - started) shouldBeGreaterThanOrEqual 240L
    }

    test("an already-cancelled wait returns at once") {
        val started = System.currentTimeMillis()
        sleepUnlessCancelled(30_000) { true } shouldBe false
        (System.currentTimeMillis() - started) shouldBeLessThan 500L
    }

    test("a wait notices cancellation that arrives partway through") {
        // Ctrl+C during a long rate-limit wait must not wedge the console.
        val cancelled = AtomicBoolean(false)
        thread { Thread.sleep(150); cancelled.set(true) }

        val started = System.currentTimeMillis()
        sleepUnlessCancelled(30_000) { cancelled.get() } shouldBe false
        (System.currentTimeMillis() - started) shouldBeLessThan 2_000L
    }
})
