# Feature Specification: Conversation Memory

**Feature Branch**: `006-conversation-memory`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "Feature 006-conversation-memory: memoria de conversación. Hoy cada petición al chat llega al modelo como si fuera la primera, aunque el cliente envíe el mismo sessionId, porque la API no guarda historial. Se necesita que las preguntas dentro de la misma sesión recuerden los turnos anteriores, usando una ventana de mensajes con un tamaño máximo configurable (20 por defecto) y descartando los más antiguos al excederlo, preservando siempre el mensaje de sistema. Debe convivir con las plantillas de la feature 005 (la plantilla elegida sigue aplicando en cada turno) y con las métricas de uso de la feature 003 (promptTokens crece con el historial y debe seguir reportándose). Sesiones distintas no deben mezclar su historial. Una petición sin sessionId debe seguir funcionando como hasta ahora. Se necesita además poder medir cómo crece promptTokens a lo largo de una conversación de 20 turnos (Experimento E). Un cliente no debe poder leer ni modificar el historial de otra sesión."

## Clarifications

### Session 2026-10-27

- Q: How is silent forgetting made observable? → A: A new field `historyMessages` in the chat response reports how many history messages were sent to the model; truncation by the model's context size is still deduced from `promptTokens` (no extra flag).
- Q: Is the session identifier case-sensitive? → A: Yes; `Demo` and `demo` are different sessions.
- Analysis resolution (2026-10-27): the smallest window is 2 messages (one question and its answer); a smaller value stops the service at startup with a message that names the setting and the minimum.
- Analysis resolution: SC-001 checks that the history of the session reaches the model (verifiable with a simulated model); how often the real model uses that history is measured and reported, not required.
- Analysis resolution: FR-006 requires the system instructions to be sent to Ollama in every turn; whether Ollama keeps them when it truncates a prompt is measured (T047), not assumed.
- Analysis resolution: `promptTokens` is the number of tokens the model processed, which can be lower than the number sent when the prompt exceeds the context size.
- Measured finding (T013, 2026-10-27, research R7): Ollama does not truncate an oversize prompt up to the context size; it reduces a single oversize prompt to about half of the context size plus 2 tokens (1026 at 2048, 2050 at 4096, 4098 at 8192) and answers 200 without a signal. Experiment E (2026-10-27) measured the effect on a conversation: Ollama drops the oldest messages one at a time (about 525 tokens per step) and answers 200 without a signal, so `promptTokens` levels off at a plateau (1791 from turn 5 at 2048; 3885 from turn 9 at 4096) while `historyMessages` keeps growing; at 8192 `promptTokens` keeps growing until the window is full (5458 at turn 11). The signal is therefore a plateau of `promptTokens` while `historyMessages` keeps growing, not a fall. Ollama's default context here appears to be 4096 (inference: the unset default gave 2050, the same as an explicit 4096).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Follow-up questions remember the conversation (Priority: P1)

A person using the chat API sends several questions with the same session identifier. Each answer takes into account the earlier questions and answers of that session, so a follow-up such as "and what about its history?" is understood in context instead of being treated as a first question.

**Why this priority**: It is the core of the request: today the session identifier has no effect on what the model knows.

**Independent Test**: In one session, send "My name is Ana" and then "What is my name?"; the second answer mentions Ana. Send the second question alone in a new session; the answer does not.

**Acceptance Scenarios**:

1. **Given** a session with earlier turns, **When** a new question is sent with the same session identifier, **Then** the model receives the earlier turns of that session together with the new question.
2. **Given** a first request with a session identifier that has no history yet, **When** it is sent, **Then** it behaves like a request without history and the session starts to keep its turns.
3. **Given** two different session identifiers, **When** each receives its own questions in any order, **Then** the answers of one never use the turns of the other.
4. **Given** a request with no session identifier, **When** it is sent, **Then** it is answered exactly as before this feature: no history is read and none is kept.

