# Research: Per-Request Chat Sampling Parameters

All findings were checked against the jars resolved by the build (`spring-ai-ollama-2.0.0.jar` in the Gradle cache, inspected with `javap`).

## R1. How to pass per-request options to the model

- **Decision**: Build an `OllamaChatOptions` builder per request with only the settings the client sent, and pass the builder to `chatClient.prompt().user(q).options(builder).call()`.
- **Rationale**: In Spring AI 2.0, `ChatClient.ChatClientRequestSpec.options(...)` takes a `ChatOptions.Builder`, not built options. `OllamaChatOptions.AbstractBuilder` has `temperature(Double)`, `topP(Double)`, `topK(Integer)`, `numPredict(Integer)` and `seed(Integer)`. The options exist only for that call, so nothing is shared between requests (FR-003, FR-009).
- **Alternatives considered**: Rebuilding the `ChatClient` or changing `spring.ai.ollama.chat.*` at runtime. Rejected: shared mutable state, a race between concurrent requests, and no gain.

## R2. Unset settings keep the configured defaults

- **Decision**: Leave builder fields `null` for settings that were not sent.
- **Rationale**: `OllamaChatOptions.AbstractBuilder.combineWith(other)` copies a field from `other` only when it is non-null (checked in the bytecode: one `ifnull` guard per field). The defaults from `application.properties` (temperature 0.7, numPredict 1024) therefore survive for every unset field. topP, topK and seed stay unset, so Ollama uses the model's own values, as it does today (FR-002).
- **Verification during implementation**: a unit test asserting that a request with only `topK` still sends temperature 0.7 and numPredict 1024. This test exists because the merge behavior is library-internal.

## R3. "0.0" vs "not sent"

- **Decision**: Declare every setting as a nullable wrapper or `BigDecimal` in `ChatRequest`, never as a primitive.
- **Rationale**: Absent and JSON `null` both deserialize to `null` ("not sent"). `0` / `0.0` become non-null values, so `combineWith` applies them and Ollama receives them (`OllamaChatOptions` serializes non-null fields) (FR-004).

## R4. Range and type validation

- **Decision**: Use Bean Validation on `ChatRequest`, which is already active through `@Valid` and `BindingResult` in `ChatController`:
  - `temperature`, `topP`: `Double` with `@DecimalMin`/`@DecimalMax` (inclusive).
  - `topK`, `numPredict`, `seed`: `BigDecimal` with `@Digits(fraction = 0)` plus `@DecimalMin`/`@DecimalMax`, converted with `intValueExact()` after validation.
- **Rationale**:
  - Jackson silently truncates `3.5` to `3` for `Integer` fields by default (`ACCEPT_FLOAT_AS_INT`). It also fails on overflow before validation runs, which would report a single parse error.
  - `BigDecimal` keeps the exact value, so non-whole numbers and out-of-range values all become validation errors and are reported together (FR-007).
  - Changing a global Jackson setting was rejected: `MockMvcBuilders.standaloneSetup` in the existing tests does not use Boot's Jackson properties, so the behavior would differ between tests and runtime.
- **Accepted consequence**: `@Digits` treats `3.0` as the whole number 3 (it strips trailing zeros). Numeric strings such as `"0.5"` are accepted through Jackson's default scalar coercion. Neither case is part of the contract.

## R5. Wrong-kind values (`"hot"`, `true`, `{}`)

- **Decision**: Add `@ExceptionHandler(HttpMessageNotReadableException.class)` to `GlobalExceptionHandler`. It returns 400 with `status: "error"` and one entry in `errors` that names the field from the Jackson exception path and says a number is expected. If no path is available (malformed JSON), it returns a generic "request body is not valid JSON" entry.
- **Rationale**: Today this exception is a `RuntimeException`, so the existing catch-all handler turns it into **500**. A more specific handler wins over the `RuntimeException` one.
- **Limitation**: Jackson stops at the first unreadable value, so a request with a wrong-kind value reports only that field, even if other fields are out of range. Out-of-range and non-whole values are always reported together (R4).
- **Side effect**: A malformed JSON body now returns 400 instead of 500. This is a correction; no client depends on the 500.

## R6. Error body shape

- **Decision**: Add a nullable `errors` list (`[{ "field": "...", "message": "..." }]`) to the existing `ChatResponse`, serialized only when non-null (`@JsonInclude(NON_NULL)` on that field). Each field appears at most once; when several constraints fail on the same field, the first message is kept.
- **Rationale**: This keeps the existing 400 body (`requestId`, `answer`, `elapsedMs`, `status`, `usage`) and only adds a field (FR-008). Successful responses are byte-for-byte unchanged, because `errors` is omitted.
- **Alternatives considered**: RFC 9457 `ProblemDetail`. Rejected because it replaces the 400 body shape that existing clients and scripts read.

## R7. Upper limit for numPredict

- **Decision**: 2048 (clarification 2026-10-25). Seed: the 32-bit signed range (-2147483648 to 2147483647), matching `seed(Integer)`.

## R8. Reproducibility with seed

- **Decision**: Pass the seed through unchanged. No server-side caching.
- **Rationale**: Ollama produces the same output for the same model, prompt, options and seed on the same machine. SC-005 allows 5% for non-determinism (for example, GPU kernels); it is measured with the quickstart, not asserted in unit tests.

## Verified in implementation (T001-T002, 2026-10-26)

- **Baseline (T001)**: `gradle test` in `backend/` passes (15 tests, up to date); `bash scripts/smoke_test.sh` passes against the running stack.
- **R1 / T002(a)**: `OllamaChatOptions.builder()` returns `OllamaChatOptions$Builder`. The built `OllamaChatOptions` exposes `getTemperature()` (`Double`), `getTopP()` (`Double`), `getTopK()` (`Integer`), `getNumPredict()` (`Integer`) and `getSeed()` (`Integer`). Checked with `javap` against `spring-ai-ollama-2.0.0.jar`.
- **R5 / T002(b)**: the project resolves Jackson 3 (`tools.jackson.core:jackson-databind:3.1.4`). The wrong-kind-of-value exceptions are `tools.jackson.databind.exc.InvalidFormatException`, which extends `MismatchedInputException`, which extends `tools.jackson.databind.DatabindException`. All of them extend `tools.jackson.core.JacksonException`, which has `getPath()` returning `List<JacksonException.Reference>`. `Reference.getPropertyName()` gives the field name. The handler therefore looks for a `JacksonException` in the cause chain and takes the property name of the last path element. The annotation package `com.fasterxml.jackson.annotation` is still the one in use, so `@JsonInclude` keeps its import.