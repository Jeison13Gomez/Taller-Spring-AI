# Implementation Plan: Conversation Memory

**Branch**: `006-conversation-memory` | **Date**: 2026-10-27 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/006-conversation-memory/spec.md`

## Summary

`POST /api/v1/chat` starts to remember the earlier turns of a session. When a request carries a `sessionId`, the backend loads that session's history, sends it to the model after the system instructions of the chosen template, and stores the new question and its answer. A request without `sessionId` behaves exactly as today.

The design follows the technical decisions given for the plan, checked against Spring AI 2.0.0 with `javap` and a temporary probe ([research.md](research.md)):

- **Window**: `MessageWindowChatMemory` with `maxMessages` from `app.memory.max-messages` (20), applied through `MessageChatMemoryAdvisor`. The conversation id is the `sessionId`, passed per request under `ChatMemory.CONVERSATION_ID`.
- **Store**: a bounded `ChatMemoryRepository` of our own (idle expiry 30 min, 100 sessions, least recently used out first), because `InMemoryChatMemoryRepository` has neither limit.
- **Two defects of the stock advisor are covered by our own code**, both reproduced by the probe: a model failure leaves the question in the history (breaks FR-010), and two concurrent turns of one session lose messages (103 of 400 kept, breaks FR-013). Each turn therefore runs under a per-session lock and restores the history if it fails.
- **Templates**: the system message of the chosen template is set on every request, goes first, and is never stored (verified).
- **`/classify`** is not touched: it keeps its own `ChatClient` and the advisor is attached per request only in the chat path.
- **Response**: `historyMessages` is added to the 200 chat response; no field is removed or renamed.
- **Context size**: an oversize prompt is not cut to the context size: Ollama reduces a single oversize prompt to about half of `num-ctx` plus 2 tokens and answers 200 without a signal (measured with 2048, 4096 and 8192, research R7); in a growing conversation it drops the oldest messages one at a time and `promptTokens` levels off at a plateau (Experiment E); Ollama's default context here appears to be 4096 (inference: the unset default gave the same 2050 as an explicit 4096). 20 messages can overflow that, so `num-ctx` becomes configurable and is set to 8192 by default. Whether Ollama keeps the system message when it truncates is not assumed: T047 measures it.
- **Experiment E**: a PowerShell script runs 20 turns with memory and 20 without and writes a CSV; it is run three times, with `num-ctx` 2048, 4096 and 8192, to separate forgetting by context size from forgetting by the message window (the exchanges are long enough that the three contexts are exceeded at different turns, or never). The chart is made by Python (`experimentos/graficas.py`, as in point 2), not by PowerShell, and overlays the three runs.

## Technical Context

**Language/Version**: Java 21 (backend); Windows PowerShell 5.1 for the experiment. The frontend is not touched.

**Primary Dependencies**: Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-starter-model-ollama`, which already brings `spring-ai-client-chat` with `MessageWindowChatMemory`, `MessageChatMemoryAdvisor`, `ChatMemoryRepository`). No new dependency.

**Storage**: In memory only (a map inside the repository class). Nothing is written to disk; a restart clears every session.

**Testing**: JUnit 5 + `spring-boot-starter-test`, standalone MockMvc, and the scripted `ChatModel` of feature 005 (`ScriptedChatModel`); no test calls Ollama. Expiry is tested with a controllable `Clock`. Gradle commands run from `backend/`. Shell scripts get new assertions.

**Target Platform**: Local Docker Compose demo (backend, Ollama, frontend).

**Project Type**: web-application (Spring Boot backend + static HTML frontend).

**Performance Goals**: A request rejected for an invalid `sessionId` answers in under 1 s without calling the model (SC-009). Memory adds no model call; its cost is the extra prompt tokens.

**Constraints**: Chat response and usage metrics of 003 intact; generation settings of 004 per request; templates of 005 applied every turn; history, questions and answers never logged by new code; the frontend and the existing question log line (feature 007) are not changed.

**Scale/Scope**: At most 100 sessions of at most 20 messages each (about 2000 messages in memory); one new response field; one new package `memory` with 4 small classes; 3 new properties plus `num-ctx`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|-----------|------------|
| Demo-Focused Simplicity | PASS. No new service, database or dependency; one small package. The custom repository is about 60 lines because the stock one cannot expire or cap sessions (research R3); the alternative, a caching library, adds a dependency for the same two rules. |
| Reproducible Local Execution | PASS. Same `docker compose` flow; `num-ctx` is an environment-overridable property, passed through the compose file, and [quickstart.md](quickstart.md) states how to change it. |
| Clear Interfaces & Contracts | PASS. Additive change to the versioned `/api/v1` contract (`sessionId` rules, `historyMessages`), with examples for 200 and 400 in [contracts/chat-api.md](contracts/chat-api.md); acceptance checks added to both scripts. |
| Data Minimization & Secrets | **PASS WITH NOTE, plus one pre-existing NON-COMPLIANCE (unchanged)**. Conversation text is now kept in RAM (it is user data): it is bounded in time (30 min idle) and count (100 sessions), never written to disk, never logged by new code, and no endpoint returns it (FR-011, FR-012, FR-015). Pre-existing: `ChatController` still logs the full chat question at INFO and `LogRedactionFilter` does not redact; the fix stays in feature 007. |
| Testing & Observability | **NON-COMPLIANT (pre-existing, partially mitigated)**. The full core flow (frontend → backend → model → frontend) still has no automated coverage; deferred to feature 007 as in the 003, 004 and 005 plans. This feature adds tests for its own behavior (store, lock and rollback, window, isolation, validation, response field, scripts) and the `historyMessages` field makes the memory observable. |

