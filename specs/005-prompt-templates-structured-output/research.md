# Research: Prompt Templates and Structured Output

Everything marked **verified** was checked on 2026-10-26 against the jars resolved by the build (Spring AI 2.0.0, Spring Boot 4.1.0, Jackson 3.1.4) with `javap`, or by running a temporary JUnit probe (deleted afterwards, not part of the repository). Anything not run is labelled **inferred** or **to verify**.

## R1. What `entity()` / `responseEntity()` really offer in 2.0.0 (verified)

`ChatClient.CallResponseSpec` has `entity(Class | ParameterizedTypeReference | StructuredOutputConverter [, Consumer<EntityParamSpec>])` and the same six overloads of `responseEntity(...)`. `responseEntity` returns `ResponseEntity<ChatResponse, T>`, a record with `response()` (the Spring AI `ChatResponse`, which carries usage) and `entity()`.

`ChatClient.EntityParamSpec` has exactly two methods, both without parameters:

- `validateSchema()`
- `useProviderStructuredOutput()`

So the API the request describes exists. What it can and cannot do is below.

## R2. `validateSchema()` cannot satisfy the spec (verified by bytecode)

`validateSchema()` pushes a `StructuredOutputValidationAdvisor` built with `.outputJsonSchema(schema)` only. Reading its bytecode:

| Question | Finding |
|----------|---------|
| Is the retry limit configurable through `validateSchema()`? | **No.** The `Builder` has `maxRepeatAttempts(int)`, but `validateSchema()` does not pass one, so the builder default **3** applies. The loop runs `attempt++`, calls the model, and repeats while the answer is invalid and `attempt <= 3`: **up to 4 attempts (3 retries)**. FR-017 asks for at most **2 retries**. |
| Is the number of retries reported? | **No.** The counter is a local variable. The advisor returns only the last `ChatClientResponse`; nothing about the attempts is added to it. |
| Can tokens of all attempts be summed? | **No.** Earlier responses are discarded; only the last one is returned, so only its `Usage` is visible. |
| What does a retry send? | The same request with the validation error text appended to the user message (`Prompt.augmentUserMessage`). There is no conversation history. |
| What if the last attempt is still invalid? | The advisor returns that last response anyway; the failure only appears later, when the converter fails to read it. |
| Dependencies | It validates with `com.networknt.schema` (draft 2020-12), which is not a direct dependency of this project. |

Conclusion: `validateSchema()` cannot report retries, cannot sum tokens and uses a fixed, different retry limit. **It does not exist in a usable form for FR-017, FR-019 or FR-020.**

## R3. `useProviderStructuredOutput()` (verified)

It sets the context attribute `ChatClientAttributes.STRUCTURED_OUTPUT_NATIVE`. `ChatModelCallAdvisor` then, if the request options implement `StructuredOutputChatOptions`, rebuilds them with `outputSchema(schema)`. `OllamaChatOptions` implements `StructuredOutputChatOptions`. Its builder's `outputSchema(String)` parses the JSON text into a map and stores it in the Ollama `format` field (`getOutputSchema()` serializes it back). `OllamaChatModel` sends `format` to Ollama, which is Ollama's native structured-output mechanism.

So the native strategy needs no advisor: `OllamaChatOptions.builder().outputSchema(schema)` does the same thing directly, and it composes with the temperature builder from feature 004.

**To verify in implementation**: that `llama3.2:3b` on the Ollama image in `docker/docker-compose.yml` (`ollama/ollama:latest`) honors `format` with this schema.

## R4. Getting the schema from the classification class (verified)

`new BeanOutputConverter<>(Classification.class).getJsonSchema()` returns the JSON Schema text; `getFormat()` returns the instruction text that embeds it. For a record `Classification(Category category, Double confidence, String justification)` with `enum Category { consulta, queja, incidencia, otro }` the schema is:

- `type: object`, `properties.category = { type: string, enum: [consulta, queja, incidencia, otro] }`, `confidence = { type: number, format: double }`, `justification = { type: string }`;
- `required: [category, confidence, justification]`, `additionalProperties: false`.

Using the enum makes the schema itself carry the four allowed categories (FR-010). The schema has no minimum or maximum for confidence.

## R5. What the converter does and does not check (verified by probe)

`BeanOutputConverter.convert(text)` on malformed answers:

