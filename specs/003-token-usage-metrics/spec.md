# Feature Specification: Chat Usage Metrics

**Feature Branch**: `003-token-usage-metrics`

**Created**: 2026-10-15

**Status**: Draft

**Input**: User description: "Quiero que cada respuesta del chat informe el consumo de tokens (de entrada, de salida y total), el modelo usado, la razón de terminación y los tokens por segundo, para poder medir el costo y el rendimiento de cada consulta."

## Clarifications

### Session 2026-10-15

- Q: Which time base is used for tokens per second? → A: Only the response generation time (excludes model loading and prompt processing).
- Q: What is shown for tokens per second when it cannot be computed (zero output tokens or zero time)? → A: "Not available" (never 0).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See usage and performance of each answer (Priority: P1)

A person using the chat asks a question and, together with each answer, sees a compact summary of that query: tokens consumed by the question (input), tokens in the answer (output), the total, the model that produced the answer, why the answer ended, and the generation speed in tokens per second.

**Why this priority**: This is the core of the request. Without per-answer metrics, cost and performance cannot be measured.

**Independent Test**: Send one question in the chat and verify the answer is displayed along with all six values (input tokens, output tokens, total tokens, model, finish reason, tokens per second), and that total equals input plus output.

**Acceptance Scenarios**:

1. **Given** the chat is running and the model is available, **When** the user sends a question, **Then** the answer is shown with input tokens, output tokens, total tokens, model name, finish reason and tokens per second.
2. **Given** an answer with metrics is shown, **When** the user checks the values, **Then** total tokens equals input tokens plus output tokens.
3. **Given** the user sends two different questions in a row, **When** both answers are shown, **Then** each answer shows its own metrics, independent of the other.

---

### User Story 2 - Compare queries within a conversation (Priority: P2)

A person reviewing a conversation can look back at earlier answers and still see the metrics that belonged to each of them, so they can compare the cost and speed of different questions.

**Why this priority**: Measuring cost and performance means comparing queries; metrics that vanish when the next answer arrives limit that.

**Independent Test**: Send three questions, then verify every answer in the visible conversation still shows its own metrics.

**Acceptance Scenarios**:

1. **Given** several answers are visible in the conversation, **When** the user scrolls through them, **Then** each answer keeps the metrics it was delivered with.

---

### User Story 3 - Understand when metrics are unavailable (Priority: P3)

If the model does not provide some of the values (for example token counts), the person sees a clear "not available" indication for just those values instead of wrong numbers, blanks, or a failed answer.

**Why this priority**: Prevents misleading cost data and keeps the chat usable when information is partial.

**Independent Test**: Simulate a response lacking token counts and verify the answer still appears, with the missing values labelled as not available.

**Acceptance Scenarios**:

1. **Given** the model response lacks some usage values, **When** the answer is shown, **Then** the answer text is displayed normally and each missing value is marked as not available.
2. **Given** a query fails with an error, **When** the error is shown, **Then** no fabricated metrics are displayed.

---

### Edge Cases

- Answer finishes extremely fast or with zero output tokens: tokens per second must not show an error or infinite value; show "not available".
- Answer is cut off because the length limit was reached: the finish reason must reflect this so the user can tell the answer is incomplete.
- Model returns no finish reason: show "not available".
- Very long questions or answers: token counts remain accurate and the summary layout stays readable.
- Model name differs between requests (for example, configuration changed): each answer shows the model that actually produced it.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST report, with every successful chat answer, the number of input tokens consumed by the query.
- **FR-002**: System MUST report, with every successful chat answer, the number of output tokens generated.
- **FR-003**: System MUST report the total tokens for the query, equal to input plus output tokens.
- **FR-004**: System MUST report the name of the model that produced the answer.
- **FR-005**: System MUST report the reason the generation ended (for example, completed normally, length limit reached).
- **FR-006**: System MUST report the generation speed in tokens per second for the query, computed as output tokens divided by the response generation time only, excluding model loading and prompt processing time.
- **FR-007**: The chat interface MUST display these metrics next to each answer, in a form distinct from the answer text.
- **FR-008**: Metrics MUST be tied to the specific answer they describe and remain visible with it while the conversation is displayed.
- **FR-009**: When any metric value is unavailable from the model, the system MUST still deliver the answer and mark only that value as not available, never showing an invented value.
- **FR-010**: Failed queries MUST NOT display metrics.
- **FR-011**: Token counts MUST be shown as whole numbers and tokens per second with at most two decimal places.

### Key Entities

- **Usage Metrics**: The measurements for one answer: input tokens, output tokens, total tokens, model name, finish reason, tokens per second. Belongs to exactly one answer.
- **Chat Answer**: The reply text for one question; has exactly one Usage Metrics record when the query succeeds.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of successful chat answers display all six metrics (or an explicit "not available" for any the model did not supply).
- **SC-002**: For every answer, displayed total tokens equals input plus output tokens.
- **SC-003**: A user can find the cost and speed figures of any answer in the conversation within 5 seconds, without leaving the chat.
- **SC-004**: Showing metrics adds no perceptible delay: the answer appears no more than 5% later than before the feature.
- **SC-005**: In a 10-question session, 0 answers display metrics belonging to a different answer.

## Assumptions

- Applies to the existing chat flow (question in, single answer out) in this demo; no new user accounts or permissions.
- Metrics are shown per answer only; aggregated totals, dashboards, monetary cost conversion and long-term storage are out of scope.
- Metrics are informational and not persisted beyond what the conversation display already keeps, consistent with the project's data-minimization principle.
- The model provider supplies token counts and finish reason; when it does not, the "not available" behavior applies.
- The feature must work in the local Docker-based demo setup.
