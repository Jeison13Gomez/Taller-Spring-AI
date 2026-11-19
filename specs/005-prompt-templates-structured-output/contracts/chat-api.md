# Contract: chat templates and classification

Additive changes to `POST /api/v1/chat` (documented in the [003](../../003-token-usage-metrics/contracts/chat-api.md) and [004](../../004-chat-sampling-params/contracts/chat-api.md) contracts) and a new endpoint `POST /api/v1/classify`. Both are in `ChatController` and share its CORS configuration.

## POST /api/v1/chat: template identifier

New optional request field:

```json
{ "question": "Explain recursion", "templateId": "concise", "temperature": 0.3 }
```

| Field | Rule | If absent, `null` or `""` |
|-------|------|---------------------------|
| `templateId` | exactly one of the registered ids: `default`, `concise`, `tutor`, `extractor` | the `default` template is used (same behavior as today) |

The client sends an identifier only, never prompt text. Variable values are set by the server.

The 200 response is **unchanged** for every request, with or without `templateId`: the chat response never says which template was used.

### Response 400: unknown template

Request: `{"question":"hi","templateId":"pirata"}`

```json
{
  "requestId": "1d6c...", "answer": "", "elapsedMs": 0, "status": "error", "usage": null,
  "errors": [
    { "field": "templateId", "message": "unknown template 'pirata'; available: concise, default, extractor, tutor" }
  ]
}
```

The identifier is repeated in the message only up to 40 characters followed by `…`. Matching is exact: `Concise` and `concise ` are unknown. It is combined with any other invalid field in the same response, in this field order: `question`, `templateId`, `temperature`, `topP`, `topK`, `numPredict`, `seed`.

## POST /api/v1/classify

### Request

```json
{ "text": "My invoice has the wrong amount, I want a refund", "strategy": "validate-retry", "temperature": 0.0 }
```

| Field | Required | Allowed values |
|-------|----------|----------------|
| `text` | yes | 1 to 5000 characters, not blank |
| `strategy` | no | `instruction` (default), `validate-retry`, `native-schema` |
| `temperature` | no | number 0.0 to 2.0 (`0.0` is a value); default 0.7 |

### Response 200

```json
{
  "requestId": "6f0c2c1e-8a0b-4f55-9d0e-0a1b2c3d4e5f",
  "status": "ok",
  "result": { "category": "queja", "confidence": 0.87, "justification": "The customer reports a billing error and asks for a refund." },
  "strategy": "validate-retry",
  "retried": true,
  "retries": 1,
  "attempts": 2,
  "elapsedMs": 6120,
  "usage": {
    "promptTokens": 540, "completionTokens": 96, "totalTokens": 636,
    "model": "llama3.2:3b", "finishReason": "stop", "tokensPerSecond": 14.2
  }
}
```

| Field | Rule |
|-------|------|
| `result.category` | one of `consulta`, `queja`, `incidencia`, `otro` |
| `result.confidence` | decimal 0.0 to 1.0 |
| `result.justification` | non-empty |
| `strategy` | the strategy used (also when the request omitted it) |
| `retried`, `retries`, `attempts` | `instruction` and `native-schema`: `false`, `0`, `1`. `validate-retry`: `retries` 0 to 2, `attempts = retries + 1` |
| `elapsedMs` | whole request, including retries |
| `usage` | same fields as feature 003, summed over all attempts: a token figure is `null` unless every attempt supplied it; `tokensPerSecond` uses the summed generation time; `model` and `finishReason` come from the last attempt |

### Response 400: invalid input

Same shape and field-error rules as the chat (`errors` list; one entry per field).

Request: `{"text":"","strategy":"magic","temperature":2.5}`

```json
{
  "requestId": "...", "status": "error",
  "errors": [
    { "field": "text", "message": "text must be provided" },
    { "field": "strategy", "message": "unknown strategy 'magic'; available: instruction, validate-retry, native-schema" },
    { "field": "temperature", "message": "temperature must be between 0.0 and 2.0" }
  ]
}
```

A text longer than 5000 characters returns `{ "field": "text", "message": "text must be at most 5000 characters" }`. Error entries are ordered `text`, `strategy`, `temperature`. As in feature 004, a wrongly typed value (for example `"temperature": "hot"`) or malformed JSON reports only the first unreadable field.

### Known limitation: unreadable body on `/classify` returns the chat error body

When the body cannot be read at all (a value of the wrong kind such as `"temperature": "hot"`, or malformed JSON), the request never reaches the classification code. The handler that answers is the chat's, so the HTTP 400 has the **chat error body** (`requestId`, `answer`, `elapsedMs`, `status`, `usage` and `errors`) and **not** the classification shape described above:

Request: `{"text":"hi","temperature":"hot"}`

```json
{
  "requestId": "530c8e72-...", "answer": "", "elapsedMs": 0, "status": "error", "usage": null,
  "errors": [ { "field": "temperature", "message": "temperature must be a number" } ]
}
```

For malformed JSON (`{"text":`) the single entry is `{ "field": "body", "message": "request body is not valid JSON" }`. Clients of `/classify` should read `status` and `errors` and ignore the chat-only fields `answer`, `elapsedMs` and `usage` (always `""`, `0` and `null` here). Out-of-range and non-whole values are validated by the classification request itself and do use the classification shape.

### Response 502: the model did not meet the format

Returned through the global exception handler, with a gateway-type status (not 500). It is never a success.

```json
{
  "requestId": "...", "status": "error",
  "strategy": "validate-retry", "retried": true, "retries": 2, "attempts": 3,
  "elapsedMs": 9800,
  "failure": { "check": "category", "message": "category must be one of consulta, queja, incidencia, otro" },
  "consumed": {
    "promptTokens": 810, "completionTokens": 120, "totalTokens": 930,
    "model": "llama3.2:3b", "finishReason": "stop", "tokensPerSecond": 12.5
  }
}
```

| `failure.check` | Meaning |
|-----------------|---------|
| `json` | the answer is not valid JSON, or is empty |
| `category` | missing, or not one of the four values (case sensitive) |
| `confidence` | missing, not a number, or outside 0.0 to 1.0 |
| `justification` | missing or blank |

`consumed` is the tokens and time of the failed attempts, summed, labelled as consumed by a failed request. A figure the model did not supply is `null`.

### Other failures

If the model service itself fails (not reachable, HTTP error), the existing handlers answer exactly as they do for the chat (no `failure` object), so a service failure is distinguishable from a format failure.

## Compatibility

- Chat requests without `templateId`: same behavior, same response shape, same usage metrics (feature 003) and generation settings (feature 004).
- The chat 200 response gains no field.
- The 400 body keeps its existing fields and the `errors` list from feature 004.
- Logs of the classification operation and of template handling contain no input text, template text or model answer.
