# Contract: conversation memory

Additive changes to `POST /api/v1/chat` (see the [003](../../003-token-usage-metrics/contracts/chat-api.md), [004](../../004-chat-sampling-params/contracts/chat-api.md) and [005](../../005-prompt-templates-structured-output/contracts/chat-api.md) contracts). No endpoint is added and `POST /api/v1/classify` does not change: it has no `sessionId` and keeps no history.

## Request: `sessionId`

```json
{ "question": "And what about its capital?", "sessionId": "demo", "templateId": "concise" }
```

| Value of `sessionId` | Result |
|----------------------|--------|
| absent or `null` | No memory: the request is answered as before this feature. No history is read or written. |
| matches `[A-Za-z0-9_-]{1,64}` (for example `demo`, `it-1`, a UUID) | Memory: the earlier turns of this session are sent to the model and this turn is stored. The first request of an unknown id starts the session. |
| `""`, only spaces, more than 64 characters, or any other character | HTTP 400, see below. The model is not called and no history changes. |

Identifiers are compared exactly: `Demo` and `demo` are different sessions. There is no authentication; the only protection of a session is that its identifier is not guessed by another client (a short value such as `demo` is easy to guess). No endpoint returns a history, lists identifiers, or edits or deletes messages. Real authentication belongs to the guardrails feature.

The template of every request (`templateId`, or `default`) supplies the system instructions of that turn, whichever template earlier turns used. System instructions are never stored as history.

## Response 200: `historyMessages`

```json
{
  "requestId": "6f0c...", "answer": "Madrid.", "elapsedMs": 1840, "status": "ok",
  "usage": { "promptTokens": 118, "completionTokens": 4, "totalTokens": 122, "model": "llama3.2:3b",
             "finishReason": "stop", "tokensPerSecond": 13.7 },
  "historyMessages": 4
}
```

| Field | Meaning |
|-------|---------|
| `historyMessages` | Number of earlier messages (questions and answers) sent to the model for this request. Excludes the system instructions and the current question. `0` without `sessionId` and on the first turn of a session. Never more than the window (`app.memory.max-messages`, 20 by default). |

All other fields keep their names and meaning. `usage.promptTokens` counts the whole prompt sent, history included, so it grows with the conversation until the window is full.

How to read the two numbers together:

- `historyMessages` stops growing: the window is full and the oldest messages are being dropped.
- `promptTokens` stops growing (variation under 1% from the previous turn) while `historyMessages` keeps rising: the prompt exceeded the model context size (`spring.ai.ollama.chat.num-ctx`) and Ollama silently dropped the oldest messages, one at a time, so the model may be forgetting turns (which part is kept is recorded in the quickstart notes, T047).

`historyMessages` is omitted from 400 and 502 responses, like `usage`.

## Response 400: invalid `sessionId`

Request: `{"question":"hi","sessionId":""}`

```json
{
  "requestId": "1d6c...", "answer": "", "elapsedMs": 0, "status": "error", "usage": null,
  "errors": [
    { "field": "sessionId", "message": "sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore" }
  ]
}
```

The error is combined with any other invalid field in the same response. Field order: `sessionId`, `question`, `templateId`, `temperature`, `topP`, `topK`, `numPredict`, `seed`. The value is not repeated in the message.

## Failures

A model failure, or a blank answer, returns exactly what it returned before this feature (502 with `status: "error"`), and the session is left as it was before the request: the failed question is not remembered.

## Configuration (not part of the HTTP contract)

| Property | Default | Meaning |
|----------|---------|---------|
| `app.memory.max-messages` | `20` | messages kept per session (minimum 2; a smaller value stops the service at startup) |
| `app.memory.idle-timeout` | `30m` | idle time after which a session is forgotten; the next request with that id starts empty (`historyMessages` 0) |
| `app.memory.max-sessions` | `100` | sessions kept; the least recently used is dropped first |
| `spring.ai.ollama.chat.num-ctx` | `8192` | context size given to the model (Ollama's own default appears to be 4096, an inference); override with `SPRING_AI_OLLAMA_CHAT_NUM_CTX` |

## Compatibility

- A client that does not send `sessionId` sees no change, except that 200 responses carry `historyMessages: 0`.
- A client that already sends a valid `sessionId` (the frontend does) now gets memory; its requests are otherwise unchanged.
- Features 003 (usage), 004 (generation settings) and 005 (templates, classification) keep their contracts.
