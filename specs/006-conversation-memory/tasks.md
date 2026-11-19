---

description: "Task list for Conversation Memory (feature 006)"
---

# Tasks: Conversation Memory

**Input**: Design documents from `/specs/006-conversation-memory/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/chat-api.md](contracts/chat-api.md), [quickstart.md](quickstart.md)

**Tests**: Included, because plan.md commits to unit and controller tests for the new behavior (Constitution: Testing & Observability). Tests for the rest of the flow are out of scope (feature 007).

**Organization**: Tasks are grouped by user story so each can be implemented and tested independently. Phases follow the priority order of the spec: US1 (P1), US4 (P1), US2 (P2), US3 (P2), US5 (P3).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US5 from spec.md
- Paths are relative to the repository root. Abbreviations: `chat/` = `backend/src/main/java/com/example/chat/`, `test/` = `backend/src/test/java/com/example/chat/`, `res/` = `backend/src/main/resources/`.

## Conventions for this feature

- Gradle is on the PATH and `JAVA_HOME` points to JDK 21. Run `gradle test` / `gradle build` from `backend/`. Rebuild the stack with `docker compose -f docker/docker-compose.yml up -d --build`.
- The IDE shows false "package does not exist" errors because it lacks the Gradle classpath; trust `gradle build`.
- Never import both `org.springframework.ai.chat.model.ChatResponse` and the DTO `com.example.chat.dto.ChatResponse` in one file; use the fully qualified Spring AI name.
- **Tests before code**: write the tests of a story first, run them, and report which ones could not fail beforehand (for example because the code already satisfies them). A test that fails only because it does not compile counts as failing.
- **Stop at the end of each phase** with a short summary and the `gradle test` result. Do not commit unless asked.
- Exact names (verified, see [research.md](research.md) R1): `MessageWindowChatMemory.builder().chatMemoryRepository(repo).maxMessages(n).build()`; `MessageChatMemoryAdvisor.builder(chatMemory).order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER).build()`; `ChatMemory.CONVERSATION_ID`; `ChatMemoryRepository` (`findConversationIds`, `findByConversationId`, `saveAll`, `deleteByConversationId`). There is no `DEFAULT_CONVERSATION_ID`.
- The advisor is attached **per request**, only when the request has a `sessionId`: `request.advisors(a -> a.advisors(memoryAdvisor).param(ChatMemory.CONVERSATION_ID, sessionId))`. Never as a default of the builder.
- `sessionId` rule (exact): `[A-Za-z0-9_-]{1,64}`, case sensitive. Message: `sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore`. Absent or `null` means no memory. Error field order: `sessionId, question, templateId, temperature, topP, topK, numPredict, seed`.
- Properties: `app.memory.max-messages` (20, minimum 2: a smaller value stops the service at startup with a message naming the property and the minimum), `app.memory.idle-timeout` (30m), `app.memory.max-sessions` (100), `spring.ai.ollama.chat.num-ctx` (8192; override `SPRING_AI_OLLAMA_CHAT_NUM_CTX`). Do not use the deprecated `spring.ai.ollama.chat.options.num-ctx`.
- `historyMessages` is an `Integer` in the 200 chat response: size of the history snapshot taken under the session lock; `0` without `sessionId`; omitted (`null`) in 400 and 502 responses.
- Logs written by new code carry only the abbreviated session id, `historyMessages` and fixed words. Never log a question, an answer or history text. Do not change the existing line that logs the chat question (feature 007).
- Tests that need the model use `test/support/ScriptedChatModel.java` passed to `ChatClient.builder(chatModel)`; never call Ollama from unit tests. Expiry tests use a controllable `Clock`, never `Thread.sleep`.
- Experiment scripts: ASCII only in `.ps1` (accented text through `Texto()` with `\uXXXX` escapes), run with `powershell -ExecutionPolicy Bypass -File ...`. Do not rewrite repository files with `Set-Content`; line endings are LF.
- Do not touch the frontend.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the baseline and capture the figures that later tasks compare against.

- [X] T001 Run `gradle test` in `backend/` and confirm the existing 129 tests pass; run `bash scripts/smoke_test.sh` against the running stack and confirm it passes. Record any failure before changing code.
- [X] T002 Record the baseline for SC-005 **before any code changes**: with the running stack built from the current commit, send each of three fixed questions (`Que es una API?`, `Say hi in one sentence`, `Name one colour`) without `sessionId`, with `temperature` 0.0 and `numPredict` 1, and write the three `usage.promptTokens` in a "Baseline before memory" note under the "Validation results" heading of [quickstart.md](quickstart.md). Use a single-line edit or append; do not rewrite the file.
- [X] T003 [P] In `test/support/ScriptedChatModel.java` make the `prompts` list thread-safe (`Collections.synchronizedList`) and `last` safe to read under concurrent calls (synchronize `call`), so tests can call the model from several threads. Run the existing tests to confirm nothing changed.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The `memory` package (settings, bounded store, session lock, turn runner), the `ModelResult` field and the context-size setting. No user story starts before this is done.

- [X] T004 [P] Write `test/memory/MemorySettingsTest.java`: binding `app.memory.*` with Spring Boot's `Binder` and a `MapConfigurationPropertySource` gives the defaults (20, 30 minutes, 100) when nothing is set; `max-messages` 1, 0, -1 or `abc` (the error says the minimum is 2), `max-sessions` 0, and `idle-timeout` 0 or negative each fail with an error that names the property (`app.memory.max-messages` and so on); `max-messages` 2 is accepted.
- [X] T005 [P] Write `test/memory/BoundedChatMemoryRepositoryTest.java` with a controllable `Clock`: `saveAll` then `findByConversationId` returns the same messages in order; two ids never see each other's messages; the returned list is a copy (changing it does not change the store); an id idle for longer than `idle-timeout` is gone on the next read, one idle for less is not; a read refreshes the last-use time; with `max-sessions` 3, saving a 4th id drops the least recently used (not the oldest created) and the count never exceeds 3; `deleteByConversationId` removes one id only; `findByConversationId` of an unknown id returns an empty list.
- [X] T006 [P] Write `test/memory/ConversationMemoryTest.java` (uses the repository of T008): `runTurn` gives the action the history size (0 for a new session); when the action returns an answer, the turn is kept; when the action throws, the repository holds exactly what it held before and the exception propagates; when the answer is blank or `null`, the repository is restored; a session that did not exist stays absent after a failed first turn; two threads running turns of the **same** session never overlap (record start and end markers in the action) and 200 turns from 8 threads leave exactly 400 stored messages in alternating question/answer order (window large enough); turns of **different** sessions overlap (a latch proves the second starts while the first is blocked); the `SessionLocks` instance passed to the secondary constructor of T010 reports `size()` 0 after all turns finish.
- [X] T007 Create `chat/memory/MemorySettings.java`: `@ConfigurationProperties("app.memory")` holder (record or class, the style of `chat/prompt/PromptVariables.java`) with `maxMessages` (default 20), `idleTimeout` (`Duration`, default 30 minutes), `maxSessions` (default 100), each validated so a value outside the rules of [data-model.md](data-model.md) fails binding with an error naming the property (`maxMessages` at least 2; the message also states the minimum). Short Spanish Javadoc. Makes T004 pass.
- [X] T008 Create `chat/memory/BoundedChatMemoryRepository.java` implementing `ChatMemoryRepository` as described in research R3: an access-ordered `LinkedHashMap` guarded by one lock, entries with messages and last-use time, expired sessions dropped at the start of every operation, least recently used dropped when `saveAll` of a new id exceeds `maxSessions`, lists copied in and out, an injected `Clock`. Constructor `(MemorySettings, Clock)`. Makes T005 pass.
- [X] T009 Create `chat/memory/SessionLocks.java`: one `ReentrantLock` per session id in a `ConcurrentHashMap`, reference counted (`acquire(id)` returns a handle that unlocks and removes the entry when the count reaches zero), usable in try-with-resources, with a package-private `size()` that the T006 test reads.
- [X] T010 Create `chat/memory/ConversationMemory.java` and extend `chat/ModelResult.java`. `ModelResult` becomes `record ModelResult(String answer, UsageMetrics usage, Integer historyMessages)` and keeps the two-argument constructor (sets `historyMessages` to 0). `ConversationMemory` is a `@Component` with an `@Autowired` constructor `(MemorySettings, ChatMemoryRepository)` that creates its own `SessionLocks`, plus a package-private secondary constructor `(MemorySettings, ChatMemoryRepository, SessionLocks)` that tests use to observe the locks; it builds the `MessageWindowChatMemory` and the `MessageChatMemoryAdvisor` once, and exposes `ModelResult runTurn(String sessionId, TurnAction action)` where `TurnAction` is `ModelResult run(Advisor memoryAdvisor, int historyMessages)`: acquire the session lock → snapshot `repository.findByConversationId(id)` → run the action → if it throws or its answer is blank, restore the snapshot (`saveAll`, or `deleteByConversationId` when the snapshot is empty) → return the result with `historyMessages` set to the snapshot size → release the lock. Debug logs only with the abbreviated id and numbers (`PromptTemplateRegistry.abbreviate`). Makes T006 pass.
- [X] T011 Add a `@Configuration` class `chat/memory/MemoryConfig.java` with `@EnableConfigurationProperties(MemorySettings.class)`, a `Clock` bean (`Clock.systemUTC()`) and a `ChatMemoryRepository` bean of type `BoundedChatMemoryRepository`. Add to `res/application.properties`, after the prompt section and in the same comment style: `app.memory.max-messages=20`, `app.memory.idle-timeout=30m`, `app.memory.max-sessions=100`, with a comment that memory lives only in RAM and is lost on restart. Run `gradle build` to confirm the context still builds.
- [X] T012 Context size (FR-019): add `spring.ai.ollama.chat.num-ctx=8192` to `res/application.properties` with a comment: an oversize prompt is cut by Ollama to about half of the context size without a signal (measured, research R7), 20 messages may not fit in a small context, override with `SPRING_AI_OLLAMA_CHAT_NUM_CTX`. In `docker/docker-compose.yml` add to the backend `environment` the line `- SPRING_AI_OLLAMA_CHAT_NUM_CTX=${SPRING_AI_OLLAMA_CHAT_NUM_CTX:-8192}`.
- [X] T013 Verify T012 on the real stack: rebuild (`docker compose -f docker/docker-compose.yml up -d --build`), run `docker compose -f docker/docker-compose.yml exec backend printenv SPRING_AI_OLLAMA_CHAT_NUM_CTX` (expect 8192), and send one chat request with a question of about 12000 words (build it with Python or a loop) and `numPredict` 1: `usage.promptTokens` must now be well above 2050 (up to about 8192, not cut at 2048). Then run once with `SPRING_AI_OLLAMA_CHAT_NUM_CTX=2048` and confirm it is cut near 2050 again, and restore 8192. Record the three figures in a "Verified in implementation" note at the end of [research.md](research.md).

**Checkpoint**: `gradle test` passes (existing 129 + the new memory tests); nothing in the chat flow uses memory yet.

---

## Phase 3: User Story 1 - Follow-up questions remember the conversation (Priority: P1) 🎯 MVP

**Goal**: Questions with the same `sessionId` are answered with the earlier turns of that session; sessions never mix; a request without `sessionId` is unchanged.

**Independent Test**: With the scripted model, the second request of a session carries the first question and answer after the system message; a different session does not; `historyMessages` equals the number of history messages the model received.

### Tests for User Story 1 (write first, confirm they fail)

- [X] T014 [P] [US1] Write `test/impl/SpringAiModelServiceMemoryTest.java` with a real `ConversationMemory` (repository and settings built in the test) and `ScriptedChatModel`: first turn of a session sends `[SYSTEM, USER]` and `historyMessages` 0; second turn sends `[SYSTEM, USER q1, ASSISTANT a1, USER q2]` in that order and `historyMessages` 2; third turn 4; session `s1` and `s2` interleaved never include each other's text; `sessionId` `null` sends `[SYSTEM, USER]` every time, returns `historyMessages` 0 and stores nothing (check the repository); `historyMessages` always equals `prompt.getInstructions().size() - 2` for session turns. Helper constructor for the service in `test/support/TestPrompts.java` or a new support class so other tests can build it. Add a loop over 10 independent pairs of sessions that proves SC-001: in 10 of 10 follow-up requests the history of the same session reached the model, in 0 of 10 the history of the other session.
- [X] T015 [P] [US1] Write `test/ChatControllerSessionTest.java` (standalone MockMvc, lambda `ModelService`, same style as `ChatControllerTemplateTest`): a 200 response contains `historyMessages` equal to the value in `ModelResult`; every existing field (`requestId`, `answer`, `elapsedMs`, `status`, `usage`) is still present with the same names; a 502 (blank answer) and a 400 contain no `historyMessages` key.

### Implementation for User Story 1

- [X] T016 [US1] In `chat/dto/ChatResponse.java` add `private Integer historyMessages` with `@JsonInclude(JsonInclude.Include.NON_NULL)`, getter and setter in the existing one-line style. In `chat/ChatController.java` set it from `result.historyMessages()` only when `status` is `ok`. Do not touch the log lines.
- [X] T017 [US1] In `chat/impl/SpringAiModelService.java` add the `ConversationMemory` constructor parameter. When `sessionId` is not `null`, build the usual request (system text of the template, user question, options, `NoOpTemplateRenderer`) inside `conversationMemory.runTurn(sessionId, (advisor, history) -> ...)` and add `.advisors(a -> a.advisors(advisor).param(ChatMemory.CONVERSATION_ID, sessionId))`; when `null`, keep the current path unchanged (no advisor, no lock, `historyMessages` 0). Extract the shared request building into one private method so both paths use it. Keep the blank-answer result returning `new ModelResult("", null)` so the rollback in T010 triggers.
- [X] T018 [US1] Update every test that constructs `SpringAiModelService` (`test/impl/SpringAiModelServiceTemplateTest.java`, `SpringAiModelServiceUsageTest.java`, `UsageMetricsMissingValuesTest.java`, `GenerationOptionsTest.java` and any other found with a search) to pass a `ConversationMemory` built by the helper of T014. Their assertions must not change.
- [X] T019 [US1] Run `gradle test`; T014 and T015 must pass and the existing tests must be unchanged.

**Checkpoint**: User Story 1 works with the scripted model. Report `gradle test`.

---

## Phase 4: User Story 4 - A session belongs to its owner only (Priority: P1)

**Goal**: `sessionId` is validated before the model is called; no endpoint exposes history; new code logs no history.

**Independent Test**: Invalid ids return 400 in the existing error shape without calling the model; the controller exposes no history, listing or deletion route; logs of the memory path contain no question or answer text.

### Tests for User Story 4 (write first, confirm they fail)

- [X] T020 [P] [US4] Extend `test/ChatControllerSessionTest.java`: valid ids return 200 (`demo`, `Demo`, `a-b_c`, a 64-character id, a UUID); invalid ids return 400 with one error `{"field":"sessionId","message":"sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore"}` and the model stub is **not** called (counter): `""`, `"   "`, `"a b"`, `"café"`, `"a/b"`, `"a.b"`, a 65-character id; absent field and `"sessionId": null` return 200 with the stub called; the invalid value does not appear in the message; combined with an invalid `temperature` and an unknown `templateId`, the errors come in the order `sessionId, templateId, temperature`; `Demo` and `demo` reach the model as different strings (capture the argument).
- [X] T021 [P] [US4] Write `test/ChatControllerEndpointsTest.java`: scan the `com.example.chat` package for `@RestController` classes (`ClassPathScanningCandidateComponentProvider`) and assert the set of mapped paths and HTTP methods is exactly the known set (chat, classify, the LLM health route found in `chat/health/LlmHealthController.java`); the test fails if a route with `session`, `history` or `memory` in its path, or any `GET`, `PUT`, `PATCH` or `DELETE` on `/chat`, is added (FR-011).
- [X] T022 [P] [US4] Write `test/memory/MemoryLoggingTest.java`: attach a Logback `ListAppender` to the loggers of `com.example.chat.memory` and `com.example.chat.impl.SpringAiModelService`, run three turns of one session with distinctive question and answer texts, a rollback and an expiry, and assert no captured message (formatted) contains any question text, answer text or the history; messages may contain the abbreviated session id and numbers (FR-015).

### Implementation for User Story 4

- [X] T023 [US4] In `chat/dto/ChatRequest.java` add `@Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore")` to `sessionId` (null passes by design). In `chat/ChatController.java` put `"sessionId"` first in `FIELD_ORDER`. Confirm that validation errors return before the line that logs the chat question, so an invalid id is never logged raw.
- [X] T024 [US4] Run `gradle test`; T020–T022 must pass. Report which of them could not fail beforehand (T021 and T022 are expected to pass already).

