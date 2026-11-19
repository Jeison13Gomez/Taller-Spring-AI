# Data Model: Per-Request Chat Sampling Parameters

Nothing is persisted. All entities exist only for the duration of one request.

## ChatRequest (extended)

| Field | Type in JSON | Required | Rule | Meaning when absent/null |
|-------|--------------|----------|------|--------------------------|
| `question` | string | yes | not blank (unchanged) | n/a |
| `sessionId` | string | no | unchanged | unchanged |
| `temperature` | number | no | 0.0 ≤ x ≤ 2.0 | default 0.7 |
| `topP` | number | no | 0.0 ≤ x ≤ 1.0 | model default |
| `topK` | number | no | whole number, 0 ≤ x ≤ 200 | model default |
| `numPredict` | number | no | whole number, 1 ≤ x ≤ 2048 | default 1024 |
| `seed` | number | no | whole number, -2147483648 ≤ x ≤ 2147483647 | model default (random) |

All bounds are inclusive. A sent `0` / `0.0` is a value, not "absent".

## GenerationSettings (new, internal)

An immutable value with five nullable fields: `temperature` (Double), `topP` (Double), `topK` (Integer), `numPredict` (Integer), `seed` (Integer). It is built from a `ChatRequest` only after validation passes, and passed to `ModelService`. Each null field means "use the default". An instance with all fields null is equivalent to today's behavior.

## ValidationError (new, response item)

| Field | Type | Example |
|-------|------|---------|
| `field` | string | `"temperature"` |
| `message` | string | `"temperature must be between 0.0 and 2.0"` |

## ChatResponse (extended)

Adds `errors`: a list of `ValidationError`, present only on 400 responses and omitted otherwise. All existing fields are unchanged. On 400, `status` is `"error"` and `usage` is `null`, as today.

## Relationships

`ChatRequest` 1 → 0..1 `GenerationSettings` (only when valid) → used for exactly one model call. A rejected `ChatRequest` → 1 `ChatResponse` with ≥1 `ValidationError` and no model call.
