# Data Model: Conversation Memory

Nothing is persisted. Everything below lives in the memory of the running backend and disappears on restart.

## MemorySettings (configuration, `app.memory.*`)

| Property | Default | Rule |
|----------|---------|------|
| `app.memory.max-messages` | `20` | whole number >= 2; counts messages (a question and its answer are 2); an odd value of 3 or more behaves like the even value below it |
| `app.memory.idle-timeout` | `30m` | duration > 0; time without use after which a session is forgotten |
| `app.memory.max-sessions` | `100` | whole number >= 1; when exceeded, the least recently used session is dropped |

Any value outside these rules stops the application at startup with an error that names the property (FR-007). The model context size is a separate property: `spring.ai.ollama.chat.num-ctx` (default 8192 here, Ollama's own default appears to be 4096, an inference).

## Session (kept by `BoundedChatMemoryRepository`)

| Field | Rule |
|-------|------|
| `id` | the `sessionId`: 1 to 64 characters, `A-Z a-z 0-9 - _`; compared exactly (case sensitive) |
| `messages` | ordered list of Message; at most `max-messages`; always starts with a question (user message) |
| `lastUsed` | time of the last read or write; compared with the clock to apply `idle-timeout` |

Lifecycle: created by the first successful turn that names the id; refreshed by every read or write; removed when idle longer than `idle-timeout` or when it is the least recently used and the count exceeds `max-sessions`. A turn that fails restores the messages as they were before the turn (a session that did not exist stays absent).

Isolation: no operation takes more than one id; stored lists are copied on the way in and out; there is no operation that returns ids to a client.

## Message (Spring AI `Message`)

| Field | Rule |
|-------|------|
| `role` | `USER` (the question) or `ASSISTANT` (the answer). `SYSTEM` is never stored: the system text comes from the template of each request |
| `text` | the question or the answer as exchanged |

## Turn (one request with a `sessionId`)

Runs while holding the lock of its session; so turns of one session are sequential and turns of different sessions run in parallel.

| Step | Data |
|------|------|
| Snapshot | stored messages before the turn (restored on failure) |
| `historyMessages` | size of the snapshot (messages sent as history) |
| Request to the model | system text of the template + snapshot + current question |
| Success | stored: current question + answer, then the window is applied |
| Failure or blank answer | snapshot restored |

## ChatRequest (extended)

`sessionId`: string, optional. Absent or `null` = no memory (same as before the feature). Otherwise it must match `[A-Za-z0-9_-]{1,64}`, else HTTP 400 with `field: "sessionId"`. Other fields unchanged.

## ChatResponse (extended)

Adds `historyMessages`: integer. Present in 200 responses (`0` without `sessionId` or on the first turn of a session); omitted in 400 and 502 responses. No existing field changes.

## ModelResult (internal)

`record ModelResult(String answer, UsageMetrics usage, Integer historyMessages)`; the 2-argument constructor stays and sets `historyMessages` to `0`.
