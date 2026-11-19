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
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Memoria de conversacion (feature 006): lo que el controlador devuelve en la respuesta del chat. */
class ChatControllerSessionTest {

    private static final UsageMetrics USAGE = new UsageMetrics(40, 10, 50, "llama3.2:3b", "stop", 13.7);

    private static final String SESSION_ID_MESSAGE =
            "sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore";

    private ModelResult next = new ModelResult("respuesta", USAGE, 4);
    /** Los sessionId que recibio el modelo, uno por llamada: una peticion rechazada no deja ninguno. */
    private final List<String> sessionIds = new ArrayList<>();

    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ChatController(
                    (question, sessionId, settings, templateId) -> {
                        sessionIds.add(sessionId);
                        return next;
                    },
                    TestPrompts.registry(), TestPrompts.unusedClassification()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private ResultActions send(String body) throws Exception {
        return mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void aSuccessfulResponseCarriesHistoryMessagesFromTheModelResult() throws Exception {
        send("{\"question\":\"hola\",\"sessionId\":\"demo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyMessages").value(4));
    }

    @Test
    void aRequestWithoutHistoryReportsZeroNotAbsent() throws Exception {
        next = new ModelResult("respuesta", USAGE);

        send("{\"question\":\"hola\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyMessages").value(0));
    }

    @Test
    void everyExistingFieldKeepsItsNameAndMeaning() throws Exception {
        send("{\"question\":\"hola\",\"sessionId\":\"demo\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.answer").value("respuesta"))
                .andExpect(jsonPath("$.elapsedMs").exists())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.usage.promptTokens").value(40))
                .andExpect(jsonPath("$.usage.completionTokens").value(10))
                .andExpect(jsonPath("$.usage.totalTokens").value(50))
                .andExpect(jsonPath("$.usage.model").value("llama3.2:3b"))
                .andExpect(jsonPath("$.usage.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.tokensPerSecond").value(13.7))
                .andExpect(jsonPath("$.*", hasSize(6)));
    }

    @Test
    void aBlankAnswerIsA502WithoutHistoryMessages() throws Exception {
        next = new ModelResult("", null, 2);

        send("{\"question\":\"hola\",\"sessionId\":\"demo\"}")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.historyMessages").doesNotExist());
    }

    @Test
    void aRejectedRequestIsA400WithoutHistoryMessages() throws Exception {
        send("{\"question\":\"\",\"sessionId\":\"demo\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.historyMessages").doesNotExist());
    }

    // --- Validacion del sessionId (feature 006, US4) ------------------------------------------

    private static String withSession(String jsonValue) {
        return "{\"question\":\"hola\",\"sessionId\":" + jsonValue + "}";
    }

    private void assertRejected(String jsonValue) throws Exception {
        int before = sessionIds.size();
        send(withSession(jsonValue))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.usage").value((Object) null))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("sessionId"))
                .andExpect(jsonPath("$.errors[0].message").value(SESSION_ID_MESSAGE))
                .andExpect(jsonPath("$.historyMessages").doesNotExist());
        assertEquals(before, sessionIds.size(), "el modelo no debe invocarse con el sessionId " + jsonValue);
    }

    @Test
    void validSessionIdsReachTheModelExactlyAsSent() throws Exception {
        String uuid = UUID.randomUUID().toString();
        List<String> valid = List.of("demo", "Demo", "a-b_c", "a".repeat(64), uuid, "X", "_", "-");
        for (String id : valid) {
            send(withSession("\"" + id + "\"")).andExpect(status().isOk());
        }
        assertEquals(valid, sessionIds);
    }

    @Test
    void anEmptySessionIdIsRejected() throws Exception {
        assertRejected("\"\"");
    }

    @Test
    void aSessionIdOfOnlySpacesIsRejected() throws Exception {
        assertRejected("\"   \"");
    }

    @Test
    void aSessionIdLongerThan64CharactersIsRejected() throws Exception {
        assertRejected("\"" + "a".repeat(65) + "\"");
        assertRejected("\"" + "a".repeat(500) + "\"");
    }

    @Test
    void aSessionIdWithCharactersOutsideTheRuleIsRejected() throws Exception {
        for (String bad : List.of("a b", "caf\u00e9", "a/b", "a.b", "a:b", "a\\n", " demo", "demo ", "a+b", "<b>", "..", "a;b")) {
            assertRejected("\"" + bad + "\"");
        }
    }

    @Test
    void theRejectedValueIsNotRepeatedInTheResponse() throws Exception {
        send(withSession("\"valor/raro.secreto\""))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(not(containsString("secreto"))));
    }

    @Test
    void anAbsentOrNullSessionIdIsNotAnError() throws Exception {
        send("{\"question\":\"hola\"}").andExpect(status().isOk());
        send("{\"question\":\"hola\",\"sessionId\":null}").andExpect(status().isOk());

        assertEquals(2, sessionIds.size());
        assertNull(sessionIds.get(0));
        assertNull(sessionIds.get(1));
    }

    @Test
    void sessionIdsThatDifferOnlyInCaseReachTheModelAsDifferentStrings() throws Exception {
        send(withSession("\"Demo\"")).andExpect(status().isOk());
        send(withSession("\"demo\"")).andExpect(status().isOk());

        assertEquals(List.of("Demo", "demo"), sessionIds);
        assertTrue(!sessionIds.get(0).equals(sessionIds.get(1)));
    }

    @Test
    void theSessionIdErrorComesFirstAmongSeveralInvalidFields() throws Exception {
        send("{\"question\":\"hola\",\"sessionId\":\"a b\",\"templateId\":\"pirata\",\"temperature\":3}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("sessionId"))
                .andExpect(jsonPath("$.errors[1].field").value("templateId"))
                .andExpect(jsonPath("$.errors[2].field").value("temperature"));
        assertTrue(sessionIds.isEmpty());
    }
}