---

### User Story 2 - The history window has a limit (Priority: P2)

The operator sets the maximum number of conversation messages kept per session (20 by default). When a session grows past that number, the oldest messages are dropped first, so the cost of a request stops growing. The system instructions of the chosen template are always sent and never count as a dropped message.

**Why this priority**: Without a limit, memory makes every request slower and more expensive until the model's context is exceeded; the limit makes memory safe to ship.

**Independent Test**: With the window set to 4, send six questions in one session; the sixth request carries only the 4 most recent messages plus the system instructions, and the first turn is no longer remembered.

**Acceptance Scenarios**:

1. **Given** a session that already holds the maximum number of messages, **When** a new turn is added, **Then** the oldest messages are removed until the window is back at its maximum, and the newest are kept.
2. **Given** any size of history, **When** a request is sent, **Then** the system instructions are present in that request and are never removed by the window.
3. **Given** no configuration, **When** the service starts, **Then** the window is 20 messages.
4. **Given** an invalid window size (smaller than 2, negative, or not a number), **When** the service starts, **Then** it refuses to start with a message that names the setting and says the minimum is 2.

---

### User Story 3 - Memory works with templates and usage metrics (Priority: P2)

The template chosen for a turn (feature 005) shapes that turn's system instructions, whichever template earlier turns used. The usage figures of feature 003 are still reported on every answer, and `promptTokens` reflects the history sent, so it grows as the conversation grows until the window is full.

**Why this priority**: The two earlier features must not break, and the token growth is the visible cost of memory.

**Independent Test**: In one session send the same question three times with the `concise` template, then once with `tutor`; `promptTokens` is reported each time and grows over the first three; the fourth answer follows the `tutor` style while remembering the earlier turns.

**Acceptance Scenarios**:

1. **Given** a session whose earlier turns used one template, **When** a turn names another template, **Then** that turn is answered with the new template's instructions and the earlier turns are still remembered.
2. **Given** a request without a template identifier, **When** it is sent in a session, **Then** the default template applies, as in feature 005.
3. **Given** a session with history, **When** an answer is returned, **Then** `usage` is present with the same fields as in feature 003, and `promptTokens` counts the history that was sent.
4. **Given** an unknown template identifier or invalid generation settings, **When** the request is sent, **Then** it is rejected with HTTP 400 as before and the session history does not change.

---

### User Story 4 - A session belongs to its owner only (Priority: P1)

A client can neither read nor change the history of a session that is not its own. The API offers no way to read a history, and a request can only add its own turn to the session it names.

**Why this priority**: A conversation can contain personal data; leaking it between clients would be a privacy failure.

**Independent Test**: With two clients, A holds a conversation in session S1. Client B, without S1's identifier, tries every way the API offers and cannot obtain A's turns or alter them.

**Acceptance Scenarios**:

1. **Given** a session with history, **When** any client sends a request, **Then** no response contains messages from a session other than the one named in that request.
2. **Given** the API, **When** its endpoints are listed, **Then** none returns a history, lists session identifiers, or edits or deletes stored messages.
3. **Given** a request whose session identifier is empty, longer than 64 characters, or contains anything other than letters, digits, hyphen and underscore, **When** it is sent, **Then** it is rejected with HTTP 400 before the model is called and the history of every session is untouched. A short value such as `demo` is accepted.
4. **Given** the service logs, **When** a conversation takes place, **Then** question texts and answers kept as history do not appear in any new log line written by this feature.

> **Security note (explicit limit of this feature)**: there is no authentication. The only protection of a session is that its identifier is not guessed by another client, and a short identifier such as `demo` is easy to guess, so this protection is weak by design. What the feature does guarantee: no endpoint returns a history, session identifiers cannot be listed, and sessions are never mixed. Real authentication is left to the guardrails feature (Point 5).

---

### User Story 5 - Measure how the prompt grows over 20 turns (Priority: P3)