**Checkpoint**: Validation and exposure rules hold. Report `gradle test`.

---

## Phase 5: User Story 2 - The history window has a limit (Priority: P2)

**Goal**: Each session keeps at most the configured number of messages; old messages go first; idle sessions expire; the number of sessions is capped; the system instructions are never dropped.

**Independent Test**: With a window of 4, the sixth request carries only the last 4 history messages and the system message.

### Tests for User Story 2 (write first, confirm which fail)

- [X] T025 [P] [US2] Extend `test/impl/SpringAiModelServiceMemoryTest.java` (window 4): after six turns the sixth request's history is exactly the last 4 messages (`q4, a4, q5, a5`), `historyMessages` 4, and `q1` appears nowhere in it; every request, whatever the history size, has the system message first; window 2 keeps exactly the last exchange (`q, a`, `historyMessages` 2) and window 3 (odd) behaves like 2, each starting the history with a question, never an orphan answer; with the default settings 12 turns give `historyMessages` 0, 2, …, 20, 20, 20.
- [X] T026 [P] [US2] Extend `test/impl/SpringAiModelServiceMemoryTest.java` (controllable `Clock`): after a turn, advancing 29 minutes keeps the history (`historyMessages` 2 on the next turn); advancing 31 minutes starts empty (`historyMessages` 0); with `max-sessions` 100, turns in 101 different sessions leave 100 stored and the least recently used one empty; a loop over 1000 distinct sessions never makes the stored count exceed the cap (SC-007).
- [X] T027 [US2] Run `gradle test`. If T025 or T026 fail, fix the cause in `chat/memory/` (not in the tests); the window itself comes from `MessageWindowChatMemory`. Report which of the tests could not fail beforehand.

