---

description: "Task list for Prompt Templates and Structured Output (feature 005)"
---

# Tasks: Prompt Templates and Structured Output

**Input**: Design documents from `/specs/005-prompt-templates-structured-output/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/chat-api.md](contracts/chat-api.md), [quickstart.md](quickstart.md)

**Tests**: Included, because plan.md commits to unit and controller tests for the new behavior (Constitution: Testing & Observability). Tests for the rest of the flow are out of scope (feature 007).

**Organization**: Tasks are grouped by user story so each can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US6 from spec.md
- Paths are relative to the repository root. Abbreviations: `chat/` = `backend/src/main/java/com/example/chat/`, `test/` = `backend/src/test/java/com/example/chat/`, `res/` = `backend/src/main/resources/`.

## Conventions for this feature

- Gradle está en el PATH; `JAVA_HOME` apunta al JDK 21. Run `gradle test` / `gradle build` from `backend/`. Rebuild the stack with `docker compose -f docker/docker-compose.yml up -d --build`.
- The IDE shows false "package does not exist" errors on these files because it lacks the Gradle classpath; trust `gradle build`.
- Never import both `org.springframework.ai.chat.model.ChatResponse` and the DTO `com.example.chat.dto.ChatResponse` in one file; use the fully qualified Spring AI name.
- Request field names: `templateId` (chat); `text`, `strategy`, `temperature` (classify). Strategy ids: `instruction` (default), `validate-retry`, `native-schema`. Categories: `consulta`, `queja`, `incidencia`, `otro`. Failed-check ids: `json`, `category`, `confidence`, `justification`.
- Error messages are exact (see [contracts/chat-api.md](contracts/chat-api.md)):
  - `unknown template '<id>'; available: concise, default, extractor, tutor` (ids sorted; `<id>` cut to 40 characters plus `…`)
  - `unknown strategy '<value>'; available: instruction, validate-retry, native-schema`
  - `text must be provided`, `text must be at most 5000 characters`
  - `temperature must be between 0.0 and 2.0` (existing)
- Field order inside `errors`: chat = `question, templateId, temperature, topP, topK, numPredict, seed`; classify = `text, strategy, temperature`. One entry per field.
- Templates: files `res/prompts/<id>.st`, id `[a-z0-9-]+`, delimiters `<` and `>` (strict mode: a variable without a value, a malformed expression or a lone `<` is an error). Literal `{` and `}` are allowed. Variable values come only from `app.prompts.variables.*`; the client never sends them.
- The chat 200 response gains no field and never says which template was used.
- Logs written by new code carry only template id (cut to 40 characters), strategy, attempt and retry counts, failed-check name and numbers. Never log the text, the question, a template body or a model answer. Do not change the existing line that logs the chat question (feature 007).
- Tests that need the model use a scripted `ChatModel` (`test/support/ScriptedChatModel.java`, created in T021) passed to `ChatClient.builder(chatModel)`; never call Ollama from unit tests.
- Do not touch the frontend.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Confirm the baseline and the one runtime assumption still to verify.

- [X] T001 Run `gradle test` in `backend/` and confirm the existing 36 tests pass; run `bash scripts/smoke_test.sh` against the running stack and confirm it passes. Record any failure before changing code.
- [X] T002 Verify that `llama3.2:3b` honors Ollama's `format` with the classification schema (research R3): with `curl` against `http://localhost:11434/api/chat` (`stream: false`, `format` = the schema text from research R4, a short complaint as the user message) check that the answer is JSON with the four allowed categories. Write the result (and a short example answer) in a "Verified in implementation" note at the end of `specs/005-prompt-templates-structured-output/research.md`. If `format` is not honored, record it; the `native-schema` strategy is still built and its failures are reported as format failures.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Carry a `templateId` value (always `null` for now) from the request to `ModelService`, and create the strategy enum, so the project compiles and behavior is unchanged. No user story starts before this is done.

- [X] T003 [P] Add `private String templateId` with getter and setter to `chat/dto/ChatRequest.java`, in the existing one-line style. No constraints yet.
- [X] T004 [P] Create `chat/classification/FormatStrategy.java`: `public enum FormatStrategy` with constants `INSTRUCTION("instruction", 0)`, `VALIDATE_RETRY("validate-retry", 2)`, `NATIVE_SCHEMA("native-schema", 0)`, fields `id` and `maxRetries` with getters, `static Optional<FormatStrategy> fromId(String)` (exact match) and `static List<String> ids()` in declaration order. Short Spanish Javadoc in the style of `chat/GenerationSettings.java`.
- [X] T005 Change `chat/ModelService.java` to `ModelResult generateResponse(String question, String sessionId, GenerationSettings settings, String templateId)` and update its Javadoc (`templateId` null means the default template).
- [X] T006 Update `chat/impl/SpringAiModelService.java` to the new signature, ignoring `templateId` for now (no behavior change). Depends on T005.
- [X] T007 In `chat/ChatController.java`, call `modelService.generateResponse(req.getQuestion(), req.getSessionId(), settings, req.getTemplateId())`. Depends on T003, T005.
- [X] T008 Update every `ModelService` lambda and the `Capturing` stub in `test/ChatControllerUsageTest.java` and `test/ChatControllerSettingsTest.java` to the four-argument form `(q, s, o, t)`. Depends on T005.
- [X] T009 Run `gradle build` in `backend/`; all 36 existing tests pass. Depends on T003–T008.

