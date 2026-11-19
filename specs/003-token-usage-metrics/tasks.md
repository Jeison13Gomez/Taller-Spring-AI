---

description: "Task list for Chat Usage Metrics (feature 003)"
---

# Tasks: Chat Usage Metrics

**Input**: Design documents from `/specs/003-token-usage-metrics/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/chat-api.md](contracts/chat-api.md), [quickstart.md](quickstart.md)

**Tests**: Included, because plan.md commits to unit and controller tests for the new behavior (`backend/src/test/` is currently empty; backfilling tests for the rest of the flow is out of scope, feature 007).

**Organization**: Tasks are grouped by user story so each can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1, US2, US3 from spec.md
- Paths are relative to the repository root. Backend package: `backend/src/main/java/com/example/chat/` (abbreviated `chat/` below). Test package: `backend/src/test/java/com/example/chat/` (abbreviated `test/`).

## Conventions for this feature

- Spring AI's `org.springframework.ai.chat.model.ChatResponse` and our DTO `com.example.chat.dto.ChatResponse` share a simple name. Never import both in one file; use the fully qualified Spring AI name.
- API field names are `promptTokens`, `completionTokens`, `totalTokens`, `model`, `finishReason`, `tokensPerSecond`, nested under `usage` (see contract). `null` means "not available"; never substitute `0`.
- `tokensPerSecond = completionTokens / generationSeconds`, using only the model's generation time (not `elapsedMs`).
- Do not change what is logged about user input (pre-existing issue, feature 007).
- The frontend is the single static page `frontend/src/index.html`. Do not touch `frontend/src/app/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Establish a baseline and resolve the two open research items before coding.

- [X] T001 Run `./gradlew compileJava` in `backend/` and inspect the resolved Spring AI 2.0.0 jars to record the exact accessors for: usage (prompt/completion/total tokens), model name, finish reason, and Ollama generation duration (`eval_duration`: location and unit). Write the verified names and any fallback decision into a "Verified in implementation" note under sections 1 and 2 of `specs/003-token-usage-metrics/research.md`.
- [ ] T002 Start the stack per README (`docker compose up --build`), run `bash scripts/smoke_test.sh`, and confirm the baseline passes and `POST /api/v1/chat` returns no `usage` field yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Introduce the data shapes and thread them through every place the DTO is built, so the project compiles with `usage` always `null`. No user story starts until this is done.

- [X] T003 [P] Create `chat/dto/UsageMetrics.java` with nullable fields `Integer promptTokens`, `Integer completionTokens`, `Integer totalTokens`, `String model`, `String finishReason`, `Double tokensPerSecond`, an all-args constructor and getters, in the style of the existing DTOs (see [data-model.md](data-model.md)).
- [X] T004 [P] Create `chat/ModelResult.java`, a small record `ModelResult(String answer, UsageMetrics usage)` returned by the model service (not the DTO, to avoid the `ChatResponse` name collision).
- [X] T005 Add a nullable `usage` field (type `UsageMetrics`, getter and setter) to `chat/dto/ChatResponse.java` and replace the 4-argument constructor with a 5-argument one `(requestId, answer, elapsedMs, status, usage)`. Depends on T003.
- [X] T006 Change `chat/ModelService.java` so `generateResponse(String question, String sessionId)` returns `ModelResult` instead of `String`. Depends on T004.
- [X] T007 Update `chat/impl/SpringAiModelService.java` to compile against the new signature: return `new ModelResult(answer, null)` (and `new ModelResult("", null)` for the blank case). Real extraction comes in US1. Depends on T006.
- [X] T008 Update both construction sites in `chat/exception/GlobalExceptionHandler.java` (`handleHttpClient`, `handleRuntime`) to the 5-argument constructor with `usage = null`. Depends on T005.
- [X] T009 Update `chat/ChatController.java`: validation-error response (about line 36) passes `usage = null`; normal path (about line 46) takes `answer` and `usage` from the `ModelResult` and builds the response with `result.usage()`. Keep the `status` logic and the log lines unchanged. Depends on T005, T006.
- [X] T010 Run `./gradlew build` in `backend/` and fix compilation errors; re-run `bash scripts/smoke_test.sh`. Expected: still passes, `usage` is `null` in responses. Depends on T007, T008, T009.

**Checkpoint**: Backend compiles; API unchanged except for `"usage": null`.

---

## Phase 3: User Story 1 - See usage and performance of each answer (Priority: P1) 🎯 MVP

**Goal**: Each successful answer shows prompt, completion and total tokens, model, finish reason and tokens per second.

**Independent Test**: Send one question; the answer shows all six values and total equals prompt + completion ([quickstart.md](quickstart.md) steps 2-3).

### Tests for User Story 1

