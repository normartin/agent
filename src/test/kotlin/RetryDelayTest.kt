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

    context("parseDelayMs reads the formats OpenAI emits") {
        withData(
            nameFn = { (raw, _) -> "\"$raw\"" },
            "3" to 3_000L,              // bare seconds
            "8.134s" to 8_134L,         // the format in a real 429 body
            "500ms" to 500L,            // "ms" must win over "s"
            "1m30s" to 90_000L,
            "6m0s" to 360_000L,
            "1h" to 3_600_000L
        ) { (raw, expected) -> parseDelayMs(raw) shouldBe expected }
    }

    test("unparseable values fall through to the caller's backoff") {
        parseDelayMs(null) shouldBe null
        parseDelayMs("") shouldBe null
        parseDelayMs("   ") shouldBe null
        parseDelayMs("soon") shouldBe null
    }

    test("the server's stated reset wins, plus a pad") {
        // Straight from the reported rate-limit error: "try again in 8.134s".
        val response = headersOnly(429, "x-ratelimit-reset-tokens" to "8.134s")
        retryDelayMs(response, 0) shouldBe 8_384L
        // Landing exactly on the window boundary earns a second 429.
        retryDelayMs(response, 0) shouldBeGreaterThan 8_134L
    }

    context("headers are consulted in precedence order") {
        test("retry-after-ms is milliseconds, not seconds") {
            retryDelayMs(headersOnly(429, "retry-after-ms" to "8134"), 0) shouldBe 8_384L
        }
        test("retry-after is seconds") {
            retryDelayMs(headersOnly(429, "retry-after" to "3"), 0) shouldBe 3_250L
        }
        test("retry-after-ms outranks the reset headers") {
            val response = headersOnly(
                429,
                "retry-after-ms" to "1000",
                "x-ratelimit-reset-tokens" to "50s"
            )
            retryDelayMs(response, 0) shouldBe 1_250L
        }
        test("the request-reset header is the last resort") {
            retryDelayMs(headersOnly(429, "x-ratelimit-reset-requests" to "2s"), 0) shouldBe 2_250L
        }
    }

    context("without headers it backs off exponentially") {
        withData(
            nameFn = { (attempt, _) -> "attempt $attempt" },
            0 to 1_000L, 1 to 2_000L, 2 to 4_000L, 3 to 8_000L, 4 to 16_000L
        ) { (attempt, expected) -> retryDelayMs(headersOnly(503), attempt) shouldBe expected }
    }

    test("waits are clamped at both ends") {
        retryDelayMs(headersOnly(503), 20) shouldBe MAX_RETRY_WAIT_MS
        retryDelayMs(headersOnly(429, "retry-after" to "10m"), 0) shouldBe MAX_RETRY_WAIT_MS
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
