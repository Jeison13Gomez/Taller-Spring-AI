# Research: Chat Usage Metrics

No `NEEDS CLARIFICATION` items remained after the clarification session. The points below record design decisions and the one item that must be verified during implementation.

## 1. Source of token counts, model and finish reason

- **Decision**: Call `chatClient.prompt().user(q).call().chatResponse()` instead of `.content()` and read the Spring AI `ChatResponse`: `getMetadata().getUsage()` (prompt / completion / total tokens), `getMetadata().getModel()`, and `getResult().getMetadata().getFinishReason()`.
- **Rationale**: These are the provider-neutral fields Spring AI already fills from the Ollama reply; no extra request is needed, so latency is unaffected (SC-004).
- **Alternatives considered**: Calling the Ollama REST API directly (bypasses the Spring AI abstraction the demo is meant to show); parsing logs (fragile).
- **Verify during implementation**: exact accessor names in Spring AI 2.0.0 (they changed between milestones). Confirm against the resolved dependency before coding.
- **Verified in implementation (T001, from the Spring AI 2.0.0 sources jars)**: `org.springframework.ai.chat.model.ChatResponse#getMetadata()` returns `ChatResponseMetadata` with `getUsage()` (`Usage#getPromptTokens()`, `getCompletionTokens()`, `getTotalTokens()`, all `Integer`) and `getModel()`. The finish reason is `getResult().getMetadata().getFinishReason()` (nullable; `getResult()` is nullable). The answer text is `getResult().getOutput().getText()`. Caveat: `OllamaChatModel` turns a missing `prompt_eval_count`/`eval_count` into `0`, not `null`, and only sets the finish reason when both counts are present. Therefore a token count of `0` or less is treated as "not available" (`null`), since a non-empty answer always has at least one completion token and a prompt always has at least one prompt token.

## 2. Generation time for tokens per second

- **Decision**: Use the generation duration reported by Ollama (`eval_duration`, exposed by Spring AI in the response metadata) and compute `completionTokens / generationSeconds`. If the duration or output tokens are missing or zero, tokens per second is `null` (shown as "not available", per clarification).
- **Rationale**: Matches the clarified definition (generation time only, excluding model load and prompt evaluation). Wall-clock time measured in the controller would include load and prompt processing and would contradict it.
- **Alternatives considered**: Wall-clock `elapsedMs` already in the response (rejected: wrong base); showing both rates (rejected in clarification).
- **Verify during implementation**: how Spring AI 2.0.0 exposes `eval_duration` (typed metadata or generic map entry, unit). If it is not exposed, fall back to the provider's native response through the Ollama API client rather than approximating with wall-clock time.
- **Verified in implementation (T001)**: it is exposed. `OllamaChatModel` stores it in the response metadata map under the key `"eval-duration"` as a `java.time.Duration` (read with `chatResponse.getMetadata().get("eval-duration")`). No fallback to the native client is needed.

## 3. Total tokens

- **Decision**: Use the provider's total when present; otherwise compute input + output only when both are present. Never estimate.
- **Rationale**: Satisfies FR-003/SC-002 without inventing values (FR-009).

## 4. API shape

- **Decision**: Add one nullable `usage` object to the existing response (nested, not flat); individual fields are nullable. Failed requests (status `error`) return `usage: null`. Token fields are named `promptTokens` / `completionTokens` / `totalTokens`, matching the workshop and Spring AI's `Usage` abstraction.
- **Why nested rather than flat**:
  - The six metrics always travel together and belong to one answer; one object makes "no metrics" a single `usage: null` on error responses, instead of six separate nulls that could be confused with "not available".
  - The existing response already has top-level `elapsedMs` and `status`; a `usage` namespace keeps wall-clock time (`elapsedMs`) visibly separate from model-reported generation speed (`tokensPerSecond`) and avoids name clashes.
  - It maps one-to-one to the new `UsageMetrics` class and to a per-message `usage` property in the page, so metrics cannot be detached from their answer (FR-008, SC-005).
  - Cost: one extra level of nesting for clients, which is negligible here. A flat shape would also be additive, so this is a judgement call rather than a technical constraint.
- **Rationale**: Additive, so existing clients and scripts keep working (constitution: explicit, stable contracts). Distinguishes "unavailable" (`null`) from a real zero.
- **Alternatives considered**: HTTP headers (invisible to the UI contract and harder to test); a separate metrics endpoint (extra call, correlation overhead).

## 5. Frontend presentation

- **Decision**: Store `usage` on each bot message object and render a one-line, visually secondary summary under the answer. Missing values render as "not available". Tokens as integers, tokens/s with up to 2 decimals (FR-011).
- **Rationale**: Per-message storage guarantees metrics never move to another answer (FR-008, SC-005).

## 6. Logging

- **Decision**: Log the usage values at INFO next to the existing per-request log line. They are numeric/technical and contain no user content.
- **Rationale**: Supports the constitution's observability guidance. This feature adds no user content to logs.
- **Existing issue (not addressed here)**: the codebase already violates Data Minimization: `ChatController.java:40` logs the full user question, and `LogRedactionFilter` defines a redaction pattern that is never applied. This feature neither fixes nor worsens it; the fix is deferred to feature 007 (see Complexity Tracking in [plan.md](plan.md)).
