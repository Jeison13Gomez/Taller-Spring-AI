# Contract: POST /api/v1/chat (generation settings extension)

Additive change to the endpoint documented in [003 contract](../../003-token-usage-metrics/contracts/chat-api.md). Requests without the new fields behave exactly as before.

## Request

All five new fields are optional and independent.

```json
{
  "question": "Write a haiku about rain",
  "sessionId": "exp-1",
  "temperature": 0.0,
  "topP": 0.9,
  "topK": 40,
  "numPredict": 64,
  "seed": 42
}
```

| Field | Allowed values (inclusive) | Default when absent or `null` |
|-------|----------------------------|-------------------------------|
| `temperature` | number 0.0–2.0 | 0.7 |
| `topP` | number 0.0–1.0 | model default |
| `topK` | whole number 0–200 | model default |
| `numPredict` | whole number 1–2048 | 1024 |
| `seed` | whole number in the 32-bit signed range | model default |

Settings apply to this request only.

## Response 200

Same shape as in 003: no new fields, and no `errors` key. With a small `numPredict`, expect `usage.finishReason: "length"` and `usage.completionTokens <= numPredict`.

## Response 400: invalid settings

Returned before the model is called. Every out-of-range or non-whole setting is listed, one entry per field.

Request: `{"question":"hi","temperature":2.5,"topK":500,"numPredict":3.5}`

```json
{
  "requestId": "1d6c...",
  "answer": "",
  "elapsedMs": 0,
  "status": "error",
  "usage": null,
  "errors": [
    { "field": "temperature", "message": "temperature must be between 0.0 and 2.0" },
    { "field": "topK", "message": "topK must be a whole number between 0 and 200" },
    { "field": "numPredict", "message": "numPredict must be a whole number between 1 and 2048" }
  ]
}
```

## Response 400: wrong kind of value or malformed JSON

Request: `{"question":"hi","temperature":"hot"}`

```json
{
  "requestId": "...", "answer": "", "elapsedMs": 0, "status": "error", "usage": null,
  "errors": [ { "field": "temperature", "message": "temperature must be a number" } ]
}
```

Only the first unreadable value is reported. If the body is not valid JSON, `field` is `"body"` and `message` is `"request body is not valid JSON"`. Previously this case returned 500.

## Response 400: missing question (existing case)

Unchanged, except that it now includes `errors: [{ "field": "question", "message": "question must be provided" }]`.

## Compatibility

- Existing request bodies: unchanged behavior and response.
- 200 and 502 responses: unchanged shape.
- 400 responses: the existing fields are kept, and the `errors` field is added.
- Unknown extra fields: handled as before.
