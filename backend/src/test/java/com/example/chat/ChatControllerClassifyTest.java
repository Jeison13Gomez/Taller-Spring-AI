package com.example.chat;

import com.example.chat.classification.Category;
import com.example.chat.classification.Classification;
import com.example.chat.classification.FormatStrategy;
import com.example.chat.dto.ClassifyResponse;
import com.example.chat.dto.UsageMetrics;
import com.example.chat.exception.GlobalExceptionHandler;
import com.example.chat.exception.StructuredOutputException;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerClassifyTest {

    /** Stub que guarda lo que recibe el servicio de clasificacion. */
    private static class Capturing implements ClassificationService {
        final List<String> texts = new ArrayList<>();
        final List<FormatStrategy> strategies = new ArrayList<>();
        final List<Double> temperatures = new ArrayList<>();
        /** Si no es null, el servicio lanza esta excepcion en lugar de responder. */
        RuntimeException toThrow;

        @Override
        public ClassifyResponse classify(String text, FormatStrategy strategy, Double temperature) {
            if (toThrow != null) {
                throw toThrow;
            }
            texts.add(text);
            strategies.add(strategy);
            temperatures.add(temperature);
            ClassifyResponse response = new ClassifyResponse();
            response.setRequestId("req-1");
            response.setStatus("ok");
            response.setResult(new Classification(Category.queja, 0.87, "Reporta un error de cobro."));
            response.setStrategy(strategy.getId());
            response.setRetried(false);
            response.setRetries(0);
            response.setAttempts(1);
            response.setElapsedMs(1234L);
            response.setUsage(new UsageMetrics(32, 118, 150, "llama3.2:3b", "stop", 29.5));
            return response;
        }
    }

    private final Capturing classification = new Capturing();
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ChatController(TestPrompts.unusedModel(), TestPrompts.registry(), classification))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/v1/classify").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions rejected(String body, String... fieldAndMessage) throws Exception {
        ResultActions result = send(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.usage").doesNotExist())
                .andExpect(jsonPath("$.errors", hasSize(fieldAndMessage.length / 2)));
        for (int i = 0; i < fieldAndMessage.length / 2; i++) {
            result.andExpect(jsonPath("$.errors[" + i + "].field").value(fieldAndMessage[2 * i]))
                    .andExpect(jsonPath("$.errors[" + i + "].message").value(fieldAndMessage[2 * i + 1]));
        }
        assertTrue(classification.texts.isEmpty(), "el servicio no debe invocarse con una peticion invalida");
        return result;
    }

    @Test
    void aValidRequestReturnsTheStructuredResultWithUsageAndTiming() throws Exception {
        send("{\"text\":\"Mi factura tiene un monto equivocado\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.result.category").value("queja"))
                .andExpect(jsonPath("$.result.confidence").value(0.87))
                .andExpect(jsonPath("$.result.justification").value("Reporta un error de cobro."))
                .andExpect(jsonPath("$.strategy").value("instruction"))
                .andExpect(jsonPath("$.retried").value(false))
                .andExpect(jsonPath("$.retries").value(0))
                .andExpect(jsonPath("$.attempts").value(1))
                .andExpect(jsonPath("$.elapsedMs").value(1234))
                .andExpect(jsonPath("$.usage.promptTokens").value(32))
                .andExpect(jsonPath("$.usage.completionTokens").value(118))
                .andExpect(jsonPath("$.usage.totalTokens").value(150))
                .andExpect(jsonPath("$.usage.model").value("llama3.2:3b"))
                .andExpect(jsonPath("$.usage.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.tokensPerSecond").value(29.5))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.failure").doesNotExist())
                .andExpect(jsonPath("$.consumed").doesNotExist());

        assertEquals(List.of("Mi factura tiene un monto equivocado"), classification.texts);
        assertEquals(List.of(FormatStrategy.INSTRUCTION), classification.strategies);
    }

    @Test
    void theTemperatureIsPassedAndZeroIsNotNull() throws Exception {
        send("{\"text\":\"hola\",\"temperature\":0.0}").andExpect(status().isOk());
        send("{\"text\":\"hola\",\"temperature\":1.5}").andExpect(status().isOk());
        send("{\"text\":\"hola\"}").andExpect(status().isOk());

        assertEquals(0.0, classification.temperatures.get(0));
        assertEquals(1.5, classification.temperatures.get(1));
        assertNull(classification.temperatures.get(2));
    }

    @Test
    void anEmptyOrMissingTextIsRejected() throws Exception {
        rejected("{\"text\":\"\"}", "text", "text must be provided");
        rejected("{\"text\":\"   \"}", "text", "text must be provided");
        rejected("{}", "text", "text must be provided");
    }

    @Test
    void aTextOver5000CharactersIsRejectedAndExactly5000IsAccepted() throws Exception {
        rejected("{\"text\":\"" + "a".repeat(5001) + "\"}", "text", "text must be at most 5000 characters");

        send("{\"text\":\"" + "a".repeat(5000) + "\"}").andExpect(status().isOk());
        assertEquals(5000, classification.texts.get(0).length());
    }

    @Test
    void aTemperatureOutOfRangeIsRejected() throws Exception {
        rejected("{\"text\":\"hola\",\"temperature\":2.5}", "temperature", "temperature must be between 0.0 and 2.0");
        rejected("{\"text\":\"hola\",\"temperature\":-0.1}", "temperature", "temperature must be between 0.0 and 2.0");
    }

    @Test
    void aTemperatureOfTheWrongKindIsRejectedNamingTheField() throws Exception {
        rejected("{\"text\":\"hola\",\"temperature\":\"hot\"}", "temperature", "temperature must be a number");
    }

    @Test
    void severalInvalidFieldsAreReportedTogetherInOrder() throws Exception {
        rejected("{\"text\":\"\",\"temperature\":2.5}",
                "text", "text must be provided",
                "temperature", "temperature must be between 0.0 and 2.0");
    }

    @Test
    void malformedJsonIsABadRequest() throws Exception {
        rejected("{\"text\":", "body", "request body is not valid JSON");
    }

    // --- Estrategia (US4, T040) -----------------------------------------------------------------

    @Test
    void eachStrategyIdIsAcceptedAndPassedToTheService() throws Exception {
        send("{\"text\":\"hola\",\"strategy\":\"instruction\"}").andExpect(status().isOk());
        send("{\"text\":\"hola\",\"strategy\":\"validate-retry\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategy").value("validate-retry"));
        send("{\"text\":\"hola\",\"strategy\":\"native-schema\"}").andExpect(status().isOk());

        assertEquals(List.of(FormatStrategy.INSTRUCTION, FormatStrategy.VALIDATE_RETRY, FormatStrategy.NATIVE_SCHEMA),
                classification.strategies);
    }

    @Test
    void anOmittedOrEmptyStrategyMeansInstruction() throws Exception {
        send("{\"text\":\"hola\"}").andExpect(status().isOk());
        send("{\"text\":\"hola\",\"strategy\":null}").andExpect(status().isOk());
        send("{\"text\":\"hola\",\"strategy\":\"\"}").andExpect(status().isOk());

        assertEquals(List.of(FormatStrategy.INSTRUCTION, FormatStrategy.INSTRUCTION, FormatStrategy.INSTRUCTION),
                classification.strategies);
    }

    @Test
    void anUnknownStrategyIsRejectedListingTheValidOnes() throws Exception {
        rejected("{\"text\":\"hola\",\"strategy\":\"magic\"}",
                "strategy", "unknown strategy 'magic'; available: instruction, validate-retry, native-schema");
        // Distingue mayusculas y espacios, como el identificador de plantilla.
        rejected("{\"text\":\"hola\",\"strategy\":\"Instruction\"}",
                "strategy", "unknown strategy 'Instruction'; available: instruction, validate-retry, native-schema");
        rejected("{\"text\":\"hola\",\"strategy\":\"instruction \"}",
                "strategy", "unknown strategy 'instruction '; available: instruction, validate-retry, native-schema");
    }

    @Test
    void aLongUnknownStrategyIsCutTo40CharactersInTheMessage() throws Exception {
        rejected("{\"text\":\"hola\",\"strategy\":\"" + "x".repeat(100) + "\"}",
                "strategy", "unknown strategy '" + "x".repeat(40) + "…'; available: instruction, validate-retry, native-schema");
    }

    @Test
    void textStrategyAndTemperatureErrorsComeTogetherInOrder() throws Exception {
        rejected("{\"text\":\"\",\"strategy\":\"magic\",\"temperature\":2.5}",
                "text", "text must be provided",
                "strategy", "unknown strategy 'magic'; available: instruction, validate-retry, native-schema",
                "temperature", "temperature must be between 0.0 and 2.0");
    }

    // --- Fallos de formato del modelo (US5, T046) -----------------------------------------------

    private static StructuredOutputException formatFailure(int attempts, int retries, UsageMetrics consumed) {
        return new StructuredOutputException("category", "category must be one of consulta, queja, incidencia, otro",
                "validate-retry", attempts, retries, 9800, consumed);
    }

    @Test
    void aFormatFailureIsAGatewayErrorWithTheFailureAndTheCostOfTheAttempts() throws Exception {
        classification.toThrow = formatFailure(3, 2, new UsageMetrics(810, 120, 930, "llama3.2:3b", "stop", 12.5));

        send("{\"text\":\"hola\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.failure.check").value("category"))
                .andExpect(jsonPath("$.failure.message").value("category must be one of consulta, queja, incidencia, otro"))
                .andExpect(jsonPath("$.strategy").value("validate-retry"))
                .andExpect(jsonPath("$.retried").value(true))
                .andExpect(jsonPath("$.retries").value(2))
                .andExpect(jsonPath("$.attempts").value(3))
                .andExpect(jsonPath("$.elapsedMs").value(9800))
                .andExpect(jsonPath("$.consumed.promptTokens").value(810))
                .andExpect(jsonPath("$.consumed.completionTokens").value(120))
                .andExpect(jsonPath("$.consumed.totalTokens").value(930))
                .andExpect(jsonPath("$.consumed.model").value("llama3.2:3b"))
                .andExpect(jsonPath("$.consumed.finishReason").value("stop"))
                .andExpect(jsonPath("$.consumed.tokensPerSecond").value(12.5))
                .andExpect(jsonPath("$.result").doesNotExist())
                .andExpect(jsonPath("$.usage").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void tokensTheModelDidNotSupplyAreNullInConsumedNeverZero() throws Exception {
        classification.toThrow = formatFailure(3, 2, new UsageMetrics(null, null, null, "llama3.2:3b", null, null));

        send("{\"text\":\"hola\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.consumed.promptTokens").value((Object) null))
                .andExpect(jsonPath("$.consumed.completionTokens").value((Object) null))
                .andExpect(jsonPath("$.consumed.totalTokens").value((Object) null))
                .andExpect(jsonPath("$.consumed.tokensPerSecond").value((Object) null))
                .andExpect(jsonPath("$.consumed.model").value("llama3.2:3b"));
    }

    @Test
    void aFailureOnTheFirstAttemptReportsNoRetry() throws Exception {
        classification.toThrow = formatFailure(1, 0, new UsageMetrics(100, 40, 140, "llama3.2:3b", "stop", 20.0));

        send("{\"text\":\"hola\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.retried").value(false))
                .andExpect(jsonPath("$.retries").value(0))
                .andExpect(jsonPath("$.attempts").value(1));
    }

    @Test
    void aPlainRuntimeExceptionStillGivesA500WithoutAFailureObject() throws Exception {
        classification.toThrow = new RuntimeException("boom");

        send("{\"text\":\"hola\"}")
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.failure").doesNotExist())
                .andExpect(jsonPath("$.consumed").doesNotExist())
                .andExpect(jsonPath("$.result").doesNotExist());
    }
}
