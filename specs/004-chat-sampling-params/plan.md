# Implementation Plan: Per-Request Chat Sampling Parameters

**Branch**: `004-chat-sampling-params` | **Date**: 2026-10-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/004-chat-sampling-params/spec.md`

## Summary

`POST /api/v1/chat` accepts five optional fields: `temperature`, `topP`, `topK`, `numPredict` and `seed`. They are validated with Bean Validation on `ChatRequest`. All out-of-range and non-whole values are reported together in a new `errors` list on the 400 response, and the model is not called. Valid settings become a `GenerationSettings` value. `SpringAiModelService` turns it into an `OllamaChatOptions` builder for that single call. Spring AI's `combineWith` keeps the `application.properties` defaults for every field left null. Requests without the new fields behave exactly as today. A new handler maps unreadable bodies (wrong kind of value, malformed JSON) to 400 instead of the current 500.

## Technical Context

**Language/Version**: Java 21 (backend). The frontend is not touched.

**Primary Dependencies**: Spring Boot 4.1.0 (web, validation), Spring AI 2.0.0 (`spring-ai-starter-model-ollama`; `OllamaChatOptions`, `ChatClient.options(Builder)`)

**Storage**: N/A (settings are not persisted)

**Testing**: JUnit 5 + `spring-boot-starter-test` (MockMvc standalone, as in `ChatControllerUsageTest`); shell scripts `scripts/smoke_test.sh` and `tests/integration/test_chat_flow.sh`

**Target Platform**: Local Docker Compose demo (backend, Ollama, frontend)

**Project Type**: web-application (Spring Boot backend + static HTML frontend)

**Performance Goals**: Invalid requests are rejected in < 1 s without calling the model (SC-006). Valid requests add no measurable overhead, because they only build an options object.

**Constraints**: Backward compatible; no shared mutable state between requests; `0`/`0.0` distinct from "absent"; numPredict ≤ 2048

**Scale/Scope**: One endpoint, one DTO extended, one new internal value type, one new response item type, one service signature change, one new exception handler

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|-----------|------------|
| Demo-Focused Simplicity | PASS. No new services or config. Five optional request fields, validated with the existing Bean Validation setup. UI controls are out of scope. |
| Reproducible Local Execution | PASS. No infrastructure change, and nothing to rebuild or restart between experiments (FR-009). |
| Clear Interfaces & Contracts | PASS. Additive change to the versioned `/api/v1/chat` contract, with examples for 200/400 in [contracts/chat-api.md](contracts/chat-api.md). The script assertions and controller tests cover the new paths. |
| Data Minimization & Secrets | **NON-COMPLIANT (pre-existing, unchanged)**. `ChatController.java:41` still logs the full question at INFO, and `LogRedactionFilter` still does not redact; both are deferred to feature 007 (see 003 plan). This feature logs only the numeric settings, so the problem does not get worse. |
| Testing & Observability | **NON-COMPLIANT (pre-existing, partially mitigated)**. The full core flow (frontend → backend → model → frontend) still has no automated test coverage, which the constitution requires. This feature adds tests only for its own behavior: unit tests (validation, settings → options mapping, defaults kept) and controller tests (400 body, backward compatibility), plus the new script assertions. Coverage of the rest of the flow is deferred to feature 007 (same diagnosis as the [003 plan](../003-token-usage-metrics/plan.md)). |

Two principles are non-compliant because of pre-existing code; see Complexity Tracking.

**Post-design re-check**: unchanged. The design adds no components, storage or log content beyond the numeric settings, and does not worsen either non-compliance.

## Project Structure

### Documentation (this feature)

```text
specs/004-chat-sampling-params/
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
backend/src/main/java/com/example/chat/
├── ChatController.java              # map BindingResult field errors → errors list (one per field);
│                                    # build GenerationSettings; pass to ModelService; log settings
├── ModelService.java                # generateResponse(question, sessionId, GenerationSettings)
├── GenerationSettings.java          # new record, five nullable fields; none() = all null
├── dto/
│   ├── ChatRequest.java             # five new fields + constraints; toGenerationSettings()
│   ├── ChatResponse.java            # new nullable `errors` (@JsonInclude NON_NULL), setter only
│   └── ValidationError.java         # new record(field, message)
├── exception/GlobalExceptionHandler.java  # new HttpMessageNotReadableException → 400 handler
└── impl/SpringAiModelService.java   # build OllamaChatOptions builder from non-null settings,
                                     # .options(builder) only when at least one is set

backend/src/test/java/com/example/chat/
├── ChatControllerUsageTest.java     # update lambdas to 3-arg ModelService
├── ChatControllerSettingsTest.java  # new: pass-through, 0.0 kept, ranges, multi-error, wrong kind, compat
└── impl/GenerationOptionsTest.java  # new: settings → builder mapping; combineWith keeps defaults

scripts/smoke_test.sh                # + 400 on temperature 2.5
tests/integration/test_chat_flow.sh  # + numPredict 5 → finishReason "length"
```

**Structure Decision**: Keep the existing backend layout. `GenerationSettings` lives next to `ModelResult` in `com.example.chat`, because both are internal service types and not DTOs. The frontend is untouched (assumption in spec).

### Implementation notes

- **ModelService signature**: `ModelService` is used as a lambda in `ChatControllerUsageTest` (`(q, s) -> ...`). Changing it to three parameters requires updating those lambdas to `(q, s, o) -> ...`. Keep it a single-method interface so the tests can still pass lambdas.
- **Builder, not options**: `ChatClientRequestSpec.options(...)` takes `OllamaChatOptions.builder()` (a `ChatOptions.Builder`) in Spring AI 2.0. Call `numPredict(...)`, not `maxTokens(...)`, to match the configured `spring.ai.ollama.chat.num-predict`.
- **Integer conversion**: `topK`, `numPredict` and `seed` are `BigDecimal` in the DTO. Convert them with `intValueExact()`, and only after `BindingResult` has no errors.
- **Error messages**: Every constraint on a field uses the same message text (for example "topK must be a whole number between 0 and 200"). Deduplicate by field name and keep the order fields appear in the request DTO.
- **Error construction sites**: `errors` is set with a setter on the validation-error response in `ChatController` and in the new `GlobalExceptionHandler` handler. The existing five-argument constructor and its four call sites stay unchanged.

## Complexity Tracking

> Two violations remain, and both predate this feature. Same justification as in [003 plan](../003-token-usage-metrics/plan.md): correcting them is outside the scope of this feature.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Data Minimization: full question logged at `ChatController.java:41`; no redaction | Pre-existing; this feature adds only numeric settings to logs. Fixed in feature 007. | Fixing it here would mix an unrelated privacy change into this feature. |
| Testing & Observability: no automated coverage of the full core flow (frontend → backend → model → frontend) | Pre-existing; this feature adds tests only for its own behavior (validation, options mapping, 400 body, backward compatibility). Broader coverage is fixed in feature 007. | Backfilling tests for the whole chat flow here would exceed this feature's scope. |
