# Feature Specification: Prompt Templates and Structured Output

**Feature Branch**: `005-prompt-templates-structured-output`

**Created**: 2026-10-26

**Status**: Draft

**Input**: User description: "Feature 005-prompt-templates-structured-output: (1) Mover el prompt de sistema a plantillas editables fuera del código, con variables, y permitir que el cliente elija una plantilla registrada (por ejemplo conciso, tutor o extractor) enviando solo su identificador, nunca el texto del prompt. Si no envía identificador se usa una plantilla por defecto equivalente al comportamiento actual; un identificador desconocido se rechaza con un error claro (HTTP 400). (2) Un endpoint que, dado un texto, devuelva una clasificación estructurada con categoria, confianza y justificacion, y que permita elegir la estrategia para obtener el formato: solo instrucción en el prompt, validación del esquema con reintento, o esquema nativo del proveedor. La respuesta debe indicar si hubo reintento y cuántos, y el endpoint debe aceptar la temperatura por petición. Si el modelo no cumple el formato, el error debe ser claro. (3) Debe ser compatible hacia atrás con las peticiones existentes del chat y mantener las métricas de uso de la feature 003. Propósito: poder comparar la fiabilidad, el costo en tokens y el tiempo de cada estrategia sin recompilar."

## Clarifications

### Session 2026-10-26

- Q: Which categories does the classification use? → A: A fixed set of four: `consulta`, `queja`, `incidencia`, `otro`.
- Q: Who supplies the values of template variables? → A: Only the server; the client never sends variable values.
- Scope: the logging rule (FR-024) applies only to the classification operation and the new template handling; the existing chat-question log is left to feature 007.
- Scope: templates are files in `src/main/resources/prompts/`, edited without touching Java; applying a change requires rebuilding the backend image, and no external folder is mounted.
- Q: Do format-failure errors report the cost of the failed attempts? → A: Yes. The error includes the tokens accumulated across all attempts and the total elapsed time, labelled as consumed by a failed request.
- Q: What is the maximum length of the text to classify? → A: 5000 characters; longer texts are rejected with HTTP 400.
- Q: Does the chat response say which template was used? → A: No. The chat response shape does not change, with or without a template identifier.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Choose a chat personality by template identifier (Priority: P1)

A person using the chat API picks one of the registered prompt templates (for example concise, tutor or extractor) by sending only its identifier with the question. The answer is produced following that template's instructions. The client never sends or sees the prompt text itself.

**Why this priority**: It is the first half of the request: changing the model's behavior without touching code and without letting clients inject their own instructions.

**Independent Test**: Send the same question twice, once with the identifier of the concise template and once with the tutor template; verify the two answers follow clearly different styles, and that neither request contained any prompt text.

**Acceptance Scenarios**:

1. **Given** a registered template identifier, **When** a chat request includes it, **Then** the answer is generated with that template's instructions.
2. **Given** a request with an identifier that is not registered, **When** it is sent, **Then** it is rejected with HTTP 400, the error names the unknown identifier and lists the available identifiers, and the model is not called.
3. **Given** a request that sends text in the identifier field that is not a registered identifier (for example a full sentence of instructions), **When** it is sent, **Then** it is rejected like any unknown identifier and the text is never used as a prompt.
4. **Given** a template with variables, **When** it is applied, **Then** every variable is replaced by a value before the prompt reaches the model, and no unreplaced placeholder is ever sent to the model.

---

### User Story 2 - Existing chat clients and usage metrics keep working (Priority: P1)

Any client already using the chat keeps sending requests exactly as before: no template identifier is needed, the behavior matches the current system prompt, and each successful answer still carries the usage metrics (tokens, model, finish reason, tokens per second) and honors the optional generation settings.

**Why this priority**: Backward compatibility is an explicit requirement, and the previous features must not regress.

**Independent Test**: Replay a request in the current format and verify the answer, response shape, usage metrics and generation settings behave exactly as before this feature.

**Acceptance Scenarios**:

1. **Given** a request without a template identifier, **When** it is sent, **Then** the default template is used and the answer is equivalent in behavior to the current system prompt.
2. **Given** a successful chat answer using any template, **When** the response is returned, **Then** it contains the same usage metrics as before.
3. **Given** a request with a template identifier and generation settings (for example temperature), **When** it is sent, **Then** both are honored together, and invalid settings are still rejected with HTTP 400.

---

### User Story 3 - Classify a text into a structured result (Priority: P2)

A person sends a text to a classification endpoint and receives a structured result with three fields: a category, a confidence value and a short justification. The request can include the temperature to use.