**Checkpoint**: Backend compiles; API behavior is identical to before.

---

## Phase 3: User Story 1 - Choose a chat personality by template identifier (Priority: P1) 🎯 MVP

**Goal**: A chat request can name a registered template by identifier; the answer follows that template; an unknown identifier is a 400 listing the valid ones; the client never sends prompt text.

**Independent Test**: Send the same question with `templateId` `concise` and `tutor`: both return 200 and the concise answer is clearly shorter; `templateId: "pirata"` returns 400 with the list of valid ids ([quickstart.md](quickstart.md) step 2).

### Tests for User Story 1

- [X] T010 [P] [US1] Create `test/support/TestPrompts.java` (a static `registry()` that builds a `PromptTemplateRegistry` over `classpath:prompts/*.st` with variables `max_sentences=3` and `audience=estudiante`) and `test/prompt/PromptTemplateRegistryTest.java`:
  - (a) `ids()` is exactly `concise, default, extractor, tutor`, sorted;
  - (b) the rendered `default` text equals the current system prompt: `"Eres un asistente conciso y directo.\nResponde siempre en el mismo idioma en el que te escriba el usuario.\nSi no sabes algo, dilo en lugar de inventar.\n"`;
  - (c) `concise` and `tutor` contain the variable values after rendering and no `<` placeholder;
  - (d) `extractor` renders with its JSON braces intact (a `{` and a `}` survive);
  - (e) `contains("pirata")` is false and `render("pirata")` throws `IllegalArgumentException`;
  - (f) `abbreviate("x".repeat(100))` is 40 characters followed by `…`, and a short id is returned unchanged.

  Must fail until T014.
- [X] T011 [P] [US1] Create `test/ChatControllerTemplateTest.java` (standalone MockMvc with `GlobalExceptionHandler`, a stub `ModelService` that captures the `templateId` it receives and counts calls, and `TestPrompts.registry()`):
  - (a) `{"question":"hola","templateId":"concise"}` → 200 and captured id `concise`;
  - (b) `"templateId":"pirata"` → 400, `status: "error"`, `usage: null`, exactly one error with field `templateId` and message `unknown template 'pirata'; available: concise, default, extractor, tutor`, and the stub was not called;
  - (c) a 100-character identifier → the message repeats only the first 40 characters followed by `…`;
  - (d) `Concise` and `concise ` (trailing space) → 400;
  - (e) a full sentence of instructions as `templateId` → 400 and the stub was not called;
  - (f) `templateId: "pirata"` with `temperature: 3` → two errors, `templateId` first, then `temperature`.

  Must fail until T016.

### Implementation for User Story 1

- [X] T012 [P] [US1] Create the template files in `res/prompts/`:
  - `default.st`: the current text block of `SYSTEM_PROMPT` in `chat/impl/SpringAiModelService.java`, word for word with its final newline and no variables;
  - `concise.st`: a short Spanish prompt that limits answers to `<max_sentences>` sentences;
  - `tutor.st`: a prompt for an explanatory tutor addressed to `<audience>`;
  - `extractor.st`: a prompt that asks for data extraction and includes a JSON example with literal braces.

  Add `app.prompts.variables.max_sentences=2` and `app.prompts.variables.audience=estudiante de secundaria` to `res/application.properties` with a short Spanish comment in the style of that file. Use no literal `<` outside variables.
- [X] T013 [P] [US1] Create `chat/prompt/PromptVariables.java`: `@ConfigurationProperties(prefix = "app.prompts")` class with `Map<String, String> variables` (getter/setter, default empty map). Add `@ConfigurationPropertiesScan` to `chat/Application.java`.
- [X] T014 [US1] Create `chat/prompt/PromptTemplateRegistry.java`, a `@Component`:
  - a public constructor `(PromptVariables)` that delegates to a package-private constructor `(String locationPattern, Map<String,String> variables)` (the pattern is `classpath:prompts/*.st` in production);
  - the constructor loads each file with `PathMatchingResourcePatternResolver` into a `TreeMap` keyed by the file name without `.st`;
  - `Set<String> ids()` (sorted), `boolean contains(String id)` (exact match), `String render(String id)` (throws `IllegalArgumentException` for an unknown id) and `static String abbreviate(String id)` (first 40 characters plus `…` when longer);
  - rendering uses `StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').validationMode(ValidationMode.THROW).build()`.

  Depends on T013 and T012.