**Checkpoint**: Window, expiry and cap are proven with the scripted model. Report `gradle test`.

---

## Phase 6: User Story 3 - Memory works with templates and usage metrics (Priority: P2)

**Goal**: The template of each turn applies and is never stored; `usage` is reported with the same fields; rejected requests change nothing; classification has no memory.

**Independent Test**: In one session, a turn with another template sends that template's text as the only system message while keeping the history; a 400 in the middle of a conversation leaves the history unchanged.

### Tests for User Story 3 (write first, confirm which fail)

- [X] T028 [P] [US3] Extend `test/impl/SpringAiModelServiceMemoryTest.java`: turn 1 with `concise`, turn 2 with `tutor` in the same session: the second request has exactly one `SYSTEM` message, equal to the rendered `tutor` text, followed by the history of turn 1; a turn without `templateId` uses the `default` text; no `SYSTEM` message is ever present in the repository (inspect the repository passed to `ConversationMemory`); the number of `SYSTEM` messages in every request is 1 (no duplication across ten turns).
- [X] T029 [P] [US3] Write `test/ChatMemoryFlowTest.java`: standalone MockMvc with the real controller, real `SpringAiModelService`, real `ConversationMemory` and `ScriptedChatModel` (scripted `promptTokens` 40, 60, 80): turn 1 and 2 are 200 and `usage` has all fields of feature 003 with the scripted values; a third request with an unknown `templateId` returns 400 and the model is not called; a fourth request returns `historyMessages` 4 (not 6); a model failure (scripted exception) returns the same status and body as before the feature, and the next request still reports `historyMessages` 4 (the failed question was not stored).
- [X] T030 [P] [US3] Extend `test/impl/SpringAiClassificationServiceTest.java`: a classification request built from the same `ChatClient.Builder` as the chat service, after chat turns in a session, sends exactly `[SYSTEM, USER]` (two messages) and its constructor takes no `ConversationMemory` (FR: `/classify` has no memory).
- [X] T031 [US3] Run `gradle test`. Fix any failure in the production code, not in the tests. Report which of T028–T030 could not fail beforehand.

