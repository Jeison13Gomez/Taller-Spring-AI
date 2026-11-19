package com.example.chat.impl;

import com.example.chat.GenerationSettings;
import com.example.chat.ModelResult;
import com.example.chat.support.ScriptedChatModel;
import com.example.chat.support.TestMemory;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prueba de compatibilidad (feature 005, US2): lo que llega al modelo sin plantilla es lo mismo que
 * antes de la feature, y la pregunta nunca se interpreta como plantilla.
 */
class SpringAiModelServiceTemplateTest {

    /** El prompt de sistema que tenia SpringAiModelService antes de esta feature, palabra por palabra. */
    private static final String ORIGINAL_SYSTEM_PROMPT =
            "Eres un asistente conciso y directo.\n"
                    + "Responde siempre en el mismo idioma en el que te escriba el usuario.\n"
                    + "Si no sabes algo, dilo en lugar de inventar.\n";

    private final ScriptedChatModel model = ScriptedChatModel.answering("respuesta");
    private final SpringAiModelService service =
            new SpringAiModelService(ChatClient.builder(model), TestPrompts.registry(), TestMemory.conversationMemory());

    @Test
    void withoutTemplateTheModelReceivesTheOriginalSystemPromptAndTheQuestionVerbatim() {
        service.generateResponse("Que es Spring AI?", "t", GenerationSettings.none(), null);

        Prompt prompt = model.lastPrompt();
        assertEquals(ORIGINAL_SYSTEM_PROMPT, prompt.getSystemMessage().getText());
        assertEquals("Que es Spring AI?", prompt.getUserMessage().getText());
    }

    @Test
    void anEmptyTemplateIdIsTheSameAsNoTemplate() {
        service.generateResponse("hola", "t", GenerationSettings.none(), "");

        assertEquals(ORIGINAL_SYSTEM_PROMPT, model.lastPrompt().getSystemMessage().getText());
    }

    @Test
    void aQuestionWithBracesAndAngleBracketsIsSentAsIsAndDoesNotFail() {
        String question = "Que significa {x} en {\"a\": 1}? y si a < b entonces <tag>?";

        ModelResult result = service.generateResponse(question, "t", GenerationSettings.none(), null);

        assertEquals(question, model.lastPrompt().getUserMessage().getText());
        assertEquals("respuesta", result.answer());
        // Lo mismo con una plantilla elegida: la pregunta sigue sin interpretarse.
        service.generateResponse(question, "t", GenerationSettings.none(), "extractor");
        assertEquals(question, model.lastPrompt().getUserMessage().getText());
    }

    @Test
    void aChosenTemplateReplacesTheSystemPromptWithItsRenderedText() {
        service.generateResponse("hola", "t", GenerationSettings.none(), "concise");

        String system = model.lastPrompt().getSystemMessage().getText();
        assertEquals(TestPrompts.registry().render("concise"), system);
        assertNotEquals(ORIGINAL_SYSTEM_PROMPT, system);
    }

    @Test
    void theExtractorTemplateReachesTheModelWithItsJsonBracesIntact() {
        service.generateResponse("hola", "t", GenerationSettings.none(), "extractor");

        String system = model.lastPrompt().getSystemMessage().getText();
        assertEquals(TestPrompts.registry().render("extractor"), system);
    }

    @Test
    void generationSettingsStillReachTheModelTogetherWithATemplate() {
        service.generateResponse("hola", "t", new GenerationSettings(0.0, null, null, 5, null), "tutor");

        Prompt prompt = model.lastPrompt();
        assertNotNull(prompt.getOptions());
        assertEquals(0.0, prompt.getOptions().getTemperature());
        assertEquals(TestPrompts.registry().render("tutor"), prompt.getSystemMessage().getText());
    }

    @Test
    void usageMetricsOfFeature003AreStillFilled() {
        ModelResult result = service.generateResponse("hola", "t", GenerationSettings.none(), "concise");

        var usage = result.usage();
        assertNotNull(usage);
        assertEquals(32, usage.getPromptTokens());
        assertEquals(118, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals("llama3.2:3b", usage.getModel());
        assertEquals("stop", usage.getFinishReason());
        assertEquals(29.5, usage.getTokensPerSecond());
    }
}