A workshop participant runs a script (Experiment E) that holds a 20-turn conversation in one session and records `promptTokens` and `historyMessages` after each turn, at three context sizes (2048, 4096 and 8192), so the growth caused by memory, the flattening once the window is full and the plateau when the context size is exceeded can be seen and compared with a conversation without a session identifier.

**Why this priority**: It lets the cost of memory be measured rather than assumed, and it is the project's usual way to validate a feature's claims.

**Independent Test**: Run the experiment; a table of 20 rows with `promptTokens` and `historyMessages` per turn and a summary is saved, for the session run and for the run without session identifier, at each of the three context sizes.

**Acceptance Scenarios**:

1. **Given** the service running, **When** the experiment is run, **Then** it records for each of the 20 turns the turn number, `promptTokens`, `historyMessages`, `completionTokens` and elapsed time, in a file under `experimentos/resultados/`.
2. **Given** a conversation without a session identifier, **When** the same 20 questions are sent, **Then** `promptTokens` stays roughly constant, which serves as the baseline.
3. **Given** a window smaller than the 20 turns, **When** the experiment is run, **Then** the recorded `promptTokens` stops growing once the window is full; the summary states the turn where that happens.
4. **Given** the context sizes 2048, 4096 and 8192, **When** the experiment is run at each, **Then** in a run whose context size the prompt exceeds, `promptTokens` stops growing (variation under 1% from one turn to the next) while `historyMessages` keeps rising, and in a run where the prompt fits it does not; the summary states, per context size, the turn of the plateau or that there was none.

---

### Edge Cases