- [X] T011 [P] [US1] Create `test/impl/SpringAiModelServiceUsageTest.java`: build a Spring AI chat response with usage 32/118/150, model `llama3.2:3b`, finish reason `stop` and a known generation duration, call the package-private `SpringAiModelService.toUsageMetrics(...)` and assert every field, including `tokensPerSecond = 118 / generationSeconds` rounded to 2 decimals. Must fail until T013 is done.
- [X] T012 [P] [US1] Create `test/ChatControllerUsageTest.java` using standalone `MockMvc` with a stub `ModelService` returning a `ModelResult` with usage and `GlobalExceptionHandler` as controller advice: assert HTTP 200, `status: "ok"`, all six `usage.*` JSON fields, `usage.totalTokens == usage.promptTokens + usage.completionTokens`, and existing fields (`answer`, `elapsedMs`, `status`, `requestId`) unchanged. Must fail until T013-T014 are done.

### Implementation for User Story 1

- [X] T013 [US1] In `chat/impl/SpringAiModelService.java`, replace `.call().content()` with `.call().chatResponse()` (use the fully qualified Spring AI `ChatResponse`), take the answer text from the first result, and add a package-private static `toUsageMetrics(org.springframework.ai.chat.model.ChatResponse)` that fills the six fields using the accessors recorded in T001. Return `new ModelResult(answer, usage)`. Keep the blank-answer handling. Depends on T001, T007.
- [X] T014 [US1] In `chat/ChatController.java`, add one INFO log line with the numeric usage values only (`promptTokens`, `completionTokens`, `totalTokens`, `model`, `finishReason`, `tokensPerSecond`), with `sessionId`; do not log question or answer text. Depends on T009.
- [X] T015 [P] [US1] In `frontend/src/index.html`, add CSS for a `.usage` line (small, muted, monospace, using the existing `--muted`, `--line` and `--mono` variables), distinct from the answer text (FR-007).
- [X] T016 [US1] In `frontend/src/index.html`, extend `addTurn(...)` with an optional `usage` argument and render, for bot turns, a `.usage` element inside that turn showing: prompt tokens, completion tokens, total tokens, model, finish reason and tokens/s. Build it with `textContent` only (never `innerHTML` for model-provided strings). Pass `data.usage` from the `ask(...)` success branch. Depends on T015.
- [X] T017 [P] [US1] Extend `scripts/smoke_test.sh` to also assert the response contains `"usage"` and the keys `promptTokens`, `completionTokens`, `totalTokens`, `model`, `finishReason`, `tokensPerSecond`.
- [X] T018 [P] [US1] Extend `tests/integration/test_chat_flow.sh` to assert `totalTokens` equals `promptTokens + completionTokens` (extract with `grep -o`/`sed`; do not require `jq`).
- [X] T019 [US1] Run `./gradlew test` in `backend/` (T011, T012 pass), rebuild the stack, and walk through [quickstart.md](quickstart.md) steps 2 and 3 for one question. Depends on T011-T018. (Verificado 2026-10-25: `build` con 15 pruebas, smoke y test de integración pasan; recorrido manual en el navegador OK.)

**Checkpoint**: User Story 1 works on its own and is demonstrable (MVP).

---

## Phase 4: User Story 2 - Compare queries within a conversation (Priority: P2)

**Goal**: Each answer keeps its own metrics while the conversation grows; none ever moves to another answer.

**Independent Test**: Ask three questions; every answer still shows its own values ([quickstart.md](quickstart.md) step 3).

- [X] T020 [US2] Review `frontend/src/index.html` `addTurn`/`ask` and ensure the metrics element is created inside the turn element of the answer it belongs to and nothing later re-renders, clears or rewrites earlier `.usage` elements (no shared or global usage state). Fix if any exists. Depends on T016.
- [X] T021 [P] [US2] Extend `tests/integration/test_chat_flow.sh` to send three different questions in sequence and assert each response contains its own `usage` object whose `totalTokens` equals `promptTokens + completionTokens` (SC-002, SC-005).
- [X] T022 [US2] Manual check in the browser: ask three different questions and confirm each answer shows distinct metrics that remain visible after later answers arrive and after scrolling.

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 3 - Understand when metrics are unavailable (Priority: P3)

**Goal**: Missing values show "no disponible" for just those values; the answer is still delivered; failed queries show no metrics.

**Independent Test**: Use a response without usage data; the answer appears with the missing values marked ([quickstart.md](quickstart.md) steps 4-5). The UI text is Spanish, so the label is "no disponible" (spec term: "not available").

### Tests for User Story 3

