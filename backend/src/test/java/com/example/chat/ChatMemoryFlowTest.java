package com.example.chat;

import com.example.chat.exception.GlobalExceptionHandler;
import com.example.chat.impl.SpringAiModelService;
import com.example.chat.memory.BoundedChatMemoryRepository;
import com.example.chat.memory.ConversationMemory;
import com.example.chat.memory.MemorySettings;
import com.example.chat.support.ScriptedChatModel;
import com.example.chat.support.ScriptedChatModel.Answer;
import com.example.chat.support.TestMemory;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Flujo completo de una conversacion (feature 006, US3): controlador real, servicio real, memoria real y un
 * modelo guionizado. Comprueba que las metricas de la feature 003 siguen en cada turno, que una peticion
 * rechazada no cambia el historial y que un fallo del modelo no deja la pregunta guardada.
 */
class ChatMemoryFlowTest {

    /** Modelo guionizado que puede fallar en la siguiente llamada. */
    private static final class FlakyModel extends ScriptedChatModel {
        volatile boolean failNext;

        FlakyModel(Answer... answers) {
            super(answers);
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("el modelo no responde");
            }
            return super.call(prompt);
        }
    }

    private static Answer answer(String text, int promptTokens) {
        return new Answer(text, promptTokens, 10, "llama3.2:3b", Duration.ofSeconds(1));
    }

    private final FlakyModel model = new FlakyModel(
            answer("a1", 40), answer("a2", 60), answer("a3", 80), answer("a4", 100), answer("a5", 120));
    private final BoundedChatMemoryRepository repository = TestMemory.repository();
    private final ConversationMemory memory = TestMemory.conversationMemory(new MemorySettings(), repository);
    private final SpringAiModelService service =
            new SpringAiModelService(ChatClient.builder(model), TestPrompts.registry(), memory);
    private final MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ChatController(service, TestPrompts.registry(), TestPrompts.unusedClassification()))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private ResultActions send(String sessionId, String question, String extra) throws Exception {
        String body = "{\"question\":\"" + question + "\",\"sessionId\":\"" + sessionId + "\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
        return mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void everyTurnKeepsReportingTheUsageOfFeature003WithTheScriptedValues() throws Exception {
        send("flow", "q1", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.promptTokens").value(40))
                .andExpect(jsonPath("$.usage.completionTokens").value(10))
                .andExpect(jsonPath("$.usage.totalTokens").value(50))
                .andExpect(jsonPath("$.usage.model").value("llama3.2:3b"))
                .andExpect(jsonPath("$.usage.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.tokensPerSecond").value(10.0))
                .andExpect(jsonPath("$.historyMessages").value(0));
        send("flow", "q2", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage.promptTokens").value(60))
                .andExpect(jsonPath("$.usage.completionTokens").value(10))
                .andExpect(jsonPath("$.usage.totalTokens").value(70))
                .andExpect(jsonPath("$.usage.model").value("llama3.2:3b"))
                .andExpect(jsonPath("$.usage.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.tokensPerSecond").value(10.0))
                .andExpect(jsonPath("$.historyMessages").value(2));
    }

    @Test
    void aRejectedRequestInTheMiddleDoesNotCallTheModelNorChangeTheHistory() throws Exception {
        send("flow", "q1", "").andExpect(status().isOk());
        send("flow", "q2", "").andExpect(status().isOk());
        assertEquals(2, model.prompts().size());

        send("flow", "q3", "\"templateId\":\"pirata\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("templateId"))
                .andExpect(jsonPath("$.historyMessages").doesNotExist());
        send("flow", "q3", "\"temperature\":3")
                .andExpect(status().isBadRequest());

        assertEquals(2, model.prompts().size(), "el modelo no debe invocarse con una peticion rechazada");
        assertEquals(4, repository.findByConversationId("flow").size(), "el historial no debe cambiar");

        send("flow", "q3", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyMessages").value(4))   // 4, no 6
                .andExpect(jsonPath("$.usage.promptTokens").value(80));
    }

    @Test
    void aModelFailureKeepsTheSameStatusAndBodyAsWithoutMemoryAndTheFailedQuestionIsNotStored() throws Exception {
        send("flow", "q1", "").andExpect(status().isOk());
        send("flow", "q2", "").andExpect(status().isOk());

        // Mismo fallo sin sesion (como antes de la feature): estado y cuerpo de referencia.
        model.failNext = true;
        MvcResult withoutMemory = mvc.perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"q-fallo\"}")).andReturn();

        model.failNext = true;
        MvcResult withMemory = send("flow", "q-fallo", "").andReturn();

        assertEquals(withoutMemory.getResponse().getStatus(), withMemory.getResponse().getStatus());
        assertEquals(500, withMemory.getResponse().getStatus());
        assertEquals(withoutMemory.getResponse().getContentAsString(), withMemory.getResponse().getContentAsString());

        assertEquals(4, repository.findByConversationId("flow").size(), "la pregunta fallida no debe quedar guardada");
        send("flow", "q3", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyMessages").value(4));
    }

    @Test
    void aFailureInTheFirstTurnLeavesNoSessionBehind() throws Exception {
        model.failNext = true;
        send("nueva", "q1", "").andExpect(status().isInternalServerError());

        assertEquals(0, repository.findConversationIds().size());
        send("nueva", "q1", "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.historyMessages").value(0));
    }
}
