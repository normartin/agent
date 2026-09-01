# agent

An experimental small (1k lines), [single-file](./agent.kt) coding agent written in Kotlin.

It has only one tool **bash**. No guardrails! Run it in a [sandbox](#sandbox). OpenAI API only.

## Features

- Bash tool with foreground and background jobs.
- Interactive and batch mode.
- Sub-agents: calls itself recursively.
- Supports `CLAUDE.md` / `AGENTS.md`.
- History trimming.
- Cost estimation.

## Run

Requires [JBang](https://www.jbang.dev/) (SDKMan: ```sdk install jbang```)

```sh
export OPENAI_API_KEY=...
./agent.kt                          # interactive

echo "fix the tests" | ./agent.kt   # one-shot: answer on stdout
```

## Sandbox

`./sandbox.sh` starts a Docker container (nixos/nix) with JBang, JDK, and common tools,
mounting the current directory at `/project` and dropping you into a fish shell.
Inside, set the key (`set -x OPENAI_API_KEY ...`) and run `./agent.kt` as usual.
Nix and JBang caches persist in named volumes across runs.

Please note: Docker is not a secure sandbox. Prefer using real VM for serious work. 

## Bash tool

| Action   | Parameters                   | Does                                                    |
|----------|------------------------------|---------------------------------------------------------|
| `run`    | `command`, `stdin`?          | Execute a command in the foreground (killed after 120s) |
| `start`  | `command`, `stdin`?, `name`? | Run a command as a named background job                 |
| `list`   | —                            | Show known jobs                                         |
| `output` | `name`                       | What a job has printed so far                           |
| `wait`   | `name`, `seconds`?           | Block until a job finishes (default 60s, max 600s)      |
| `stop`   | `name`                       | Kill a job                                              |

| Parameter | Meaning                                                                                    |
|-----------|--------------------------------------------------------------------------------------------|
| `command` | Shell command to execute                                                                   |
| `stdin`   | Text fed to the command's standard input: multi-line scripts (`bash -s`) or exact file content (`cat > path`) |
| `name`    | Job identifier; picked automatically if omitted on `start`                                 |
| `seconds` | How long `wait` may block                                                                  |

```json
{"action":"run","command":"cat > hello.py","stdin":"print('hi')","name":null,"seconds":null}
{"action":"start","command":"./gradlew build","name":"build","stdin":null,"seconds":null}
{"action":"wait","name":"build","seconds":120,"command":null,"stdin":null}
```

- Output over 12k chars is truncated in the middle; a marker names a file holding the rest.
- A finished background job delivers its output to the model, even while the user
  is idle at the prompt — sub-agents report back the same way.

# Demo

```
echo "briefly explain agent.kt" | ./agent.kt
[jbang] Building jar for agent.kt...
📄  Instructions: CLAUDE.md
🧠  **Preparing to inspect file**
💻  Bash: sed -n '1,260p' agent.kt
📥  [Exit Code: 0 after 0s] (260 lines)

🧠  **Inspecting code structure**
💻  Bash: grep -n "^// ----------\|^class \|^fun main\|^data class\|^object\|^enum class\|^val TOOLS\|^fun systemPrompt\|^class BashAgentHarness\|^class JobRegistry\|^class Spinner" agent.kt | sed -n '1,220p'
📥  [Exit Code: 0 after 0s] (16 lines)

📊  23,953 in (14,080 cached, 58% hit) / 622 out (123 reasoning) · $0.0284 · session $0.0284 · ctx 9,643 (2%)
`agent.kt` is the **entire CLI agent implementation** in one Kotlin file.

At a high level, it does 5 things:

1. **Defines the model setup and tools**  
   - Chooses model/pricing constants  
   - Builds a stable system prompt (plus `CLAUDE.md`/`AGENTS.md` instructions)  
   - Declares tool schemas (`bash` + `web_search`) for Responses API

2. **Runs the agent loop (`BashAgentHarness`)**  
   - Sends conversation + tool results to `/v1/responses`  
   - Executes requested tools  
   - Appends outputs back into conversation until assistant gives final answer  
   - Tracks token usage/cost and trims/summarizes history when it gets too large

3. **Provides entrypoints**  
   - `main()` supports:
     - **Interactive TTY mode** (prompt loop, Ctrl+C handling, spinner)
     - **One-shot mode** (`echo "task" | ./agent.kt`)

4. **Implements shell job management**  
   - Foreground command execution with timeout/truncation  
   - Background jobs (`start/list/output/wait/stop`) via `JobRegistry` and `BackgroundJob`  
   - Job-finished notifications that can trigger a new model turn

5. **Includes infra utilities**  
   - HTTP + retry logic for API calls  
   - JSONL event logging (`JsonlLog`)  
   - terminal spinner/render helpers and misc utility functions

So: **prompting + tool-calling + command execution + job orchestration + console UX** all live in this one file.
```

