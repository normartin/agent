import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

private const val EPS = 1e-9

class CostTest : FunSpec({

    test("prices match the configured model") {
        MODEL shouldBe "gpt-5"
        INPUT_USD_PER_1M shouldBe (1.25 plusOrMinus EPS)
        CACHED_INPUT_USD_PER_1M shouldBe (0.125 plusOrMinus EPS)
        OUTPUT_USD_PER_1M shouldBe (10.00 plusOrMinus EPS)
    }

    test("uncached input bills at the full rate") {
        turnCost(1_000_000, 0, 0) shouldBe (1.25 plusOrMinus EPS)
    }

    test("output bills at the output rate") {
        turnCost(0, 0, 1_000_000) shouldBe (10.0 plusOrMinus EPS)
    }

    test("cached tokens are a subset, not an addition") {
        // The API reports cached_tokens inside prompt_tokens. Charging both
        // would overstate a fully-cached turn tenfold.
        turnCost(1_000_000, 1_000_000, 0) shouldBe (0.125 plusOrMinus EPS)
        turnCost(1_000_000, 1_000_000, 0) shouldBeLessThan turnCost(1_000_000, 0, 0)
    }

    test("a partly cached prompt splits across both rates") {
        // 500k uncached at $1.25 + 500k cached at $0.125
        turnCost(1_000_000, 500_000, 0) shouldBe (0.6875 plusOrMinus EPS)
    }

    test("zero usage is free") {
        turnCost(0, 0, 0) shouldBe (0.0 plusOrMinus EPS)
    }

    test("a fresh harness has spent nothing") {
        BashAgentHarness(File("."), "dummy-key").sessionCost() shouldBe (0.0 plusOrMinus EPS)
    }

    test("gpt-5 undercuts the gpt-4o rates it replaced") {
        // Same turn shape, priced under the old gpt-4o constants ($2.50/$10.00).
        val onGpt4o = 171_000 / 1_000_000.0 * 2.50 + 12_000 / 1_000_000.0 * 10.0
        val onGpt5 = turnCost(171_000, 0, 12_000)
        onGpt5 shouldBeLessThan onGpt4o
        turnCost(171_000, 136_800, 12_000) shouldBeLessThan onGpt5
    }

    test("usage details are read from the nested objects the API returns") {
        val usage = Json.parseToJsonElement(
            """{"input_tokens":1000,"output_tokens":200,
                "input_tokens_details":{"cached_tokens":768},
                "output_tokens_details":{"reasoning_tokens":128}}"""
        ).jsonObject

        usage["input_tokens_details"]?.jsonObject?.get("cached_tokens")
            ?.jsonPrimitive?.longOrNull shouldBe 768L
        usage["output_tokens_details"]?.jsonObject?.get("reasoning_tokens")
            ?.jsonPrimitive?.longOrNull shouldBe 128L
    }
})