**Checkpoint**: Templates, usage and classification are proven compatible. Report `gradle test`.

---

## Phase 7: User Story 5 - Measure how the prompt grows over 20 turns (Priority: P3)

**Goal**: Experiment E records `promptTokens` and `historyMessages` over 20 turns with and without memory, at three context sizes (2048, 4096 and 8192), and the chart overlays the three runs.

**Independent Test**: Three CSV files of 40 rows each and one PNG; with 8192 the flattening is at turn 11 (`historyMessages` 20); with 4096 and 2048 `promptTokens` stops growing (plateau) while `historyMessages` keeps growing.

- [X] T032 [US5] Extend `Invoke-Chat` in `experimentos/comun.ps1`: add an optional fourth parameter `[string]$SessionId = ''` (sent as `"sessionId"` when not empty, after `question`) and return two more properties, `historyMessages` (from the response) and `totalTokens` (from `usage`). Existing calls (experiments A, B, C and the template experiment) must not change. Keep the file ASCII only.
- [X] T033 [US5] Create `experimentos/experimento_e.ps1` (ASCII only, `Texto()` for accented text, header comment in the style of `experimento_d.ps1`): parameters `-NumCtx` (mandatory integer), `-Turnos` (default 20), `-OutDir` (default `resultados`). Fixed list of 20 Spanish instructions, one per country, each of about 70 words and asking for an answer of about 300 words, with `templateId` `tutor` (the `concise` template limits the answer to 2 sentences and the answers would never be long enough), `numPredict` 400, `temperature` 0.0, `seed` 42, so that almost every answer reaches the cap and every exchange (instruction plus answer) has a similar size of about 500 tokens (an estimate, checked in T035). The size matters: with 10 exchanges in the window the prompt should reach about 5000 tokens, so it exceeds 2048 around turn 5 and 4096 around turn 9 or 10 and never exceeds 8192, which is what lets the three runs show different events (short exchanges would fit every context and make the runs identical). Two runs: `memoria` (one `sessionId` `exp-e-<timestamp>-<NumCtx>` for all turns) and `base` (no `sessionId`), the `base` run after the `memoria` run. Per turn one row: `numCtx, run, turno, http, promptTokens, completionTokens, totalTokens, elapsedMs, historyMessages`. Write `experimento_e_ctx<NumCtx>.csv` (40 rows) with `Guardar`. In the `memoria` run print a warning naming the turn when `promptTokens` varies less than 1% from the previous turn while `historyMessages` keeps rising (a plateau: Ollama drops the oldest messages, research R10), and warn when any `http` is not 200. Rebuild `experimento_e_resumen.csv` from all existing `experimento_e_ctx*.csv`: per `numCtx` and `run`, `turnos_ok`, `primer_turno_ventana_llena` (first turn with `historyMessages` equal to the maximum seen), `turno_meseta` (first turn with a plateau as defined above, empty if none), `promptTokens` at turns 1, 11 and 20, growth percentage between turn 1 and 11, variation percentage between turn 11 and 20 (SC-004 expects within 25%), `promptTokens_max` and `errores`. Print the summary with `Format-List`.
- [X] T034 [US5] Add a third graph to `experimentos/graficas.py` without changing graphs 1 and 2: read every `resultados/experimento_e_ctx*.csv` that exists (`utf-8-sig`), plot `promptTokens` against `turno` with one solid line per `numCtx` for the `memoria` run and one dashed line per `numCtx` for the `base` run, a horizontal dotted mark at each `numCtx` value, a second small panel with `historyMessages` against `turno` for the `memoria` runs, Spanish axis labels and title in the style of the existing graphs, saved to `experimentos/graficas/grafica_3_prompttokens_vs_turno.png`. Skip the graph silently when no file exists. Run `py experimentos\graficas.py` with only the quick-test CSV to see it works, then delete that temporary data.
- [X] T035 [US5] Quick check of the script, do not touch `experimentos/resultados/`: rebuild the stack (`docker compose -f docker/docker-compose.yml up -d --build`), run `powershell -ExecutionPolicy Bypass -File experimentos/experimento_e.ps1 -NumCtx 8192 -Turnos 3 -OutDir "$env:TEMP\exp_e_prueba"` and confirm 6 rows, `historyMessages` 0, 2, 4 in the `memoria` run and 0, 0, 0 in `base`, and no warning. From the `promptTokens` of the three `memoria` turns work out the size of one exchange (the difference between consecutive turns); it should be between 400 and 600 tokens. If it is not, change the length of the 20 instructions (the only thing allowed to change, and only here) and repeat, so that the full runs of T036 and T037 meet the expectation of T033. Report the output and stop; do not run the full experiment in this task.
- [X] T036 [US5] Run the full experiment with the two smaller contexts, 2048 and then 4096. For each: `$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX='<value>'; docker compose -f docker/docker-compose.yml up -d`, wait for the backend, confirm the variable inside the container, then `powershell -ExecutionPolicy Bypass -File experimentos/experimento_e.ps1 -NumCtx <value>`. Each run takes about 25 to 27 minutes. Report the summary, every non-200 row and every plateau warning. Do not change parameters or questions.
- [X] T037 [US5] Run it a third time with context 8192: set `SPRING_AI_OLLAMA_CHAT_NUM_CTX` to `8192`, restart the backend the same way, confirm the variable, and run `experimento_e.ps1 -NumCtx 8192` (no plateau is expected). Afterwards leave the stack on 8192 (the default) and confirm it.
- [X] T038 [US5] Run `py experimentos\graficas.py`, open `experimentos/graficas/grafica_3_prompttokens_vs_turno.png` and check it shows the three runs overlaid. Write an "Experiment E" note in the "Validation results" section of [quickstart.md](quickstart.md): the summary table of the three contexts, the turn where the window fills (expected 11) in each, the turn of the sharp fall of `promptTokens` at 2048 and 4096 (expected around 5 and around 9 or 10, estimates) while `historyMessages` kept growing, and that there was none at 8192, the SC-004 check (within 25% after the plateau) and the SC-008 check (one CSV per context size with 20 rows per run, `memoria` and `base`). State plainly anything that did not match the expectation. Commit nothing unless asked.

