import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

private fun msg(role: String, content: String, toolCalls: Boolean = false, toolId: String? = null) =
    buildJsonObject {
        put("role", role)
        put("content", content)
        if (toolId != null) put("tool_call_id", toolId)
        if (toolCalls) putJsonArray("tool_calls") {
            addJsonObject {
                put("id", "call_x")
                put("type", "function")
                putJsonObject("function") {
                    put("name", "bash")
                    put("arguments", """{"command":"ls"}""")
                }
            }
        }
    }

private fun roles(messages: List<JsonObject>) =
    messages.map { it["role"]!!.jsonPrimitive.content }

/** A realistic session: system, then user -> assistant(tool_calls) -> tool -> assistant. */
private fun session(turns: Int, pad: Int): MutableList<JsonObject> {
    val messages = mutableListOf(msg("system", "sys"))
    repeat(turns) { i ->
        messages.add(msg("user", "task $i"))
        messages.add(msg("assistant", "thinking $i", toolCalls = true))
        messages.add(msg("tool", "output $i " + "x".repeat(pad), toolId = "call_x"))
        messages.add(msg("assistant", "done $i"))
    }
    return messages
}

private fun size(messages: List<JsonObject>) = messages.sumOf { it.toString().length }

class TrimHistoryTest : FunSpec({

    test("a history inside the budget is left alone") {
        val messages = session(turns = 2, pad = 10)
        val before = messages.toList()
        trimHistory(messages)
        messages shouldContainExactly before
    }

    test("an oversized history is actually shrunk") {
        val messages = session(turns = 40, pad = MAX_HISTORY_CHARS / 20)
        val before = size(messages)
        before shouldBeGreaterThan MAX_HISTORY_CHARS

        trimHistory(messages)
        size(messages) shouldBeLessThanOrEqualTo before
    }

    test("the system prompt is never dropped") {
        val messages = session(turns = 40, pad = MAX_HISTORY_CHARS / 20)
        trimHistory(messages)
        messages.first()["role"]!!.jsonPrimitive.content shouldBe "system"
        messages.first()["content"]!!.jsonPrimitive.content shouldBe "sys"
    }

    test("the newest message always survives") {
        val messages = session(turns = 40, pad = MAX_HISTORY_CHARS / 20)
        val newest = messages.last()
        trimHistory(messages)
        messages.last() shouldBe newest
    }

    test("history never resumes on an orphaned tool reply") {
        // A role:"tool" message whose assistant tool_calls was trimmed away is
        // an orphan, and the API rejects the next request with a 400.
        val messages = session(turns = 40, pad = MAX_HISTORY_CHARS / 20)
        trimHistory(messages)
        messages[1]["role"]!!.jsonPrimitive.content shouldNotBe "tool"
    }

    test("a single oversized turn cannot empty the history") {
        val messages = mutableListOf(
            msg("system", "sys"),
            msg("user", "y".repeat(MAX_HISTORY_CHARS * 3))
        )
        trimHistory(messages)
        roles(messages) shouldContainExactly listOf("system", "user")
    }

    test("trimming twice changes nothing the second time") {
        val messages = session(turns = 40, pad = MAX_HISTORY_CHARS / 20)
        trimHistory(messages)
        val once = messages.toList()
        trimHistory(messages)
        messages shouldContainExactly once
    }

    test("the invariants hold across arbitrary sessions and budget pressures") {
        checkAll(200, Arb.int(1..30), Arb.int(1..16_000)) { turns, pad ->
            val messages = session(turns, pad)
            val newest = messages.last()
            trimHistory(messages)

            messages.first()["role"]!!.jsonPrimitive.content shouldBe "system"
            messages.last() shouldBe newest
            if (messages.size > 1) {
                messages[1]["role"]!!.jsonPrimitive.content shouldNotBe "tool"
            }
        }
    }
})
