# Quickstart: Validate Chat Usage Metrics

Prerequisites: Docker running; stack started as in the project README (backend on `:8080`, Ollama with the configured model pulled, frontend on `:4200`).

## 1. Start the stack

```bash
docker compose up --build
```

## 2. API check (User Story 1)

```bash
curl -s -H "Content-Type: application/json" \
  -d '{"question":"Say hi in one sentence","sessionId":"qs"}' \
  http://localhost:8080/api/v1/chat
```

Expected: JSON with `status: "ok"` and a `usage` object containing `promptTokens`, `completionTokens`, `totalTokens`, `model`, `finishReason`, `tokensPerSecond` (see [contracts/chat-api.md](contracts/chat-api.md)). `totalTokens` equals `promptTokens + completionTokens`.

## 3. UI check (User Stories 1 and 2)

1. Open `http://localhost:4200`.
2. Ask three different questions.
3. Expected: each bot answer shows its own metrics line (input, output, total tokens, model, finish reason, tokens/s). Earlier answers keep their values after later ones arrive.

## 4. Unavailable values (User Story 3)

Run the backend unit tests that stub a model response without usage data:

```bash
cd backend && ./gradlew test
```

Expected: answer still returned, missing fields `null`, UI shows "not available" for them (verified by the frontend check in the task list).

## 5. Error case

```bash
curl -s -H "Content-Type: application/json" -d '{"question":""}' http://localhost:8080/api/v1/chat
```

Expected: HTTP 400, `status: "error"`, `usage: null`, no metrics shown in the UI for failed queries.

## 6. Scripts

```bash
bash scripts/smoke_test.sh
bash tests/integration/test_chat_flow.sh
```

Expected: both pass, including the new `usage` assertions.

## Validation results (2026-10-25)

- `gradle build` (JDK 21, Gradle 9.6.1): BUILD SUCCESSFUL; 15 tests, 0 failures (`ChatControllerUsageTest` 5, `SpringAiModelServiceUsageTest` 3, `UsageMetricsMissingValuesTest` 7).
- `bash scripts/smoke_test.sh` and `bash tests/integration/test_chat_flow.sh`: both pass; `totalTokens == promptTokens + completionTokens` in every response.
- UI walkthrough (steps 3 and 5, backend stopped for the error case): confirmed manually, as expected.
- Latency (SC-004): **not verified.** No baseline `elapsedMs` was recorded before the change (T002 could not be done, because the feature was already implemented when the stack was first started on this machine), so the 5% limit cannot be checked. Current values for reference, same machine, CPU, `llama3.2:3b`: `"Hola"` 1108 ms; integration script short answers 394 and 457 ms; one 226-token answer 19311 ms. The change only reads metadata already returned by the model, so no relevant overhead is expected, but this was not measured.
