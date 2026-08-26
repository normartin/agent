import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Optional
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSession
import kotlin.concurrent.thread

/** Minimal stand-in so retryDelayMs can be driven by header alone. */
fun headersOnly(status: Int, vararg headers: Pair<String, String>) = object : HttpResponse<String> {
    override fun statusCode() = status
    override fun request(): HttpRequest = throw UnsupportedOperationException()
    override fun previousResponse(): Optional<HttpResponse<String>> = Optional.empty()
    override fun headers(): HttpHeaders =
        HttpHeaders.of(headers.associate { it.first to listOf(it.second) }) { _, _ -> true }
    override fun body() = ""
    override fun sslSession(): Optional<SSLSession> = Optional.empty()
    override fun uri(): URI = URI.create("https://example.test")
    override fun version(): HttpClient.Version = HttpClient.Version.HTTP_1_1
}

class RetryDelayTest : FunSpec({

    test("the server's Retry-After wins, plus a pad") {
        val response = headersOnly(429, "retry-after" to "8.134")
        retryDelayMs(response, 0) shouldBe 8_384L
        // Landing exactly on the window boundary earns a second 429.
        retryDelayMs(response, 0) shouldBeGreaterThan 8_134L
    }

    context("without headers it backs off exponentially") {
        withData(
            nameFn = { (attempt, _) -> "attempt $attempt" },
            0 to 1_000L, 1 to 2_000L, 2 to 4_000L, 3 to 8_000L, 4 to 16_000L
        ) { (attempt, expected) -> retryDelayMs(headersOnly(503), attempt) shouldBe expected }
    }

    test("waits are clamped at both ends") {
        retryDelayMs(headersOnly(503), 20) shouldBe MAX_RETRY_WAIT_MS
        retryDelayMs(headersOnly(429, "retry-after" to "600"), 0) shouldBe MAX_RETRY_WAIT_MS
        // A zero hint must not turn the retry loop into a spin.
        retryDelayMs(headersOnly(429, "retry-after" to "0"), 0) shouldBeGreaterThanOrEqual 250L
    }

    test("an uninterrupted wait runs to completion") {
        val started = System.currentTimeMillis()
        sleepUnlessCancelled(400) { false } shouldBe true
        (System.currentTimeMillis() - started) shouldBeGreaterThanOrEqual 390L
    }

    test("an already-cancelled wait returns at once") {
        val started = System.currentTimeMillis()
        sleepUnlessCancelled(30_000) { true } shouldBe false
        (System.currentTimeMillis() - started) shouldBeLessThan 500L
    }

    test("a wait notices cancellation that arrives partway through") {
        // Ctrl+C during a long rate-limit wait must not wedge the console.
        val cancelled = AtomicBoolean(false)
        thread { Thread.sleep(300); cancelled.set(true) }

        val started = System.currentTimeMillis()
        sleepUnlessCancelled(30_000) { cancelled.get() } shouldBe false
        (System.currentTimeMillis() - started) shouldBeLessThan 2_000L
    }
})