- [X] T023 [P] [US3] Create `test/impl/UsageMetricsMissingValuesTest.java` covering `toUsageMetrics`: (a) usage absent → all token fields and `tokensPerSecond` `null`, model kept if present; (b) finish reason absent → `null`; (c) completion tokens 0 → `tokensPerSecond` `null` (not 0); (d) generation duration missing or 0 → `tokensPerSecond` `null`; (e) provider total absent but prompt and completion present → total computed as their sum; (f) only one of prompt/completion present and no provider total → `totalTokens` `null`; (g) `finishReason` `length` is passed through unchanged. Must fail until T025.
- [X] T024 [P] [US3] Add a case to `test/ChatControllerUsageTest.java` (or a new `test/ChatControllerErrorUsageTest.java` if run in parallel with T012): validation failure (blank question → HTTP 400) and a stubbed `RuntimeException` (HTTP 500) both return `status: "error"` and `usage` `null`.

### Implementation for User Story 3

- [X] T025 [US3] Harden `toUsageMetrics` in `chat/impl/SpringAiModelService.java` to satisfy T023: null-safe on every metadata access; `tokensPerSecond` is `null` unless completion tokens > 0 and generation duration > 0, otherwise rounded to 2 decimals; total taken from the provider when present, else the sum only when both parts are present; never estimate or default to 0. Depends on T013, T023.
- [X] T026 [US3] In `frontend/src/index.html`, make the metrics renderer show "no disponible" for any `null`/missing field individually, show token counts as whole numbers and tokens/s with at most 2 decimals (FR-011), and treat an `ok` response whose `usage` is missing as all six values "no disponible" while still showing the answer. Depends on T016.
- [X] T027 [US3] In `frontend/src/index.html`, confirm the fault branches (HTTP error, empty answer, network error) call `addTurn` without `usage`, so no metrics are displayed for failed queries (FR-010). Adjust only if a branch renders metrics. Depends on T016.
- [X] T028 [US3] Run `./gradlew test`, then manually verify in the browser: with the backend stopped (error turn, no metrics) and, if feasible, with a stubbed response lacking usage (answer shown, values "no disponible"). Depends on T023-T027.

**Checkpoint**: All three user stories work independently.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T029 [P] Document the new `usage` object (field names, `null` = not available, tokens/s basis) in `backend/README.md`, linking to [contracts/chat-api.md](contracts/chat-api.md).
- [X] T030 Compare `elapsedMs` for the same short question before and after the change (T002 baseline vs. now) to check SC-004 (no more than 5% slower); record the numbers in `specs/003-token-usage-metrics/quickstart.md` under a short "Validation results" note.
- [X] T031 Run the full validation from [quickstart.md](quickstart.md) (steps 1-6): `./gradlew test`, `bash scripts/smoke_test.sh`, `bash tests/integration/test_chat_flow.sh`, and the UI walkthrough.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies; T001 gates T013.
- **Foundational (Phase 2)**: after Setup; blocks all user stories.
- **US1 (Phase 3)**: after Foundational. MVP.
- **US2 (Phase 4)**: after Foundational; its frontend tasks need T016 from US1 (it verifies and extends the per-turn rendering).
- **US3 (Phase 5)**: after Foundational; T025 builds on T013 and T026-T027 on T016 (all US1).
- **Polish (Phase 6)**: after the stories you ship.

### Within phases

- Foundational order: T003, T004 → T005, T006 → T007, T008, T009 → T010.
- Tests (T011, T012, T023, T024) are written first and fail before their implementation tasks.
- Files touched by several tasks must be done in order: `chat/impl/SpringAiModelService.java` (T007 → T013 → T025), `chat/ChatController.java` (T009 → T014), `frontend/src/index.html` (T015 → T016 → T020 → T026 → T027), `tests/integration/test_chat_flow.sh` (T018 → T021).

### Parallel opportunities

- T003 and T004 together.
- T011 and T012 together; T015, T017 and T018 together once T010 is done.
- T023 and T024 together.
- T029 any time after US1.

### Parallel example: User Story 1

```text
Together: T011 (service test), T012 (controller test), T015 (CSS), T017 (smoke script), T018 (integration script)
Then:     T013 (extraction) → T014 (log) ; T016 (render)
Finally:  T019 (verify)
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 → Phase 2 (API compiles with `usage: null`).
2. Phase 3 (US1). Stop and validate with quickstart steps 2-3. This is the demoable increment.

### Incremental delivery

1. Add US2 (per-answer persistence checks), validate.
2. Add US3 (missing values and error handling), validate.
3. Polish (docs, latency check, full quickstart run).

## Notes

- Tasks T001 and T013 depend on what Spring AI 2.0.0 actually exposes; if generation duration is not available from the high-level response, use the fallback in research.md section 2 (native Ollama response), never wall-clock time.
- The Angular files under `frontend/src/app/` are out of scope and unused by the Dockerfile.
- Commit after each phase or logical group.
