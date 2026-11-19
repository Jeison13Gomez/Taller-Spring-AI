# Quickstart: Validate Conversation Memory

Prerequisites: the stack runs as in the project README (`docker compose -f docker/docker-compose.yml up -d --build`), backend on `:8080`, model pulled. Contract: [contracts/chat-api.md](contracts/chat-api.md). Gradle commands run from `backend/`.

```bash
H='Content-Type: application/json'
CHAT=http://localhost:8080/api/v1/chat
```

## 1. Unit tests (no Ollama)

```bash
cd backend && gradle test
```

Expected: all green. The memory tests use the scripted model and a controllable clock; they cover isolation, window eviction, the system message kept and replaced per template, rollback of a failed turn, concurrent turns of one session, expiry and the session cap.

## 2. A session remembers (User Story 1)

```bash
curl -s -H "$H" -d '{"question":"My name is Ana. Answer only OK.","sessionId":"qs-a","numPredict":20}' $CHAT
curl -s -H "$H" -d '{"question":"What is my name?","sessionId":"qs-a","numPredict":30}' $CHAT
curl -s -H "$H" -d '{"question":"What is my name?","sessionId":"qs-b","numPredict":30}' $CHAT
```

Expected: `historyMessages` is `0`, then `2`, then `0` (a different session). The second answer should mention Ana; the third should not. The model's wording is not deterministic, so the numbers are the check and the wording is a manual look.

## 3. Without `sessionId` (User Story 1, scenario 4)

```bash
curl -s -H "$H" -d '{"question":"Say hi in one sentence"}' $CHAT
curl -s -H "$H" -d '{"question":"Say hi in one sentence"}' $CHAT
```

Expected: both 200, `historyMessages` `0`, similar `promptTokens` (SC-005).

## 4. Invalid session identifiers (User Story 4)

```bash
for ID in '""' '"   "' '"a b"' '"café"'; do
  curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" -H "$H" -d "{\"question\":\"hi\",\"sessionId\":$ID}" $CHAT
done
LONG=$(python -c "print('a'*65)")
curl -s -o /dev/null -w "%{http_code} %{time_total}s\n" -H "$H" -d "{\"question\":\"hi\",\"sessionId\":\"$LONG\"}" $CHAT
curl -s -H "$H" -d '{"question":"hi","sessionId":"demo"}' $CHAT | head -c 200
```

Expected: five 400 in under 1 s each (SC-009), the model is not called; `demo` gives 200. `Demo` and `demo` are different sessions.

## 5. Window and templates (User Stories 2 and 3)

Send 12 questions in one session; `historyMessages` should read 0, 2, 4, ..., 20, 20 (it stops at the window). Then send one with `"templateId":"tutor"` in the same session: the answer follows the tutor style, `historyMessages` stays 20 and `usage` is present.

## 6. Context size (FR-018, FR-019)

```bash
docker compose -f docker/docker-compose.yml exec backend printenv | grep NUM_CTX
```