- [X] T015 [US1] In `chat/impl/SpringAiModelService.java`: inject the registry; remove `defaultSystem(SYSTEM_PROMPT)` and the constant; per request resolve `templateId` (null or `""` → `default`), render it, and call `.system(text)` with `.templateRenderer(<the same < > renderer>)` on the request spec so braces in the rendered text are not parsed again. The user question stays unrendered. Keep the debug log free of any text. Depends on T006, T014.
- [X] T016 [US1] In `chat/ChatController.java`: inject `PromptTemplateRegistry` (constructor); when `templateId` is non-null and not `""` and `!registry.contains(id)`, add an error `templateId` with the exact message from Conventions; merge it with the bean-validation errors; sort the `errors` entries by the chat field order from Conventions (`question, templateId, temperature, topP, topK, numPredict, seed`, replacing `FIELD_ORDER`); on error return 400 before calling the model. Log `Chat template sessionId=… templateId=<abbreviated>` at INFO (no question text). Depends on T007, T014.
- [X] T017 [US1] Update the `new ChatController(...)` calls in `test/ChatControllerUsageTest.java` and `test/ChatControllerSettingsTest.java` to pass `TestPrompts.registry()`. Depends on T010, T016.
- [X] T018 [P] [US1] Extend `scripts/smoke_test.sh` to POST `{"question":"hi","templateId":"pirata"}` and assert HTTP 400 and `"field":"templateId"`; extend `tests/integration/test_chat_flow.sh` to send one question with `templateId` `concise` and one with `tutor` and assert both return an `answer` and a `usage` object.
- [X] T019 [US1] Run `gradle test` (T010 and T011 pass), rebuild the stack, and walk through [quickstart.md](quickstart.md) step 2 and `bash scripts/smoke_test.sh`. Depends on T010–T018.

**Checkpoint**: User Story 1 works on its own (MVP). Clients can choose a template by identifier.

---

## Phase 4: User Story 2 - Existing chat clients and usage metrics keep working (Priority: P1)

**Goal**: Requests without `templateId` behave exactly as before; usage metrics and generation settings keep working with and without a template.

**Independent Test**: Requests in the current format return the same shape and the same system behavior ([quickstart.md](quickstart.md) step 1), including questions that contain `{`, `}` and `<`.

- [X] T020 [P] [US2] Add tests to `test/ChatControllerTemplateTest.java`:
  - the legacy body `{"question":"hola","sessionId":"t"}` → 200, captured `templateId` null, the response has exactly the keys `requestId, answer, elapsedMs, status, usage`;
  - `"templateId":null` and `"templateId":""` → captured as not sent and 200;
  - `templateId` `tutor` with `temperature` 0.0 and `numPredict` 5 → 200, and the model receives both the id and the settings;
  - the 200 response for a request with `templateId` has the same key set as the legacy one (no `templateId`, no `errors`).

  Depends on T016.
- [X] T021 [P] [US2] Create `test/support/ScriptedChatModel.java` (a `ChatModel` that returns a queue of scripted answers, each with prompt/completion tokens, model name and an `eval-duration` like `test/impl/SpringAiModelServiceUsageTest.java`, and records every `Prompt` it receives) and `test/impl/SpringAiModelServiceTemplateTest.java`, building `SpringAiModelService` over `ChatClient.builder(scriptedModel)` and `TestPrompts.registry()`:
  - (a) without `templateId`, the system message of the captured prompt equals the `default` text and the user message equals the question verbatim;
  - (b) a question containing `{x}`, `{"a": 1}`, `a < b` and `<tag>` is sent verbatim as the user message and does not fail;
  - (c) with `templateId` `concise` the system message equals the rendered `concise` text (differs from `default`);
  - (d) a `GenerationSettings` temperature of 0.0 still reaches the model options together with a template;
  - (e) the usage metrics of the result are still filled (feature 003).

  Depends on T015. Must pass; (a) and (b) are the backward-compatibility proof.
- [X] T022 [US2] Run `gradle test`, rebuild the stack and walk through [quickstart.md](quickstart.md) step 1; run `bash scripts/smoke_test.sh` and `bash tests/integration/test_chat_flow.sh` and confirm the original assertions still pass unchanged. Depends on T020, T021.

**Checkpoint**: User Stories 1 and 2 both work.

---

## Phase 5: User Story 3 - Classify a text into a structured result (Priority: P2)

**Goal**: `POST /api/v1/classify` returns `category`, `confidence`, `justification` plus usage and elapsed time, using the default strategy and an optional temperature.

**Independent Test**: Send a clearly classifiable text; the response has a valid category, a confidence in 0.0–1.0, a justification, `strategy: "instruction"`, `retried: false`, `retries: 0`, `attempts: 1`, `usage` and `elapsedMs` ([quickstart.md](quickstart.md) step 3, first command).

