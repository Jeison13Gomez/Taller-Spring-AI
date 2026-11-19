# Research: Conversation Memory

Everything marked **verified** was checked on 2026-10-27 against the jars resolved by the build (Spring AI 2.0.0, Spring Boot 4.1.0) with `javap` and a temporary JUnit probe using the scripted `ChatModel` (deleted afterwards, not part of the repository), or against the running stack. Anything not run is labelled **inferred** or **to verify**.

## R1. Exact names in Spring AI 2.0.0 (verified with `javap`)

| Piece | Exact form |
|-------|------------|
| Window memory | `org.springframework.ai.chat.memory.MessageWindowChatMemory` (final); `MessageWindowChatMemory.builder().chatMemoryRepository(ChatMemoryRepository).maxMessages(int).build()`; `maxMessages` must be greater than 0 (`Assert.isTrue`) |
| Memory contract | `ChatMemory`: `add(String, Message)`, `add(String, List<Message>)`, `get(String)`, `clear(String)`; constant `ChatMemory.CONVERSATION_ID = "chat_memory_conversation_id"`. There is **no** `DEFAULT_CONVERSATION_ID` in 2.0.0 |
| Repository contract | `ChatMemoryRepository`: `findConversationIds()`, `findByConversationId(String)`, `saveAll(String, List<Message>)` (replaces), `deleteByConversationId(String)` |
| Stock repository | `InMemoryChatMemoryRepository` (final, public no-arg constructor): no expiry and no cap, so it cannot be extended or configured for FR-012 |
| Advisor | `org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor` (final); `MessageChatMemoryAdvisor.builder(ChatMemory).order(int).scheduler(Scheduler).build()`; implements `BaseChatMemoryAdvisor` (`getConversationId(Map)`) |
| Order constant | `Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER = -2147483448` |
| Attaching per request | `ChatClientRequestSpec.advisors(Consumer<AdvisorSpec>)`; inside it `a.advisors(Advisor...)` and `a.param(String, Object)` |
| Builder defaults | `ChatClient.Builder.defaultAdvisors(...)` and `clone()` exist. The scope of the auto-configured `ChatClient.Builder` bean was **not** confirmed, hence the advisor is attached per request, never as a default |

## R2. Behaviour of the stock memory, measured with a probe (verified)

A scripted model, `MessageWindowChatMemory` over `InMemoryChatMemoryRepository`, one `ChatClient` with the advisor, system text changing on every call:

| Question | Observed | Consequence |
|----------|----------|-------------|
| Where does the system message go, and is it stored? | Prompt of turn 2: `SYSTEM:SYS0, USER:Q1, ASSISTANT:A, USER:Q2`. Turn 3 got `SYS1`. Memory held only user and assistant messages. | The template of each turn applies and is never duplicated or stored: FR-006 and FR-008 come for free. |
| Window of 3 messages: what is kept? | After each full turn the memory held `USER, ASSISTANT` (2 messages), never an orphan answer first. | FR-005 is met by the library. An odd window behaves like the even value below it. With a window of 1 the same rule would leave an empty history, so the project requires at least 2 instead of measuring 1 (analysis C2). |
| Model throws on the 2nd call | Memory after the failure: `USER:Q1, ASSISTANT:A, USER:Q2`. The question stayed, without an answer. | **FR-010 is not met by the library.** The turn must be restored by us (R4). |
| 200 calls from 8 threads in one conversation (window 1000) | 103 messages stored, expected 400. | **FR-013 is not met.** `MessageWindowChatMemory.add` reads, merges and replaces without a lock. Turns of one session must be serialized (R4). |
| Call without a conversation id | `IllegalArgumentException: conversationId cannot be null` | The memory path must be used only when there is a `sessionId` (FR-003). |

## R3. Store with idle expiry and a session cap (decision)

**Decision**: our own `BoundedChatMemoryRepository implements ChatMemoryRepository`. One `LinkedHashMap` in access order (`accessOrder = true`) guarded by one lock (operations are short, nothing slow runs inside it), holding for each id the list of messages and the time of its last use. Every operation first drops sessions idle longer than `app.memory.idle-timeout`; `saveAll` of a new id drops the least recently used session when the count would exceed `app.memory.max-sessions`. Lists are copied in and out so no caller can change stored history. A `Clock` is injected so tests move time without sleeping. `findConversationIds()` exists because the interface demands it, but nothing exposes it (FR-011).

**Why**: it is the shape the plan request suggested; the stock repository has no policy; about 60 lines and no dependency.