- [X] T047 [US5] Measure whether the system message survives when Ollama drops old messages because the prompt does not fit in the context size (spec edge case and FR-006; [research.md](research.md) R12). Create `experimentos/experimento_truncado.ps1` (ASCII only, `Texto()` for accented text, same style as `experimento_e.ps1`) with parameter `-NumCtx` (mandatory integer) and `-OutDir`: one session with `templateId` `extractor` (its system text demands a JSON object with `entidades`, `fechas` and `resumen`), `numPredict` 200, `temperature` 0.0, `seed` 42; 10 turns, each with a different fixed Spanish paragraph of about 250 words, then one short final turn; per turn record `numCtx, turno, http, promptTokens, historyMessages, answerIsJson, answerHasKeys` into `experimento_truncado_ctx<NumCtx>.csv`, where `answerIsJson` (strict) is true when the answer parses as a JSON object with the keys `entidades`, `fechas` and `resumen`, and `answerHasKeys` (relaxed) is true when the answer text contains `"entidades"`, `"fechas"` and `"resumen"`. The deciding column is `answerHasKeys`, because `llama3.2:3b` often cuts a JSON answer without the closing brace (experiment D) and the strict column alone could make the control fail for a reason unrelated to truncation; show both columns in the note. Run it with `SPRING_AI_OLLAMA_CHAT_NUM_CTX` 2048, 4096 and 8192 (same restart procedure as T036; 8192 is the control, where the prompt is expected to fit), then leave the stack on 8192 and confirm. For each context size find the turn of the plateau of `promptTokens` (variation under 1% from the previous turn while `historyMessages` keeps rising, the criterion of `experimento_e.ps1`) and record whether the system message survives once Ollama is dropping messages: the share of `answerHasKeys` before and after that turn, and in the short final turn, which can only follow the `extractor` instruction if the system message was kept; compare with the control (`answerIsJson` shown next to it). Record the result exactly as it comes (a format failure also in the control, no plateau at some size, or an inconclusive outcome are valid results) in a "System message and truncation" note under "Validation results" in [quickstart.md](quickstart.md), and replace `Measured result: pending (T047).` in the spec edge case about a question that exceeds the context with one sentence stating what was measured (single-line edit). Do not state which part Ollama keeps beyond what the figures show. Depends on T013 and T036; run before T046. Commit nothing unless asked.

