# Data Model: Chat Usage Metrics

No storage; these are transient shapes in the API response and in the on-screen conversation.

## UsageMetrics

Measurements for exactly one chat answer. Every field is nullable; `null` means "not available" (never a fabricated value).

| Field | Type | Rule |
|-------|------|------|
| `promptTokens` | integer >= 0, nullable | Tokens consumed by the query (input). |
| `completionTokens` | integer >= 0, nullable | Tokens generated in the answer (output). |
| `totalTokens` | integer >= 0, nullable | Provider total, or `promptTokens + completionTokens` when both are present. Must equal prompt + completion whenever all three are present. |
| `model` | string, nullable | Model that actually produced the answer (for example `llama3.2:3b`). |
| `finishReason` | string, nullable | Why generation ended (for example `stop`, `length`). |
| `tokensPerSecond` | number, nullable | `completionTokens / generationSeconds`, generation time only. `null` if completion tokens or generation time is missing or zero. Presented with at most 2 decimals. |

## ChatAnswer (existing response, extended)

| Field | Type | Change |
|-------|------|--------|
| `requestId` | string | unchanged |
| `answer` | string | unchanged |
| `elapsedMs` | integer | unchanged (wall-clock, not used for tokens/s) |
| `status` | `"ok"` or `"error"` | unchanged |
| `usage` | UsageMetrics, nullable | **new**. Present on `status: "ok"`; `null` on `status: "error"`. |

## Relationships and rules

- One ChatAnswer has at most one UsageMetrics; a successful answer always has the object (its fields may be individually `null`).
- On the frontend each bot message keeps its own `usage`, so metrics cannot attach to another answer.
- No state transitions.
