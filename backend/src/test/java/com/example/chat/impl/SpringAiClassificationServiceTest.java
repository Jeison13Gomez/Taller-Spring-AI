package com.example.chat.impl;

import com.example.chat.classification.Category;
import com.example.chat.classification.Classification;
import com.example.chat.classification.FormatStrategy;
import com.example.chat.dto.ClassifyResponse;
import com.example.chat.exception.StructuredOutputException;
import com.example.chat.support.ScriptedChatModel;
import com.example.chat.support.TestMemory;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiClassificationServiceTest {

    private static final String VALID =
            "{\"category\":\"queja\",\"confidence\":0.87,\"justification\":\"Reporta un error de cobro.\"}";
    private static final String TEXT = "Mi factura tiene un monto equivocado, quiero un reembolso";

    private ClassifyResponse classify(ScriptedChatModel model, String text, Double temperature) {
        return new SpringAiClassificationService(ChatClient.builder(model))
                .classify(text, FormatStrategy.INSTRUCTION, temperature);
    }

    private ClassifyResponse classify(ScriptedChatModel model, FormatStrategy strategy, Double temperature) {
        return new SpringAiClassificationService(ChatClient.builder(model)).classify(TEXT, strategy, temperature);
    }

    @Test
    void aValidAnswerGivesTheResultWithOneAttemptAndNoRetry() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);

        ClassifyResponse response = classify(model, TEXT, null);

        assertEquals("ok", response.getStatus());
        assertNotNull(response.getRequestId());
        assertEquals(new Classification(Category.queja, 0.87, "Reporta un error de cobro."), response.getResult());
        assertEquals("instruction", response.getStrategy());
        assertEquals(Boolean.FALSE, response.getRetried());
        assertEquals(0, response.getRetries());
        assertEquals(1, response.getAttempts());
        assertTrue(response.getElapsedMs() >= 0);
        assertNull(response.getFailure());
        assertNull(response.getConsumed());
        assertNull(response.getErrors());
        assertEquals(1, model.prompts().size());
    }

    @Test
    void theUsageIsTheUsageOfTheAttempt() {
        ClassifyResponse response = classify(ScriptedChatModel.answering(VALID), TEXT, null);

        var usage = response.getUsage();
        assertNotNull(usage);
        assertEquals(32, usage.getPromptTokens());
        assertEquals(118, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals("llama3.2:3b", usage.getModel());
        assertEquals("stop", usage.getFinishReason());
        assertEquals(29.5, usage.getTokensPerSecond());
    }

    @Test
    void thePromptCarriesTheClassificationInstructionsTheTextAndTheFormat() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);

        classify(model, TEXT, null);

        Prompt prompt = model.lastPrompt();
        String system = prompt.getSystemMessage().getText();
        for (Category category : Category.values()) {
            assertTrue(system.contains(category.name()), "falta la categoria " + category + " en las instrucciones");
        }
        String user = prompt.getUserMessage().getText();
        assertTrue(user.contains(TEXT), user);
        // El texto de formato de Spring AI usa el separador de la plataforma; el servicio lo normaliza a \n.
        String format = new BeanOutputConverter<>(Classification.class).getFormat().replace("\r\n", "\n");
        assertTrue(user.contains(format), user);
    }

    @Test
    void theUserMessageIsTheTextThenOneLineFeedThenTheFormatWithNoPlatformSeparators() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);

        classify(model, TEXT, null);

        String user = model.lastPrompt().getUserMessage().getText();
        assertTrue(user.startsWith(TEXT + "\n"), user);
        assertFalse(user.contains("\r"), "el mensaje no debe llevar \\r (separador de Windows)");
        assertFalse(model.lastPrompt().getSystemMessage().getText().contains("\r"));
    }

    @Test
    void theTextIsSentAsIsEvenWithBracesAndAngleBrackets() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);
        String text = "Falla {x} al abrir {\"a\": 1} cuando a < b <tag>";

        classify(model, text, null);

        assertTrue(model.lastPrompt().getUserMessage().getText().startsWith(text));
    }

    @Test
    void theTemperatureReachesTheModelAndZeroIsAValue() {
        ScriptedChatModel withZero = ScriptedChatModel.answering(VALID);
        classify(withZero, TEXT, 0.0);
        assertEquals(0.0, withZero.lastPrompt().getOptions().getTemperature());

        ScriptedChatModel withValue = ScriptedChatModel.answering(VALID);
        classify(withValue, TEXT, 1.2);
        assertEquals(1.2, withValue.lastPrompt().getOptions().getTemperature());

        ScriptedChatModel without = ScriptedChatModel.answering(VALID);
        classify(without, TEXT, null);
        var options = without.lastPrompt().getOptions();
        assertTrue(options == null || options.getTemperature() == null, "sin temperatura no se fija ninguna");
    }

    @Test
    void anAnswerInsideAJsonFenceIsAccepted() {
        ClassifyResponse response = classify(ScriptedChatModel.answering("```json\n" + VALID + "\n```"), TEXT, null);

        assertEquals(Category.queja, response.getResult().category());
    }

    @Test
    void anInvalidAnswerFailsAfterExactlyOneModelCall() {
        ScriptedChatModel model = ScriptedChatModel.answering("No puedo clasificar eso");

        StructuredOutputException error = assertThrows(StructuredOutputException.class, () -> classify(model, TEXT, null));

        assertEquals("json", error.getCheck());
        assertEquals("instruction", error.getStrategy());
        assertEquals(1, error.getAttempts());
        assertEquals(0, error.getRetries());
        assertFalse(error.getFailureMessage().isBlank());
        assertNotNull(error.getConsumed());
        assertEquals(150, error.getConsumed().getTotalTokens());
        assertEquals(1, model.prompts().size(), "la estrategia instruction nunca reintenta");
    }

    @Test
    void anAnswerThatBreaksAFieldRuleIsAFormatFailureNamingTheField() {
        String badConfidence = "{\"category\":\"queja\",\"confidence\":1.5,\"justification\":\"x\"}";

        StructuredOutputException error = assertThrows(StructuredOutputException.class,
                () -> classify(ScriptedChatModel.answering(badConfidence), TEXT, null));

        assertEquals("confidence", error.getCheck());
    }

    // --- Estrategias (US4, T039) ----------------------------------------------------------------

    /** Categoria con mayuscula: el conversor la rechaza y el chequeo que falla es "category". */
    private static final String BAD_CATEGORY =
            "{\"category\":\"Queja\",\"confidence\":0.87,\"justification\":\"Reporta un error de cobro.\"}";
    private static final String CATEGORY_MESSAGE = "category must be one of consulta, queja, incidencia, otro";

    @Test
    void validateRetryRetriesOnceAndSumsTheUsageOfBothAttempts() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.Answer.of(BAD_CATEGORY), ScriptedChatModel.Answer.of(VALID));

        ClassifyResponse response = classify(model, FormatStrategy.VALIDATE_RETRY, null);

        assertEquals("ok", response.getStatus());
        assertEquals("validate-retry", response.getStrategy());
        assertEquals(Boolean.TRUE, response.getRetried());
        assertEquals(1, response.getRetries());
        assertEquals(2, response.getAttempts());
        assertEquals(Category.queja, response.getResult().category());
        assertEquals(2, model.prompts().size());
        var usage = response.getUsage();
        assertEquals(64, usage.getPromptTokens());
        assertEquals(236, usage.getCompletionTokens());
        assertEquals(300, usage.getTotalTokens());
    }

    @Test
    void theRetryPromptNamesTheFailedCheckAndItsMessage() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.Answer.of(BAD_CATEGORY), ScriptedChatModel.Answer.of(VALID));

        classify(model, FormatStrategy.VALIDATE_RETRY, null);

        String first = model.prompts().get(0).getUserMessage().getText();
        String second = model.prompts().get(1).getUserMessage().getText();
        assertFalse(first.contains(CATEGORY_MESSAGE), "el primer intento no lleva ningun error");
        assertTrue(second.startsWith(first), "el reintento conserva el mensaje original");
        assertTrue(second.contains(CATEGORY_MESSAGE), second);
        assertTrue(second.contains("category"), second);
        assertFalse(second.contains("\r"));
    }

    @Test
    void validateRetryWithAValidFirstAnswerDoesNotRetry() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);

        ClassifyResponse response = classify(model, FormatStrategy.VALIDATE_RETRY, null);

        assertEquals(Boolean.FALSE, response.getRetried());
        assertEquals(0, response.getRetries());
        assertEquals(1, response.getAttempts());
        assertEquals(1, model.prompts().size());
        assertEquals(150, response.getUsage().getTotalTokens());
    }

    @Test
    void validateRetryGivesUpAfterThreeAttemptsAndNeverMakesAFourthCall() {
        ScriptedChatModel model = ScriptedChatModel.answering(BAD_CATEGORY);

        StructuredOutputException error = assertThrows(StructuredOutputException.class,
                () -> classify(model, FormatStrategy.VALIDATE_RETRY, null));

        assertEquals("category", error.getCheck());
        assertEquals("validate-retry", error.getStrategy());
        assertEquals(3, error.getAttempts());
        assertEquals(2, error.getRetries());
        assertEquals(3, model.prompts().size(), "como maximo tres llamadas: el intento inicial y dos reintentos");
        assertEquals(450, error.getConsumed().getTotalTokens(), "se suma el coste de los tres intentos");
    }

    @Test
    void instructionWithAnInvalidAnswerMakesOneCallOnly() {
        ScriptedChatModel model = ScriptedChatModel.answering(BAD_CATEGORY);

        StructuredOutputException error = assertThrows(StructuredOutputException.class,
                () -> classify(model, FormatStrategy.INSTRUCTION, null));

        assertEquals(1, error.getAttempts());
        assertEquals(1, model.prompts().size());
    }

    @Test
    void nativeSchemaSendsTheSchemaInTheOptionsAndNoFormatInstruction() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);

        ClassifyResponse response = classify(model, FormatStrategy.NATIVE_SCHEMA, null);

        assertEquals("native-schema", response.getStrategy());
        assertEquals(0, response.getRetries());
        assertEquals(1, response.getAttempts());
        Prompt prompt = model.lastPrompt();
        String schema = ((OllamaChatOptions) prompt.getOptions()).getOutputSchema();
        assertNotNull(schema, "native-schema debe fijar el esquema en las opciones");
        for (Category category : Category.values()) {
            assertTrue(schema.contains(category.name()), "falta " + category + " en el esquema: " + schema);
        }
        String user = prompt.getUserMessage().getText();
        assertEquals(TEXT, user, "sin instruccion de formato: solo el texto");
    }

    @Test
    void nativeSchemaStillRejectsAnInvalidAnswerAfterOneCall() {
        ScriptedChatModel model = ScriptedChatModel.answering(BAD_CATEGORY);

        StructuredOutputException error = assertThrows(StructuredOutputException.class,
                () -> classify(model, FormatStrategy.NATIVE_SCHEMA, null));

        assertEquals("category", error.getCheck());
        assertEquals("native-schema", error.getStrategy());
        assertEquals(0, error.getRetries());
        assertEquals(1, model.prompts().size());
    }

    @Test
    void theOtherStrategiesDoNotSetAnOutputSchema() {
        for (FormatStrategy strategy : new FormatStrategy[] {FormatStrategy.INSTRUCTION, FormatStrategy.VALIDATE_RETRY}) {
            ScriptedChatModel model = ScriptedChatModel.answering(VALID);
            classify(model, strategy, 0.0);
            var options = model.lastPrompt().getOptions();
            assertTrue(!(options instanceof OllamaChatOptions ollama) || ollama.getOutputSchema() == null,
                    strategy.getId() + " no debe enviar format a Ollama");
        }
    }

    @Test
    void theTemperatureIsAppliedInEveryStrategy() {
        for (FormatStrategy strategy : FormatStrategy.values()) {
            ScriptedChatModel withZero = ScriptedChatModel.answering(VALID);
            classify(withZero, strategy, 0.0);
            assertEquals(0.0, withZero.lastPrompt().getOptions().getTemperature(), strategy.getId());
        }
    }

    // --- Fallos de formato por chequeo (US5, T047) ----------------------------------------------

    private static String answer(String category, String confidence, String justification) {
        StringBuilder json = new StringBuilder("{");
        if (category != null) {
            json.append("\"category\":\"").append(category).append("\",");
        }
        if (confidence != null) {
            json.append("\"confidence\":").append(confidence).append(',');
        }
        if (justification != null) {
            json.append("\"justification\":\"").append(justification).append("\",");
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.setLength(json.length() - 1);
        }
        return json.append('}').toString();
    }

    /** Respuesta invalida -> chequeo que debe nombrar la excepcion. */
    private static final java.util.Map<String, String> FAILING_ANSWERS = java.util.Map.ofEntries(
            java.util.Map.entry("No puedo clasificar eso", "json"),
            java.util.Map.entry("", "json"),
            java.util.Map.entry("{\"category\":\"queja\",\"confidence\":0.8,\"justification\":\"x\"", "json"),
            java.util.Map.entry(answer("otra", "0.5", "x"), "category"),
            java.util.Map.entry(answer("Queja", "0.5", "x"), "category"),
            java.util.Map.entry(answer(null, "0.5", "x"), "category"),
            java.util.Map.entry(answer("queja", null, "x"), "confidence"),
            java.util.Map.entry(answer("queja", "1.5", "x"), "confidence"),
            java.util.Map.entry(answer("queja", "-0.1", "x"), "confidence"),
            java.util.Map.entry(answer("queja", "0.5", ""), "justification"),
            java.util.Map.entry(answer("queja", "0.5", "   "), "justification"),
            java.util.Map.entry(answer("queja", "0.5", null), "justification"));

    @Test
    void eachBrokenAnswerFailsWithTheCheckThatItBreaks() {
        FAILING_ANSWERS.forEach((badAnswer, expectedCheck) -> {
            ScriptedChatModel model = ScriptedChatModel.answering(badAnswer);

            StructuredOutputException error = assertThrows(StructuredOutputException.class,
                    () -> classify(model, FormatStrategy.INSTRUCTION, null), "respuesta: [" + badAnswer + "]");

            assertEquals(expectedCheck, error.getCheck(), "respuesta: [" + badAnswer + "]");
            assertFalse(error.getFailureMessage().isBlank());
        });
    }

    @Test
    void consumedIsTheSumOfTheFailedAttemptsAndElapsedIsNotNegative() {
        FAILING_ANSWERS.forEach((badAnswer, expectedCheck) -> {
            ScriptedChatModel model = ScriptedChatModel.answering(badAnswer);

            StructuredOutputException error = assertThrows(StructuredOutputException.class,
                    () -> classify(model, FormatStrategy.VALIDATE_RETRY, null), "respuesta: [" + badAnswer + "]");

            assertEquals(expectedCheck, error.getCheck(), "el chequeo es el del ultimo intento");
            assertEquals(3, error.getAttempts());
            // Cada intento guionizado cuesta 32 + 118 tokens y 4 s de generacion.
            assertEquals(96, error.getConsumed().getPromptTokens());
            assertEquals(354, error.getConsumed().getCompletionTokens());
            assertEquals(450, error.getConsumed().getTotalTokens());
            assertEquals(29.5, error.getConsumed().getTokensPerSecond());
            assertTrue(error.getElapsedMs() >= 0);
        });
    }

    @Test
    void aFailureOfTheModelPropagatesAsIsInEveryStrategy() {
        for (FormatStrategy strategy : FormatStrategy.values()) {
            IllegalStateException original = new IllegalStateException("Ollama no responde");
            var failingModel = new ScriptedChatModel() {
                @Override
                public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                    throw original;
                }
            };

            RuntimeException thrown = assertThrows(RuntimeException.class, () -> classify(failingModel, strategy, null));

            assertTrue(!(thrown instanceof StructuredOutputException), strategy.getId());
            assertEquals(IllegalStateException.class, thrown.getClass(), strategy.getId());
        }
    }

    @Test
    void aFailureOfTheModelOnARetryPropagatesAndIsNotAFormatFailure() {
        var model = new ScriptedChatModel(ScriptedChatModel.Answer.of(BAD_CATEGORY)) {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                if (!prompts().isEmpty()) {
                    throw new IllegalStateException("Ollama se cayo");
                }
                return super.call(prompt);
            }
        };

        assertThrows(IllegalStateException.class, () -> classify(model, FormatStrategy.VALIDATE_RETRY, null));
    }

    @Test
    void aFailureOfTheModelItselfIsNotAFormatFailure() {
        var failingModel = new ScriptedChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                throw new IllegalStateException("Ollama no responde");
            }
        };

        assertThrows(IllegalStateException.class, () -> classify(failingModel, TEXT, null));
    }

    // --- /classify no usa memoria (feature 006) --------------------------------------------------

    @Test
    void aClassificationAfterChatTurnsInASessionSendsOnlySystemAndUserAndStoresNothing() {
        ScriptedChatModel model = ScriptedChatModel.answering(VALID);
        // El mismo ChatClient.Builder para el chat y la clasificacion, como en la aplicacion.
        ChatClient.Builder builder = ChatClient.builder(model);
        com.example.chat.memory.BoundedChatMemoryRepository repository = TestMemory.repository();
        SpringAiModelService chat = new SpringAiModelService(builder, TestPrompts.registry(),
                TestMemory.conversationMemory(new com.example.chat.memory.MemorySettings(), repository));
        SpringAiClassificationService classification = new SpringAiClassificationService(builder);

        chat.generateResponse("pregunta 1", "sesion", com.example.chat.GenerationSettings.none(), null);
        chat.generateResponse("pregunta 2", "sesion", com.example.chat.GenerationSettings.none(), null);
        assertEquals(4, repository.findByConversationId("sesion").size());

        classification.classify(TEXT, FormatStrategy.INSTRUCTION, null);

        Prompt prompt = model.lastPrompt();
        assertEquals(2, prompt.getInstructions().size(), "solo sistema y usuario, sin historial");
        assertEquals("SYSTEM", prompt.getInstructions().get(0).getMessageType().name());
        assertEquals("USER", prompt.getInstructions().get(1).getMessageType().name());
        assertTrue(prompt.getUserMessage().getText().startsWith(TEXT));
        assertEquals(java.util.List.of("sesion"), repository.findConversationIds(),
                "clasificar no crea ni cambia ninguna sesion");
        assertEquals(4, repository.findByConversationId("sesion").size());
    }

    @Test
    void theClassificationServiceHasNoWayToReceiveTheConversationMemory() {
        for (java.lang.reflect.Constructor<?> constructor : SpringAiClassificationService.class.getDeclaredConstructors()) {
            for (Class<?> type : constructor.getParameterTypes()) {
                assertFalse(type.equals(com.example.chat.memory.ConversationMemory.class),
                        "el constructor de la clasificacion no debe recibir la memoria: " + constructor);
            }
        }
        for (java.lang.reflect.Field field : SpringAiClassificationService.class.getDeclaredFields()) {
            assertFalse(field.getType().equals(com.example.chat.memory.ConversationMemory.class),
                    "la clasificacion no debe tener la memoria como campo: " + field.getName());
        }
    }
}
