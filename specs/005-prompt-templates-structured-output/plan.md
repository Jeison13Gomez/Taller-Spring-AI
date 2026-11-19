# Implementation Plan: Prompt Templates and Structured Output

**Branch**: `005-prompt-templates-structured-output` | **Date**: 2026-10-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-prompt-templates-structured-output/spec.md`

## Summary

Two additions to the backend:

1. **Prompt templates.** The system prompt moves out of `SpringAiModelService` into files under `backend/src/main/resources/prompts/` (`default`, `concise`, `tutor`, `extractor`), rendered with StringTemplate using `<` and `>` as delimiters so templates may contain JSON. Variable values come only from `application.properties`. The chat request gets an optional `templateId`; absent means `default` (the current prompt, word for word); an unknown id is a 400 with the list of valid ids. All templates are rendered once at startup, so a broken template stops the application with its name.
2. **Structured classification.** `POST /api/v1/classify`, a method of `ChatController`, returns `category`, `confidence` and `justification` using one of three strategies: `instruction`, `validate-retry`, `native-schema`. The response reports the strategy, whether there was a retry and how many, the usage summed over attempts and the elapsed time. A format failure becomes a `StructuredOutputException` handled by `GlobalExceptionHandler` as a 502 with the failed check and the cost of the failed attempts.

The APIs the request asked about were checked with `javap` and a probe ([research.md](research.md)). `entity()`/`responseEntity()` with `validateSchema()` and `useProviderStructuredOutput()` exist, but `validateSchema()` uses a fixed limit of 3 retries and exposes neither the retry count nor the earlier attempts' tokens. So all three strategies share one attempt loop of our own built on `call().chatResponse()`, which keeps usage available for every attempt, including failed ones.

## Technical Context

**Language/Version**: Java 21 (backend). The frontend is not touched.

**Primary Dependencies**: Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-starter-model-ollama`, which brings `spring-ai-template-st` and `BeanOutputConverter`), Jackson 3.1.4. No new dependency.

**Storage**: N/A. Templates are packaged resources; nothing is persisted.

**Testing**: JUnit 5 + `spring-boot-starter-test`, standalone MockMvc as in features 003/004. The attempt loop is tested with a scripted `ChatModel` passed to `ChatClient.create(...)`, so retries, token sums and format failures are deterministic. Shell scripts `scripts/smoke_test.sh` and `tests/integration/test_chat_flow.sh` get new assertions. Gradle commands run from `backend/`.

**Target Platform**: Local Docker Compose demo (backend, Ollama, frontend). Templates travel inside the backend image; no volume is mounted.

**Project Type**: web-application (Spring Boot backend + static HTML frontend)

**Performance Goals**: Invalid requests are rejected in under 1 s without calling the model (SC-009). Template rendering is done at startup to check and once per request to fill variables; it adds no measurable time.

**Constraints**: Chat 200 response shape unchanged; usage metrics of 003 and settings of 004 preserved; the client never sends prompt text or variable values; a template cannot contain a lone `<`; classification text at most 5000 characters; at most 2 retries; logs of the new code carry no input text, template text or model answer.

**Scale/Scope**: One new endpoint, one extended request, 4 template files, one registry class, one classification service (3 strategies), one exception type with one handler.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|-----------|------------|
| Demo-Focused Simplicity | PASS. No new service or dependency. The three strategies are required by the feature purpose (comparison). The shared attempt loop is smaller than three separate code paths and than adding a validation library. Chat-page controls and a template editor over HTTP are out of scope. |
| Reproducible Local Execution | PASS. Works with the existing `docker compose` flow; templates are in the image, so a rebuild is the documented way to change them ([quickstart.md](quickstart.md) step 5). |
| Clear Interfaces & Contracts | PASS. Additive, documented change to the versioned `/api/v1` contract with examples for every status (200, 400, 502) in [contracts/chat-api.md](contracts/chat-api.md); acceptance checks added to the scripts. |
| Data Minimization & Secrets | **NON-COMPLIANT (pre-existing, unchanged)**. `ChatController` still logs the full chat question at INFO and `LogRedactionFilter` still does not redact (see the [003 plan](../003-token-usage-metrics/plan.md) and [004 plan](../004-chat-sampling-params/plan.md)); the fix is deferred to feature 007. The new code (classification, templates, the unknown-id error) does not log texts, template content or answers, and echoes a template id truncated to 40 characters, so it does not add to the problem. |
| Testing & Observability | **NON-COMPLIANT (pre-existing, partially mitigated)**. The full core flow (frontend → backend → model → frontend) still has no automated test coverage, which the constitution requires. This feature adds tests only for its own behavior (registry and startup validation, rendering, attempt loop, controller and handler, script assertions); coverage of the rest of the flow is deferred to feature 007, as in the 003 and 004 plans. |