**Why this priority**: It delivers the structured-output capability, but it is only useful for comparison once strategies exist (User Story 4).

**Independent Test**: Send a clearly classifiable text with the default strategy and verify the response contains a category from the allowed set, a confidence between 0 and 1, and a non-empty justification.

**Acceptance Scenarios**:

1. **Given** a non-empty text, **When** it is sent for classification, **Then** the response contains `category`, `confidence` and `justification`, and `category` belongs to the allowed set.
2. **Given** a request with a temperature, **When** it is sent, **Then** that temperature is applied to this request only; an out-of-range temperature is rejected with HTTP 400 as in the chat.
3. **Given** an empty or missing text, **When** it is sent, **Then** it is rejected with HTTP 400 and a clear message.
4. **Given** a successful classification, **When** the response is returned, **Then** it also contains the usage metrics (tokens, model, finish reason, tokens per second) and the total elapsed time.

---

### User Story 4 - Compare format strategies (Priority: P2)

A person running experiments chooses, per request, how the model is made to respect the format: (a) only an instruction in the prompt, (b) schema validation with retry, or (c) the provider's native schema support. The response says which strategy was used, whether a retry happened and how many, and reports tokens and time for the whole request including retries, so the strategies can be compared on reliability, token cost and time without rebuilding anything.

**Why this priority**: This is the stated purpose of the feature (compare reliability, cost and time of each strategy).

**Independent Test**: Send the same text with each of the three strategies; verify each response names its strategy, reports retry information, and reports tokens and elapsed time; repeat many times to compare failure rates.

**Acceptance Scenarios**:

1. **Given** the instruction-only strategy, **When** a text is classified, **Then** no retry is ever made and the response reports zero retries.
2. **Given** the validation-with-retry strategy and an answer that does not match the format on the first attempt, **When** a later attempt matches, **Then** the response is the valid result, reports that a retry happened and how many, and the usage metrics add up all attempts.
3. **Given** the validation-with-retry strategy and an answer that never matches within the allowed number of retries, **When** the limit is reached, **Then** the request fails with a clear error that states the number of attempts made.
4. **Given** the provider-native strategy, **When** a text is classified, **Then** the format is enforced by the provider and the response reports zero retries.
5. **Given** a request with no strategy, **When** it is sent, **Then** the default strategy is used and the response states which one it was.
6. **Given** a strategy name that does not exist, **When** the request is sent, **Then** it is rejected with HTTP 400 listing the valid strategies.

---

### User Story 5 - Clear error when the model does not meet the format (Priority: P3)

When the model's answer cannot be turned into the required structure (not valid structure, missing fields, a category outside the allowed set, a confidence out of range), the person receives a clear error saying what was wrong, never a malformed or partially filled result presented as valid.

**Why this priority**: It makes failures of the weaker strategies visible and measurable instead of silent.

**Independent Test**: Force an answer that does not match the format (for example with a stubbed model) and verify the error identifies the problem and the strategy and attempts used.

**Acceptance Scenarios**:

1. **Given** a model answer that is not valid structure, **When** no retry is allowed or retries are exhausted, **Then** the response is an error (not a success) that says the output did not meet the required format.
2. **Given** an answer with a missing field, a category outside the allowed set or a confidence outside 0–1, **When** it is processed, **Then** it is treated as not meeting the format and reported as such, naming the offending field.
3. **Given** a format failure, **When** the error is returned, **Then** it includes the strategy used, the number of attempts, the tokens accumulated across all attempts and the total elapsed time, labelled as consumed by a failed request, and it is never presented as a successful result.

---

### User Story 6 - Edit templates without touching code (Priority: P3)

A maintainer changes the wording of an existing template, or adds a new one, by editing or adding a file in the templates folder, without touching any Java code. After rebuilding the backend image and restarting, clients can use the changed or new template by its identifier.

**Why this priority**: It is what makes experiments possible without changing code, but it is secondary to the template and classification behavior itself.

**Independent Test**: Edit the wording of a template, rebuild the backend image and restart, and verify the answers follow the new wording; add a new template file and verify its identifier is accepted.

**Acceptance Scenarios**:

1. **Given** an edited template file, **When** the backend image is rebuilt and the service restarted, **Then** requests using that identifier follow the new wording.
2. **Given** a new template file added with a valid identifier, **When** the image is rebuilt and the service restarted, **Then** the identifier is accepted and appears in the list shown by the unknown-identifier error.
3. **Given** a template file that is malformed or refers to a variable with no value, **When** the service starts, **Then** the problem is reported clearly at startup and names the template, rather than failing later on a user request.

---

### Edge Cases