Expected: `SPRING_AI_OLLAMA_CHAT_NUM_CTX=8192` (or the value you set). To try a smaller context (4096 appears to be Ollama's own default), run `SPRING_AI_OLLAMA_CHAT_NUM_CTX=2048 docker compose -f docker/docker-compose.yml up -d` and repeat step 5 with long answers: when the prompt exceeds 2048 `promptTokens` levels off at a plateau (about 1791 in Experiment E) while `historyMessages` keeps growing.

## 7. Logs (FR-015)

```bash
docker logs pruebachat_backend | grep -c "<a word from an earlier answer>"
```

Expected: no new log line carries history or answers. The existing `Received chat request ... question=` line (feature 007) is the known exception.

## 8. Experiment E (User Story 5)

Three runs, one per context size (2048, 4096 and 8192). The script records `-NumCtx` in every row but cannot change the backend, so restart it between runs. Each run takes about 20 minutes (an estimate: 40 requests of up to 400 tokens):

```powershell
$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '2048'
docker compose -f docker/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File experimentos/experimento_e.ps1 -NumCtx 2048

$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '4096'
docker compose -f docker/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File experimentos/experimento_e.ps1 -NumCtx 4096

$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '8192'
docker compose -f docker/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File experimentos/experimento_e.ps1 -NumCtx 8192

py experimentos\graficas.py
```

Expected: `experimentos/resultados/experimento_e_ctx2048.csv`, `experimento_e_ctx4096.csv` and `experimento_e_ctx8192.csv` (40 rows each: 20 with memory, 20 without), `experimento_e_resumen.csv`, and `experimentos/graficas/grafica_3_prompttokens_vs_turno.png` with the three runs overlaid. With 8192, `historyMessages` reaches 20 at turn 11 and `promptTokens` flattens from there (the window is the limit). With 4096 and 2048 `promptTokens` stops growing at a plateau (3885 from turn 9 and 1791 from turn 5, measured) while `historyMessages` keeps growing (the context is the limit): Ollama drops the oldest messages one at a time, about 525 tokens per step, and answers 200 without a signal. The summary reports that turn in `turno_meseta`. The runs without memory stay flat. Remember to restore 8192 afterwards.

## 9. Scripts

```bash
bash scripts/smoke_test.sh
bash tests/integration/test_chat_flow.sh
```

Expected: both pass, including the new memory checks.

## 10. System message and Ollama's truncation (FR-006, T047)

```powershell
$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '2048'
docker compose -f docker/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File experimentos/experimento_truncado.ps1 -NumCtx 2048

$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '4096'
docker compose -f docker/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File experimentos/experimento_truncado.ps1 -NumCtx 4096

$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '8192'
docker compose -f docker/docker-compose.yml up -d
powershell -ExecutionPolicy Bypass -File experimentos/experimento_truncado.ps1 -NumCtx 8192
```

Expected: `experimentos/resultados/experimento_truncado_ctx2048.csv`, `experimento_truncado_ctx4096.csv` and `experimento_truncado_ctx8192.csv`, one row per turn (10 long turns and one short final turn) with `promptTokens`, `historyMessages`, `answerIsJson` (strict) and `answerHasKeys` (relaxed). The deciding column is `answerHasKeys`. With 2048 and 4096 `promptTokens` levels off at a plateau (Experiment E: from turn 5 and turn 9) while `historyMessages` keeps growing; compare the share of `answerHasKeys` before and after that turn, including the short final turn, and with the 8192 control, where the history is expected to fit. This is how the note answers whether the system message survives when the prompt is reduced to half. Any outcome is valid, including an inconclusive one; it is recorded under "System message and truncation" in "Validation results". The stack is left on 8192.

## Validation results

Filled in when the quickstart is run (tasks of the final phase).

### Baseline before memory (T002, 2026-10-27)

Measured on the stack built from the feature 005 code (no memory code yet; Ollama default context, `num-ctx` not set), `POST /api/v1/chat` without `sessionId`, `temperature` 0.0, `numPredict` 1, `default` template. This is the reference for SC-005 (T042).

| Question | `usage.promptTokens` |
|----------|----------------------|
| `Que es una API?` (with its accent) | 73 |
| `Say hi in one sentence` | 73 |
| `Name one colour` | 71 |

A repeat of the second question twice gave 73 both times, so the figure is stable at this temperature.

### Experiment E (T038, 2026-10-27)

Three runs of `experimento_e.ps1` (20 turns, `tutor` template, `numPredict` 400, `temperature` 0.0, `seed` 42), one per context size, each with a `memoria` run (one `sessionId`) and a `base` run (no `sessionId`). Source: `experimentos/resultados/experimento_e_resumen.csv`; chart: `experimentos/graficas/grafica_3_prompttokens_vs_turno.png`. All 120 requests answered 200.

| `num-ctx` | `memoria`: window full at turn | `memoria`: plateau from turn | `promptTokens` at turns 1 / 11 / 20 | max | `base` at turns 1 / 11 / 20 |
|-----------|-------------------------------|------------------------------|-------------------------------------|-----|-----------------------------|
| 2048 | 11 | 5 | 219 / 1791 / 1791 | 1793 | 219 / 220 / 218 |
| 4096 | 11 | 9 | 219 / 3887 / 3887 | 3888 | 219 / 220 / 218 |
| 8192 | 11 | none | 219 / 5458 / 5459 | 5460 | 219 / 220 / 218 |

- **Window:** `historyMessages` grows by 2 per turn and reaches 20 at turn 11 in the three runs, then stays at 20. Without `sessionId` it is 0 in every turn.
- **Plateau:** with 2048 `promptTokens` stops growing at turn 5 (1791, then 1792, 1791...) and with 4096 at turn 9 (3885 from then on), while `historyMessages` keeps rising (8 to 20 and 16 to 20). With 8192 there is no plateau before the window fills: `promptTokens` grows up to 5458 at turn 11 and levels off only because the 20-message window is full, with `historyMessages` also stopped.
- **What did not match the expectation:** a **sharp fall** of `promptTokens` to about half of the context size (the T013 behaviour for a single oversize prompt: 1026, 2050, 4098) was expected at 2048 and 4096. It did not happen: the result is a **plateau**. Ollama drops the oldest messages one at a time (about 525 tokens per step, the size of one exchange) and answers 200 without a signal, so the prompt stays just under the context size. The detection criterion was changed accordingly (variation under 1% while `historyMessages` rises; column `turno_meseta`). The turns where the prompt first stops growing (5 and 9) match the estimates; the shape does not. The size of an exchange measured in T035 (523 to 524 tokens) holds.
- **SC-004** (with default settings `promptTokens` rises in the first turns and, from the turn where the window is full, stays within 25% of its value at that turn): met. The variation between turn 11 and turn 20 is 0% at 2048 and 4096 and about 0.02% at 8192.
- **SC-008** (one CSV per context size with 20 rows per run, `memoria` and `base`, and a summary with the turn the window fills and the turn of the plateau, or none): met. `experimento_e_ctx2048.csv`, `experimento_e_ctx4096.csv` and `experimento_e_ctx8192.csv` have 40 rows each (20 `memoria`, 20 `base`) and `experimento_e_resumen.csv` has the six summary rows.
- **Not shown by this experiment:** which messages Ollama keeps when it drops them, and whether the system message survives; that is T047.

### System message and truncation (T047, 2026-10-28)

Three runs of `experimento_truncado.ps1` (one `extractor` session per context size: 10 turns with a different paragraph of about 245 words, then one short final turn; `numPredict` 200, `temperature` 0.0, `seed` 42). Source: `experimentos/resultados/experimento_truncado_ctx2048.csv`, `_ctx4096.csv` and `_ctx8192.csv`. The `extractor` system message demands a JSON object with `entidades`, `fechas` and `resumen`. All 33 requests answered 200, and `answerHasKeys` (the deciding column) and `answerIsJson` (strict) were `True` in every turn of the three runs.

| Turn | 2048 `promptTokens` | 4096 `promptTokens` | 8192 `promptTokens` | `historyMessages` |
|------|---------------------|---------------------|---------------------|-------------------|
| 1 | 499 | 499 | 499 | 0 |
| 2 | 979 | 979 | 979 | 2 |
| 3 | 1463 | 1463 | 1463 | 4 |
| 4 | 1960 | 1960 | 1960 | 6 |
| 5 | 1971 | 2453 | 2453 | 8 |
| 6 | 1991 | 2946 | 2946 | 10 |
| 7 | 1997 | 3446 | 3446 | 12 |
| 8 | 1991 | 3940 | 3940 | 14 |
| 9 | 2001 | 4058 | 4437 | 16 |
| 10 | 1997 | 4073 | 4932 | 18 |
| 11 (short final) | 1744 | 3727 | 5070 | 20 |

- **Plateau** (variation under 1% from the previous turn while `historyMessages` rises): turn 5 at 2048 (1960 to 1971), turn 10 at 4096 (4058 to 4073), none at 8192, where `promptTokens` grows in every turn up to 5070 at turn 11 and the prompt fits. At 4096 `promptTokens` rose 3% at turn 9 (3940 to 4058) before levelling off, so the first sign of the limit there is the turn 9 slowdown and the plateau criterion fires at turn 10. In both truncated runs the prompt stays close to the context size (about 2000 of 2048 and about 4060 of 4096).
- **Result:** `answerHasKeys` was `True` before the plateau, from the plateau on and in the short final turn, with the three context sizes, and so was `answerIsJson`. The control (8192, no plateau) behaved the same. No turn lost the JSON format, so nothing was observed that would show the system instructions being dropped.
- **Reservation (a), the result does not prove the system message is kept:** the recent history sent with each turn contains the previous JSON answers, so the model could have imitated that pattern even if it had lost the instruction. The result is compatible with the system message being kept, but it does not demonstrate it. A test that would: a final turn after enough history is dropped that the early JSON answers are gone too, or a template whose answers do not look like the instruction (not run).
- **Reservation (b), few turns after the plateau at 4096:** only two turns have the prompt at the limit (turn 10, and the short final turn 11), against seven at 2048 (turns 5 to 11). The 4096 run carries little weight as evidence of what happens after truncation.
- **What the figures show and nothing more:** with 2048 and 4096 `promptTokens` levels off while `historyMessages` keeps rising, so part of what was sent is not counted; which part Ollama keeps is not determined by these data. `historyMessages` is the number of messages the backend sent, not the number Ollama processed.

### Final checks (T042, T044, T045, 2026-10-28)

Stack built from the final code, `SPRING_AI_OLLAMA_CHAT_NUM_CTX` 8192 (checked inside the container).

**SC-005, behaviour without `sessionId` (T042).** The three baseline requests of T002 repeated with the same parameters (`temperature` 0.0, `numPredict` 1, `default` template, no `sessionId`), twice each:

| Question | Baseline `promptTokens` | Now (both runs) | Difference |
|----------|-------------------------|-----------------|------------|
| `Que es una API?` | 73 | 73 and 73 | 0% |
| `Say hi in one sentence` | 73 | 73 and 73 | 0% |
| `Name one colour` | 71 | 71 and 71 | 0% |

All three are within 5% (identical). `historyMessages` is 0 in every answer. Two caveats about the baseline: the file `evidence/linea_base_memoria.txt` holds a single model answer (an identity question) and **no `promptTokens`**, so the reference for this check is the T002 table above, not that file; the file is valid UTF-8 (its accents and the opening `¿` read correctly), so the repaired encoding does not affect this comparison. The T002 note does not say whether the first question carried the accent (`Qué`); both spellings were run now and both give 73.

**SC-009 and FR-015 (T044).** Rejected requests, `POST /api/v1/chat` with an invalid `sessionId`, each answered 400 naming the field `sessionId`:

| `sessionId` | HTTP | Time |
|-------------|------|------|
| `""` | 400 | 491 ms (first request after a pause) |
| three spaces | 400 | 23 ms |
| `a b` | 400 | 15 ms |
| 65 characters | 400 | 14 ms |
| `café` | 400 | 12 ms |

All five are under 1 s. Logs: one session turn was sent with a distinctive question (`Zorbaxian quokka telescopio, dime una curiosidad del planeta Marte`, answer about Marte and Olympus Mons) and `docker logs pruebachat_backend` was read (213 lines, 21 `Received chat request` lines). The question appears only in the existing `Received chat request ... question=` line (0 other lines contain `question=` or the distinctive words), and none of the answer's words (`Olympus`, `Mons`, `27 km`, `lava`, `volcán`...) appears in any line. The turn's other lines carry only the session id, lengths, `historyMessages` and token figures; the validation failures log only `fields=[sessionId]`, and the response line prints the object reference (`ChatResponse@109cd5d1`), not its content.

**Quickstart run (T045).**
- `gradle cleanTest test` in `backend`: BUILD SUCCESSFUL, **206 tests, 0 failures, 0 errors, 0 skipped**.
- `bash scripts/smoke_test.sh`: "Smoke test passed", exit 0, 19 s.
- `bash tests/integration/test_chat_flow.sh`: "Integration smoke test passed", exit 0, 2 min 16 s. The memory block gave `historyMessages` 0, 2 and 4 in one session, a second session started at 0, and the invalid ids (`""`, `a b`, 65 characters) gave 400; the recall question answered "Ana." (no warning printed).

### Memory with the real model (T043, 2026-10-28)

Source: `experimentos/resultados/experimento_memoria.csv` (`experimento_memoria.ps1`, `numPredict` 60, `temperature` 0.0, 70 requests, 0 not valid (HTTP other than 200)). **Informative only: no result here makes SC-001 pass or fail** (SC-001 is proven with the simulated model, T014); SC-002 is read from the interleaved sessions.

| Group | Result |
|-------|--------|
| 10 dependent pairs, 10 fresh sessions (`con_memoria`) | the second answer uses the fact in **9 of 10** |
| the same 10 second questions alone, 10 fresh sessions (`sin_memoria`) | **0 of 10** (every answer says it has no information) |
| two interleaved sessions of 20 turns (`intercaladas`) | **0 of 40** responses contain the other session's word (SC-002 met) |
| requests not valid | **0 of 70** |

**The miss** (`con_memoria`, session `mem-con-20261028000701-3`, turn 2, `historyMessages` 2, so the history did reach the model): the question was `¿Cuál es mi número de la suerte?` and the model answered: "No tengo información sobre un número de la suerte específico para ti. La suerte es subjetiva y puede variar de persona a persona. ¿Quieres compartir contigo mismo qué número consideras tu número de la suerte?" The fact (47) had been given in turn 1 of that session; the 3B model did not use it.

**Own secret word in the interleaved sessions: 1 of 4 per session, and why.** Each session gave its word in turn 1 and asked for it again in turns 6, 12, 17 and 20 (both sessions the same: the word was recalled once in each, at turn 6, and missed at turns 12, 17 and 20). The CSV explains it by the 20-message window: at turn 6 `historyMessages` is 10 and the history still contains turn 1 (the answers: "Ornitorrinco. Recuerdo que me la dijiste al principio." and "Recuerdo que tu palabra secreta es \"cardamomo\"."); from turn 12 on `historyMessages` is 20, a full window, which holds the last 10 exchanges (turns 2 to 11 at turn 12) and turn 1 has been dropped, so the later questions get answers such as "No tengo información sobre tu palabra secreta." The model was not forgetting a word it could see; the word was no longer sent. That is the behaviour the window is designed to have (FR-005).

**What this part of the design shows and what it does not:** because the word was given only in turn 1 and the questions come after the window has moved on, it tests the limit of the window, not recall in long conversations (a fact repeated or given later, or a conversation where the fact stays inside the window, was not measured here). The 9 of 10 in the first group is the only figure about how often the 3B model uses the history, over ten short, one-fact conversations.

### Scope checks (T046, 2026-10-28)

- **Baseline:** `main` is the initial commit of the repository (the frontend and `ChatController` are not in it), so a diff against `main` lists everything built since features 003 to 005 and says nothing about this feature. The checks below use the last commit before feature 006 (`5bd5cbc`, "Record the full quickstart run (005 T057)").
- `git diff --stat 5bd5cbc -- frontend`: nothing (0 lines); no commit of this feature touches `frontend/`. (`git diff --stat main -- frontend` does list 10 files, all of them from earlier features.)
- The log line `Received chat request sessionId={} question={}` of `ChatController.java` (line 61) is unchanged: no added or removed line mentioning `question=` against `5bd5cbc`. (The `sessionId={}` part was already in that line.)
- Files changed by this feature outside the code, specs, experiments, scripts, tests and docker folders: only `.github/copilot-instructions.md` (the agent-context pointer updated by `/speckit-plan`).
- `git status` before this commit: only `experimentos/experimento_memoria.ps1` and `experimentos/resultados/experimento_memoria.csv` (and, in this commit, the quickstart and tasks edits).
- Stack: `docker exec pruebachat_backend printenv SPRING_AI_OLLAMA_CHAT_NUM_CTX` gives **8192**.