Two principles are non-compliant because of pre-existing code; see Complexity Tracking.

**Post-design re-check**: unchanged. The design adds no component outside the backend, no storage and no log content beyond ids, strategy names and numbers, and does not worsen either non-compliance.

## Project Structure

### Documentation (this feature)

```text
specs/005-prompt-templates-structured-output/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── chat-api.md
└── tasks.md             # created later by /speckit-tasks
```

### Source Code (repository root)

```text
backend/src/main/
├── resources/
│   ├── application.properties           # + app.prompts.variables.* (max_sentences, audience)
│   └── prompts/
│       ├── default.st                   # the current system prompt, word for word, no variables
│       ├── concise.st                   # uses <max_sentences>
│       ├── tutor.st                     # uses <audience>
│       ├── extractor.st                 # contains a JSON example (needs the < > delimiters)
│       └── internal/
│           └── classification-system.st # fixed classification instructions; NOT a chat template
└── java/com/example/chat/
    ├── ChatController.java              # + templateId check; + POST /classify (inherits @CrossOrigin)
    ├── ModelService.java                # generateResponse(question, sessionId, settings, templateId)
    ├── ClassificationService.java       # new interface: classify(text, strategy, temperature)
    ├── prompt/
    │   ├── PromptTemplateRegistry.java  # loads prompts/*.st, validates at startup, render(id), ids()
    │   └── PromptVariables.java         # @ConfigurationProperties("app.prompts"): Map<String,String> variables
    ├── classification/
    │   ├── Category.java                # enum consulta, queja, incidencia, otro
    │   ├── Classification.java          # record(Category category, Double confidence, String justification)
    │   ├── FormatStrategy.java          # enum: id, maxRetries; fromId(); ids()
    │   └── ClassificationChecks.java    # post-conversion checks: missing fields, confidence range, blank justification
    ├── dto/
    │   ├── ChatRequest.java             # + templateId
    │   ├── ClassifyRequest.java         # text (1..5000), strategy, temperature
    │   ├── ClassifyResponse.java        # NON_NULL fields (see data-model)
    │   └── FailureInfo.java             # record(check, message)
    ├── exception/
    │   ├── StructuredOutputException.java  # failed check, strategy, attempts, retries, elapsed, consumed usage
    │   └── GlobalExceptionHandler.java     # + StructuredOutputException → 502
    └── impl/
        ├── SpringAiModelService.java    # system text from the registry per request; .templateRenderer(< >)
        ├── SpringAiClassificationService.java  # attempt loop for the 3 strategies; sums usage
        └── UsageAggregator.java         # sums Attempt usage (tokens only if all present; tokens/s from summed eval time)

backend/src/test/java/com/example/chat/
├── prompt/PromptTemplateRegistryTest.java   # ids, default equals current prompt, startup failures, JSON in template
├── classification/ClassificationChecksTest.java
├── impl/SpringAiClassificationServiceTest.java  # scripted ChatModel: retries, sums, failures, native options
├── ChatControllerTemplateTest.java          # templateId passthrough, unknown id 400, field order, compat
├── ChatControllerClassifyTest.java          # classify request validation, 200 shape, 502 via handler
└── ChatControllerSettingsTest.java / ChatControllerUsageTest.java  # lambdas updated to the new ModelService signature

scripts/smoke_test.sh                    # + unknown template → 400; + one classification
tests/integration/test_chat_flow.sh      # + concise vs tutor; + three strategies return a valid result
backend/README.md                        # document templates, classify, errors
```

