package com.example.chat;

import com.example.chat.dto.UsageMetrics;
import com.example.chat.exception.GlobalExceptionHandler;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ChatControllerUsageTest {

    private static final String BODY = "{\"question\":\"hola\",\"sessionId\":\"t\"}";

    private MockMvc mvc(ModelService service) {
        return MockMvcBuilders.standaloneSetup(new ChatController(service, TestPrompts.registry(), TestPrompts.unusedClassification()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void successfulAnswerCarriesAllSixUsageValues() throws Exception {
        var usage = new UsageMetrics(32, 118, 150, "llama3.2:3b", "stop", 27.64);

        mvc((q, s, o, t) -> new ModelResult("respuesta", usage))
                .perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.answer").value("respuesta"))
                .andExpect(jsonPath("$.elapsedMs").exists())
                .andExpect(jsonPath("$.requestId").exists())
                .andExpect(jsonPath("$.usage.promptTokens").value(32))
                .andExpect(jsonPath("$.usage.completionTokens").value(118))
                .andExpect(jsonPath("$.usage.totalTokens").value(150))
                .andExpect(jsonPath("$.usage.model").value("llama3.2:3b"))
                .andExpect(jsonPath("$.usage.finishReason").value("stop"))
                .andExpect(jsonPath("$.usage.tokensPerSecond").value(27.64));
    }

    @Test
    void successfulAnswerWithUnavailableValuesKeepsAnswerAndNulls() throws Exception {
        var usage = new UsageMetrics(null, null, null, "llama3.2:3b", null, null);

        mvc((q, s, o, t) -> new ModelResult("respuesta", usage))
                .perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("respuesta"))
                .andExpect(jsonPath("$.usage.promptTokens").value((Object) null))
                .andExpect(jsonPath("$.usage.tokensPerSecond").value((Object) null))
                .andExpect(jsonPath("$.usage.model").value("llama3.2:3b"));
    }

    @Test
    void validationErrorHasNoUsage() throws Exception {
        mvc((q, s, o, t) -> new ModelResult("x", null))
                .perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"\",\"sessionId\":\"t\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.usage").value((Object) null));
    }

    @Test
    void emptyModelAnswerHasNoUsage() throws Exception {
        var usage = new UsageMetrics(1, 1, 2, "m", "stop", 1.0);

        mvc((q, s, o, t) -> new ModelResult("", usage))
                .perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.usage").value((Object) null));
    }

    @Test
    void modelExceptionHasNoUsage() throws Exception {
        mvc((q, s, o, t) -> { throw new RuntimeException("boom"); })
                .perform(post("/api/v1/chat").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("error"))
                .andExpect(jsonPath("$.usage").value((Object) null));
    }
}
