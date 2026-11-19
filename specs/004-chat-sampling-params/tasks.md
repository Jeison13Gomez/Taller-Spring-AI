---

description: "Task list for Per-Request Chat Sampling Parameters (feature 004)"
---

# Tasks: Per-Request Chat Sampling Parameters

**Input**: Design documents from `/specs/004-chat-sampling-params/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/chat-api.md](contracts/chat-api.md), [quickstart.md](quickstart.md)

**Tests**: Included, because plan.md commits to unit and controller tests for this feature's behavior (Constitution: Testing & Observability). Tests for the rest of the flow are out of scope (feature 007).

**Organization**: Tasks are grouped by user story so each can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US4 from spec.md
- Paths are relative to the repository root. Backend package: `backend/src/main/java/com/example/chat/` (abbreviated `chat/` below). Test package: `backend/src/test/java/com/example/chat/` (abbreviated `test/`).

## Conventions for this feature

- Request field names: `temperature`, `topP`, `topK`, `numPredict`, `seed`, at the top level of the request body next to `question` and `sessionId`.
- DTO types:
  - `temperature` and `topP` are `Double`.
  - `topK`, `numPredict` and `seed` are `BigDecimal`, so non-whole and overflowing values reach validation instead of being truncated or failing in Jackson (research R4). Never use primitives.
  - `null` means "not sent". `0` and `0.0` are values.
- Ranges (inclusive):
  - temperature 0.0–2.0
  - topP 0.0–1.0
  - topK 0–200, whole number
  - numPredict 1–2048, whole number
  - seed -2147483648–2147483647, whole number
- Error messages, exactly one per field:
  - `temperature must be between 0.0 and 2.0`
  - `topP must be between 0.0 and 1.0`
  - `topK must be a whole number between 0 and 200`
  - `numPredict must be a whole number between 1 and 2048`
  - `seed must be a whole number between -2147483648 and 2147483647`
  - `<field> must be a number` (wrong kind of value)
  - `request body is not valid JSON` (field `body`)
  - `question must be provided` (existing)
- Spring AI 2.0: `chatClient.prompt().options(...)` takes a **builder** (`OllamaChatOptions.builder()`), not built options. Use `numPredict(...)`, not `maxTokens(...)`.
- Never import both `org.springframework.ai.chat.model.ChatResponse` and the DTO `com.example.chat.dto.ChatResponse` in one file.
- Log only numeric settings. Do not change what is logged about the question (pre-existing issue, feature 007).
- The build has no Gradle wrapper. Run `gradle test` / `gradle build` from `backend/` (the 003 validation used Gradle 9.6.1 with JDK 21).
- Gradle no está en el PATH de esta máquina; usar `C:\Users\fores\.gradle\wrapper\dists\gradle-9.6.1-bin\4ticwg1pgcbps2hj28r8so764\gradle-9.6.1\bin\gradle.bat` con `JAVA_HOME` en JDK 21 (`C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot`). Donde estas tareas dicen `gradle test` / `gradle build`, se refieren a ese ejecutable.
- Do not touch the frontend.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the baseline and the library details the code depends on.

- [X] T001 Run `gradle test` in `backend/` and confirm the existing 15 tests pass; run `bash scripts/smoke_test.sh` against the running stack and confirm it passes. Record any failure before changing code.
- [X] T002 With `javap` against the resolved jars in the Gradle cache, verify and record in a "Verified in implementation" note at the end of `specs/004-chat-sampling-params/research.md`:
  - (a) the return type of `OllamaChatOptions.builder()` and the getters on `OllamaChatOptions` for temperature, topP, topK, numPredict and seed;
  - (b) the fully qualified class of the Jackson databind exception that `HttpMessageNotReadableException` wraps under Spring Boot 4.1.0 (expected `tools.jackson.databind.DatabindException`, Jackson 3), and how to read the property path from it.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Carry a `GenerationSettings` value from the controller to the model service with every field `null`, so behavior is unchanged and the project compiles. No user story starts before this is done.

- [X] T003 Create `chat/GenerationSettings.java`: `public record GenerationSettings(Double temperature, Double topP, Integer topK, Integer numPredict, Integer seed)` with a static `none()` returning all-null, plus a short Spanish Javadoc in the style of `chat/ModelResult.java` (see [data-model.md](data-model.md)).
- [X] T004 Change `chat/ModelService.java` to a single method `ModelResult generateResponse(String question, String sessionId, GenerationSettings settings)` and update its Javadoc. Depends on T003.
- [X] T005 Update `chat/impl/SpringAiModelService.java` to the new signature, ignoring `settings` for now (no behavior change). Depends on T004.
- [X] T006 In `chat/ChatController.java`, call `modelService.generateResponse(req.getQuestion(), req.getSessionId(), GenerationSettings.none())`. Depends on T004.
- [X] T007 Update every `ModelService` lambda in `test/ChatControllerUsageTest.java` from `(q, s) -> ...` to `(q, s, o) -> ...`. Depends on T004.
- [X] T008 Run `gradle build` in `backend/`; all existing tests pass. Depends on T005–T007.

**Checkpoint**: Backend compiles; API behavior is identical to before.

---

## Phase 3: User Story 1 - Tune generation per request (Priority: P1) 🎯 MVP

**Goal**: Settings sent in a request apply to that request only; unsent settings keep their defaults.

**Independent Test**: `numPredict: 5` truncates the answer (`finishReason: "length"`, `completionTokens <= 5`) and the next request without it does not ([quickstart.md](quickstart.md) step 2).

### Tests for User Story 1

- [X] T009 [P] [US1] Create `test/impl/GenerationOptionsTest.java` for a package-private static `SpringAiModelService.toOptionsBuilder(GenerationSettings)`:
  - (a) all five values set → the built options have exactly those values, including `temperature 0.0`, `topP 0.0` and `topK 0` (FR-004);
  - (b) only `topK = 40` set → only topK is non-null on the built options;
  - (c) a defaults builder `OllamaChatOptions.builder().temperature(0.7).numPredict(1024)` `.combineWith(toOptionsBuilder(only topK 40))` builds with temperature 0.7, numPredict 1024 and topK 40 (research R2; FR-002);
  - (d) `GenerationSettings.none()` → `toOptionsBuilder` returns `null`.

  Use the getters recorded in T002. Must fail until T011.
- [X] T010 [P] [US1] Create `test/ChatControllerSettingsTest.java` (standalone `MockMvc` with `GlobalExceptionHandler`, as in `ChatControllerUsageTest`). Use a stub `ModelService` that captures the `GenerationSettings` it receives.
  - (a) Body `{"question":"hola","temperature":0.0,"topP":0.9,"topK":40,"numPredict":64,"seed":42}` → 200, and the captured settings equal those values; temperature is `0.0`, not null.
  - (b) Body with only `"numPredict":5` → captured numPredict 5, every other field null.
  - (c) Two sequential requests, the first with settings and the second without → the second capture is all-null (FR-003).

  Must fail until T012–T013.

### Implementation for User Story 1

- [X] T011 [US1] In `chat/impl/SpringAiModelService.java`:
  - Add package-private static `toOptionsBuilder(GenerationSettings)`. It returns `null` when `settings` is null or all fields are null; otherwise an `OllamaChatOptions` builder with only the non-null fields set (`temperature`, `topP`, `topK`, `numPredict`, `seed`).
  - In `generateResponse`, build the prompt spec and call `.options(builder)` only when the builder is non-null, then `.call().chatResponse()` as today.
  - Extend the existing debug log line with the non-null settings.

  Depends on T005, T009.
- [X] T012 [US1] In `chat/dto/ChatRequest.java`, add the fields `Double temperature`, `Double topP`, `BigDecimal topK`, `BigDecimal numPredict` and `BigDecimal seed`, with getters and setters in the existing one-line style, and a method `GenerationSettings toGenerationSettings()` that converts the `BigDecimal` fields with `intValueExact()` (null stays null). Range constraints come in US3.
- [X] T013 [US1] In `chat/ChatController.java`, pass `req.toGenerationSettings()` instead of `GenerationSettings.none()`. Add one INFO log line with `sessionId` and the five settings (numbers only). Depends on T006, T012.
- [X] T014 [P] [US1] Extend `tests/integration/test_chat_flow.sh`:
  - Send `{"question":"Explain photosynthesis in detail","sessionId":"it","numPredict":5}` and assert `"finishReason":"length"` and `completionTokens <= 5` (reuse `int_field`).
  - Then send the same question without `numPredict` and assert the response does not contain `"finishReason":"length"`.
- [X] T015 [US1] Run `gradle test` in `backend/` (T009 and T010 pass), rebuild the stack (`docker compose -f docker/docker-compose.yml up -d --build`) and run [quickstart.md](quickstart.md) step 2 and `bash tests/integration/test_chat_flow.sh`. Depends on T009–T014.

**Checkpoint**: User Story 1 works on its own (MVP). Requests can vary settings with no restart.

---

## Phase 4: User Story 2 - Existing clients keep working unchanged (Priority: P1)

**Goal**: Requests without the new fields behave and respond exactly as before.

**Independent Test**: The current request format returns the same response shape and uses the same defaults ([quickstart.md](quickstart.md) step 1).

- [X] T016 [P] [US2] Add a test to `test/ChatControllerSettingsTest.java`: the legacy body `{"question":"hola","sessionId":"t"}` → 200, the captured settings equal `GenerationSettings.none()`, and the response has exactly the keys `requestId`, `answer`, `elapsedMs`, `status`, `usage`. Also send an explicit `"temperature":null` and assert it is captured as null (spec edge case). Depends on T013.
- [X] T017 [US2] Confirm that `scripts/smoke_test.sh` and the original part of `tests/integration/test_chat_flow.sh` pass unchanged, and that `test/ChatControllerUsageTest.java` passes with only the lambda arity change from T007. Run `gradle test` and both scripts. Depends on T015, T016.

**Checkpoint**: User Stories 1 and 2 both work.

---

## Phase 5: User Story 3 - Clear rejection of invalid settings (Priority: P2)

**Goal**: Invalid settings return 400 with an `errors` list naming the field(s), and the model is not called.

**Independent Test**: `temperature: 2.5` returns 400 naming temperature and 0.0–2.0 ([quickstart.md](quickstart.md) steps 4–5).

### Tests for User Story 3

- [X] T018 [P] [US3] Add tests to `test/ChatControllerSettingsTest.java` using a stub `ModelService` that counts calls. For each case, assert 400, `status: "error"`, `usage: null` and the listed `errors[*].field` / `errors[*].message` values from Conventions, and that the stub was **not** called:
  - (a) `temperature: 2.5` → one error, `temperature`.
  - (b) `topP: -0.1, topK: 500` → exactly two errors, `topP` then `topK`.
  - (c) `numPredict: 0` and, separately, `numPredict: 2049` → `numPredict`.
  - (d) `topK: 3.5` → `topK` (whole-number message).
  - (e) `topK: 500.5` → a single `topK` entry, not two (one per field).
  - (f) `temperature: "hot"` → one error, `temperature must be a number`.
  - (g) body `{"question":` (malformed) → 400 (not 500), field `body`.
  - (h) blank question → `errors` contains `question must be provided`.

  Must fail until T019–T023.
- [X] T019 [P] [US3] Add boundary tests to `test/ChatControllerSettingsTest.java`. Both bodies → 200 with the stub called once each, and the response has no `errors` key:
  - `temperature 2.0, topP 1.0, topK 200, numPredict 2048`
  - `temperature 0.0, topP 0.0, topK 0, numPredict 1`

### Implementation for User Story 3

- [X] T020 [P] [US3] Create `chat/dto/ValidationError.java`: `public record ValidationError(String field, String message)`.
- [X] T021 [US3] In `chat/dto/ChatResponse.java`, add `private List<ValidationError> errors` with a getter and setter, annotated `@JsonInclude(JsonInclude.Include.NON_NULL)` (`com.fasterxml.jackson.annotation`, which Jackson 3 keeps). Leave the existing five-argument constructor and its call sites unchanged. Depends on T020.
- [X] T022 [US3] In `chat/dto/ChatRequest.java`, add the constraints, each using the single message for that field from Conventions:
  - `@DecimalMin("0.0") @DecimalMax("2.0")` on `temperature`
  - `@DecimalMin("0.0") @DecimalMax("1.0")` on `topP`
  - `@Digits(integer = 3, fraction = 0) @DecimalMin("0") @DecimalMax("200")` on `topK`
  - `@Digits(integer = 4, fraction = 0) @DecimalMin("1") @DecimalMax("2048")` on `numPredict`
  - `@Digits(integer = 10, fraction = 0) @DecimalMin("-2147483648") @DecimalMax("2147483647")` on `seed`

  Depends on T012.
- [X] T023 [US3] In `chat/ChatController.java`, on `bindingResult.hasErrors()`:
  - Build `errors` from `bindingResult.getFieldErrors()`, one `ValidationError` per field (first message wins).
  - Order the entries `question, temperature, topP, topK, numPredict, seed`.
  - Set them on the 400 response with `setErrors(...)`, then log the field names at WARN instead of the raw `getAllErrors()` dump.

  Depends on T021, T022.
- [X] T024 [US3] In `chat/exception/GlobalExceptionHandler.java`, add `@ExceptionHandler(HttpMessageNotReadableException.class)` returning 400 with `new ChatResponse(UUID.randomUUID().toString(), "", 0, "error", null)` and `errors` set to one entry:
  - If the cause chain contains the Jackson databind exception recorded in T002, use its last path property name as `field` and `<field> must be a number` as `message`.
  - Otherwise use field `body` and the message `request body is not valid JSON`.

  Log at WARN without the request body. Depends on T002, T021.
- [X] T025 [P] [US3] Extend `scripts/smoke_test.sh`: POST `{"question":"hi","temperature":2.5}`, assert HTTP 400 (`curl -s -o` / `-w '%{http_code}'`), and assert the body contains `"field":"temperature"`.
- [X] T026 [US3] Run `gradle test` (T018 and T019 pass), rebuild the stack (`docker compose -f docker/docker-compose.yml up -d --build`), and run [quickstart.md](quickstart.md) steps 4–5 and `bash scripts/smoke_test.sh`. Check that each rejection responds in under 1 s (SC-006) and that the log shows no model call for them. Depends on T018–T025.

**Checkpoint**: User Stories 1–3 work independently.

---

## Phase 6: User Story 4 - Reproducible answers with a seed (Priority: P3)

**Goal**: The same question, settings and seed give the same answer.

**Independent Test**: Send two identical requests with `seed: 42, temperature: 0.8`; the answers match ([quickstart.md](quickstart.md) step 3).

- [X] T027 [P] [US4] Add seed tests to `test/ChatControllerSettingsTest.java`:
  - `seed: -2147483648` and `seed: 2147483647` → 200, with the exact value captured.
  - `seed: 2147483648` and `seed: 1.5` → 400 naming `seed`.

  Depends on T022.
- [X] T028 [US4] Run [quickstart.md](quickstart.md) step 3 against the stack, and repeat the seeded pair 20 times. Record in a "Validation results" note at the end of `specs/004-chat-sampling-params/quickstart.md` how many pairs matched (SC-005 requires at least 19), plus the result for `temperature: 0.0`. Depends on T015.

**Checkpoint**: All four user stories work independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T029 [P] Document the five optional settings in `backend/README.md`: ranges, defaults, `null` = not sent, the 400 `errors` format, and the limitation that a wrongly typed value reports only the first offending field. Link to [contracts/chat-api.md](contracts/chat-api.md).
- [X] T030 Concurrency check (spec edge case; FR-003). Start two requests in parallel, `{"question":"Explain photosynthesis in detail","numPredict":5}` and the same question with no settings. Assert that only the first has `finishReason: "length"`. Record the result in the quickstart "Validation results" note.
- [X] T031 Run the full [quickstart.md](quickstart.md) (steps 1–6): `gradle test`, `bash scripts/smoke_test.sh` and `bash tests/integration/test_chat_flow.sh`. Record the test count and the results in the "Validation results" note.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies. T002 gates T009, T011 and T024.
- **Foundational (Phase 2)**: after Setup; blocks every user story.
- **US1 (Phase 3)**: after Foundational. MVP.
- **US2 (Phase 4)**: after Foundational. T016 needs T013 from US1, because the controller must pass the request's settings for the capture to be meaningful.
- **US3 (Phase 5)**: after Foundational. T022 adds constraints to the fields from T012 (US1).
- **US4 (Phase 6)**: T027 needs T022 (US3); T028 needs a running build with T015 (US1).
- **Polish (Phase 7)**: after the stories you ship.

### Within phases

- Foundational order: T003 → T004 → T005, T006, T007 → T008.
- Tests are written first and fail before their implementation: T009 and T010 before T011–T013; T018 and T019 before T020–T024.
- Files touched by several tasks must be done in order:
  - `chat/impl/SpringAiModelService.java`: T005 → T011
  - `chat/ChatController.java`: T006 → T013 → T023
  - `chat/dto/ChatRequest.java`: T012 → T022
  - `test/ChatControllerSettingsTest.java`: T010 → T016 → T018 → T019 → T027
  - `quickstart.md` notes: T028 → T030 → T031

### Parallel opportunities

- T009 and T010 together; T014 alongside them.
- T020 and T025 together, alongside T018 and T019 (T018 and T019 share a file, so they run in sequence with each other).
- T029 at any time after US3.

### Parallel example: User Story 1

```text
Together: T009 (options mapping test), T010 (controller capture test), T014 (integration script)
Then:     T011 (service) ; T012 (DTO) → T013 (controller)
Finally:  T015 (verify)
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 → Phase 2 (compiles, no behavior change).
2. Phase 3 (US1). Stop and validate with quickstart step 2. Experiments can already run.
3. Until US3 is done, out-of-range values are **not** rejected: they go to the model, and `topK: 3.5` fails with 500 from `intValueExact()`. Do not ship the MVP without US3 if anyone other than the developer uses it.

### Incremental delivery

1. US2: confirm backward compatibility.
2. US3: validation and the 400 error format.
3. US4: seed reproducibility check.
4. Polish: README, concurrency check, full quickstart run.

## Notes

- Seed reproducibility depends on the model (research R8). T028 measures it and is not asserted in automated tests, to avoid flaky runs.
- Commit after each phase or logical group.