**Alternatives considered**:

- *Caffeine cache* (`expireAfterAccess` + `maximumSize`): fewer lines, but a new dependency and a second place with policy semantics for a demo project.
- *`InMemoryChatMemoryRepository` plus a scheduled sweeper*: would need access times kept elsewhere and does not enforce the cap between sweeps.
- *Redis or JDBC repositories*: persistence the spec excludes.

**Expiry measured from the last use** (a read or a write). A session that is read and then rolled back by a failed turn still counts as used.

## R4. Per-session serialization and rollback (decision)

**Decision**: `ConversationMemory.runTurn(sessionId, action)` takes a lock for that `sessionId`, snapshots the stored messages, runs the action, and restores the snapshot if the action throws or returns a blank answer. `SessionLocks` keeps one `ReentrantLock` per active id in a `ConcurrentHashMap` with a use counter and removes the entry when the counter reaches zero, so the lock map cannot grow.

**Why**: R2 shows both defects. Holding the lock for the whole model call is acceptable: two turns of one session are sequential by nature (the second question depends on the first answer), and other sessions are never blocked. The snapshot also gives the exact `historyMessages` (R6).

**Alternatives considered**: a lock only around `add` (does not fix the user message left by a failed call, and interleaves `USER, USER, ASSISTANT, ASSISTANT`); a global lock (blocks every session during a multi-second model call); replacing the advisor by our own code (drops a supported component to fix two cases that a lock and a snapshot cover).

A rollback after eviction is harmless: if the session was evicted by the cap during the call, `saveAll` re-creates it, which counts as new use and may evict another one (the cap still holds).

## R5. Where the advisor sits relative to the template (verified)

The service builds the request exactly as in feature 005 (`.system(rendered template)`, `.user(question)`, `NoOpTemplateRenderer`, options) and adds the advisor. From R2: the advisor places the request's system message first, then the stored history, then the current question, and saves only the last user message and the model's answer. So:

1. the template is rendered and set before the advisor runs;
2. the history follows the system message and never contains one;
3. the order value is set explicitly to `DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER`; with a single advisor in the chain it has no practical effect, but fixing it keeps the behavior from changing if another advisor is added.

`/classify` uses the builder without this advisor and has no `sessionId`.

## R6. `historyMessages` (decision)

Counted as the size of the snapshot taken under the session lock. The advisor prepends exactly the stored messages (R2), so the snapshot size equals the history sent. The lock guarantees nobody changes it between the snapshot and the advisor read. A test compares the number with the messages the scripted model received, minus the system message and the current question.

The field alone cannot show truncation by the model's context: Ollama truncates silently after the message count is fixed. That is observed from `promptTokens` (R7), as the spec (FR-018) says.

## R7. Context size, `num-ctx` and its property name

**Property name (verified in the configuration metadata of `spring-ai-autoconfigure-model-ollama` 2.0.0)**: two properties exist, `spring.ai.ollama.chat.num-ctx` and `spring.ai.ollama.chat.options.num-ctx`; the second is marked `deprecated` with `spring.ai.ollama.chat.num-ctx` as its replacement. The plan request named the `options` form; the plan uses the non-deprecated one. The project's existing `temperature` and `num-predict` already use the form without `options`.