- A request with no `sessionId` field (absent or `null`): works as before and keeps nothing.
- A session identifier that is an empty string, only spaces, longer than 64 characters, or has any character other than letters, digits, hyphen and underscore: rejected with HTTP 400 (FR-016); it is never written raw to logs beyond a short abbreviation.
- Two requests at the same moment in the same session: both complete, and the history ends with both turns in a consistent order; neither is lost or duplicated.
- The model fails or the request is rejected: the failed turn is not added to the history, so a retry does not repeat it.
- A single question so long that history plus question exceeds the model's context: Ollama cuts the prompt without any signal; measured, it reduces a single oversize prompt to about half of the context size plus 2 tokens (research R7); with a growing history it drops the oldest messages one at a time instead (Experiment E, research R10). Which part is kept (the oldest history, or also the system instructions) is not assumed here; it is measured in T047. Measured result (T047): with the `extractor` template, from the turn where `promptTokens` levelled off (turn 5 at 2048, turn 10 at 4096, none at 8192) every answer, including the short final turn, still had the JSON keys required by the template's system message; this is compatible with the system message being kept but does not prove it, because the history contains earlier JSON answers (see quickstart, "System message and truncation"). If the question alone is too long, the existing behaviour for such a question applies.
- Window size smaller than 2 (0, 1, negative): not allowed; the service refuses to start with a message that names `app.memory.max-messages` and the minimum 2 (FR-007). With a window of 2 only the latest question and its answer are kept; the system instructions are still sent.
- The service restarts: histories are lost and sessions start empty (see Assumptions).
- Many sessions: the service must not grow without bound (see FR-012).
- History larger than the model's context window: the model silently cuts part of the prompt and can "forget" turns without any warning, even though the message window has not been exceeded; `promptTokens` then stops growing (plateau) while `historyMessages` keeps rising. This must be observable (FR-018) and the context size must be configurable (FR-019).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: Requests that carry the same session identifier MUST be answered with the earlier questions and answers of that session available to the model, in their original order.
- **FR-002**: Requests with different session identifiers MUST NOT share history; the history of a session MUST NOT be visible to any other session in any request or response.
- **FR-003**: A request without a session identifier (field absent or `null`) MUST be answered exactly as before this feature and MUST NOT read or write any history.
- **FR-004**: Each session MUST keep at most a configurable number of conversation messages (the window), at least 2 and 20 by default; a question and its answer count as two messages.
- **FR-005**: When a session would exceed the window, the oldest messages MUST be discarded first and the newest kept; the discard MUST NOT leave an answer whose question was dropped as the first message of the history.
- **FR-006**: The system instructions of the template used for the current request MUST be sent to Ollama in every turn, and MUST NOT count towards the message window or be removed by it. What Ollama keeps when it truncates a prompt that exceeds the context size is outside the control of this feature; it is measured and recorded in the edge cases.
- **FR-007**: The window size MUST be read from configuration; a value that is not a whole number of at least 2 MUST stop the service from starting, with an error that names the setting and the minimum.
- **FR-008**: The template chosen in a request (or the default) MUST apply to that turn regardless of the template used in earlier turns; the history MUST store only conversation messages, not system instructions.
- **FR-009**: Every successful answer MUST keep reporting the usage figures of feature 003, and `promptTokens` MUST be the number of tokens the model processed for that request, which includes the history sent. When the prompt exceeds the context size Ollama processes only part of it (it drops the oldest messages, about 525 tokens per step in Experiment E) and `promptTokens` reports that smaller number, so the figure can be lower than what was sent; a plateau of `promptTokens` (variation under 1% from the previous turn) while `historyMessages` keeps rising signals that the prompt was truncated.
- **FR-010**: A turn MUST be added to the history only when the request succeeded; rejected requests (HTTP 400) and model failures MUST leave the history unchanged.
- **FR-011**: The API MUST NOT offer any operation that returns, lists, edits or deletes the stored history or the existing session identifiers, and no response MAY contain history messages other than the answer to the current question.
- **FR-012**: The memory MUST be bounded: sessions that stay unused for a configurable time MUST be forgotten, and the total number of sessions kept MUST have a configurable upper limit, with the least recently used session dropped first.
- **FR-013**: Concurrent requests in the same session MUST NOT corrupt or lose history: the final history MUST contain every successful turn exactly once.
- **FR-014**: The chat request and response shapes MUST remain compatible with features 003, 004 and 005; a client that ignores memory MUST see no change except that a session identifier now has an effect and that successful chat answers carry the extra field `historyMessages` (FR-018); no existing field changes.
- **FR-015**: Question texts, answers and history contents MUST NOT be written to any new log line added by this feature; logs MAY record the number of messages in the window and the abbreviated session identifier.
- **FR-016**: The client chooses the session identifier. It MUST be validated before the model is called: an empty string, a value longer than 64 characters, or a value with any character other than letters, digits, hyphen and underscore MUST be rejected with HTTP 400 in the existing error shape, naming the `sessionId` field, and MUST NOT change any history. Values such as `demo` MUST be accepted. Identifiers are compared exactly, so `Demo` and `demo` are different sessions. Without authentication, the only protection of a session is that its identifier is not guessed by another client; this limit is documented (see User Story 4) and real authentication is out of scope.
- **FR-017**: An experiment script (Experiment E) MUST send 20 turns in one session and record, per turn, `promptTokens`, `historyMessages`, `completionTokens` and elapsed time, plus a baseline run without a session identifier, saved as CSV files under `experimentos/resultados/`.
- **FR-018**: Every successful chat answer MUST include a field `historyMessages` with the number of history messages (questions and answers, not the system instructions or the current question) sent to the model for that request: 0 for a request without a session identifier or for the first request of a session, and never more than the window size. Together with `promptTokens` (FR-009), a client can tell that the window dropped messages (`historyMessages` stops growing) and that the model's context size was probably exceeded (a plateau of `promptTokens`, with a variation under 1% from the previous turn, while `historyMessages` keeps rising). No other flag is added.
- **FR-019**: The context size the model is given MUST be configurable, and its value MUST be recorded with the experiment results, so a run can be reproduced.

### Key Entities

