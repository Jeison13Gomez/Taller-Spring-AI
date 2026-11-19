package com.example.chat;

import com.example.chat.dto.UsageMetrics;
import com.example.chat.exception.GlobalExceptionHandler;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerSettingsTest {

    /** Stub que guarda los parametros de generacion recibidos en cada llamada. */
    private static class Capturing implements ModelService {
        final List<GenerationSettings> calls = new ArrayList<>();

        @Override
        public ModelResult generateResponse(String question, String sessionId, GenerationSettings settings, String templateId) {
            calls.add(settings);
            return new ModelResult("respuesta", new UsageMetrics(1, 1, 2, "m", "stop", 1.0));
        }
    }

    private final Capturing model = new Capturing();
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ChatController(model, TestPrompts.registry(), TestPrompts.unusedClassification()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static String body(String settings) {
        return "{\"question\":\"hola\",\"sessionId\":\"t\"" + (settings.isEmpty() ? "" : "," + settings) + "}";
    }

    /** Espera 400 con status error, usage null, sin llamar al modelo y con los pares campo/mensaje dados en orden. */
    private void rejected(String body, String... fieldAndMessage) throws Exception {
        ResultActions result = send(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.usage").value((Object) null))
                .andExpect(jsonPath("$.errors", hasSize(fieldAndMessage.length / 2)));
        for (int i = 0; i < fieldAndMessage.length / 2; i++) {
            result.andExpect(jsonPath("$.errors[" + i + "].field").value(fieldAndMessage[2 * i]))
                    .andExpect(jsonPath("$.errors[" + i + "].message").value(fieldAndMessage[2 * i + 1]));
        }
        assertTrue(model.calls.isEmpty(), "el modelo no debe invocarse con una peticion invalida");
    }

    // --- User Story 1: ajuste por peticion ---------------------------------------------------

    @Test
    void allFiveSettingsReachTheModelAndZeroIsKept() throws Exception {
        send(body("\"temperature\":0.0,\"topP\":0.9,\"topK\":40,\"numPredict\":64,\"seed\":42"))
                .andExpect(status().isOk());

        assertEquals(new GenerationSettings(0.0, 0.9, 40, 64, 42), model.calls.get(0));
    }

    @Test
    void onlyNumPredictSent() throws Exception {
        send(body("\"numPredict\":5")).andExpect(status().isOk());

        assertEquals(new GenerationSettings(null, null, null, 5, null), model.calls.get(0));
    }

    @Test
    void settingsDoNotCarryOverToTheNextRequest() throws Exception {
        send(body("\"temperature\":1.5,\"numPredict\":5")).andExpect(status().isOk());
        send(body("")).andExpect(status().isOk());

        assertEquals(new GenerationSettings(1.5, null, null, 5, null), model.calls.get(0));
        assertEquals(GenerationSettings.none(), model.calls.get(1));
    }

    // --- User Story 2: compatibilidad hacia atras --------------------------------------------

    @Test
    void legacyBodyKeepsTheSameResponseShapeAndDefaults() throws Exception {
        send(body(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(6))) // los 5 campos de siempre + historyMessages (feature 006)
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.answer").value("respuesta"))
                .andExpect(jsonPath("$.elapsedMs").exists())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.usage").exists())
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertEquals(GenerationSettings.none(), model.calls.get(0));
    }

    @Test
    void explicitNullsAreTreatedAsNotSent() throws Exception {
        send(body("\"temperature\":null,\"topP\":null,\"topK\":null,\"numPredict\":null,\"seed\":null"))
                .andExpect(status().isOk());

        assertEquals(GenerationSettings.none(), model.calls.get(0));
    }

    // --- User Story 3: rechazo de valores invalidos -------------------------------------------

    @Test
    void temperatureOutOfRange() throws Exception {
        rejected(body("\"temperature\":2.5"), "temperature", "temperature must be between 0.0 and 2.0");
        rejected(body("\"temperature\":-0.1"), "temperature", "temperature must be between 0.0 and 2.0");
    }

    @Test
    void severalInvalidSettingsAreReportedTogetherInFixedOrder() throws Exception {
        rejected(body("\"topK\":500,\"topP\":-0.1"),
                "topP", "topP must be between 0.0 and 1.0",
                "topK", "topK must be a whole number between 0 and 200");
    }

    @Test
    void numPredictOutOfRange() throws Exception {
        String message = "numPredict must be a whole number between 1 and 2048";
        rejected(body("\"numPredict\":0"), "numPredict", message);
        rejected(body("\"numPredict\":2049"), "numPredict", message);
    }

    @Test
    void nonWholeNumbersAreRejectedNotTruncated() throws Exception {
        rejected(body("\"topK\":3.5"), "topK", "topK must be a whole number between 0 and 200");
    }

    @Test
    void aFieldWithTwoViolationsIsReportedOnce() throws Exception {
        rejected(body("\"topK\":500.5"), "topK", "topK must be a whole number between 0 and 200");
    }

    @Test
    void wrongKindOfValueIsRejectedWithTheFieldName() throws Exception {
        rejected(body("\"temperature\":\"hot\""), "temperature", "temperature must be a number");
    }

    @Test
    void malformedJsonIsABadRequestNotAServerError() throws Exception {
        rejected("{\"question\":", "body", "request body is not valid JSON");
    }

    @Test
    void blankQuestionIsReportedInTheErrorsList() throws Exception {
        rejected("{\"question\":\"\",\"sessionId\":\"t\"}", "question", "question must be provided");
    }

    @Test
    void boundaryValuesAreAccepted() throws Exception {
        send(body("\"temperature\":2.0,\"topP\":1.0,\"topK\":200,\"numPredict\":2048"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());
        send(body("\"temperature\":0.0,\"topP\":0.0,\"topK\":0,\"numPredict\":1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").doesNotExist());

        assertEquals(new GenerationSettings(2.0, 1.0, 200, 2048, null), model.calls.get(0));
        assertEquals(new GenerationSettings(0.0, 0.0, 0, 1, null), model.calls.get(1));
    }

    // --- User Story 4: seed -------------------------------------------------------------------

    @Test
    void seedAcceptsTheFull32BitSignedRange() throws Exception {
        send(body("\"seed\":-2147483648")).andExpect(status().isOk());
        send(body("\"seed\":2147483647")).andExpect(status().isOk());

        assertEquals(-2147483648, model.calls.get(0).seed());
        assertEquals(2147483647, model.calls.get(1).seed());
    }

    @Test
    void seedOutsideTheRangeOrNotWholeIsRejected() throws Exception {
        String message = "seed must be a whole number between -2147483648 and 2147483647";
        rejected(body("\"seed\":2147483648"), "seed", message);
        rejected(body("\"seed\":1.5"), "seed", message);
    }
}
