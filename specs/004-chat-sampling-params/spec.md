# Feature Specification: Per-Request Chat Sampling Parameters

**Feature Branch**: `004-chat-sampling-params`

**Created**: 2026-10-25

**Status**: Draft

**Input**: User description: "Permitir que el cliente envíe, de forma opcional, temperature, topP, topK, numPredict y seed en cada petición del chat; si no los envía, se usan los valores por defecto actuales; los valores fuera de rango se rechazan con un error claro (HTTP 400). Rangos: temperature 0.0-2.0, topP 0.0-1.0, topK 0-200, numPredict 1-2048 (tope a confirmar en clarificación). Un valor 0.0 de temperatura es válido y distinto de "no enviado". Debe ser compatible hacia atrás con las peticiones existentes. Propósito: poder hacer experimentos controlados de variabilidad (temperatura, top-k, top-p) y truncamiento (numPredict) sin reconstruir ni reiniciar el backend."

## Clarifications

### Session 2026-10-25

- Q: What is the upper limit for numPredict? → A: 2048.
- Q: Must a wrongly typed value (e.g. `"temperature":"hot"`) be reported together with every other invalid setting? → A: No. Out-of-range and non-whole settings are reported together; a wrongly typed value or malformed JSON reports at least the first offending setting (see research.md R5).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Tune generation per request (Priority: P1)

A person running experiments sends a chat question and includes one or more generation settings (temperature, topP, topK, numPredict, seed) with that single request. The answer is produced using exactly those settings, while any setting not included keeps its current default. The next request can use completely different settings without restarting or rebuilding anything.

**Why this priority**: This is the core of the request: controlled variability and truncation experiments are impossible without per-request settings.

**Independent Test**: Send the same question twice, once with numPredict set to a small value (e.g. 5) and once without it; verify the first answer is cut off (finish reason indicates the length limit) and the second is not, with no restart in between.

**Acceptance Scenarios**:

1. **Given** the chat service is running, **When** a request includes numPredict = 5, **Then** the answer contains at most 5 output tokens and its finish reason indicates the length limit was reached.
2. **Given** the chat service is running, **When** a request includes temperature = 0.0, **Then** the answer is generated with temperature 0.0, not with the default temperature.
3. **Given** a request was just sent with custom settings, **When** the next request is sent without any settings, **Then** the next answer uses the default values (settings do not carry over between requests).
4. **Given** a request includes only topK, **When** the answer is generated, **Then** topK uses the sent value and every other setting uses its default.

---

### User Story 2 - Existing clients keep working unchanged (Priority: P1)

Any client that already talks to the chat (including the current chat UI) keeps sending requests exactly as before and gets the same behavior as before the feature.

**Why this priority**: Backward compatibility is an explicit requirement; breaking the existing demo flow is unacceptable.

**Independent Test**: Replay the existing chat request format (with no new fields) and verify it succeeds with the same response shape and the same default generation values as before.

**Acceptance Scenarios**:

