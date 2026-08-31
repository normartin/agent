import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

// The four Responses input-item shapes a session is built out of.

private fun roleMsg(role: String, content: String) = buildJsonObject {
    put("role", role)
    put("content", content)
}

private fun reasoning(id: String = "rs_x") = buildJsonObject {
    put("type", "reasoning")
    put("id", id)
    putJsonArray("summary") {}
}

private fun functionCall(callId: String = "call_x") = buildJsonObject {
    put("type", "function_call")
    put("id", "fc_x")
    put("call_id", callId)
    put("name", "bash")
    put("arguments", """{"command":"ls"}""")
}

private fun functionCallOutput(output: String, callId: String = "call_x") = buildJsonObject {
    put("type", "function_call_output")
    put("call_id", callId)
    put("output", output)
}

/** Role for message items, item type for the rest. */
private fun kinds(input: List<JsonObject>) = input.map { it.str("role") ?: it.str("type")!! }

/**
 * A realistic session: system, then per turn
 * user -> reasoning -> function_call -> function_call_output -> assistant.
 */
private fun session(turns: Int, pad: Int): MutableList<JsonObject> {
    val input = mutableListOf(roleMsg("system", "sys"))
    repeat(turns) { i ->
        input.add(roleMsg("user", "task $i"))
        input.add(reasoning())
        input.add(functionCall())
        input.add(functionCallOutput("output $i " + "x".repeat(pad)))
        input.add(roleMsg("assistant", "done $i"))
    }
    return input
}

private fun size(input: List<JsonObject>) = input.sumOf { it.toString().length }

// The trigger is the measured token count of the last request, not the history's chars.
private const val OVER_BUDGET = MAX_HISTORY_TOKENS * 2L

class TrimHistoryTest : FunSpec({

    test("a history measured inside the budget is left alone") {
        val input = session(turns = 40, pad = 6_000)
        val before = input.toList()
        trimHistory(input, MAX_HISTORY_TOKENS.toLong())
        input shouldContainExactly before
    }

    test("a history measured over the budget is actually shrunk") {
        val input = session(turns = 40, pad = 6_000)
        val before = size(input)
        trimHistory(input, OVER_BUDGET)
        size(input) shouldBeLessThan before
    }

    test("the system prompt is never dropped") {
        val input = session(turns = 40, pad = 6_000)
        trimHistory(input, OVER_BUDGET)
        input.first().str("role") shouldBe "system"
        input.first().str("content") shouldBe "sys"
    }

    test("the newest item always survives") {
        val input = session(turns = 40, pad = 6_000)
        val newest = input.last()
        trimHistory(input, OVER_BUDGET)
        input.last() shouldBe newest
    }

    test("history always resumes on a user message") {
        // Anything else is orphaned from its function_call or reasoning item: a 400.
        val input = session(turns = 40, pad = 6_000)
        trimHistory(input, OVER_BUDGET)
        input[1].str("role") shouldBe "user"
    }

    test("a history with no turn boundary to resume on is left untouched") {
        // Mid tool-loop every cut orphans something; over budget beats a 400.
        val input = mutableListOf(
            roleMsg("system", "sys"), roleMsg("user", "task"), reasoning(),
            functionCall(), functionCallOutput("x".repeat(120_000))
        )
        val before = input.toList()
        trimHistory(input, OVER_BUDGET)
        input shouldContainExactly before
    }

    test("a single oversized item cannot empty the history") {
        val input = mutableListOf(
            roleMsg("system", "sys"),
            roleMsg("user", "y".repeat(360_000))
        )
        trimHistory(input, OVER_BUDGET)
        kinds(input) shouldContainExactly listOf("system", "user")
    }

    test("a trim cuts down to the target, so the next few turns do not trim again") {
        // Each trim forfeits the prompt cache on the whole history; trimming to just under the
        // budget would repeat that on every turn past it. Chars apportion the cut: what survives
        // is at most the TRIM_TARGET_TOKENS share of the measured tokens, in chars.
        val input = session(turns = 40, pad = 6_000)
        val before = size(input)
        trimHistory(input, OVER_BUDGET)
        size(input).toLong() shouldBeLessThanOrEqual before.toLong() * TRIM_TARGET_TOKENS / OVER_BUDGET

        // The next request re-measures the trimmed history: back around the target, so no new trim.
        input.add(roleMsg("user", "z".repeat(6_000)))
        val afterOneMoreTurn = input.toList()
        trimHistory(input, TRIM_TARGET_TOKENS.toLong())
        input shouldContainExactly afterOneMoreTurn
    }

    test("the summarizer sees exactly the dropped items and its text lands at index 1 as a user item") {
        val input = session(turns = 40, pad = 6_000)
        var seen: List<JsonObject>? = null
        trimHistory(input, OVER_BUDGET) { dropped -> seen = dropped; "the gist" }

        val dropped = checkNotNull(seen)
        dropped.first() shouldBe roleMsg("user", "task 0")         // system prompt not offered up
        dropped.last().str("role") shouldBe "assistant"            // stops right before the surviving user turn
        input[1] shouldBe roleMsg("user", "[summary of earlier conversation]\nthe gist")
        input[2].str("role") shouldBe "user"
        input.first().str("content") shouldBe "sys"
    }

    test("a summarizer returning null falls back to the plain trim") {
        val plain = session(turns = 40, pad = 6_000)
        trimHistory(plain, OVER_BUDGET)
        val input = session(turns = 40, pad = 6_000)
        trimHistory(input, OVER_BUDGET) { null }
        input shouldContainExactly plain
    }

    test("the invariants hold across arbitrary sessions and budget pressures") {
        checkAll(200, Arb.int(1..30), Arb.int(1..16_000), Arb.long(0L..150_000L)) { turns, pad, tokens ->
            val input = session(turns, pad)
            val newest = input.last()
            trimHistory(input, tokens)

            input.first().str("role") shouldBe "system"
            input.last() shouldBe newest
            if (input.size > 1) {
                input[1].str("role") shouldBe "user"
            }
        }
    }
})
