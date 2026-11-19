# Data Model: Prompt Templates and Structured Output

Nothing is persisted. Templates are read-only files packaged with the application; every other entity lives for one request.

## PromptTemplate (files in `backend/src/main/resources/prompts/`)

| Field | Source | Rule |
|-------|--------|------|
| `id` | file name without `.st` | `[a-z0-9-]+`; unique; matched exactly (case and spaces matter) |
| `text` | file content | rendered with `< >` delimiters; may contain literal `{` `}` (for example a JSON example); must not contain a lone `<` |

Registered set: `default` (required; the current system prompt word for word, no variables), `concise`, `tutor`, `extractor`. Adding a file adds a template after an image rebuild.

Startup validation (any failure stops the application and names the template): `default` exists; ids are valid and unique; each template renders with the configured variables in strict mode, so no variable is left without a value and no expression is malformed.

## TemplateVariables (configuration)

A map of name to text, bound from `app.prompts.variables.*` in `application.properties` (for example `max_sentences`, `audience`). Only the server sets them; no request field can carry a value.

## ChatRequest (extended)

Adds `templateId`: string, optional. `null` or `""` = not sent = `default`. Any other value must be a registered id, otherwise HTTP 400 (`field: "templateId"`). Existing fields (`question`, `sessionId`, and the five generation settings of feature 004) are unchanged.

## Category (enum)

`consulta`, `queja`, `incidencia`, `otro`. Case sensitive, serialized in lower case.

## Classification (result)

| Field | Type | Rule |
|-------|------|------|
| `category` | Category | one of the four values |
| `confidence` | decimal | 0.0 to 1.0 inclusive; not NaN |
| `justification` | string | not blank |

Wrapper types are used in code so that a missing field is detected by name and not as a parse failure.

## FormatStrategy (enum)

| Id | Retries allowed | How the format is obtained |
|----|-----------------|----------------------------|
| `instruction` (default) | 0 | format instruction appended to the prompt |
| `validate-retry` | 2 (up to 3 attempts) | same instruction; each failed check is appended and the model is asked again |
| `native-schema` | 0 | JSON schema sent to the provider's structured-output option; result still checked |

## ClassifyRequest

| Field | Type | Rule |
|-------|------|------|
| `text` | string | required, not blank, at most 5000 characters |
| `strategy` | string | optional; one of the three ids; default `instruction` |
| `temperature` | number | optional; 0.0 to 2.0; `0.0` is a value |

## Attempt (internal)

One model call: prompt tokens, completion tokens, model, finish reason, generation duration, and the outcome (valid result, or the failed check). A request has 1 to 3 attempts.

## UsageMetrics (existing, feature 003)

Reused unchanged for the classification response and for the consumed usage in failures. For a request with several attempts the values are sums (see research R10): tokens only if every attempt has them, tokens per second from the summed generation time, model and finish reason from the last attempt.

## ClassifyResponse

| Field | Present when | Meaning |
|-------|--------------|---------|
| `requestId` | always | unique id |
| `status` | always | `ok` or `error` |
| `result` | success | a Classification |
| `strategy` | success and format failure | the id used |
| `retried` | success and format failure | whether at least one retry happened |
| `retries` | success and format failure | number of retries (0 to 2) |
| `attempts` | success and format failure | number of model calls (`retries + 1`) |
| `elapsedMs` | success and format failure | total time including retries |
| `usage` | success | UsageMetrics summed over attempts |
| `failure` | format failure | `{ check, message }` with check in `json`, `category`, `confidence`, `justification` |
| `consumed` | format failure | UsageMetrics summed over the failed attempts |
| `errors` | HTTP 400 | list of `{ field, message }` |

Absent fields are omitted from the JSON (not sent as `null`).

## StructuredOutputException (internal)

Raised when the model's answer never meets the format. Carries the failed check, a message, the strategy, attempts, retries, elapsed time and the summed usage. `GlobalExceptionHandler` turns it into the HTTP 502 body above.

## Relationships

`ChatRequest` 0..1 → `PromptTemplate` (looked up by id) → rendered system text → one model call.
`ClassifyRequest` 1 → `FormatStrategy` → 1..3 `Attempt` → one `Classification` (success) or one `StructuredOutputException` (failure).