1. **Given** a request in the current format with no generation settings, **When** it is sent, **Then** it succeeds and the answer is generated with the current defaults (temperature 0.7, numPredict 1024, and the model's own defaults for topP, topK and seed).
2. **Given** a request in the current format, **When** the response is returned, **Then** its structure is unchanged for that client.

---

### User Story 3 - Clear rejection of invalid settings (Priority: P2)

A person who sends a setting outside its allowed range, or of the wrong kind, gets an immediate, clear error explaining which setting is wrong and what values are allowed, and no answer is generated.

**Why this priority**: Experiments are only trustworthy if invalid settings are never silently clamped or ignored.

**Independent Test**: Send a request with temperature = 2.5 and verify a "bad request" error (HTTP 400) is returned that names temperature and its allowed range 0.0–2.0, and that the model was not invoked.

**Acceptance Scenarios**:

1. **Given** a request with temperature = 2.5, **When** it is sent, **Then** it is rejected with HTTP 400 and a message naming `temperature` and the range 0.0–2.0.
2. **Given** a request with topP = -0.1 and topK = 500, **When** it is sent, **Then** it is rejected with HTTP 400 and the message lists both invalid settings with their allowed ranges.
3. **Given** a request with numPredict = 0, **When** it is sent, **Then** it is rejected with HTTP 400 naming `numPredict` and its allowed range.
4. **Given** a request with topK = 3.5 or temperature = "hot", **When** it is sent, **Then** it is rejected with HTTP 400 indicating the expected kind of value.

---

### User Story 4 - Reproducible answers with a seed (Priority: P3)

A person sends the same question twice with the same seed and the same settings and gets the same answer, so experiment results can be repeated and compared.

**Why this priority**: Reproducibility strengthens experiments but is secondary to being able to vary the settings at all.

**Independent Test**: Send an identical question twice with seed = 42 and temperature = 0.8; verify both answers are identical.

**Acceptance Scenarios**:

1. **Given** the same model, question, settings and seed, **When** the request is sent twice, **Then** both answers are identical.
2. **Given** the same question and settings but different seeds, **When** sent at a non-zero temperature, **Then** the answers are allowed to differ.

---

### Edge Cases

- temperature = 0.0, topP = 0.0, topK = 0 are valid values and must be applied, not confused with "not sent".
- A setting explicitly sent as empty/null is treated the same as "not sent" (default applies).
- Boundary values (temperature 2.0, topP 1.0, topK 200, numPredict 1 and 2048) are accepted.
- Several out-of-range or non-whole settings in one request: all of them are reported in a single error. A wrongly typed value is reported on its own, and other problems in the same request may only appear after it is fixed.
- Unknown extra fields in the request are handled exactly as they are today (no new failure introduced).
- An invalid setting combined with a valid question: no answer is generated and no usage metrics are reported.
- A very small numPredict truncates the answer; the existing usage metrics show the length-limit finish reason.
- Concurrent requests with different settings: each answer uses only its own request's settings.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The chat request MUST accept five optional generation settings: temperature, topP, topK, numPredict and seed.
- **FR-002**: Any setting not sent (or sent as empty/null) MUST fall back to the current default: temperature 0.7, numPredict 1024; topP, topK and seed use the model's own defaults, as today.
- **FR-003**: Any setting that is sent and valid MUST be applied to that request only; it MUST NOT affect other requests, concurrent or later.
- **FR-004**: A sent value of 0 or 0.0 MUST be treated as a real value, distinct from "not sent".
- **FR-005**: The system MUST accept only these ranges (inclusive): temperature 0.0–2.0; topP 0.0–1.0; topK whole number 0–200; numPredict whole number 1–2048; seed any whole number.
- **FR-006**: Requests with any setting out of range or of the wrong kind MUST be rejected with HTTP 400 before any answer is generated.
- **FR-007**: The 400 error message MUST name every setting that is out of range or not a whole number where one is required, each with its allowed range. If a setting has the wrong kind of value (for example text instead of a number) or the body is not valid JSON, the message MUST name at least the first offending setting (or the body) and the expected kind of value.
- **FR-008**: Requests that do not include any of the new settings MUST behave exactly as before the feature (same defaults, same response shape).
- **FR-009**: Changing settings between requests MUST NOT require rebuilding, reconfiguring or restarting the service.
- **FR-010**: The existing usage metrics (from feature 003) MUST continue to be reported for answers generated with custom settings.
- **FR-011**: The documented request/response contract and example requests MUST be updated to show the new optional settings and the error format.

### Key Entities

- **Generation Settings**: Optional per-request values controlling how the answer is generated: temperature (variability), topP and topK (candidate selection), numPredict (maximum answer length in tokens), seed (reproducibility). Each value is either "not sent" (default applies) or a valid value within its range.
- **Chat Request**: The existing question sent to the chat, now optionally carrying one Generation Settings set.
- **Validation Error**: A rejection describing every invalid setting, with its allowed range or expected kind of value.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of existing-format requests (without new settings) succeed with the same defaults and response shape as before.
- **SC-002**: 100% of requests with an out-of-range, non-whole or wrongly typed setting are rejected with HTTP 400. Errors for out-of-range and non-whole settings name every offending setting. Errors for a wrongly typed setting name at least the first one.
- **SC-003**: An experimenter can run at least 10 consecutive requests with different settings in one session with zero restarts or rebuilds.
- **SC-004**: Requests with numPredict = N never produce more than N output tokens.
- **SC-005**: Two requests with the same question, settings and seed produce identical answers in at least 95% of repeated trials on the same model.
- **SC-006**: Invalid requests are rejected without invoking the model, returning in under 1 second.

## Assumptions

- The "client" is any caller of the chat HTTP endpoint (e.g. scripts, API tools); adding controls for these settings to the chat UI is out of scope for this feature.
- Defaults remain those currently configured (temperature 0.7, numPredict 1024); topP, topK and seed are not configured today and keep the model's defaults.
- Seed accepts any whole number in the usual 32-bit signed range; reproducibility depends on the model honoring the seed.
- The response does not need to echo back the applied settings; out of scope for this feature.
- There is no authentication or per-user restriction on who may send these settings, consistent with the demo scope.
- Settings are not persisted or logged beyond what current request logging already does, consistent with data-minimization.
- Must work in the local Docker-based demo setup with the existing local model.