- A template identifier sent with different letter case or surrounding spaces: treated as unknown unless it matches a registered identifier exactly.
- A template identifier that is empty or `null`: treated as "not sent" and the default template applies.
- A variable value that contains text resembling instructions: it is inserted as plain content of that variable and cannot change which template is used.
- Two templates with the same identifier: reported at startup as a configuration error.
- The default template is missing: startup fails with a clear message, because requests without an identifier could not be served.
- Classification text longer than 5000 characters: rejected with HTTP 400 and a message stating the limit; a text of exactly 5000 characters is accepted.
- The model answers with the right structure wrapped in extra text or code fences: counted according to the chosen strategy (the instruction-only strategy treats it as not meeting the format; validation-with-retry may retry).
- A retry that costs more tokens than the first attempt: the reported totals still include every attempt.
- Concurrent requests with different templates, strategies or temperatures: each one uses only its own choices.
- The model service is unavailable during classification: the error is reported as a service failure, not as a format failure.

## Requirements *(mandatory)*

### Functional Requirements

**Prompt templates**

- **FR-001**: The system prompt MUST be defined in templates stored as files in `src/main/resources/prompts/`, each with a unique identifier, so they can be edited without touching Java code. Applying a change to a template requires rebuilding the backend image; no external folder is mounted.
- **FR-002**: Templates MUST support variables that are replaced with values before the prompt is sent to the model; a prompt with an unreplaced variable MUST never reach the model.
- **FR-003**: The chat request MUST accept an optional template identifier. The client MUST NOT be able to send the text of a prompt.
- **FR-004**: When no identifier (or an empty/null one) is sent, the system MUST use the default template, whose behavior is equivalent to the system prompt used today.
- **FR-005**: An identifier that does not match a registered template MUST be rejected with HTTP 400 before the model is called; the error MUST name the unknown identifier and list the available ones.
- **FR-006**: The set of registered templates MUST include at least the default template and templates for concise, tutor and extractor behavior.
- **FR-007**: The values of template variables MUST be supplied only by the server (fixed or configured values). The client MUST NOT be able to send or override any variable value.
- **FR-008**: Template problems (duplicate identifier, missing default, malformed file, variable without a value) MUST be detected when the service starts and reported with the template name.

**Structured classification**

- **FR-009**: The system MUST provide a classification operation that, given a text, returns a structured result with exactly three fields: category, confidence and justification.
- **FR-010**: The category MUST be one of a fixed set of four allowed values: `consulta`, `queja`, `incidencia` and `otro`. The client cannot send its own list, and the set is documented in the contract.
- **FR-011**: The confidence MUST be a number from 0.0 to 1.0 inclusive, and the justification MUST be a non-empty text.
- **FR-012**: The classification request MUST accept an optional temperature with the same range and error behavior as the chat settings (0.0–2.0 inclusive; HTTP 400 when invalid); a temperature of 0.0 is a real value distinct from "not sent".
- **FR-013**: An empty or missing text MUST be rejected with HTTP 400 and a clear message. A text longer than 5000 characters MUST also be rejected with HTTP 400, and the message MUST state the limit.

**Format strategies**

- **FR-014**: The classification request MUST accept an optional strategy among exactly three: instruction-only, validation-with-retry, and provider-native schema. An unknown strategy MUST be rejected with HTTP 400 listing the valid ones.
- **FR-015**: If no strategy is sent, the system MUST use the instruction-only strategy and state it in the response.
- **FR-016**: With the instruction-only strategy the system MUST make one attempt, with no retry.
- **FR-017**: With the validation-with-retry strategy the system MUST check each answer against the required structure and, when it does not match, ask the model again up to a maximum of 2 retries.
- **FR-018**: With the provider-native strategy the system MUST make one attempt in which the provider is given the required structure, and still verify the result; it MUST NOT retry.
- **FR-019**: Every classification response MUST state the strategy used, whether a retry happened, and the number of retries.
- **FR-020**: Every successful classification response MUST include the usage metrics of feature 003 (input, output and total tokens, model, finish reason, tokens per second) summed across all attempts, plus the total elapsed time in milliseconds.

**Errors and compatibility**