Two principles are non-compliant because of pre-existing code; see Complexity Tracking.

**Post-design re-check**: unchanged. The design adds no component outside the backend and no log content beyond counts; the only new sensitive data is the in-RAM history, bounded as above.

## Project Structure

### Documentation (this feature)

```text
specs/006-conversation-memory/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── chat-api.md
├── checklists/requirements.md
├── evidence/linea_base_memoria.txt
└── tasks.md             # created later by /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/
├── resources/application.properties        # + app.memory.* and spring.ai.ollama.chat.num-ctx
└── java/com/example/chat/
    ├── ChatController.java                 # + sessionId in FIELD_ORDER; historyMessages copied to the response
    ├── ModelResult.java                    # + historyMessages (2-argument constructor kept: 0)
    ├── dto/ChatRequest.java                # + @Pattern on sessionId
    ├── dto/ChatResponse.java               # + historyMessages (omitted when null)
    ├── memory/
    │   ├── MemorySettings.java             # @ConfigurationProperties("app.memory"): maxMessages, idleTimeout, maxSessions; validates at startup
    │   ├── BoundedChatMemoryRepository.java # ChatMemoryRepository: access-ordered map, idle expiry, LRU cap, Clock
    │   ├── SessionLocks.java               # one lock per sessionId, reference counted, removed when idle
    │   └── ConversationMemory.java         # bean: MessageWindowChatMemory + advisor factory + runTurn(sessionId, action) with snapshot/rollback; secondary constructor receives the SessionLocks so tests can observe them
    └── impl/SpringAiModelService.java      # chooses memory path when sessionId is not null; classification untouched

backend/src/test/java/com/example/chat/
├── memory/BoundedChatMemoryRepositoryTest.java   # expiry, LRU cap, isolation, copies, clock
├── memory/MemorySettingsTest.java                # invalid values stop startup naming the property
├── memory/ConversationMemoryTest.java            # lock, rollback, concurrent turns of one session
├── impl/SpringAiModelServiceMemoryTest.java      # scripted model: history sent, window, system, templates, isolation, historyMessages
├── ChatControllerSessionTest.java                # sessionId rules, 400 shape, field order, historyMessages in JSON
├── support/ScriptedChatModel.java                # prompts() made thread-safe
└── impl/SpringAiClassificationServiceTest.java   # + classification prompt never carries history

docker/docker-compose.yml                       # + SPRING_AI_OLLAMA_CHAT_NUM_CTX passthrough (default 8192)
scripts/smoke_test.sh                           # + two-turn check on historyMessages, invalid sessionId → 400
tests/integration/test_chat_flow.sh             # unique sessionId per case + memory block
backend/README.md                               # memory, sessionId rules, historyMessages, num-ctx
experimentos/experimento_e.ps1                  # 20 turns with and without memory; -NumCtx; one CSV per context size
experimentos/experimento_truncado.ps1           # T047: does the system message survive Ollama's truncation (num-ctx 8192, 4096 and 2048)
experimentos/graficas.py                        # + graph 3: promptTokens against turn, the three runs overlaid (matplotlib, like graphs 1 and 2)
experimentos/resultados/experimento_e_ctx2048.csv, experimento_e_ctx4096.csv, experimento_e_ctx8192.csv, experimento_e_resumen.csv, experimento_truncado_ctx*.csv
experimentos/graficas/grafica_3_prompttokens_vs_turno.png
```

**Structure Decision**: Keep the existing backend layout. New code is one package, `memory`, wired into `SpringAiModelService`; the controller only validates, forwards and copies one number. No class is added to the classification path.

### Implementation notes

- **Request flow (chat with `sessionId`)**: `ChatController` validates (400 before any log line or model call) → `SpringAiModelService.generateResponse` → `ConversationMemory.runTurn(sessionId, action)`:
  1. take the lock of that `sessionId`;
  2. `snapshot = repository.findByConversationId(id)`, `historyMessages = snapshot.size()`;
  3. run the action, which builds the usual request (`.system(template text)`, `.user(question)`, options, `NoOpTemplateRenderer`) and adds `.advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, id))`;
  4. if the action throws, or the answer is blank, restore: `saveAll(id, snapshot)` (or `deleteByConversationId` when the snapshot is empty) and rethrow / return as before;
  5. release the lock.
