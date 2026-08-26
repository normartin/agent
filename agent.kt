///usr/bin/env jbang "$0" "$@" ; exit $?
//KOTLIN 2.4.10
//DEPS org.jetbrains.kotlin:kotlin-stdlib:2.4.10
//DEPS org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
            val processBuilder = ProcessBuilder("bash", "-c", command)

            processBuilder.directory(workspace)
            val process = processBuilder.start()

            val output = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val error = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()

            buildString {
                if (output.isNotBlank()) append(output)
                if (error.isNotBlank()) append("ERROR OUTPUT:\n").append(error)
                append("\n[Exit Code: $exitCode]")
            }
        } catch (e: Exception) {
            "Execution Error: ${e.message}"
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
            Your working directory is: ${workspace.absolutePath}
            
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
    val payload = JsonObject(
        mapOf(
            "model" to JsonPrimitive("gpt-4o-mini"),
            "messages" to JsonArray(
                messages.map { (role, content) ->
                    JsonObject(
                        mapOf(
                            "role" to JsonPrimitive(role),
                            "content" to JsonPrimitive(content)
                        )
                    )
                }
            ),
            "temperature" to JsonPrimitive(0.1),
            "response_format" to JsonObject(mapOf("type" to JsonPrimitive("json_object")))
        )
    ).toString()

    val request = HttpRequest.newBuilder()
        .uri(URI.create("https://api.openai.com/v1/chat/completions"))
        .timeout(Duration.ofSeconds(30))
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(payload))
        .build()

    val response = client.send(request, HttpResponse.BodyHandlers.ofString())

    if (response.statusCode() != 200) {
        throw Exception("API Error [Status ${response.statusCode()}]: ${response.body()}")
    }

    val body = Json.parseToJsonElement(response.body()).jsonObject
    return body["choices"]
        ?.jsonArray
        ?.firstOrNull()
        ?.jsonObject
        ?.get("message")
        ?.jsonObject
        ?.get("content")
        ?.jsonPrimitive
        ?.contentOrNull
        ?: throw Exception("API response did not contain choices[0].message.content: ${response.body()}")
}

fun parseJson(rawJson: String): AgentResponse {
    val json = Json.parseToJsonElement(rawJson).jsonObject
    fun extract(key: String): String? = json[key]?.jsonPrimitive?.contentOrNull

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
