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
// 1. STRUCTURES & BASH EXECUTION
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
            // Do not pass console input through to the command, otherwise an
            // interactive command steals the chat input or blocks forever.
            processBuilder.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
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

    private val systemPrompt = """
        You are an autonomous coding agent with full access to a local bash shell.
        Your working directory is: ${workspace.absolutePath}

        You are in an ongoing conversation with a user at a console. Keep the context
        of earlier turns in mind. When the current request is done, stop and answer
        the user instead of inventing extra work.

        You must respond ONLY with a raw, valid JSON object matching this schema.
        Do not wrap it in markdown formatting (like ```json). Escape strings properly.
        {
          "reasoning": "Your step-by-step thinking process",
          "bashCommand": "The exact shell command to run next" or null,
          "finalAnswer": "Your reply to the user once the request is handled" or null
        }
    """.trimIndent()

    init {
        reset()
    }

    /** Drops the conversation history and starts over with the system prompt. */
    fun reset() {
        messages.clear()
        messages.add("system" to systemPrompt)
    }

    fun runTask(taskDescription: String) {
        messages.add("user" to taskDescription)

        var iterations = 0
        var isRunning = true

        while (isRunning && iterations < MAX_ITERATIONS) {
            iterations++

            val rawResponse = try {
                callOpenAI(httpClient, messages, apiKey)
            } catch (e: Exception) {
                println("❌ API Error: ${e.message}")
                return
            }

            val step = try {
                parseJson(rawResponse)
            } catch (e: Exception) {
                println("⚠️ Invalid JSON from the model, asking for a correction: ${e.message}")
                messages.add("assistant" to rawResponse)
                messages.add("user" to "Your last message was not valid JSON. Respond again with a raw JSON object matching the schema.")
                continue
            }

            println("🤔 Reasoning: ${step.reasoning}")

            if (step.finalAnswer != null) {
                println("\n✅ ${step.finalAnswer}")
                messages.add("assistant" to rawResponse)
                isRunning = false
            } else if (step.bashCommand != null) {
                println("💻 Executing Bash: ${step.bashCommand}")
                val output = bash.execute(step.bashCommand)
                println("📥 Shell Output:\n$output\n")

                messages.add("assistant" to rawResponse)
                messages.add("user" to "Command output:\n$output")
            } else {
                println("⚠️ Warning: No command and no final answer provided.")
                messages.add("assistant" to rawResponse)
                isRunning = false
            }
        }

        if (isRunning) {
            println("\n⏹️ Stopped after $MAX_ITERATIONS iterations. Ask again to continue.")
        }
    }

    companion object {
        const val MAX_ITERATIONS = 15
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
// 4. MAIN ENTRY POINT (INTERACTIVE CONSOLE)
// ==========================================

fun printHelp() {
    println(
        """
        Commands:
          /help    Show this help
          /reset   Clear the conversation history
          /exit    Quit (or Ctrl+D)
        Anything else is sent to the agent as a task.
        """.trimIndent()
    )
}

fun main() {
    val apiKey = System.getenv("OPENAI_API_KEY") ?: "YOUR_API_KEY_HERE"
    if (apiKey.startsWith("YOUR_")) {
        println("❌ Please set the 'OPENAI_API_KEY' environment variable.")
        return
    }

    val workspace = File("./agent_workspace").apply { mkdirs() }.canonicalFile
    val harness = BashAgentHarness(workspace, apiKey)

    println("🤖 Bash Agent — Workspace: ${workspace.absolutePath}")
    printHelp()

    while (true) {
        print("\n👤 You: ")
        System.out.flush()

        val input = readlnOrNull()?.trim() ?: break // Ctrl+D
        if (input.isEmpty()) continue

        when (input.lowercase()) {
            "/exit", "/quit" -> break
            "/help" -> {
                printHelp()
                continue
            }
            "/reset" -> {
                harness.reset()
                println("🧹 History cleared.")
                continue
            }
        }

        println()
        harness.runTask(input)
    }

    println("\n👋 Bye!")
}