### Tests for User Story 3

- [X] T023 [P] [US3] Create `test/classification/ClassificationChecksTest.java` for `ClassificationChecks`:
  - a valid `Classification` passes;
  - each of these fails with the check id named: missing category (`category`), missing confidence (`confidence`), confidence 1.5, -0.1 and NaN (`confidence`), missing or blank justification (`justification`);
  - a conversion exception mapping: a Jackson `StreamReadException` and an empty-content `MismatchedInputException` map to `json`; an `InvalidFormatException` whose path is `category` maps to `category`, one whose path is `confidence` maps to `confidence`.

  Must fail until T030.
- [X] T024 [P] [US3] Create `test/impl/UsageAggregatorTest.java` for `UsageAggregator` (T032): one attempt gives the same values as `toUsageMetrics`; two attempts sum prompt and completion tokens and total; `tokensPerSecond` is total completion tokens over the summed generation time rounded to 2 decimals; if any attempt lacks a token count, that sum is `null` (not a partial sum) and `totalTokens` is `null`; if any attempt lacks the generation time, `tokensPerSecond` is `null`; model and finish reason come from the last attempt. Must fail until T032.
- [X] T025 [P] [US3] Create `test/impl/SpringAiClassificationServiceTest.java` using `ScriptedChatModel` and `ChatClient.builder(...)`:
  - (a) valid JSON answer → result equals the scripted classification, `attempts` 1, `retries` 0, `retried` false, `strategy` `instruction`, usage equals the attempt usage;
  - (b) the captured prompt's user message contains the text and the format instruction (`converter.getFormat()`), and the system message is the classification instructions;
  - (c) `temperature` 0.0 reaches the model options as 0.0, and `null` leaves the options untouched;
  - (d) a ```` ```json ```` fenced answer is accepted;
  - (e) an invalid answer with `instruction` throws `StructuredOutputException` after exactly one model call.

  Must fail until T034.
- [X] T026 [P] [US3] Create `test/ChatControllerClassifyTest.java` (standalone MockMvc with `GlobalExceptionHandler`, `TestPrompts.registry()`, and a stub `ClassificationService`):
  - a valid request → 200 with `status`, `result.category`, `result.confidence`, `result.justification`, `strategy`, `retried`, `retries`, `attempts`, `elapsedMs`, `usage`;
  - `temperature: 0.0` is passed to the service as 0.0, not as null;
  - blank and missing `text` → 400 with `text must be provided`; 5001 characters → 400 with `text must be at most 5000 characters`; exactly 5000 characters → 200;
  - `temperature: 2.5` → 400 naming `temperature`; `"temperature":"hot"` → 400 naming `temperature`;
  - `text` blank and `temperature` 2.5 together → two errors in the order `text`, `temperature`;
  - the service is not called in any 400 case;
  - the response of a 400 has `status: "error"` and no `result`.

  Must fail until T035.

### Implementation for User Story 3

- [X] T027 [P] [US3] Create `chat/classification/Category.java` (enum `consulta, queja, incidencia, otro`) and `chat/classification/Classification.java` (`record Classification(Category category, Double confidence, String justification)`), with short Spanish Javadoc.
- [X] T028 [P] [US3] Create `chat/dto/ClassifyRequest.java` (`text` with `@NotBlank(message = "text must be provided")` and `@Size(max = 5000, message = "text must be at most 5000 characters")`; `temperature` with the same `@DecimalMin`/`@DecimalMax` and message as `ChatRequest`), `chat/dto/FailureInfo.java` (`record FailureInfo(String check, String message)`) and `chat/dto/ClassifyResponse.java` (fields `requestId`, `status`, `result`, `strategy`, `retried`, `retries`, `attempts`, `elapsedMs`, `usage`, `failure`, `consumed`, `errors`; class-level `@JsonInclude(JsonInclude.Include.NON_NULL)` so absent fields are omitted; getters and setters in the existing style). `ClassifyRequest.strategy` comes in US4.
- [X] T029 [P] [US3] Create `chat/exception/StructuredOutputException.java` (unchecked): fields `check`, `strategy`, `attempts`, `retries`, `elapsedMs` and `UsageMetrics consumed`, constructor and getters; its message names the check and the attempts (no model text).
- [X] T030 [US3] Create `chat/classification/ClassificationChecks.java`: a static `Optional<FailureInfo> check(Classification)` (missing fields, confidence outside 0.0–1.0 or NaN, blank justification, with messages such as `confidence must be a number between 0.0 and 1.0`) and a static `FailureInfo fromConversionError(Throwable)` implementing the mapping from T023 (path property name of a Jackson `DatabindException` → that check if it is one of `category`, `confidence`, `justification`; anything else → `json`). Depends on T027, T029.
- [X] T031 [P] [US3] Create `res/prompts/internal/classification-system.st`: fixed Spanish instructions that ask the model to classify the user's text into `consulta`, `queja`, `incidencia` or `otro`, give a `confidence` between 0.0 and 1.0 and a short `justification`, and answer only with the JSON. No variables and no lone `<`. It is in `internal/` so it is not a chat template.
- [X] T032 [US3] In `chat/impl/`, create `AttemptUsage.java` (a record with prompt tokens, completion tokens, model, finish reason and generation duration, plus a static `from(org.springframework.ai.chat.model.ChatResponse)` using the rules already in `toUsageMetrics`: a count `<= 0` is not available) and `UsageAggregator.java` (static `UsageMetrics sum(List<AttemptUsage>)` following the rules tested in T024). Refactor `SpringAiModelService.toUsageMetrics` to use them and keep its behavior: the existing `SpringAiModelServiceUsageTest` and `UsageMetricsMissingValuesTest` pass unchanged. Depends on T015.
- [X] T033 [US3] Create `chat/ClassificationService.java`: `interface ClassificationService { ClassifyResponse classify(String text, FormatStrategy strategy, Double temperature); }` with a short Javadoc (throws `StructuredOutputException` when the format is never met). Depends on T004, T028.
- [X] T034 [US3] Create `chat/impl/SpringAiClassificationService.java` (`@Service`) with the attempt loop for `instruction`: build a `BeanOutputConverter<Classification>`; render `classification-system.st` as the system message (read the file from `res/prompts/internal/` with the `< >` renderer); the user message is the text followed by `converter.getFormat()`; apply `temperature` through `SpringAiModelService.toOptionsBuilder` with a `GenerationSettings` holding only the temperature; call `call().chatResponse()`; read the answer text; convert and run `ClassificationChecks`; collect an `AttemptUsage` per call; on success return a `ClassifyResponse` with `status: "ok"`, `strategy`, `retried` false, `retries` 0, `attempts` 1, `elapsedMs` and `UsageAggregator.sum(...)`; on failure throw `StructuredOutputException` carrying the check, attempts, elapsed time and the summed usage. Log only strategy, attempts and the failed check name. Depends on T030, T031, T032, T033.
- [X] T035 [US3] In `chat/ChatController.java`: inject `ClassificationService` (constructor); add `@PostMapping("/classify")` taking `@Valid @RequestBody ClassifyRequest` and `BindingResult`; on errors return 400 with a `ClassifyResponse` (`requestId`, `status: "error"`, `errors` ordered `text, strategy, temperature`, one per field) using the same per-field helper as the chat; otherwise call the service with `FormatStrategy.INSTRUCTION` and return 200. Log numbers only (`text length`, not the text). Depends on T028, T033.
- [X] T036 [US3] Update the `new ChatController(...)` calls in `test/ChatControllerUsageTest.java`, `test/ChatControllerSettingsTest.java` and `test/ChatControllerTemplateTest.java` to pass a `ClassificationService` stub as the third argument. Depends on T035.
- [X] T037 [P] [US3] Extend `scripts/smoke_test.sh` to POST `{"text":"My invoice has the wrong amount, I want a refund","temperature":0.0}` to `/api/v1/classify` and assert the response contains `"result"`, `"category"`, `"confidence"`, `"justification"`, `"strategy":"instruction"` and `"attempts":1`.
- [X] T038 [US3] Run `gradle test` (T023–T026 pass), rebuild the stack and walk through [quickstart.md](quickstart.md) step 3 (first command and the omitted-strategy command) and `bash scripts/smoke_test.sh`. Depends on T023–T037.

**Checkpoint**: User Stories 1–3 work independently. Classification works with the default strategy.

---

## Phase 6: User Story 4 - Compare format strategies (Priority: P2)

**Goal**: The classification request can choose `instruction`, `validate-retry` or `native-schema`; the response says which was used, whether a retry happened and how many, and sums tokens and time over all attempts.

**Independent Test**: Send the same text with each strategy; each response names its strategy and reports `retried`/`retries`/`attempts`, `usage` and `elapsedMs`; `instruction` and `native-schema` always report 0 retries ([quickstart.md](quickstart.md) step 3).

### Tests for User Story 4

- [X] T039 [P] [US4] Add tests to `test/impl/SpringAiClassificationServiceTest.java`:
  - `validate-retry`: invalid first answer then valid → `retried` true, `retries` 1, `attempts` 2, result is the valid one, `usage` tokens equal the sum of both scripted attempts, and the second prompt's user message contains the name of the failed check;
  - `validate-retry` with a valid first answer → `retried` false, `attempts` 1;
  - `validate-retry` with three invalid answers → `StructuredOutputException` with `attempts` 3 and `retries` 2, after exactly three model calls (never a fourth);
  - `instruction` with an invalid answer → one call only;
  - `native-schema`: the captured options carry an output schema listing `consulta`, `queja`, `incidencia`, `otro` (compare with `OllamaChatOptions#getOutputSchema`), the user message has **no** format instruction, `retries` 0, and an invalid answer is still rejected after exactly one call;
  - a temperature of 0.0 is applied in every strategy.

  Must fail until T043.
