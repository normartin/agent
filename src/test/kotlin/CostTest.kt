import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.File

private const val EPS = 1e-9

class CostTest : FunSpec({

    test("uncached input bills at the full rate") {
        turnCost(1_000_000, 0, 0) shouldBe (INPUT_USD_PER_1M plusOrMinus EPS)
    }

    test("output bills at the output rate") {
        turnCost(0, 0, 1_000_000) shouldBe (OUTPUT_USD_PER_1M plusOrMinus EPS)
    }

    test("cached tokens are a subset, not an addition") {
        // The API reports cached_tokens inside prompt_tokens. Charging both
        // would overstate a fully-cached turn tenfold.
        turnCost(1_000_000, 1_000_000, 0) shouldBe (CACHED_INPUT_USD_PER_1M plusOrMinus EPS)
        turnCost(1_000_000, 1_000_000, 0) shouldBeLessThan turnCost(1_000_000, 0, 0)
    }

    test("a partly cached prompt splits across both rates") {
        turnCost(1_000_000, 500_000, 0) shouldBe ((INPUT_USD_PER_1M + CACHED_INPUT_USD_PER_1M) / 2 plusOrMinus EPS)
    }

    test("zero usage is free") {
        turnCost(0, 0, 0) shouldBe (0.0 plusOrMinus EPS)
    }

    test("a fresh harness has spent nothing") {
        BashAgentHarness(File("."), "dummy-key").sessionCost() shouldBe (0.0 plusOrMinus EPS)
    }
})
