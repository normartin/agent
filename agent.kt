///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.0.21

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

// ==========================================
// 1. STRUKTUREN & BASH EXECUTION
// ==========================================

data class AgentResponse(
    val reasoning: String,
    val bashCommand: String?,
    val finalAnswer: String?
)

class BashTool(private val workspace: File) {
    fun execute(command: String): String {
        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val processBuilder = if (isWindows) {
                ProcessBuilder("cmd.exe", "/c", command)
            } else {
                ProcessBuilder("bash", "-c", command)
            }

            processBuilder.directory(workspace)
            val process = processBuilder.start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()

            buildString {
                if (output.isNotBlank()) append(output)
                if (error.isNotBlank()) append("ERROR OUTPUT:\n").append(error)
                append("\n[Exit Code: \$exitCode]")
            }
        } catch (e: Exception) {
            "Execution Error: \${e.message}"
        }
    }
}

// ==========================================
// 2. CORE AGENT HARNESS LOOP
// ==========================================

class BashAgentHarness(private val workspace: File, private val apiKey: String) {
    private val bash = BashTool(workspace)
    private val messages = mutableListOf<Pair<String, String>>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    init {
        val systemPrompt = """
            You are an autonomous coding agent with full access to a local bash shell.
            Your working directory is: \${workspace.absolutePath}
            
            You must respond ONLY with a raw, valid JSON object matching this schema. 
            Do not wrap it in markdown formatting (like ```json). Escape strings properly.
            {
              "reasoning": "Your step-by-step thinking process",
              "bashCommand": "The exact shell command to run next" or null,
              "finalAnswer": "Summary statement if the task is completely done" or null
            }
        """.trimIndent()
        messages.add("system" to systemPrompt)
    }

    fun runTask(taskDescription: String) {
        messages.add("user" to taskDescription)
        println("🚀 Task Started: $taskDescription\n")

        var iterations = 0
        var isRunning = true

        while (isRunning && iterations < 7) {
            iterations++
            println("--- Iteration $iterations ---")

            val rawResponse = callOpenAI(httpClient, messages, apiKey)
            val step = parseJson(rawResponse)

            println("🤔 Reasoning: ${step.reasoning}")

            if (step.finalAnswer != null) {
                println("\n✅ Task Complete: ${step.finalAnswer}")
                isRunning = false
            } else if (step.bashCommand != null) {
                println("💻 Executing Bash: ${step.bashCommand}")
                val output = bash.execute(step.bashCommand)
                println("📥 Shell Output:\n$output\n")

                messages.add("assistant" to rawResponse)
                messages.add("user" to "Command output:\n$output")
            } else {
                println("⚠️ Warning: No command and no final answer provided.")
                isRunning = false
            }
        }
    }
}

// ==========================================
// 3. MODERN HTTP & PRIMITIVE JSON UTILS
// ==========================================

fun callOpenAI(client: HttpClient, messages: List<Pair<String, String>>, apiKey: String): String {
    // Manuelles Escaping und JSON-Payload Generierung
    val messagesJson = messages.joinToString(",") { (role, content) ->
        val escapedContent = content
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        """{"role": "$role", "content": "$escapedContent"}"""
    }

    val payload = """{
        "model": "gpt-4o-mini",
        "messages": [$messagesJson],
        "temperature": 0.1,
        "response_format": { "type": "json_object" }
    }"""

    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://openai.com"))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
        throw Exception("API Error [Status ${response.statusCode()}]: ${response.body()}")
    }

    // Extrahiert den "content"-String sauber aus dem flachen OpenAI Chat-JSON Response
    val contentRegex = """"content":\s*"(.*?)"\s*(?=\s*\}\s*,\s*"logprobs")""".toRegex(RegexOption.DOT_MATCHES_ALL)
    val match = contentRegex.find(response.body()) ?: return response.body()

    return match.groups[1]!!.value
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
        .replace("\\\\", "\\")
}

fun parseJson(rawJson: String): AgentResponse {
    fun extract(key: String): String? {
        val regex = """"$key"\s*:\s*"(.*?)"""".toRegex(RegexOption.DOT_MATCHES_ALL)
        return regex.find(rawJson)?.groups?.get(1)?.value
    }
    return AgentResponse(
        reasoning = extract("reasoning") ?: "Thinking...",
        bashCommand = extract("bashCommand"),
        finalAnswer = extract("finalAnswer")
    )
}

// ==========================================
// 4. MAIN ENTRY POINT
// ==========================================

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: "YOUR_API_KEY_HERE"
    if (apiKey.startsWith("YOUR_")) {
        println("❌ Bitte setze die Umgebungsvariable 'OPENAI_API_KEY'.")
        return
    }

    val workspace = File("./agent_workspace").apply { mkdirs() }
    val harness = BashAgentHarness(workspace, apiKey)

    harness.runTask("Erstelle eine Datei 'info.md' mit heutigem Datum und lies sie danach aus.")
}
