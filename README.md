# agent

An experimental small (1k lines), [single-file](./agent.kt) coding agent written in Kotlin.

It has only one tool **bash**. No guardrails! Run it in a sandbox. OpenAI API only.

## Features

- Bash tool with foreground and background jobs.
- Interactive and batch mode.
- Sub-agents: calls itself recursively.
- Supports `CLAUDE.md` / `AGENTS.md`.
- History trimming.
- Cost estimation.

## Run

Requires [JBang](https://www.jbang.dev/)

```sh
export OPENAI_API_KEY=...
./agent.kt                          # interactive

echo "fix the tests" | ./agent.kt   # one-shot: answer on stdout
```

## How it works

### The agent loop


```mermaid
sequenceDiagram
    participant U as User
    participant H as Harness (runLoop)
    participant A as OpenAI /v1/responses
    participant B as bash (JobRegistry)

    U->>H: prompt
    H->>H: pumpJobs: append finished-job notices
    loop until no function_call (max MAX_ITERATIONS)
        H->>H: trimHistory (+ summarize) if over budget
        H->>A: POST input (system + history + tools)
        A-->>H: output items: reasoning, message, function_call*
        H->>H: append output to input
        alt function_call present
            H->>B: run / start / output / wait / stop
            B-->>H: result text
            H->>H: append function_call_output
        else answer only
            H-->>U: ✅ answer + 📊 cost
        end
    end
```

# Demo

```
> echo "briefly explain agent.kt" | ./agent.kt
📄  Instructions: CLAUDE.md
🧠  **Planning file inspection**
💻  Bash: sed -n '1,220p' agent.kt && echo '---' && sed -n '220,520p' agent.kt && echo '---' && sed -n '520,920p' agent.kt
📥  Output:
   [242 lines hidden]
               put("elapsed_ms", (System.nanoTime() - started) / 1_000_000)
               put("body", runCatching { Json.parseToJsonElement(response.body()) }.getOrElse { JsonPrimitive(response.body()) })
           }
           if (status == 200) return response.toTurn()
   [Exit Code: 0 after 0s]

🧠  **Inspecting code structure**
💻  Bash: grep -nE '^(fun|class|data class|object|enum class) ' agent.kt | sed -n '1,220p'
📥  Output:
   [32 lines hidden]
   964:fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
   965:fun JsonObject?.long(key: String) = this?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
   966:fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject
   969:object Spinner {
   [Exit Code: 0 after 0s]

🧠  **Planning architecture inspection**
💻  Bash: sed -n '510,760p' agent.kt
📥  Output:
   [247 lines hidden]
   
       /** Waits until the job is over (log included), [seconds] pass, or [cancelled]. */
       fun await(seconds: Long, cancelled: () -> Boolean = { false }): Boolean {
           val deadline = System.currentTimeMillis() + seconds * 1000
   [Exit Code: 0 after 0s]

🧠  **Inspecting core harness class**
💻  Bash: sed -n '202,510p' agent.kt
📥  Output:
   [265 lines hidden]
       """.trimIndent()
   )
   
   fun main() {
   [Exit Code: 0 after 0s]

🧠  **Examining truncated code section**
💻  Bash: sed -n '320,430p' agent.kt
📥  Output:
   [107 lines hidden]
           })
       }
   
       /** One tool-less call over the dropped span as plain text (so the model summarizes rather than continues it). Null on failure. */
   [Exit Code: 0 after 0s]

🧠  **Summarizing agent.kt structure**
📊  49,930 in (34,816 cached, 69% hit) / 867 out (228 reasoning) · $0.0447 · session $0.0447
`agent.kt` is the **entire CLI agent in one file**.  
At a high level, it:

1. **Builds the system prompt + tool schema**
   - Tells the model how to use one tool: `bash` (`run/start/output/wait/stop`).
   - Loads `CLAUDE.md` / `AGENTS.md` once at startup and appends them to prompt instructions.

2. **Runs the model loop (`BashAgentHarness`)**
   - Stores conversation history as Responses API items.
   - Calls `/v1/responses`, reads assistant output, executes tool calls, then sends tool results back.
   - Repeats until the assistant returns a final text answer.

3. **Executes shell commands safely**
   - Foreground commands have a timeout.
   - Background jobs are tracked by name, can be waited/stopped, and auto-reported when finished.
   - Output is truncated for console display but preserved for model/logging.

4. **Manages context size**
   - If history gets too large, it trims old turns and optionally inserts a model-written summary.

5. **Handles UX + runtime modes**
   - **Interactive console mode** (JLine): `/help`, `/reset`, `/exit`, Ctrl+C behavior, job-finished events.
   - **One-shot mode** (`echo "..." | ./agent.kt`): reads stdin prompt once, prints only final answer to stdout.

6. **Includes infra pieces**
   - Retry/backoff for rate limits and transient API failures.
   - JSONL logging of requests/responses/tool calls.
   - Token/cost tracking per turn and per session.

So: it’s a compact, single-file harness that turns model tool calls into real local shell execution with job control and conversation continuity.
```