**Structure Decision**: Keep the existing backend layout. New code goes into three small packages (`prompt`, `classification`, plus the existing `dto`/`exception`/`impl`). The endpoint is a method of `ChatController`, as required, so it inherits `@CrossOrigin` and the `/api/v1` mapping. The frontend is untouched.

### Implementation notes

- **Template files**: the id is the file name without `.st` (`[a-z0-9-]+`). The registry scans `classpath:prompts/*.st`, which does not descend into `internal/`. Every template is rendered once at startup with `StTemplateRenderer` (`<`, `>`, `ValidationMode.THROW`) and the configured variables; any exception is rethrown as an `IllegalStateException` naming the template, which stops the application.
- **Chat path**: the registry renders the chosen template to text; `SpringAiModelService` passes it with `.system(text)` and sets `.templateRenderer(<renderer>)` on the request so that braces in the already rendered text are not parsed again. The user question stays unrendered (research R7); a test must prove that questions with `{`, `}` and `<` still return 200.
- **`ModelService` signature**: gains `String templateId`. The lambdas in `ChatControllerUsageTest` and `ChatControllerSettingsTest` change from `(q, s, o)` to `(q, s, o, t)`. `GenerationSettings` stays as it is.
- **Unknown template check**: in `ChatController`, merged with the bean-validation errors so all invalid fields come back together. Field order: `question, templateId, temperature, topP, topK, numPredict, seed`. This extends the order list of feature 004.
- **Classification**: `ClassifyRequest.strategy` is a `String`, checked against `FormatStrategy.ids()`, so an unknown value gives the list of valid ones. `temperature` reuses the same constraint and message as `ChatRequest`. `text` uses `@NotBlank` and `@Size(max = 5000)` with the messages from the contract.
- **Attempt loop**: one method with a `FormatStrategy`. Each attempt builds the prompt, calls `call().chatResponse()`, converts with `BeanOutputConverter<Classification>`, then runs `ClassificationChecks`. Conversion exceptions map to a failed check (`StreamReadException`/empty → `json`; a Jackson data-binding exception → the property name from its path, or `json` if none). `native-schema` adds `outputSchema(converter.getJsonSchema())` to the options builder produced by the feature-004 `toOptionsBuilder`; the other two append `converter.getFormat()`.
- **Retry prompt**: the next attempt appends the failed check text to the user message, as the Spring advisor does. There is no chat history.
- **Usage**: `toUsageMetrics` from feature 003 is extended to expose the generation duration so `UsageAggregator` can sum exactly (research R10).
- **Exception handling**: `StructuredOutputException` is thrown by the service when attempts are exhausted. The new handler builds the 502 body. `ClassifyResponse` for validation errors is built in the controller and is a 400.
- **Logging**: only template id (truncated), strategy, attempts, retries, failed check name and numbers.
- **To verify while implementing** (marked in research): `format` honored by `llama3.2:3b`; user text not rendered with the angle renderer; behavior of the scripted `ChatModel` in tests.

## Complexity Tracking

> Both entries are violations in code that exists before this feature. Same justification as in the [003 plan](../003-token-usage-metrics/plan.md) and the [004 plan](../004-chat-sampling-params/plan.md): correcting them is outside the scope of this feature.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Data Minimization: full chat question logged in `ChatController`; `LogRedactionFilter` does not redact | Pre-existing; this feature adds no logging of texts. Fixed in feature 007. | Fixing it here would mix an unrelated privacy change into this feature. |
| Testing & Observability: no automated coverage of the full core flow (frontend → backend → model → frontend) | Pre-existing; this feature adds tests only for its own behavior. Broader coverage is fixed in feature 007. | Backfilling tests for the whole chat flow here would exceed this feature's scope. |
