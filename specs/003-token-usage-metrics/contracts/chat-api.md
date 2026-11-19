# Contract: POST /api/v1/chat (usage metrics extension)

Additive change to the existing endpoint. Request and all existing response fields are unchanged.

## Request

```json
{ "question": "What is Spring AI?", "sessionId": "demo" }
```

## Response 200 (`status: "ok"`)

```json
{
  "requestId": "6f0c2c1e-8a0b-4f55-9d0e-0a1b2c3d4e5f",
  "answer": "Spring AI is ...",
  "elapsedMs": 4210,
  "status": "ok",
  "usage": {
    "promptTokens": 32,
    "completionTokens": 118,
    "totalTokens": 150,
    "model": "llama3.2:3b",
    "finishReason": "stop",
    "tokensPerSecond": 27.64
  }
}
```

## Response with partial information (200)

Values the model did not supply are `null`; the answer is still returned.

```json
{
  "requestId": "...",
  "answer": "...",
  "elapsedMs": 900,
  "status": "ok",
  "usage": {
    "promptTokens": null,
    "completionTokens": null,
    "totalTokens": null,
    "model": "llama3.2:3b",
    "finishReason": null,
    "tokensPerSecond": null
  }
}
```

## Error responses (400 validation, 502 empty/failed answer)

`status: "error"` and `usage: null`. No metrics are fabricated.

## Field rules

| Field | Rule |
|-------|------|
| `usage.totalTokens` | equals `promptTokens + completionTokens` when all three are non-null |
| `usage.tokensPerSecond` | generation-time basis; `null` when not computable; never `0` as a stand-in |
| `usage.finishReason` | `length` means the answer was cut off by the length limit |
| `usage` | `null` only when `status` is `error` |

## Compatibility

Clients that ignore unknown fields are unaffected. `scripts/smoke_test.sh` and `tests/integration/test_chat_flow.sh` continue to check `answer`, and gain checks for `usage`.