- **Session**: A conversation identified by a case-sensitive session identifier. Holds an ordered list of messages and the time it was last used. Has no owner data beyond the identifier.
- **Message**: One question (from the user) or one answer (from the model) kept in a session. Has a role and a text; system instructions are not stored.
- **History window**: The most recent messages of a session, at most the configured maximum, that are sent to the model with the next question.
- **Memory settings**: Window size, idle time before a session is forgotten, and maximum number of sessions kept.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: In a scripted conversation, in 100% of the follow-up requests of a session the history of that session (earlier questions and answers, in order) reaches the model, and in 0% of the requests of a different session does it (checked on at least 10 pairs of sessions with a simulated model). How often the real model then uses that history is measured and reported, but is not part of this criterion.
- **SC-002**: With two sessions interleaved over 20 turns each, 0 answers or responses contain messages of the other session.
- **SC-003**: For a conversation longer than the window, the number of history messages sent never exceeds the window size, and the system instructions are present in 100% of requests.
- **SC-004**: With default settings, `promptTokens` rises on each of the first turns of a session and, from the turn where the window is full, stays within 25% of its value at that turn.
- **SC-005**: Requests without a session identifier have a `promptTokens` within 5% of the figure they had before this feature for the same question and template.
- **SC-006**: `usage` is reported in 100% of successful answers, with the same fields as before.
- **SC-007**: After 1000 requests in distinct sessions, the number of sessions held never exceeds the configured limit.
- **SC-008**: Experiment E produces, for each of the three context sizes (2048, 4096 and 8192), one CSV with 20 rows per run (with a session identifier and without, 40 rows in all), and the summary states the turn at which the window is full and, per context size, the turn of the plateau of `promptTokens` or that there was none.
- **SC-009**: Requests rejected for an invalid session identifier answer in under 1 second and change no history.
- **SC-010**: In a 20-turn run, the turn at which the window becomes full (`historyMessages` stops growing) and the turn at which `promptTokens` reaches a plateau while `historyMessages` keeps rising (context size exceeded) can be identified from the recorded data alone, and the context size used is recorded with the results.

## Assumptions

- Memory is kept in the running service only: history is lost when the service restarts, and no database or file is added (the project is a demo; see the constitution's data-minimization principle).
- The window counts messages, not turns: a question and its answer are two messages. Window size 20 means the last 10 exchanges.
- The default idle time before a session is forgotten is 30 minutes and the default maximum number of sessions is 100; both are configurable and may be changed in planning.
- Ollama has a finite context size (`num-ctx`). Measured (research R7): when the prompt (system instructions, history and question) is larger than it, Ollama does not process it up to the limit; it reduces a single oversize prompt to about half of `num-ctx` plus 2 tokens and, with a growing history, drops the oldest messages one at a time (Experiment E); it answers without any error or signal, so the model can "forget" turns even though the message window allows them. Which part of the prompt is kept is not assumed (see T047). Ollama's default context here appears to be 4096 (inference: the unset default gave the same figure as an explicit 4096). The message window (20 by default) is not guaranteed to fit in the context; the size of the messages and of the context decide it. This is mitigated by FR-018 and FR-019, not removed.
- The session identifier is the only thing that links a request to a session; there are no user accounts or login in this project.
- History is not summarised: dropped messages are simply forgotten.
- Chat requests keep the generation settings of feature 004 per request; they are not remembered between turns.
- The classification endpoint of feature 005 does not use memory; each classification is independent.
- The frontend and the existing line that logs the chat question (feature 007) are not changed by this feature.
- The `sessionId` rule (letters, digits, hyphen, underscore; 1 to 64 characters) was chosen by the project owner after weighing three options (service-issued identifiers, UUID-only identifiers, free identifiers); the free-identifier option with format validation was kept so workshop examples such as `demo` keep working.
- The model and its token counting are those of feature 003; `promptTokens` is whatever the model reports for the full prompt sent.