- [X] T040 [P] [US4] Add tests to `test/ChatControllerClassifyTest.java`:
  - each of the three ids is accepted and passed to the stub service as the matching `FormatStrategy`;
  - an omitted strategy → the service receives `INSTRUCTION`;
  - `"strategy":"magic"` → 400 with `unknown strategy 'magic'; available: instruction, validate-retry, native-schema` and the service is not called;
  - `text` blank, `strategy` unknown and `temperature` 2.5 together → three errors in the order `text`, `strategy`, `temperature`.

  Must fail until T042.

### Implementation for User Story 4

- [X] T041 [US4] Add `private String strategy` with getter and setter to `chat/dto/ClassifyRequest.java` (optional; `null` or `""` means not sent).
- [X] T042 [US4] In `chat/ChatController.java` `classify`: when `strategy` is non-empty and `FormatStrategy.fromId(strategy)` is empty add the error `strategy` with the exact message from Conventions (the value cut to 40 characters plus `…`), merge it with the bean-validation errors in the classify order, and pass the resolved `FormatStrategy` (default `INSTRUCTION`) to the service. Depends on T035, T041.
- [X] T043 [US4] In `chat/impl/SpringAiClassificationService.java`: make the attempt loop run up to `strategy.getMaxRetries() + 1` attempts; for `VALIDATE_RETRY` append the failed check and its message to the user message of the next attempt; for `NATIVE_SCHEMA` add `outputSchema(converter.getJsonSchema())` to the options builder (create a builder when there is no temperature) and omit the format instruction from the prompt; set `retried`, `retries` and `attempts` from the attempts made; sum the usage of every attempt; throw `StructuredOutputException` with the totals when the last attempt still fails. Depends on T034, T041.
- [X] T044 [P] [US4] Extend `tests/integration/test_chat_flow.sh` to classify one text with each of the three strategies and assert each response contains `"result"`, the matching `"strategy"` and `"attempts"`, and that `instruction` and `native-schema` report `"retries":0`. A 502 from a real model is reported as a failure of that strategy with its `failure` field, not as a script error. Until T048 (the `StructuredOutputException` handler) is done, a format failure comes back as HTTP 500 without a `failure` field; run T048 before using this script against the real model, or treat a 500 as a format failure for now.
- [X] T045 [US4] Run `gradle test` (T039 and T040 pass), rebuild the stack and walk through [quickstart.md](quickstart.md) step 3 with all three strategies and `bash tests/integration/test_chat_flow.sh`. Until T048 is done, a format failure appears as HTTP 500: run T048 before this task, or accept the 500 for any strategy that fails during the walk-through. Depends on T039–T044.