**Ollama's behaviour with an oversize prompt (verified on the running stack, see "Verified in implementation" below)**: a prompt that fits in `num-ctx` is processed whole; a single oversize prompt is reduced to about half of `num-ctx` plus 2 tokens (1026 at 2048, 2050 at 4096, 4098 at 8192) and answered with 200 and no signal. With a growing conversation (Experiment E, R10) the effect is different: the oldest messages are dropped one at a time and `promptTokens` levels off at a plateau. **Default context (inference)**: with `num-ctx` not set a very long prompt reported 2050, the same figure as an explicit 4096, so Ollama's default context here appears to be 4096. The first version of this section read 2050 as the limit and concluded 2048; that was wrong. (`ollama show llama3.2:3b` reports a context length of 131072; that is the model's maximum, not what is allocated.)

**Consequence**: 20 messages of a few hundred tokens each can exceed even 4096 (the `tutor` template alone answers with about 255 tokens), and then the prompt is cut silently. **Decision**: set `spring.ai.ollama.chat.num-ctx=8192` in `application.properties` (room for 20 messages of about 300 tokens plus the system prompt) and let `SPRING_AI_OLLAMA_CHAT_NUM_CTX` override it. This changes the context allocated for every chat and classification request (more memory in Ollama); it does not change `promptTokens` of requests that fit in the smaller context (SC-005). The value was confirmed by the project owner.

**Alternatives considered**: leave Ollama's default (apparently 4096; it would work for short answers but not for 20 messages of the `tutor` template); the model maximum 131072 (a very large allocation on a CPU demo).

**Experiment E** records the value in every row (`-NumCtx`, mandatory because the script cannot read the backend configuration) and warns when `promptTokens` of a turn varies less than 1% from the previous turn while `historyMessages` keeps rising: the signal of truncation is that plateau, not the closeness to the limit. Running the experiment with 2048, 4096 and 8192 shows the plateau by context against the flattening by window.

## R8. Validation of `sessionId` (decision)

`@Pattern(regexp = "[A-Za-z0-9_-]{1,64}")` on `ChatRequest.sessionId`, with the message `sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore`. Bean Validation does not apply `@Pattern` to `null`, so absent or `null` passes and means "no memory"; `""` and `"   "` fail. The existing error builder keeps the first message per field, so one error per field. `sessionId` is placed first in `FIELD_ORDER` because it is the first field of the request body.

Existing identifiers: `demo` (smoke test) and `it` (integration test) are valid; the frontend uses `crypto.randomUUID()` (36 characters with hyphens) or `String(Date.now())` (digits): valid.

## R9. Existing scripts and memory

`tests/integration/test_chat_flow.sh` sends all its requests (including the 'Name one colour' check, the `numPredict` checks and the concise-versus-tutor comparison) with `sessionId` `it`. Before this feature the id had no effect; with memory those requests would form one conversation and the later answers would depend on the earlier ones. Each case gets its own id. New assertions use `historyMessages` (exact, deterministic) instead of the model's wording: second turn of a session `2`, third `4`, a second session starts at `0`, no `sessionId` gives `0`, invalid ids give 400. A check that the model recalls a fact is only a warning, because `llama3.2:3b` is not deterministic.

## R10. Experiment E design

- Topic: 20 parallel instructions in Spanish, one per country, of about 70 words each, asking for an answer of about 300 words; `tutor` template (its answers are naturally long; `concise` would cut them to 2 sentences) and `numPredict` 400, so almost every answer reaches the cap and each exchange has a similar size of about 500 tokens. The size is deliberate: see the expected shape below.
- Two runs of 20 turns: with one `sessionId` (unique per execution, e.g. `exp-e-<timestamp>`) and without `sessionId` (baseline, 20 independent requests).
- Per turn: `run`, `turno`, `promptTokens`, `completionTokens`, `totalTokens`, `elapsedMs`, `historyMessages`, `numCtx`.
- Expected shape with the default window (20 messages = 10 exchanges): `historyMessages` grows by 2 per turn up to 20, reached at turn 11, then stays; `promptTokens` rises until the window is full and flattens, and, if the context size is exceeded, levels off earlier at a plateau while `historyMessages` keeps rising (the first version of this note expected a sharp fall to about half of the context size; the measured result is the plateau: 1791 from turn 5 at 2048, 3885 from turn 9 at 4096, and at 8192 a growth up to 5458 at turn 11, where the window fills). The baseline stays flat. For the three contexts to show different events the exchanges must be long: with about 500 tokens per exchange (a 70-word instruction and an answer capped at 400 tokens) the prompt reaches about 5000 tokens, so it exceeds 2048 around turn 5, 4096 around turn 9 or 10 and never 8192 (an estimate, checked in T035). With short exchanges (a one-line question and a 60-token answer, about 100 tokens) the whole window fits in every context and the three runs would be identical.
- Summary: first turn with `historyMessages` at the window size, the first turn of a plateau of `promptTokens` (variation under 1% from the previous turn with `historyMessages` rising; empty if none, column `turno_meseta`), `promptTokens` at turns 1, 11 and 20, growth between them and the 25% check of SC-004.
- Three executions, one per context size (2048, 4096 and 8192), restarting the backend with `SPRING_AI_OLLAMA_CHAT_NUM_CTX` in between; each writes `experimento_e_ctx<N>.csv` with `numCtx` in every row. With 8192 the only limit is the window (flattening at turn 11); with 4096 and 2048 the context should be exceeded earlier, at different turns, so the curves separate the two causes of forgetting. Each execution is long (40 requests of up to 400 tokens, about 20 minutes, an estimate).
- Chart: a third graph in `experimentos/graficas.py` (Python and matplotlib, already installed and used by graphs 1 and 2; reads the CSV with `utf-8-sig`), saved in `experimentos/graficas/`. It overlays the three runs and the no-memory baselines. PowerShell does not draw it (decision of the project owner).
- Alternation of requests does not apply here: the turns of one conversation are sequential by definition, and the baseline run goes after it.

## R11. Not changed

- `ChatClient` for `/classify`: no memory, no `sessionId` in `ClassifyRequest`.
- `LogRedactionFilter` and the chat-question log line: feature 007.
- Frontend: untouched; it already sends a valid `sessionId` and will start to get memory.

## R12. Does the system message survive Ollama's truncation? (to verify, T047)

Not known. R7 shows that Ollama reduces a prompt that exceeds the context size to about half of it and reports that smaller figure in `promptTokens`, but not **which part** it keeps. The system message is the first message of the prompt, so if the cut starts at the beginning, the template instructions could be lost while the history stays; if the cut keeps the beginning, the oldest history could be lost instead. Neither is assumed.

T047 measures it: `experimentos/experimento_truncado.ps1` runs a session with the `extractor` template (its system text demands a JSON object with `entidades`, `fechas` and `resumen`, which is easy to recognise) for 10 turns of long paragraphs plus one short final turn, with `num-ctx` 2048, 4096 and 8192 (the last as control: about 10 long paragraphs plus answers stay below 8192, an estimate), and records per turn `promptTokens`, `historyMessages` and two columns: `answerIsJson` (strict: the answer parses as a JSON object with those three keys) and `answerHasKeys` (relaxed: the answer text contains `"entidades"`, `"fechas"` and `"resumen"`). The deciding column is `answerHasKeys`: in experiment D `llama3.2:3b` cut JSON without its closing brace in every `instruction` attempt at temperature 0.2, so the strict column alone could fail in the control run for a reason unrelated to truncation. Both columns are shown in the note. For each context size the note states the turn of the sharp fall of `promptTokens` (the prompt was cut to about half) and whether the system message survives: the share of `answerHasKeys` before and after that turn, and in the short final turn, which can only follow the `extractor` instruction if the system message was kept. The result is recorded as it comes (including a format failure in the control, or an inconclusive outcome) in [quickstart.md](quickstart.md) and in the spec edge case. FR-006 only promises that the instructions are sent to Ollama.

## Verified in implementation

### Context size (T013, 2026-10-27)

Measured on the rebuilt stack with `POST /api/v1/chat`, `numPredict` 1, a question made of `palabra0 palabra1 ...` (about 2.5 tokens per word), without `sessionId`. The container variable was read with `printenv SPRING_AI_OLLAMA_CHAT_NUM_CTX`.

| `num-ctx` in the container | Words sent | `promptTokens` reported | Reading |
|----------------------------|------------|-------------------------|---------|
| 8192 (the new default; `printenv` gave 8192) | 3000 | 8069 | fits, not truncated |
| 8192 | 3050, 3100, 4000, 6000, 12000 | 4098 | cut |
| 2048 | 500, 900 | 1069, 1869 | fits |
| 2048 | 1000, 1500, 6000 | 1026 | cut |
| 4096 | 1500 | 3569 | fits |
| 4096 | 1900, 2000, 6000 | 2050 | cut |
| not set (stack of feature 005, earlier measurement) | about 12000 | 2050 | same figure as 4096 |

What the figures show:

- `num-ctx` is honoured: a prompt that fits is processed whole (8069 tokens with 8192).
- A prompt that does not fit is not processed up to the limit. The figure **falls to about half of `num-ctx` plus 2 tokens** (1026, 2050, 4098) and Ollama answers 200 without any signal. This holds for a single oversize prompt. For a conversation that grows turn by turn the shape is different: in Experiment E `promptTokens` levels off at a plateau instead of falling, because Ollama drops the oldest messages one at a time (about 525 tokens per step).
- **Inference, to keep in mind**: the unset default gave 2050, the same as an explicit 4096, so Ollama's effective default context here is **4096 and not 2048**. The earlier statement of R7 ("default 2048, `promptTokens` 2050") read 2050 as the limit; it is half of 4096 plus 2. R7, the spec assumption, the plan, the contract and the `observe` wording of FR-009/FR-018/T033 (a plateau near `num-ctx`, a warning at 95%) were written with that reading and have not been changed by this task.
- The expectation in T013 ("`promptTokens` well above 2050, up to about 8192") held for a prompt that fits (8069) but not for the 12000-word prompt (4098), for the reason above.
