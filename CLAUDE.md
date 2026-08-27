# CLAUDE.md

A single-file Kotlin CLI agent: an interactive console that drives a local bash
shell through OpenAI Responses API (`/v1/responses`) tool calls.

## Layout

- `agent.kt` — the whole harness (bash tool, background jobs, agent loop,
  HTTP/retry, `main`).
  It lives at the repo root on purpose so it stays runnable as a JBang script
  (`./agent.kt`); `build.gradle.kts` points the main source set at just this file.
- `src/test/kotlin/` — Kotest tests, including `MockOpenAi.kt`, an in-process
  stand-in for the API (JDK `HttpServer`, no extra deps).
- `agent_workspace/` — scratch dir the agent runs commands in. Gitignored.

## Commands

- `./gradlew test` — run the suite (`useJUnitPlatform()` is required for Kotest).
- `./gradlew run` — start the console. Needs `OPENAI_API_KEY`.
- `OPENAI_BASE_URL` redirects the API to a proxy or local server.
- `AGENT_LOG` names a JSON-lines logfile (default `agent-YYYYMMDD-HHMMSSZ.jsonl` per session in the cwd; set it empty to
  disable). Every API request/response, tool call, retry and job notice lands there, so it grows
  fast and is safe to delete. `jq -c .type agent-*.jsonl` shows the event flow.
- `CLAUDE.md` and `AGENTS.md` in the cwd, when present, are read once at startup and appended to the
  system prompt (each capped at `MAX_INSTRUCTIONS_CHARS`, head and tail kept). They go in item 0 on
  purpose: read once, they are stable turn to turn, so the prompt cache keeps covering them. The
  startup banner lists which were loaded; the `session` log event records them too.
- `AGENT_CMD` overrides how a sub-agent is launched (default: `./agent.kt`, the
  JBang script in the cwd). `AGENT_DEPTH` is set on every job the agent starts; past
  `MAX_AGENT_DEPTH` the prompt stops offering sub-agents and `main` refuses to run.
- `echo "prompt" | ./agent.kt` — one-shot mode: when stdin is not a terminal the
  whole of stdin is the prompt, the answer goes to stdout, progress to stderr,
  and the process exits (0 answered, 1 did not finish, 2 usage).

## Conventions

- Dependencies are declared twice — `//DEPS` in `agent.kt` for JBang and in
  `build.gradle.kts` for Gradle. Change both; `BuildConsistencyTest` enforces it.
- `MODEL`, `REASONING_EFFORT` and the `*_USD_PER_1M` price constants move together.
- The bash tool schema is deliberately strict-shaped (`strict: true`, every property in
  `required`, the optional ones nullable). Strict is the Responses default anyway; declaring it
  keeps the logged schema honest and gives the model `null` instead of filler like `"name":""`.
- The loop echoes the Responses `output` array back verbatim as the next
  request's `input`, reasoning items included — that is what keeps gpt-5's
  thinking alive across tool calls. It works because `store` is left at its
  default of true, so the bare reasoning ids stay resolvable server-side.
  Setting `store` to false silently breaks it unless you also request
  `include: ["reasoning.encrypted_content"]`.
- The console loop is event-driven: stdin is read on its own thread and pushed
  onto a queue that a finished job also writes to. That is what lets a job
  finishing start a turn with the user idle at the prompt.
- The harness speaks to the model in-band with `[background job …]` and
  `[background jobs still running]` messages appended at the *end* of the input.
  Never fold that into the system prompt: the prefix is what the prompt cache
  keys on, and item 0 changing every turn would forfeit the cached discount on
  the whole history.
- The console line is read by JLine (raw tty mode, so arrows edit and ↑/↓ recall
  from `~/.agent_history`), and only on demand: the reader thread waits on a
  semaphore the main loop releases once per prompt, because an active
  `readLine` paints the prompt and must not overlap a task's streaming output.
  JLine enables application-cursor mode, so arrows arrive as `\eOD` etc.
- One-shot mode swaps `System.out` for stderr instead of plumbing a logger
  through the harness; the real stdout is kept aside for the answer only.
- Sub-agents are not a separate mechanism: they are this program in one-shot
  mode, started through the ordinary `start` action, so their answer arrives
  via the same finished-job delivery. The depth guard lives in the environment
  (`AGENT_DEPTH`) because that is the only channel that crosses a `bash -c`.
- The log records each request body verbatim as JSON (`JsonlLog` in `agent.kt`), which is the way
  to check that the prompt-cache prefix really is stable turn to turn. Sub-agents inherit
  `AGENT_LOG` and append to the same file; `pid` and `depth` tell their lines apart.
- Comments explain *why* a choice was made. Keep that style. Keep them very short. Usually 1 or 2 lines.

## Agent Execution Policy (Default)

Goal: deliver the smallest correct change that fully resolves the request.

1) Workflow
- Inspect first (briefly): check relevant files and local context before editing.
- Then execute directly; do not ask for confirmation unless blocked or action is high-risk.
- Prefer minimal diffs unless the user asks for broader refactor.

2) Autonomy vs Questions
- Ask before: public API changes, irreversible/destructive actions, adding/removing major dependencies, or unclear product behavior.
- Otherwise make reasonable assumptions, state them briefly, and proceed.

3) Safety / Scope
- Stay within requested scope; avoid opportunistic rewrites.
- Do not fix unrelated issues unless they block completion (mention blockers explicitly).
- Never run destructive commands (e.g., `rm -rf`, history rewrites) unless explicitly requested.

4) Validation
- Validate at the smallest level that proves correctness:
  - single file change: targeted test/lint/typecheck if available;
  - cross-cutting change: broader test run.
- If validation is skipped, say why (e.g., missing tool, time, or not applicable).

5) Performance with Tools
- Batch related shell steps with `&&` or small scripts.
- Use background jobs for long-running tasks; wait only when results are needed.
- Prefer disjoint-file parallelism only when safe.

6) Final Response Contract
   Provide:
- What changed (concise)
- Why it changed
- Validation performed (and result)
- Assumptions / risks / follow-ups (if any)

7) Style
- Be concise, concrete, and implementation-first.
- Include only necessary explanation; prioritize actionable results.