**Checkpoint**: User Stories 1–4 work independently. The strategies can be compared.

---

## Phase 7: User Story 5 - Clear error when the model does not meet the format (Priority: P3)

**Goal**: A format failure is a clear gateway error (502) that names the failed check, the strategy, the attempts and the cost of the failed attempts; a failure of the model service itself stays distinguishable.

**Independent Test**: With a scripted model that never gives a valid answer, each strategy returns 502 with `failure.check`, `strategy`, `attempts` and `consumed` ([quickstart.md](quickstart.md) step 4).

### Tests for User Story 5

- [X] T046 [P] [US5] Add tests to `test/ChatControllerClassifyTest.java` where the stub service throws a `StructuredOutputException` (check `category`, strategy `validate-retry`, 3 attempts, `consumed` with 810/120/930 tokens): assert HTTP 502 (not 500), `status: "error"`, `failure.check` `category`, a non-empty `failure.message`, `strategy`, `retried` true, `retries` 2, `attempts` 3, `elapsedMs`, `consumed.totalTokens` 930, and **no** `result` or `usage`. A second case with `consumed` token figures null → they are `null` in the body, not 0. A third case: the stub throws a plain `RuntimeException("boom")` → HTTP 500 with the existing body and **no** `failure` object. Must fail until T048.
- [X] T047 [P] [US5] Add tests to `test/impl/SpringAiClassificationServiceTest.java`: scripted answers that fail each check in turn — not JSON (`json`), empty (`json`), unknown category and `Queja` (`category`), missing or out-of-range confidence (`confidence`), blank justification (`justification`) — each throws a `StructuredOutputException` with that check; the exception's `consumed` equals the sum of the failed attempts; `elapsedMs` is non-negative; and a `ChatModel` that throws (service failure) propagates as is, not wrapped in `StructuredOutputException`. Depends on T043.

### Implementation for User Story 5