- **Request flow without `sessionId`** (null): the existing code path, no advisor, no lock, `historyMessages` 0. This is needed anyway because the advisor throws `IllegalArgumentException: conversationId cannot be null` without an id (verified; `DEFAULT_CONVERSATION_ID` does not exist in 2.0.0). Chat-path requests with an empty string never reach the service: they are a 400.
- **Advisor order**: explicit. The memory advisor is built with `order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER)` and is the only advisor in the chat chain. Its rule, verified by the probe: the system message of the request stays first, the stored history follows, the current question is last; it stores only the last user message and the answer, never the system message. So the template of each turn applies and nothing is duplicated in the history.
- **Where the advisor is attached**: per request, not as a default of the builder. The builder bean's scope was not confirmed, and a default advisor could leak into `SpringAiClassificationService`. A test proves the classification prompt has only system and user messages.
- **Validation**: `@Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore")` on `ChatRequest.sessionId`. A `null` passes (Bean Validation ignores null for `@Pattern`); `""`, spaces, accents or 65 characters fail. Matching is case sensitive by construction (the id is used as is). The field joins the existing per-field error format and sorts first in `FIELD_ORDER`, as in the request body.
- **`historyMessages`**: counted from the snapshot taken under the lock, so it is exactly the number of messages the advisor will prepend. It is `0` for a session's first turn and for requests without `sessionId`; it is omitted (`null`) in 400 and 502 responses like `usage`. Tests also compare it with the real number of messages the scripted model received.
- **Odd window sizes**: the stock window drops further until the history starts with a question (verified: size 3 kept 2 messages), which satisfies FR-005. `MemorySettings` accepts only integers of at least 2 (a question and its answer; with 1 the same rule would leave an empty history) and the documentation states that an odd value of 3 or more behaves like the even value below it.
- **Logging**: the controller logs are not changed. New `debug` lines in `ConversationMemory` carry the abbreviated session id, `historyMessages` and the words "restored after failed turn" or "session expired"; never a message text.
- **num-ctx**: `spring.ai.ollama.chat.num-ctx=8192` in `application.properties` (value accepted by the project owner). The property name differs from the one given in the plan request: `spring.ai.ollama.chat.options.num-ctx` exists but is marked deprecated with `spring.ai.ollama.chat.num-ctx` as its replacement (research R7), and the project already uses the non-`options` form for `temperature` and `num-predict`. It can be overridden with the environment variable `SPRING_AI_OLLAMA_CHAT_NUM_CTX` (the compose file passes it through, default 8192). Ollama's own default appears to be 4096 (inference, research R7).
- **Experiment E**: 20 turns of one conversation with a fixed list of 20 Spanish instructions of about 70 words that ask for a long answer, with `numPredict` 400, so that almost every answer reaches the cap and every exchange has a similar size of about 500 tokens (an estimate checked in T035). With 10 exchanges in the window the prompt reaches about 5000 tokens: it should exceed 2048 around turn 5, 4096 around turn 9 or 10 and never 8192, which lets the three runs show different events. One run with a `sessionId` and one without. The script takes `-NumCtx` (mandatory), writes it in every row (`numCtx`) and in the summary, and warns when `promptTokens` of a turn varies less than 1% from the previous turn while `historyMessages` keeps rising (a plateau: Ollama is dropping the oldest messages). It writes one CSV per context size (`experimento_e_ctx<N>.csv`, 40 rows each) and rebuilds `experimento_e_resumen.csv` from all of them. The script cannot change the backend: the operator restarts it between runs with `SPRING_AI_OLLAMA_CHAT_NUM_CTX=2048`, then `4096`, then `8192` (steps in the quickstart; about 25 to 27 minutes per context, measured in the quick test). The chart is a new graph 3 in `experimentos/graficas.py`: `promptTokens` against turn, one line per run with memory and a dashed line per run without memory, plus a horizontal mark at each `num-ctx`; it reads whichever `experimento_e_ctx*.csv` exist, so it works with one to three runs.
- **Compatibility of existing scripts**: `scripts/smoke_test.sh` uses `sessionId` `demo`, valid. `tests/integration/test_chat_flow.sh` sends all its cases with `sessionId` `it`; with memory they would share one conversation and contaminate each other's answers, so each case gets its own id (`it-<pid>-<n>`). The frontend's id (`crypto.randomUUID()` or `Date.now()` digits) is valid, so the page gets memory without a change.

## Complexity Tracking

> Both entries are violations in code that exists before this feature. Same justification as in the [003](../003-token-usage-metrics/plan.md), [004](../004-chat-sampling-params/plan.md) and [005](../005-prompt-templates-structured-output/plan.md) plans.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Data Minimization: full chat question logged in `ChatController`; `LogRedactionFilter` does not redact | Pre-existing; this feature adds no logging of texts. Fixed in feature 007. | Fixing it here would mix an unrelated privacy change into this feature. |
| Testing & Observability: no automated test of the full frontend → backend → model → frontend flow | Pre-existing; this feature tests its own behavior and adds script checks. Deferred to feature 007. | A browser-level test is a separate piece of work. |
