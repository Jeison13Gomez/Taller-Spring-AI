# Implementation Plan: Chat Usage Metrics

**Branch**: `003-token-usage-metrics` | **Date**: 2026-10-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/003-token-usage-metrics/spec.md`

## Summary

Every successful chat answer will carry a `usage` object with input, output and total tokens, model name, finish reason and generation tokens per second. The backend reads these values from the Spring AI chat response metadata (Ollama) instead of returning only the answer text, adds them to the existing `POST /api/v1/chat` response as a backward-compatible field, and the static chat page (`frontend/src/index.html`) shows them under each bot message. Values the model does not supply are `null` in the API and rendered as "not available". Tokens per second uses only the model's generation time (decision from clarification session 2026-10-15).

## Technical Context

**Language/Version**: Java 21 (backend), plain HTML/CSS/JavaScript (frontend: a single static page, `frontend/src/index.html`, served by `http-server`; `frontend/package.json` declares no Angular dependencies and the Dockerfile copies only that file)

**Primary Dependencies**: Spring Boot 4.1.0, Spring AI 2.0.0 (`spring-ai-starter-model-ollama`), browser `fetch` (frontend)

**Storage**: N/A (metrics are not persisted; they live only in the API response and the on-screen conversation)

**Testing**: JUnit 5 + `spring-boot-starter-test`; `backend/src/test/` currently contains no tests (unit tests for metric extraction and tokens/s calculation; controller test with a stubbed `ModelService`); shell smoke/integration scripts in `scripts/` and `tests/integration/` extended to check the new fields

**Target Platform**: Local Docker Compose demo (backend, Ollama, frontend)

**Project Type**: web-application (static HTML frontend + Spring Boot backend)

**Performance Goals**: Metrics add no perceptible latency (SC-004: <= 5% slower than today); only reads already-returned metadata

**Constraints**: Backward-compatible API (existing `answer`, `elapsedMs`, `status` fields unchanged); no invented values; no extra network calls; no persistence

**Scale/Scope**: Single chat endpoint, one static page, one service, one DTO addition

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Assessment |
|-----------|------------|
| Demo-Focused Simplicity | PASS. No new services; extends one DTO, one service method and one template. Dashboards/aggregation explicitly out of scope. |
| Reproducible Local Execution | PASS. No new infrastructure; works with the existing Docker Compose setup. |
| Clear Interfaces & Contracts | PASS. Additive, documented change to the versioned `/api/v1/chat` contract with example request/response ([contracts/chat-api.md](contracts/chat-api.md)); acceptance checks added to the smoke/integration scripts. |
| Data Minimization & Secrets | **NON-COMPLIANT (existing code, not introduced by this feature)**. `ChatController.java:40` logs the full user question at INFO (`question={}`), contradicting "User inputs and logs MUST avoid persisting sensitive data". `LogRedactionFilter` defines `SECRET_PATTERN` but never applies it (it only logs method and path), so no redaction happens. This feature adds only numeric/technical metadata to logs and does not make this worse; the fix is deferred to feature 007. |
| Testing & Observability | **NON-COMPLIANT (existing code)**. `backend/src/test/` is empty, so the required unit and acceptance coverage of the core flow does not exist today. This feature adds its own tests for the new behavior (extraction, tokens/s, controller response) but does not backfill coverage for the rest of the flow; that is deferred to feature 007. |

Two principles are non-compliant because of pre-existing code; see Complexity Tracking.

**Post-design re-check**: unchanged. The design adds no new components or storage and does not worsen either non-compliance.

## Project Structure

### Documentation (this feature)

```text
specs/003-token-usage-metrics/
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
backend/
└── src/
    ├── main/java/com/example/chat/
    │   ├── ChatController.java            # return usage in response; log it; builds the DTO (2 sites)
    │   ├── ModelService.java              # return answer + usage instead of String
    │   ├── dto/
    │   │   ├── ChatResponse.java          # add nullable `usage`
    │   │   └── UsageMetrics.java          # new
    │   ├── exception/GlobalExceptionHandler.java # builds the DTO (2 sites); must pass usage = null
    │   └── impl/SpringAiModelService.java # read Spring AI response metadata via chatClient...chatResponse()
    └── test/java/com/example/chat/        # new unit/controller tests

frontend/
└── src/index.html                         # keep usage per bot message, render the metrics line and its style

scripts/smoke_test.sh                      # assert usage fields present
tests/integration/test_chat_flow.sh        # assert total = input + output
```

**Structure Decision**: Keep the existing web-application layout (backend + frontend). No new modules; the change is a vertical slice through the current files. The files under `frontend/src/app/` (Angular component and service) are not built or served by the Dockerfile, so this feature does not touch them.

### Name collision and DTO construction sites

- `org.springframework.ai.chat.model.ChatResponse` (Spring AI, returned by `.chatResponse()`) has the same simple name as our DTO `com.example.chat.dto.ChatResponse`. In `SpringAiModelService` use the fully qualified Spring AI name or avoid importing the DTO there; the service should return a separate result type (for example answer + `UsageMetrics`), never the DTO.
- The DTO is constructed with the 4-argument constructor in four places that must be updated when `usage` is added: `ChatController.java:36` (validation error), `ChatController.java:46` (normal response), and `GlobalExceptionHandler.java:20` and `:27` (the two exception handlers). Error paths pass `usage = null`.

## Complexity Tracking

> Both entries are violations in code that exists before this feature. They are justified here rather than fixed because correcting them is outside the scope of the usage-metrics feature.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Data Minimization: full user question logged at `ChatController.java:40`; `LogRedactionFilter.SECRET_PATTERN` never applied | Pre-existing; this feature only adds numeric metadata to logs and does not change what is logged about user input. Fixed in feature 007. | Fixing it here would mix an unrelated logging/privacy change into this feature and enlarge its scope and review. |
| Testing & Observability: `backend/src/test/` empty, no coverage of the core flow | Pre-existing; this feature adds tests only for its own behavior. Broader coverage is fixed in feature 007. | Backfilling tests for the whole chat flow here would exceed this feature's scope. |
