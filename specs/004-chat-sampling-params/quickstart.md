# Quickstart: Validate Per-Request Generation Settings

Prerequisites: the stack is running as in the project README (`docker compose up --build`), with the backend on `:8080` and the configured model pulled. Contract: [contracts/chat-api.md](contracts/chat-api.md).

```bash
URL=http://localhost:8080/api/v1/chat
H='Content-Type: application/json'
```

## 1. Backward compatibility (User Story 2)

```bash
curl -s -H "$H" -d '{"question":"Say hi in one sentence","sessionId":"qs"}' $URL
```

Expected: 200, the same shape as before the feature, no `errors` key, and `usage` present.

## 2. Truncation with numPredict (User Story 1)

```bash
curl -s -H "$H" -d '{"question":"Explain photosynthesis in detail","numPredict":5}' $URL
curl -s -H "$H" -d '{"question":"Explain photosynthesis in detail"}' $URL
```

Expected: the first response has `usage.finishReason: "length"` and `usage.completionTokens <= 5`. The second response uses the default (1024) and normally ends with `"stop"`. There is no restart in between.

## 3. Temperature 0.0 and seed reproducibility (User Stories 1 and 4)

```bash
for i in 1 2; do curl -s -H "$H" -d '{"question":"Invent a name for a cat","temperature":0.8,"seed":42}' $URL; echo; done
for i in 1 2; do curl -s -H "$H" -d '{"question":"Invent a name for a cat","temperature":0.0}' $URL; echo; done
```

Expected: the two answers in each pair are identical. For SC-005, repeat the seeded pair 20 times; at least 19 pairs must match.

## 4. Rejections (User Story 3)

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -d '{"question":"hi","temperature":2.5}' $URL
curl -s -H "$H" -d '{"question":"hi","topP":-0.1,"topK":500}' $URL
curl -s -H "$H" -d '{"question":"hi","numPredict":0}' $URL
curl -s -H "$H" -d '{"question":"hi","topK":3.5}' $URL
curl -s -H "$H" -d '{"question":"hi","temperature":"hot"}' $URL
```

Expected: every call returns 400 with `status: "error"`, `usage: null` and `errors` naming each offending field and its range (or "must be a number"). Each one responds in under 1 second (SC-006), and the backend log shows no model call for these requests.

## 5. Boundaries are accepted

```bash
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -d '{"question":"hi","temperature":2.0,"topP":1.0,"topK":200,"numPredict":2048}' $URL
curl -s -o /dev/null -w '%{http_code}\n' -H "$H" -d '{"question":"hi","temperature":0.0,"topP":0.0,"topK":0,"numPredict":1}' $URL
```

Expected: 200 for both.

## 6. Automated checks

```bash
cd backend && gradle test
bash scripts/smoke_test.sh
bash tests/integration/test_chat_flow.sh
```

Expected: all of them pass, including the new tests for validation, defaults merging and the 400 body, and the new script assertions (an out-of-range value returns 400; `numPredict: 5` gives `finishReason: "length"`).

## 7. Combinaciones de parámetros aceptadas

```bash
for BODY in \
  '{"question":"hi","temperature":0.8,"numPredict":15}' \
  '{"question":"hi","temperature":0.8,"topK":0,"topP":0.5}' \
  '{"question":"hi","temperature":0.2,"topP":0.95}' \
  '{"question":"hi","numPredict":1000}'; do
  echo "$BODY -> $(curl -s -o /dev/null -w '%{http_code}' -H "$H" -d "$BODY" $URL)"
done
```

Expected: every line ends with `200`. Each combination is valid on its own, and settings that are not sent keep their defaults.

## Validation results (2026-10-26)

- `gradle test` in `backend/`: BUILD SUCCESSFUL; 36 tests, 0 failures (`ChatControllerSettingsTest` 16, `ChatControllerUsageTest` 5, `GenerationOptionsTest` 5, `SpringAiModelServiceUsageTest` 3, `UsageMetricsMissingValuesTest` 7).
- `bash scripts/smoke_test.sh` and `bash tests/integration/test_chat_flow.sh`: both pass against the rebuilt stack (`docker compose -f docker/docker-compose.yml up -d --build`). With `numPredict: 5` the answer ends with `finishReason: "length"` and 5 completion tokens; the same question without it ends with `"stop"` (762 completion tokens).
- Steps 1, 2, 4, 5 and 7 run by hand: the legacy request keeps its shape with no `errors` key; the five rejection cases return 400 with the expected `errors` (the malformed body reports `body`); boundaries and the four accepted combinations return 200. Rejections answered in 70-87 ms (SC-006).
- Seed (SC-005): `seed: 42, temperature: 0.8`, same question, 20 pairs: **19 of 20 pairs identical** (SC-005 requires at least 19). `temperature: 0.0`, 10 pairs: 10 of 10 identical. Different seeds gave different answers.
- Concurrency (FR-003): three rounds of two parallel requests (one with `numPredict: 5`, one without): the first always ended with `"length"`, the second always with `"stop"` (666, 492 and 717 completion tokens).