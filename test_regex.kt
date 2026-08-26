fun main() {
    val responseBody = """
    {
      "id": "chatcmpl-123",
      "object": "chat.completion",
      "created": 1677652288,
      "model": "gpt-4o-mini",
      "choices": [{
        "index": 0,
        "message": {
          "role": "assistant",
          "content": "{\"reasoning\": \"I will create the file.\", \"bashCommand\": \"touch info.md\", \"finalAnswer\": null}"
        },
        "finish_reason": "stop"
      }],
      "usage": {
        "prompt_tokens": 9,
        "completion_tokens": 12,
        "total_tokens": 21
      }
    }
    """.trimIndent()

    val contentRegex = """"content":\s*"(.*?)"\s*(?=\s*\}\s*,\s*"logprobs")""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val match = contentRegex.find(responseBody)
    
    println("Match found: ${match != null}")
    if (match != null) {
        println("Content: ${match.groups[1]?.value}")
    } else {
        println("Full body: $responseBody")
    }

    // Testing the parseJson logic
    val rawJson = """{"reasoning": "I will create the file.", "bashCommand": "touch info.md", "finalAnswer": null}"""
    fun extract(key: String): String? {
        val regex = """"$key"\s*:\s*"(.*?)"""".toRegex(RegexOption.DOT_MATCHES_ALL)
        return regex.find(rawJson)?.groups?.get(1)?.value
    }
    
    println("Reasoning: ${extract("reasoning")}")
    println("BashCommand: ${extract("bashCommand")}")
    println("FinalAnswer: ${extract("finalAnswer")}")
}