- [X] T048 [US5] In `chat/exception/GlobalExceptionHandler.java` add `@ExceptionHandler(StructuredOutputException.class)` returning HTTP 502 with a `ClassifyResponse` (`requestId` new UUID, `status: "error"`, `strategy`, `retried`, `retries`, `attempts`, `elapsedMs`, `failure`, `consumed`) and logging at WARN only strategy, attempts and the failed check name. Spring picks it before the existing `RuntimeException` handler; add no branch to that one. Depends on T029.
- [X] T049 [US5] Run `gradle test` (T046 and T047 pass), rebuild the stack, and check a service failure by hand: `docker stop pruebachat_ollama`, send a classify request, expect the existing error body (no `failure` object), then `docker start pruebachat_ollama`. Depends on T046–T048.

**Checkpoint**: User Stories 1–5 work independently.

---

## Phase 8: User Story 6 - Edit templates without touching code (Priority: P3)

**Goal**: A maintainer edits or adds a template file, rebuilds the backend image and sees the change; broken templates stop the application at startup, naming the template.

**Independent Test**: Edit `concise.st`, rebuild, see the new wording; add `pirata.st`, rebuild, the id is accepted and listed; break a template, rebuild, the backend fails to start naming it ([quickstart.md](quickstart.md) step 5).

- [X] T050 [P] [US6] Add startup-failure tests to `test/prompt/PromptTemplateRegistryTest.java` using temporary directories created with JUnit's `@TempDir` and the package-private constructor `(locationPattern, variables)`:
  - no `default.st` → `IllegalStateException` whose message names `default`;
  - a variable without a value (`<unknown_variable>`) → the message names the template file and the variable;
  - a template with a lone `<` (`a < b`) → the message names the template file;
  - an id that is not `[a-z0-9-]+` (for example `Tutor.st` or `my template.st`) → the message names it;
  - two files whose ids collide → the message names the id;
  - a template with a JSON example and valid variables loads.

  Must fail until T051.
- [X] T051 [US6] In `chat/prompt/PromptTemplateRegistry.java` add the startup validation to the constructor: `default` must exist; every id must match `[a-z0-9-]+` and be unique; every template is rendered once in strict mode with the configured variables; any failure is rethrown as `IllegalStateException` whose message names the template and the cause, which stops the application. Depends on T014, T050.
- [X] T058 [P] [US6] Create `test/prompt/TemplateDelimitersTest.java`, the reproducible evidence of the delimiter failure (research R6). It uses `StTemplateRenderer` directly, without the registry, with a template that holds a JSON example and one variable, for example `Responde solo con este JSON: {"category": "otro", "confidence": 0.5} para {tema}` (and the same text with `<tema>` for the angle-bracket renderer). Use `assertThrows(IllegalArgumentException.class, ...)` and compare `getMessage()` for equality with the exact text `The template string is not valid.`:
  - (a) default delimiters `{` `}`: rendering the template with `Map.of("tema", "x")` fails with that exact message;
  - (b) delimiters `<` `>` (`startDelimiterToken('<').endDelimiterToken('>')`): rendering succeeds, the result contains the JSON example unchanged (its `{` and `}` are literal) and the variable value in place of `<tema>`;
  - (c) delimiters `<` `>`: a lone `<` (`si a < b entonces <tema>`) fails with the same exact message.

  It needs no production code, so it passes as soon as it compiles; if a Spring AI upgrade changes the message, this test is where it shows. Depends on T009.
- [X] T052 [US6] Walk through [quickstart.md](quickstart.md) step 5: change the wording of `res/prompts/concise.st` and rebuild only the backend (`docker compose -f docker/docker-compose.yml up -d --build backend`); add `res/prompts/pirata.st` and use it; add `<unknown_variable>` to a template and confirm the backend fails to start with a log line naming it; revert every manual change. Record the elapsed time of the edit-rebuild-verify cycle for SC-003. Depends on T051.