| Model answer | Result |
|--------------|--------|
| Valid JSON | Converted. |
| Valid JSON inside a ```` ```json ```` fence | Converted (fence removed). |
| Text before the JSON | `StreamReadException` (not valid JSON). |
| Not JSON (`queja`) | `StreamReadException`. |
| Empty string | `MismatchedInputException` ("No content to map"). |
| `null` | `IllegalArgumentException`. |
| Category not in the enum, or `"Queja"` | `InvalidFormatException`; the path names `category`. Matching is case sensitive. |
| `confidence` is text | `InvalidFormatException`; path `confidence`. |
| **Missing `justification` or `confidence`** (wrapper types) | **No error: the field is `null`.** |
| **`confidence` = 1.5** | **No error.** |
| **`justification` = ""** | **No error.** |
| Extra field | No error, although the schema says `additionalProperties: false`. |
| Missing `double` (primitive) | `MismatchedInputException` ("Cannot map null into type double"). |

Conclusion: the converter alone does **not** enforce FR-011 and FR-021. A separate check after conversion is required for: missing fields, `confidence` outside 0.0–1.0 (and NaN), and blank `justification`. Wrapper types are used so a missing field is reported by name instead of being a generic parse failure.

## R6. StringTemplate delimiters (verified by probe)

Spring AI renders prompts with `StTemplateRenderer`. Its builder has `startDelimiterToken(char)`, `endDelimiterToken(char)`, `validationMode(ValidationMode)` (`THROW` default, `WARN`, `NONE`) and `validateStFunctions()`. A request can use a different one with `ChatClientRequestSpec.templateRenderer(TemplateRenderer)`.

Results with `StTemplateRenderer`:

| Case | Result |
|------|--------|
| Default `{ }`, template with a JSON example plus `{tema}` | `IllegalArgumentException: The template string is not valid.` (JSON braces are parsed as template expressions) |
| Default `{ }`, variable with no value | `IllegalStateException: Not all variables were replaced in the template. Missing variable names are: [tema].` |
| `'<'` and `'>'`, same JSON example plus `<tema>` | Rendered correctly; the JSON braces stay literal. |
| `< >`, variable with no value, mode `THROW` | `IllegalStateException: ... Missing variable names are: [tema].` |
| `< >`, mode `NONE`, variable with no value | Renders an empty string silently. |
| `< >`, text `a < b` | `IllegalArgumentException: The template string is not valid.` (a lone `<` is an expression start) |
| `< >`, text `a > b` | Rendered. |
| `< >`, variable value containing `<otro> {x}` | Inserted verbatim; values are not re-interpreted. |

Consequences:

- Templates that contain JSON (the extractor) need the `< >` delimiters, and the same failure is the reason.
- `THROW` is the right mode: it turns an unresolved variable into a startup error (FR-008).
- A template can never contain a literal `<` that is not a variable. This is a rule for template authors and is checked at startup.
- Variable values are safe to insert (no injection of template syntax), which supports FR-007.

## R7. User text is not rendered as a template (verified against the running 004 build)

With the default renderer, chat questions containing `{x}`, `{"a": 1}`, `a < b` and `<tag>` all returned HTTP 200. So the user message is not template-rendered when there are no user parameters. **To verify** in a test once the angle-bracket renderer is set on the request: the same questions must still return 200 (backward compatibility, FR-023).

## R8. Decision: one attempt loop of our own

Because of R2 and R5, all three strategies share one code path built on `call().chatResponse()`:

1. Build the request (instruction text, options).
2. Call the model once and get the Spring AI `ChatResponse` (this keeps usage available even when the answer is bad).
3. Convert with `BeanOutputConverter` and run the extra checks from R5.
4. Strategy `validate-retry` repeats from 1 up to 2 more times, appending the failed check to the user message (the same idea as the Spring advisor); the others stop after one attempt.

Per attempt we keep prompt tokens, completion tokens, model, finish reason and the Ollama generation duration, so the response can sum them (FR-020) and report the number of attempts and retries (FR-019).

Rejected: `responseEntity(..., spec -> spec.validateSchema())` (R2); `entity()`/`responseEntity()` without validation (**inferred** from the signatures: they return a value only on success, so a conversion failure throws and the usage of the failed attempt would be lost, which conflicts with FR-021).

## R9. Formatting instruction per strategy

| Strategy | What the request contains |
|----------|--------------------------|
| `instruction` | The fixed classification instructions and the text, plus `converter.getFormat()` appended to the user message. No `format` sent to Ollama. |
| `validate-retry` | Same as `instruction`; on failure the next attempt appends the failed check and asks to correct it. |
| `native-schema` | The fixed classification instructions and the text, plus `outputSchema(converter.getJsonSchema())` in the options. No format instruction in the prompt. The result is still checked (R5). |

## R10. Aggregating usage across attempts

`Usage` exposes `getPromptTokens()`, `getCompletionTokens()` and `getTotalTokens()` (all `Integer`); `OllamaChatModel` turns a missing count into 0, so as in feature 003 a count `<= 0` is treated as not available. Rules for the sum:

- Tokens are summed only if every attempt has a value; otherwise that figure is `null` ("not available"), never a partial sum (same rule as feature 003).
- `tokensPerSecond` = total completion tokens / sum of the Ollama `eval-duration` of every attempt, again `null` if any is missing.
- `model` and `finishReason` come from the last attempt.
- The existing `SpringAiModelService.toUsageMetrics` is reused for one attempt and extended to also return the eval duration, so the sum is exact and not rebuilt from rounded values.

## R11. Where templates live and how they load

- Files: `backend/src/main/resources/prompts/<id>.st`, one per template. The identifier is the file name without extension and must match `[a-z0-9-]+` (also checked at startup).
- `prompts/internal/classification-system.st` holds the fixed classification instructions. It is in a subfolder so it is **not** listed as a chat template.
- Loading uses `PathMatchingResourcePatternResolver` with `classpath:prompts/*.st`. This works inside the Spring Boot jar (the Dockerfile builds with `bootJar`, and the resources go into the image), so a change needs an image rebuild, as the spec says.
- Delimiters: `< >` with mode `THROW` for every template (R6).

## R12. Variables

- Values come only from server configuration: a `Map<String,String>` bound from `app.prompts.variables.*` in `application.properties`. The client cannot send values (FR-007).
- Startup check: every template is rendered once with that map in `THROW` mode, so a template with no value for a variable, a malformed expression, or a lone `<` stops the application with a message that names the template (FR-008). The `default` template must exist.
- `default.st` is the current system prompt, word for word, with no variables (FR-004). `concise.st`, `tutor.st` and `extractor.st` use variables such as `<max_sentences>` and `<audience>`; the extractor also contains a JSON example, which proves the delimiter choice.

## R13. Template identifier on the chat request

- New optional field `templateId` (string). `null` or `""` means not sent; anything else must match a registered id exactly (case and spaces matter), otherwise HTTP 400 with `errors: [{ field: "templateId", message: "unknown template '<id>'; available: ..." }]`.
- The id is echoed in the message and in logs only up to 40 characters followed by `…`, so a client that sends a sentence in this field cannot make the server repeat or log it in full.
- The check runs in `ChatController` together with the existing bean-validation errors, so an invalid template id and an invalid temperature are reported in the same response, in the fixed field order (`templateId` is added after `seed`).

## R14. Classification endpoint

- `POST /api/v1/classify` as a method of `ChatController` (class-level `@RequestMapping("/api/v1")` and `@CrossOrigin(origins = "http://localhost:4200")` apply).
- Request: `text` (required, 1-5000 characters), `strategy` (optional: `instruction`, `validate-retry`, `native-schema`; default `instruction`), `temperature` (optional, same range and message as chat). Validation uses the same bean-validation path and the same `errors` list as feature 004.
- Unknown strategy: 400 with `errors: [{ field: "strategy", message: "unknown strategy '<value>'; available: instruction, validate-retry, native-schema" }]`.
- Chat template ids are not accepted here (spec assumption).

## R15. Format failures: gateway error through `GlobalExceptionHandler`

- A new `StructuredOutputException` (unchecked, in `com.example.chat.exception`) carries: failed check, message, strategy, attempts, retries and the aggregated usage.
- A new `@ExceptionHandler(StructuredOutputException.class)` in `GlobalExceptionHandler` returns **HTTP 502** with `status: "error"`, `failure: { check, message }`, strategy, `retried`, `retries`, `attempts`, `elapsedMs` and `consumed` (the summed usage, FR-021). Spring picks the most specific handler, so this does not fall into the existing `RuntimeException` handler (which returns 500).
- Service failures (Ollama unreachable, HTTP errors) keep going through the existing handlers; they have no `failure` object, so they stay distinguishable from format failures (FR-022).
- `check` values: `json` (not valid JSON or empty), `category`, `confidence`, `justification`.

## R16. Template and answer text in logs

Classification and template code logs only: template id (truncated), strategy, attempt count, retry count, failed check name and numeric values. It never logs the text, the template text or the model answer (FR-024). The existing log line that records the chat question in `ChatController` is left unchanged (feature 007).

## R17. Summary of what does not exist or differs from what was assumed

| Assumed | Reality in 2.0.0 | Chosen alternative |
|---------|------------------|--------------------|
| `validateSchema()` lets us bound retries and report them | Fixed default of 3 retries; no count; only last response returned; earlier tokens lost | Own attempt loop (R8) |
| Retry count readable from the advisor | Not exposed | Counted by our loop |
| Tokens of all attempts summable through `entity()` | Not possible | Summed per attempt (R10) |
| Converter validates required fields and ranges | Only syntax and enum/number types | Extra checks after conversion (R5) |
| `useProviderStructuredOutput()` | Exists, but equals `outputSchema(schema)` in the options | Use `outputSchema` directly (R3) |

## Verified in implementation (T001-T002, 2026-10-26)

- **Baseline (T001)**: `gradle test` in `backend/` passes (36 tests, 0 failures); `bash scripts/smoke_test.sh` passes against the running stack.
- **Native `format` with `llama3.2:3b` (T002)**: a direct `POST http://localhost:11434/api/chat` (`stream: false`) with the classification schema of R4 in `format` returned `{"category": "consulta", "confidence": 0.5, "justification": "Reembolso por factura con monto equivocado"}`: valid JSON that matches the schema (the four-value enum, the number and the string), with `done_reason: "stop"`. The same request without `format` returned ```` ``` ```` fences around `{"tipo": "consulta"}`, which does not match the schema. So Ollama honors `format` on this model and image, and R3 holds. The category itself was not the "right" one for a refund request (`consulta` instead of `queja`): the schema guarantees the shape, not the quality of the classification.

## Verified in implementation (US1, T010-T019, 2026-10-26)

- **Line separator (found by the equivalence test)**: StringTemplate writes the platform's line separator when it renders. On Windows `default.st` (which contains only `\n`) rendered as `\r\n`, so the `default` prompt was not identical to the original. `PromptTemplateRegistry.render` now normalizes `\r\n` to `\n`, and the test `defaultTemplateIsTheOriginalSystemPromptWordForWord` compares with the original text exactly. The registry also normalizes `\r\n` when it reads the files, so a checkout with CRLF gives the same prompt.
- **Extra variables are harmless**: the registry passes every configured variable to every template; rendering `default` (no variables) with unused variables in the map works.
- **Renderer on the chat request (deviation from the plan, R-notes and plan.md)**: the chat path passes the already rendered system text with `templateRenderer(new NoOpTemplateRenderer())` instead of the `< >` renderer. With the `< >` renderer, a variable value that contained `<` or a rendered text that contained `<` would be parsed a second time; with the no-op renderer neither the system text nor the question is interpreted again. The `< >` renderer is still the one that renders the template files. The classification path (feature steps US3-US4) is not affected by this decision.
- **Property binding**: `app.prompts.variables.max_sentences` and `app.prompts.variables.audience` bind with their underscores intact (checked on the running stack: the `concise` template answered in 2 sentences, the value configured).
- **User text is not rendered**: against the rebuilt stack, a question with `{x}`, `{"a": 1}` and `a < b` returned HTTP 200 (also with the no-op renderer).

## Verified in implementation (US3, T023-T038, 2026-10-26)

- **Real model, `instruction` strategy, first six requests through the running stack**: 3 returned a valid result ("What are your opening hours?" → `consulta`, "The app crashes every time I open the settings screen" → `incidencia`, "Estoy muy molesto, llevo tres semanas esperando mi pedido" → `queja`) and **3 were format failures, which today come back as HTTP 500** because the `StructuredOutputException` handler is task T048: `"My invoice has the wrong amount, I want a refund"` at temperature 0.0 (`json`), `"Gracias por todo"` (`json`) and a 5000-character text of `a` (`justification`). The refund text was then repeated 3 times: it failed again twice at temperature 0.0 (500, 500) and worked once at the default temperature (200). Afterwards, four texts at temperature 0.0, three times each (12 requests), all returned 200.
- **Cause of the `json` failures (reproduced by replaying the service's exact prompt against Ollama at temperature 0.0)**: the model stops (`done_reason: "stop"`) right after the `justification` string, **without the closing `}`**:

  ```
  {
    "category" : "queja",
    "confidence" : 0.8,
    "justification" : "El usuario expresa insatisfacción y reclama un reembolso por la factura incorrecta"
  ```

  This is a model reliability problem, not a defect in the service, and it is what the strategies are meant to compare. The service does not repair the answer: `instruction` reports it as `json`. The other `json` failure ("Gracias por todo") and the `justification` one (a blank justification for meaningless text) are of the same kind.
- **Consequence for tests and scripts**: the smoke test cannot use the refund text at temperature 0.0 with the `instruction` strategy; it uses `"The app crashes every time I open the settings screen"`, which returned 200 three times out of three at 0.0, as did "What are your opening hours?", "I am very unhappy, my order is three weeks late" and "Where can I find my invoices?".
- **Quickstart step 3 uses the refund text**: with `instruction` at 0.0 it fails today (500, 502 after T048), which makes it a good example for comparing strategies in US4.
- **Usage refactor**: `SpringAiModelService.toUsageMetrics` now delegates to `UsageAggregator.sum(List.of(AttemptUsage.from(response)))`; the 13 pre-existing usage tests pass unchanged.
- **Rejections**: invalid classify requests answer in 81-100 ms and do not call the model (text empty, 5001 characters, temperature 2.5, temperature "hot", several together).
## Verified in implementation (US4, T039-T045, 2026-10-26)

- **Same prompt, three strategies, real model** (text `"My invoice has the wrong amount, I want a refund"`, temperature 0.0, through the rebuilt stack): `instruction` failed with HTTP 502 (`failure.check` `json`, 1 attempt, `consumed` 445 tokens); `validate-retry` returned 200 after 1 retry (2 attempts, `usage.promptTokens` 821, i.e. the sum of both attempts); `native-schema` returned 200 on the first attempt (245 tokens, the fewest, because the prompt carries no format instruction). Running the same three again in `tests/integration/test_chat_flow.sh`, all three returned 200 with 1 attempt: the model is not deterministic even at temperature 0.0 on this text, so one run proves the plumbing, not the success rates (those are T055).
- **Test double**: in Spring AI 2.0 `ChatClient` starts from `ChatModel.getOptions()` (not `getDefaultOptions()`). `ScriptedChatModel` now overrides `getOptions()` to return `OllamaChatOptions`, as `OllamaChatModel` does; before, the request options were rebuilt as `DefaultChatOptions` and the output schema was lost, which only affected the test double.

## Verified in implementation (US6, T052, 2026-10-26)

- **Edit cycle (SC-003)**: after editing `concise.st`, `docker compose -f docker/docker-compose.yml up -d --build backend` took 47.6 s; with the wait until the backend accepted requests, 54.0 s; edit, rebuild, wait and the verifying request together, 59.0 s (about one minute). Adding `pirata.st` and rebuilding took 50.1 s until ready.
- **Edit visible**: after adding "Empieza siempre con la palabra Claro:" the answer changed and `promptTokens` rose from 91 to 101, so the new template was loaded. `llama3.2:3b` did not obey the instruction (the answer did not start with "Claro:"); the cycle proves the template reaches the model, not that this model follows every instruction.
- **New template**: `pirata` answered 200 in pirate style, and the 400 for an unknown id listed `concise, default, extractor, pirata, tutor`.
- **Broken template**: with `<variable_inventada>` in `concise.st` the backend exited with code 1 and logged `La plantilla 'concise' (concise.st) no es valida: Not all variables were replaced in the template. Missing variable names are: [variable_inventada].`
- **Reverted**: `concise.st` restored, `pirata.st` removed, `git status` clean, chat working again (`concise` back to 91 prompt tokens, `pirata` is a 400 again).
