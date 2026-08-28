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

# Demo

```
> echo "briefly describe agent.kt" | ./agent.kt > test.txt
📄  Instructions: CLAUDE.md
🧠  **Inspecting file head**
💻  Bash: sed -n '1,220p' agent.kt && echo '---' && sed -n '220,520p' agent.kt && echo '---' && sed -n '520,980p' agent.kt
📥  Output:
   [235 lines hidden]
                   while (true) {
                       print("\r\u001B[2K${"⠹⠸⠴⠦⠇⠏"[frame++ % 6]} $text ${(System.currentTimeMillis() - startedAt) / 1000}s")
                       Thread.sleep(90)
                   }
   [Exit Code: 0 after 0s]

🧠  **Inspecting key code structure**
💻  Bash: grep -n '^// ----------\|^data class\|^class\|^object\|^fun main\|^fun ' agent.kt | sed -n '1,260p'
📥  Output:
   [33 lines hidden]
   960:fun JsonObject.str(key: String) = this[key]?.jsonPrimitive?.contentOrNull
   961:fun JsonObject?.long(key: String) = this?.get(key)?.jsonPrimitive?.longOrNull ?: 0L
   962:fun JsonObject?.obj(key: String) = this?.get(key) as? JsonObject
   965:object Spinner {
   [Exit Code: 0 after 0s]

💻  Bash: sed -n '177,360p' agent.kt && echo '---' && sed -n '360,560p' agent.kt && echo '---' && sed -n '560,860p' agent.kt
📥  Output:
   [283 lines hidden]
       @Synchronized
       fun event(type: String, build: JsonObjectBuilder.() -> Unit) {
           val line = buildJsonObject {
               put("ts", java.time.Instant.now().toString())
   [Exit Code: 0 after 0s]

🧠  **Investigating runBashCall output truncation**
💻  Bash: sed -n '360,760p' agent.kt
📥  Output:
   [281 lines hidden]
               if (left <= 0) return false
               if (process.waitFor(minOf(200L, left), TimeUnit.MILLISECONDS)) {
                   watcher.join(3000)
                   return true
   [Exit Code: 0 after 0s]

🧠  **Summarizing agent.kt architecture**

✅  `agent.kt` is a **single-file Kotlin CLI coding-agent harness**. Briefly:

- It builds a system prompt and exposes one strict tool: **`bash`** (`run/start/output/wait/stop`).
- It runs an agent loop against OpenAI **`/v1/responses`**, echoing model output items back each turn (including reasoning/tool items) so multi-step tool use works.
- It executes shell commands in the local workspace, with:
  - foreground timeout/truncation
  - named background jobs
  - automatic “job finished/still running” notices fed back to the model.
- It supports both:
  - **interactive console mode** (JLine, `/help`, `/reset`, Ctrl+C handling), and
  - **one-shot stdin mode** (`echo "..." | ./agent.kt`).
- It includes history trimming (the dropped span is replaced by a model-written summary), token/cost tracking, and session lifecycle handling.
- It has built-in HTTP retry logic (429/5xx), and optional JSONL logging (`AGENT_LOG`) of requests, responses, tool calls, retries, and job events.
📊  35,526 in (22,016 cached, 61% hit) / 707 out (207 reasoning) · $0.0374 · session $0.0374
Session cost: $0.0374
```