**Checkpoint**: Experiment E and the truncation measurement (T047) done. Report the summaries, the chart path and the T047 figures.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T039 [P] Document in `backend/README.md` (Spanish, like the rest of the file, in a new "Memoria de conversacion (feature 006)" section): the `sessionId` rule and that absent means no memory, `historyMessages` and how to read it with `promptTokens`, the three `app.memory.*` properties and `num-ctx` (default 8192; Ollama's own default appears to be 4096, an inference; an oversize prompt is silently reduced to about half of it, so a sharp fall of `promptTokens` while `historyMessages` does not decrease signals truncation; how to override it), that memory is only in RAM and is lost on restart, that the minimum window is 2 (a smaller `app.memory.max-messages` stops the service at startup with a message naming the property), the explicit limit (no authentication: the only protection is that the id is not guessed, `demo` is easy to guess, real authentication is for the guardrails feature), that `/classify` has no memory, and the result of T047 (what was measured about the system message when Ollama truncates the prompt, in the words of the "System message and truncation" note; write this part of the README after T047, so T039 comes after it). Link to [contracts/chat-api.md](contracts/chat-api.md).
- [X] T040 [P] Update `scripts/smoke_test.sh`: keep the existing `demo` check; add two requests with a fresh `sessionId` (`smoke-$$`) and assert `historyMessages` is 0 and then 2; add a request with `"sessionId":""` and assert HTTP 400. Keep the script's style.
- [X] T041 Update `tests/integration/test_chat_flow.sh`: give every existing request its own `sessionId` (`it-$$-<n>`) so they do not share a conversation; add a memory block: three turns of one session give `historyMessages` 0, 2, 4; a second session starts at 0; a request without `sessionId` gives 0; ids `""`, `a b` and 65 characters give 400; a recall question ("My name is Ana" then "What is my name?") only **warns** if the answer lacks "Ana" (the model is not deterministic). Depends on T040 only for style.
- [X] T042 Check SC-005: repeat the three baseline requests of T002 on the final build (same parameters, no `sessionId`) and compare `promptTokens` with the baseline; each must be within 5%. Record both in the "Validation results" note of [quickstart.md](quickstart.md).
- [X] T043 Check SC-002 with the real model and measure, for information, how often the 3B model uses the history (SC-001 itself is proven by T014): 10 pairs of dependent turns (state a fact, then ask for it; for example a name, a colour, a number, a city, a pet) in 10 fresh sessions, and the same 10 second questions alone in 10 other sessions; record how many answers use the fact in each group. Then two interleaved sessions of 20 turns each with a distinctive word per session and confirm no answer or response of one contains the other's word. Report every figure honestly, including each miss and the questions that failed; no result of this task makes SC-001 pass or fail.
- [X] T044 Check SC-009 and FR-015: time five rejected requests (`""`, spaces, `a b`, 65 characters, `café`) and confirm each answers in under 1 s; send one session turn and read `docker logs pruebachat_backend`: the question and answer words must not appear in any line except the existing `Received chat request ... question=` line (feature 007). Record both in the same note.
- [X] T045 Run the full [quickstart.md](quickstart.md): `gradle test` (record the test count), `bash scripts/smoke_test.sh`, `bash tests/integration/test_chat_flow.sh`. Record the results under "Validation results".
- [X] T046 Final checks: `git diff --stat main -- frontend` shows nothing; the `question=` log line in `chat/ChatController.java` is unchanged; `git status` shows only files named in these tasks; the stack is on `num-ctx` 8192 (after T047 restored it).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies. T002 must run before any production code changes.
- **Foundational (Phase 2)**: after Setup; blocks every user story. T012–T013 (context size) are independent of T004–T011 but must finish before Phase 7.
- **US1 (Phase 3)**: after Foundational. MVP.
- **US4 (Phase 4)**: after US1 (it extends `ChatControllerSessionTest` and the controller).
- **US2 (Phase 5)**: after US1; independent of US4.
- **US3 (Phase 6)**: after US1; independent of US2 and US4. T029 uses the validation of US4 only for the unknown-template case, which already exists from feature 005.
- **US5 (Phase 7)**: after US1 (needs `historyMessages` in the response) and Foundational T012. It needs the final backend build. T047 also needs T036 (restart procedure) and finishes before T046.
- **Polish (Phase 8)**: after the stories you ship. T042 needs T002.

### Within phases

- Foundational order: T004, T005, T006 → T007, T008, T009 → T010 → T011; T012 → T013.
- Tests are written first and fail before their implementation: T014, T015 before T016–T018; T020–T022 before T023; T025, T026 before T027; T028–T030 before T031.
- Files touched by several tasks must be done in this order:
  - `chat/ChatController.java`: T016 → T023
  - `chat/dto/ChatRequest.java`: T023 only
  - `chat/impl/SpringAiModelService.java`: T017 only
  - `res/application.properties`: T011 → T012
  - `test/impl/SpringAiModelServiceMemoryTest.java`: T014 → T025 → T026 → T028
  - `test/ChatControllerSessionTest.java`: T015 → T020
  - `experimentos/comun.ps1`: T032 only; `experimentos/graficas.py`: T034 only
  - `specs/006-conversation-memory/quickstart.md` notes: T002 → T038 → T047 → T042 → T044 → T045 (append or single-line edits only)
  - `specs/006-conversation-memory/research.md` note: T013
  - `specs/006-conversation-memory/spec.md` edge case about a question that exceeds the context: T047 (single-line edit)

### Parallel opportunities

- T003 alongside T004–T006.
- T004, T005 and T006 together; T007, T008 and T009 together (T010 needs all three).
- T014 and T015 together; T020, T021 and T022 together; T025 and T026 together; T028, T029 and T030 together.
- T039 and T040 together.

### Parallel example: User Story 4

```text
Together: T020 (validation tests), T021 (endpoint set test), T022 (logging test)
Then:     T023 (@Pattern + field order) ; T024 (run)
```

---

## Implementation Strategy

### MVP first

Phases 1–3: baseline, the memory package, and User Story 1. At that point a session remembers and sessions do not mix, but `sessionId` is not yet validated; do not ship before Phase 4, because an unvalidated id can be any string.

### Incremental delivery

1. Setup + Foundational → the memory package exists and is tested alone.
2. US1 → remembering works (MVP).
3. US4 → validation and exposure rules; safe to ship from here.
4. US2 → window, expiry and cap proven.
5. US3 → compatibility with templates and usage proven.
6. US5 → Experiment E and the chart.
7. Polish → documentation, scripts, measurements.

Stop at the end of every phase and report the `gradle test` result before starting the next one.

## Notes

- The first tasks of US2 and US3 may find that the tests pass already because Spring AI's window and the system-message handling provide the behavior ([research.md](research.md) R2). That is expected; report it, do not weaken the tests.
- The experiment cannot change the backend configuration; the operator restarts it between the three runs (T036, T037) and for the truncation measurement (T047).
- Pre-existing non-compliance stays untouched and is deferred to feature 007: the full chat question logged in `ChatController`, `LogRedactionFilter` not redacting, and the missing coverage of the full flow.