- **FR-021**: When the model's answer does not meet the required structure (not valid structure, missing field, category outside the allowed set, confidence outside 0.0–1.0), the system MUST return an error response, never a success. The error MUST name the failed check, the strategy used and the number of attempts made, and MUST report the tokens accumulated across all attempts (marked "not available" when the model did not supply them) and the total elapsed time, labelled as consumed by a failed request, so failed attempts count towards the cost comparison.
- **FR-022**: A failure of the model service itself MUST be reported as a service failure, distinct from a format failure.
- **FR-023**: Chat requests without the new field MUST behave exactly as before this feature, including the response shape, the usage metrics of feature 003 and the generation settings of feature 004. The chat response never reports which template was used: its shape is identical whether or not a template identifier is sent.
- **FR-024**: Logs written by the classification operation and by template handling MUST NOT contain the text being classified, the template text or the model's answer; they may contain identifiers, strategy, attempt counts and numeric values. The log that already records the chat question is out of scope here and is addressed in feature 007, as in features 003 and 004.
- **FR-025**: The documented request/response contract and examples MUST be updated for the template identifier, the classification operation, the strategies and the error formats.

### Key Entities

- **Prompt Template**: A named, editable prompt with a unique identifier and optional variables. One of them is the default. Stored as a file separate from the Java code.
- **Template Variable**: A named placeholder inside a template, replaced by a value before use.
- **Chat Request** (extended): The existing question, now optionally carrying a template identifier, plus the generation settings from feature 004.
- **Classification Request**: A text to classify, with an optional strategy and an optional temperature.
- **Classification Result**: Category, confidence (0.0–1.0) and justification.
- **Format Strategy**: One of instruction-only, validation-with-retry, provider-native schema; determines how the required structure is obtained and whether retries happen.
- **Classification Response**: The Classification Result plus the strategy used, whether a retry happened, the retry count, the aggregated usage metrics and the elapsed time; or an error describing which format check failed, together with the tokens and time consumed by the failed attempts.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of chat requests in the current format (no template identifier) succeed with the same response shape and usage metrics as before this feature.
- **SC-002**: 100% of requests with an unknown template identifier or an unknown strategy are rejected with HTTP 400 without calling the model, and each error lists the valid values.
- **SC-003**: A maintainer can change a template's wording or add a template by editing only template files (no Java code) and see it in use after rebuilding the backend image and restarting, in under 10 minutes.
- **SC-004**: Across 20 runs per template of the same question, the concise template's answers are on average at least 30% shorter than the tutor template's answers.
- **SC-005**: 100% of classification responses that are reported as successful have a category from the allowed set, a confidence between 0.0 and 1.0 and a non-empty justification.
- **SC-006**: For each of the three strategies, a person can run 20 classifications of the same text and read, from the responses alone, the success rate, the average tokens used and the average time of that strategy.
- **SC-007**: With the validation-with-retry strategy, the success rate over 20 classifications is greater than or equal to the instruction-only success rate over the same 20 texts, and every retry is visible in the response.
- **SC-008**: 100% of format failures return an error that names the failed check, the strategy, the number of attempts and the tokens and time consumed; 0 failures are presented as a successful classification.
- **SC-009**: Requests rejected for invalid input (template, strategy, temperature, empty text) answer in under 1 second.

## Assumptions

- The client is any caller of the HTTP API (scripts, API tools); adding template or strategy selectors to the chat web page is out of scope.
- The registered templates are at least: default (equivalent to today's system prompt), concise, tutor and extractor. Their exact wording is a content decision made when writing them, not part of this specification.
- Template files are packaged with the backend and read when the service starts: an edit takes effect after rebuilding the backend image and restarting. Applying edits to a running service is out of scope.
- The template identifier is chosen only among registered ones; there is no endpoint to create or edit templates over HTTP.
- The classification operation uses its own fixed instructions to obtain the structure; the client does not choose a chat template for it.
- The maximum number of retries for the validation-with-retry strategy is 2 (up to 3 attempts in total); it is a fixed setting for this feature, not chosen per request.
- If the strategy is omitted, instruction-only is used because it is the simplest baseline for comparison.
- A format failure is reported with the same kind of HTTP status the project already uses when the model gives an unusable answer (a gateway-type error), and the body states the cause.
- The usage metrics and elapsed time of a classification sum all attempts, so the cost of retries is visible.
- Per-attempt breakdowns, persistent statistics, dashboards, and automatic comparison reports across strategies are out of scope; comparison is done by repeating requests and reading the responses.
- Only the project's current local model is in scope; the provider-native strategy relies on that provider supporting structured output.
- No authentication or per-user restriction is added, consistent with the demo scope.
- The classification fields are named in English (`category`, `confidence`, `justification`), and `confidence` is a decimal number from 0.0 to 1.0. The workshop material uses Spanish field names and an integer confidence; this specification deliberately differs from it. The four category values stay in Spanish (`consulta`, `queja`, `incidencia`, `otro`).
- Must work in the local Docker-based demo setup. The templates travel inside the backend image; no external folder is mounted.