**Checkpoint**: All six user stories work independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [X] T053 [P] Document in `backend/README.md`: the template folder and the rule that a change needs an image rebuild, the variables in `application.properties`, the `templateId` field and its 400, the `/api/v1/classify` endpoint (fields, strategies, categories, retries, 502 format failure and `consumed`), and the limitation that a lone `<` is not allowed in a template. Link to [contracts/chat-api.md](contracts/chat-api.md).
- [X] T054 Measure SC-004: send the same question 20 times with `templateId` `concise` and 20 times with `tutor` (use a question whose answer is not truncated) and record the average `completionTokens` of each and whether concise is at least 30% lower, in a "Validation results" note at the end of [quickstart.md](quickstart.md).
- [X] T055 Measure SC-006 and SC-007: classify the same text 20 times with each of the three strategies at the default temperature; record per strategy the number of successes (HTTP 200) and failures (HTTP 502 with its `failure.check`), the average `usage.totalTokens` (or `consumed.totalTokens` for failures), the average `elapsedMs` and the number of retries; state whether `validate-retry` succeeded at least as often as `instruction`, in the same note.
- [X] T056 Check SC-009 and FR-024: time five rejected requests (unknown template, unknown strategy, bad temperature, empty text, 5001 characters) and confirm each answers in under 1 s; send one classification and one chat with a template, then inspect `docker logs pruebachat_backend` and confirm the classified text, template bodies and model answers do not appear (the existing chat-question line is the known exception, feature 007). Record both in the same note.
- [X] T057 Run the full [quickstart.md](quickstart.md) (steps 1–6): `gradle test`, `bash scripts/smoke_test.sh` and `bash tests/integration/test_chat_flow.sh`. Record the test count and the results in the "Validation results" note.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies. T002 informs US4's `native-schema` expectations.
- **Foundational (Phase 2)**: after Setup; blocks every user story.
- **US1 (Phase 3)**: after Foundational. MVP.
- **US2 (Phase 4)**: after US1 (it checks the template plumbing, and T021 creates the scripted model that later tests reuse).
- **US3 (Phase 5)**: after Foundational; T032 edits `SpringAiModelService.java` after US1's T015, and T035 edits `ChatController.java` after T016. It does not need US2 except for `ScriptedChatModel` (T021).
- **US4 (Phase 6)**: after US3 (it extends the service, the request and the controller of US3).
- **US5 (Phase 7)**: after US3 (needs `StructuredOutputException`); T047 needs the retry loop of US4.
- **US6 (Phase 8)**: after US1 (extends the registry); independent of US3–US5. T058 only needs the compiled project (T009) and can be done at any time after it.
- **Polish (Phase 9)**: after the stories you ship.

### Within phases

- Foundational order: T003, T004 → T005 → T006, T007, T008 → T009.
- Tests are written first and fail before their implementation: T010, T011 before T012–T016; T023–T026 before T027–T035; T039, T040 before T041–T043; T046, T047 before T048; T050 before T051. T058 is evidence of library behavior and passes as soon as it compiles.
- Files touched by several tasks must be done in this order:
  - `chat/impl/SpringAiModelService.java`: T006 → T015 → T032
  - `chat/ChatController.java`: T007 → T016 → T035 → T042
  - `chat/dto/ChatRequest.java`: T003 only
  - `chat/prompt/PromptTemplateRegistry.java`: T014 → T051
  - `chat/impl/SpringAiClassificationService.java`: T034 → T043
  - `chat/dto/ClassifyRequest.java`: T028 → T041
  - `test/ChatControllerTemplateTest.java`: T011 → T020 → T036
  - `test/ChatControllerClassifyTest.java`: T026 → T040 → T046
  - `test/impl/SpringAiClassificationServiceTest.java`: T025 → T039 → T047
  - `test/prompt/PromptTemplateRegistryTest.java`: T010 → T050
  - `scripts/smoke_test.sh`: T018 → T037; `tests/integration/test_chat_flow.sh`: T018 → T044
  - `quickstart.md` notes: T054 → T055 → T056 → T057

### Parallel opportunities

- T003 and T004 together.
- T010 and T011 together; T012 and T013 together; T018 alongside them.
- T020 and T021 together.
- T023, T024, T025 and T026 together; T027, T028, T029 and T031 together; T037 alongside.
- T039 and T040 together.
- T046 and T047 together.
- T053 any time after US4.
- T058 any time after T009, alongside T050.

### Parallel example: User Story 3

```text
Together: T023 (checks test), T024 (aggregator test), T025 (service test), T026 (controller test)
Together: T027 (Category/Classification), T028 (DTOs), T029 (exception), T031 (instructions file)
Then:     T030 (checks) ; T032 (usage) → T033 (interface) → T034 (service) → T035 (endpoint) → T036 (test constructors)
Finally:  T037 (script), T038 (verify)
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 → Phase 2 (compiles, no behavior change).
2. Phase 3 (US1). Stop and validate with quickstart step 2. Clients can already choose a template by identifier.
3. Phase 4 (US2) confirms that nothing regressed before moving on to the classifier.

### Incremental delivery

1. US3: classification with the default strategy and an optional temperature; validate.
2. US4: the three strategies and the retry information; validate. This is the purpose of the feature (comparison).
3. US5: the 502 format error; validate. Until it is done, a format failure surfaces as a 500, so do not hand out the classifier before US5.
4. US6: startup validation and the edit workflow.
5. Polish: README, measurements (SC-004, SC-006, SC-007, SC-009), log check and the full quickstart run.

## Notes

- `validateSchema()` and `entity()` are deliberately not used (research R2, R8); do not "simplify" the attempt loop into them, because they lose the retry count and the tokens of earlier attempts.
- A lone `<` in a template is rejected by StringTemplate with `The template string is not valid.` (research R6); template authors must avoid it.
- T002, T021 (user text not rendered) and the real-model measurements are the places where the plan's "to verify" items get settled; record any surprise in `research.md`.
- Commit after each phase or logical group.
