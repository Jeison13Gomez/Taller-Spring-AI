package com.example.chat.impl;

import com.example.chat.GenerationSettings;
import com.example.chat.ModelResult;
import com.example.chat.memory.BoundedChatMemoryRepository;
import com.example.chat.memory.ConversationMemory;
import com.example.chat.memory.MemorySettings;
import com.example.chat.support.ScriptedChatModel;
import com.example.chat.support.MutableClock;
import com.example.chat.support.ScriptedChatModel.Answer;
import com.example.chat.support.TestMemory;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Memoria de conversacion (feature 006, US1) con el servicio real, la memoria real y un modelo guionizado:
 * lo que se comprueba es lo que le llega al modelo y lo que queda guardado.
 */
class SpringAiModelServiceMemoryTest {

    private final BoundedChatMemoryRepository repository = TestMemory.repository();
    private final ConversationMemory memory = TestMemory.conversationMemory(new MemorySettings(), repository);

    private SpringAiModelService serviceOver(ScriptedChatModel model) {
        return new SpringAiModelService(ChatClient.builder(model), TestPrompts.registry(), memory);
    }

    private static ScriptedChatModel answering(String... texts) {
        Answer[] answers = new Answer[texts.length];
        for (int i = 0; i < texts.length; i++) {
            answers[i] = Answer.of(texts[i]);
        }
        return new ScriptedChatModel(answers);
    }

    private static ModelResult ask(SpringAiModelService service, String question, String sessionId) {
        return service.generateResponse(question, sessionId, GenerationSettings.none(), null);
    }

    /** Los mensajes que recibio el modelo, como "TIPO:texto" (el de sistema se abrevia a "SYSTEM"). */
    private static List<String> sent(Prompt prompt) {
        return prompt.getInstructions().stream()
                .map(m -> m.getMessageType() == org.springframework.ai.chat.messages.MessageType.SYSTEM
                        ? "SYSTEM" : m.getMessageType() + ":" + m.getText())
                .toList();
    }

    @Test
    void theFirstTurnOfASessionSendsOnlySystemAndQuestionAndReportsNoHistory() {
        ScriptedChatModel model = answering("a1");
        ModelResult result = ask(serviceOver(model), "q1", "s1");

        assertEquals(List.of("SYSTEM", "USER:q1"), sent(model.lastPrompt()));
        assertEquals(0, result.historyMessages());
        assertEquals("a1", result.answer());
    }

    @Test
    void laterTurnsSendTheEarlierQuestionsAndAnswersInOrderAfterTheSystemMessage() {
        ScriptedChatModel model = answering("a1", "a2", "a3");
        SpringAiModelService service = serviceOver(model);

        ask(service, "q1", "s1");
        ModelResult second = ask(service, "q2", "s1");
        assertEquals(List.of("SYSTEM", "USER:q1", "ASSISTANT:a1", "USER:q2"), sent(model.lastPrompt()));
        assertEquals(2, second.historyMessages());

        ModelResult third = ask(service, "q3", "s1");
        assertEquals(List.of("SYSTEM", "USER:q1", "ASSISTANT:a1", "USER:q2", "ASSISTANT:a2", "USER:q3"),
                sent(model.lastPrompt()));
        assertEquals(4, third.historyMessages());
    }

    @Test
    void interleavedSessionsNeverSeeEachOthersText() {
        ScriptedChatModel model = answering("ans-alpha1", "ans-beta1", "ans-alpha2", "ans-beta2");
        SpringAiModelService service = serviceOver(model);

        ask(service, "alpha1", "s1");
        ask(service, "beta1", "s2");
        ask(service, "alpha2", "s1");
        List<String> alphaTurn = sent(model.lastPrompt());
        ask(service, "beta2", "s2");
        List<String> betaTurn = sent(model.lastPrompt());

        assertEquals(List.of("SYSTEM", "USER:alpha1", "ASSISTANT:ans-alpha1", "USER:alpha2"), alphaTurn);
        assertEquals(List.of("SYSTEM", "USER:beta1", "ASSISTANT:ans-beta1", "USER:beta2"), betaTurn);
    }

    @Test
    void aRequestWithoutSessionIdSendsOnlySystemAndQuestionEveryTimeAndStoresNothing() {
        ScriptedChatModel model = answering("a1", "a2", "a3");
        SpringAiModelService service = serviceOver(model);

        for (String question : List.of("q1", "q2", "q3")) {
            ModelResult result = ask(service, question, null);
            assertEquals(List.of("SYSTEM", "USER:" + question), sent(model.lastPrompt()));
            assertEquals(0, result.historyMessages());
        }
        assertTrue(repository.findConversationIds().isEmpty(), "sin sessionId no se guarda ninguna sesion");
    }

    @Test
    void historyMessagesIsAlwaysTheNumberOfMessagesSentMinusSystemAndQuestion() {
        ScriptedChatModel model = answering("a");
        SpringAiModelService service = serviceOver(model);

        for (int turn = 1; turn <= 6; turn++) {
            ModelResult result = ask(service, "q" + turn, "s1");
            List<Message> messages = model.lastPrompt().getInstructions();
            assertEquals(messages.size() - 2, result.historyMessages(), "turno " + turn);
            assertEquals(2 * (turn - 1), result.historyMessages(), "turno " + turn);
        }
    }

    @Test
    void theHistoryOfTheSameSessionAlwaysReachesTheModelAndTheOtherSessionNeverDoes() {
        // SC-001: 10 pares de sesiones independientes, cada una con su "hecho" distintivo.
        int sameSessionHistory = 0;
        int otherSessionHistory = 0;
        ScriptedChatModel model = answering("ok");
        SpringAiModelService service = serviceOver(model);

        for (int pair = 0; pair < 10; pair++) {
            String factA = "hecho-A-" + pair;
            String factB = "hecho-B-" + pair;
            ask(service, factA, "pair-" + pair + "-a");
            ask(service, factB, "pair-" + pair + "-b");

            ask(service, "seguimiento-A-" + pair, "pair-" + pair + "-a");
            List<String> followUp = sent(model.lastPrompt());
            if (followUp.contains("USER:" + factA) && followUp.contains("ASSISTANT:ok")) {
                sameSessionHistory++;
            }
            if (followUp.contains("USER:" + factB)) {
                otherSessionHistory++;
            }
        }

        assertEquals(10, sameSessionHistory, "el historial de la misma sesion debe llegar en 10 de 10 seguimientos");
        assertEquals(0, otherSessionHistory, "el historial de la otra sesion no debe llegar en ninguno");
        assertFalse(repository.findConversationIds().isEmpty());
    }

    // --- Ventana, caducidad y tope de sesiones (feature 006, US2) --------------------------------

    /** Servicio con sus propios ajustes, reloj y repositorio, para las pruebas que los cambian. */
    private record Rig(SpringAiModelService service, BoundedChatMemoryRepository repository,
                       ScriptedChatModel model, MutableClock clock) {
    }

    private static Rig rig(MemorySettings settings, ScriptedChatModel model) {
        MutableClock clock = new MutableClock();
        BoundedChatMemoryRepository repository = new BoundedChatMemoryRepository(settings, clock);
        ConversationMemory memory = new ConversationMemory(settings, repository);
        return new Rig(new SpringAiModelService(ChatClient.builder(model), TestPrompts.registry(), memory),
                repository, model, clock);
    }

    private static MemorySettings window(int maxMessages) {
        MemorySettings settings = new MemorySettings();
        settings.setMaxMessages(maxMessages);
        return settings;
    }

    private static String[] numbered(String prefix, int count) {
        String[] texts = new String[count];
        for (int i = 0; i < count; i++) {
            texts[i] = prefix + (i + 1);
        }
        return texts;
    }

    @Test
    void withAWindowOfFourTheSixthRequestCarriesOnlyTheLastFourHistoryMessages() {
        Rig rig = rig(window(4), answering(numbered("a", 6)));

        ModelResult last = null;
        for (int turn = 1; turn <= 6; turn++) {
            last = ask(rig.service(), "q" + turn, "s1");
        }

        assertEquals(List.of("SYSTEM", "USER:q4", "ASSISTANT:a4", "USER:q5", "ASSISTANT:a5", "USER:q6"),
                sent(rig.model().lastPrompt()));
        assertEquals(4, last.historyMessages());
        for (String message : sent(rig.model().lastPrompt())) {
            assertFalse(message.endsWith("q1") || message.endsWith("a1") || message.endsWith("q2")
                    || message.endsWith("a2") || message.endsWith("q3") || message.endsWith("a3"),
                    "los mensajes antiguos deben haberse descartado: " + message);
        }
    }

    @Test
    void theSystemMessageIsFirstAndAloneInEveryRequestWhateverTheHistorySize() {
        Rig rig = rig(window(4), answering("a"));

        for (int turn = 1; turn <= 8; turn++) {
            ask(rig.service(), "q" + turn, "s1");
            List<Message> messages = rig.model().lastPrompt().getInstructions();
            assertEquals("SYSTEM", messages.get(0).getMessageType().name(), "turno " + turn);
            long systemMessages = messages.stream().filter(m -> m.getMessageType().name().equals("SYSTEM")).count();
            assertEquals(1, systemMessages, "turno " + turn);
        }
    }

    @Test
    void aWindowOfTwoKeepsExactlyTheLastExchange() {
        Rig rig = rig(window(2), answering(numbered("a", 4)));

        ModelResult last = null;
        for (int turn = 1; turn <= 4; turn++) {
            last = ask(rig.service(), "q" + turn, "s1");
        }

        assertEquals(List.of("SYSTEM", "USER:q3", "ASSISTANT:a3", "USER:q4"), sent(rig.model().lastPrompt()));
        assertEquals(2, last.historyMessages());
    }

    @Test
    void anOddWindowOfThreeBehavesLikeTwoAndNeverStartsWithAnAnswer() {
        Rig rig = rig(window(3), answering(numbered("a", 5)));

        for (int turn = 1; turn <= 5; turn++) {
            ModelResult result = ask(rig.service(), "q" + turn, "s1");
            List<String> messages = sent(rig.model().lastPrompt());
            assertEquals(Math.min(2, 2 * (turn - 1)), result.historyMessages(), "turno " + turn);
            if (turn > 1) {
                assertTrue(messages.get(1).startsWith("USER:"),
                        "el historial no puede empezar por una respuesta: " + messages);
            }
        }
        assertEquals(List.of("SYSTEM", "USER:q4", "ASSISTANT:a4", "USER:q5"), sent(rig.model().lastPrompt()));
    }

    @Test
    void withTheDefaultWindowTwelveTurnsReportZeroToTwentyAndThenStayAtTwenty() {
        Rig rig = rig(new MemorySettings(), answering(numbered("a", 12)));

        List<Integer> reported = new ArrayList<>();
        for (int turn = 1; turn <= 12; turn++) {
            reported.add(ask(rig.service(), "q" + turn, "s1").historyMessages());
            if (turn > 1) {
                assertTrue(sent(rig.model().lastPrompt()).get(1).startsWith("USER:"), "turno " + turn);
            }
        }

        assertEquals(List.of(0, 2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 20), reported);
    }

    @Test
    void aSessionIdleForLessThanTheTimeoutKeepsItsHistory() {
        Rig rig = rig(new MemorySettings(), answering("a1", "a2"));

        ask(rig.service(), "q1", "s1");
        rig.clock().advance(Duration.ofMinutes(29));
        ModelResult second = ask(rig.service(), "q2", "s1");

        assertEquals(2, second.historyMessages());
        assertEquals(List.of("SYSTEM", "USER:q1", "ASSISTANT:a1", "USER:q2"), sent(rig.model().lastPrompt()));
    }

    @Test
    void aSessionIdleForLongerThanTheTimeoutStartsEmpty() {
        Rig rig = rig(new MemorySettings(), answering("a1", "a2"));

        ask(rig.service(), "q1", "s1");
        rig.clock().advance(Duration.ofMinutes(31));
        ModelResult second = ask(rig.service(), "q2", "s1");

        assertEquals(0, second.historyMessages());
        assertEquals(List.of("SYSTEM", "USER:q2"), sent(rig.model().lastPrompt()));
    }

    @Test
    void usingASessionKeepsItAliveSoTheIdleTimeIsCountedFromTheLastUse() {
        Rig rig = rig(new MemorySettings(), answering("a"));

        ask(rig.service(), "q1", "s1");
        rig.clock().advance(Duration.ofMinutes(20));
        ask(rig.service(), "q2", "s1");
        rig.clock().advance(Duration.ofMinutes(20));
        ModelResult third = ask(rig.service(), "q3", "s1");

        assertEquals(4, third.historyMessages(), "40 minutos desde el primer turno, pero 20 desde el ultimo uso");
    }

    @Test
    void theHundredAndFirstSessionDropsTheLeastRecentlyUsedOne() {
        Rig rig = rig(new MemorySettings(), answering("a"));

        for (int i = 0; i < 100; i++) {
            ask(rig.service(), "q", "s-" + i);
        }
        ask(rig.service(), "q again", "s-0");   // s-0 se usa de nuevo: s-1 pasa a ser la menos usada
        ask(rig.service(), "q", "s-100");

        List<String> stored = rig.repository().findConversationIds();
        assertEquals(100, stored.size());
        assertTrue(stored.contains("s-0"), "s-0 se uso hace poco y debe seguir");
        assertTrue(stored.contains("s-100"));
        assertFalse(stored.contains("s-1"), "s-1 era la menos usada y debe haberse descartado");
        assertEquals(0, ask(rig.service(), "q", "s-1").historyMessages(), "la sesion descartada empieza vacia");
    }

    @Test
    void aThousandDistinctSessionsNeverMakeTheStoredCountExceedTheCap() {
        Rig rig = rig(new MemorySettings(), answering("a"));

        for (int i = 0; i < 1000; i++) {
            ask(rig.service(), "q", "mil-" + i);
            int stored = rig.repository().findConversationIds().size();
            assertTrue(stored <= 100, "hay " + stored + " sesiones tras la peticion " + (i + 1));
        }
        assertEquals(100, rig.repository().findConversationIds().size());
        // Se quedan las ultimas 100.
        assertTrue(rig.repository().findConversationIds().contains("mil-999"));
        assertFalse(rig.repository().findConversationIds().contains("mil-899"));
        assertTrue(rig.repository().findConversationIds().contains("mil-900"));
    }

    // --- Plantillas con memoria (feature 006, US3) -----------------------------------------------

    private static ModelResult askWith(SpringAiModelService service, String question, String sessionId, String templateId) {
        return service.generateResponse(question, sessionId, GenerationSettings.none(), templateId);
    }

    private static String rendered(String templateId) {
        return TestPrompts.registry().render(templateId);
    }

    @Test
    void aTurnThatChangesTemplateSendsOnlyThatTemplatesSystemTextAndKeepsTheHistory() {
        ScriptedChatModel model = answering("a1", "a2");
        SpringAiModelService service = serviceOver(model);

        askWith(service, "q1", "s1", "concise");
        assertEquals(rendered("concise"), model.lastPrompt().getSystemMessage().getText());

        ModelResult second = askWith(service, "q2", "s1", "tutor");

        List<Message> messages = model.lastPrompt().getInstructions();
        assertEquals(4, messages.size());
        assertEquals("SYSTEM", messages.get(0).getMessageType().name());
        assertEquals(rendered("tutor"), messages.get(0).getText(), "el sistema es el de la plantilla de ESTE turno");
        assertEquals(List.of("SYSTEM", "USER:q1", "ASSISTANT:a1", "USER:q2"), sent(model.lastPrompt()));
        assertEquals(2, second.historyMessages());
        assertFalse(messages.stream().skip(1).anyMatch(m -> m.getText().contains(rendered("concise"))),
                "el texto de la plantilla anterior no debe aparecer en el historial");
    }

    @Test
    void aTurnWithoutTemplateIdUsesTheDefaultTextAndKeepsTheHistory() {
        ScriptedChatModel model = answering("a1", "a2", "a3");
        SpringAiModelService service = serviceOver(model);

        askWith(service, "q1", "s1", "extractor");
        askWith(service, "q2", "s1", "tutor");
        ModelResult third = askWith(service, "q3", "s1", null);

        assertEquals(rendered("default"), model.lastPrompt().getSystemMessage().getText());
        assertEquals(4, third.historyMessages());
        assertEquals(List.of("SYSTEM", "USER:q1", "ASSISTANT:a1", "USER:q2", "ASSISTANT:a2", "USER:q3"),
                sent(model.lastPrompt()));
    }

    @Test
    void noSystemMessageIsEverStoredInTheHistory() {
        ScriptedChatModel model = answering("a");
        SpringAiModelService service = serviceOver(model);

        for (String template : new String[] {"concise", "tutor", "extractor", null, "default"}) {
            askWith(service, "q-" + template, "s1", template);
        }

        List<Message> stored = repository.findByConversationId("s1");
        assertEquals(10, stored.size());
        for (Message message : stored) {
            assertFalse(message.getMessageType().name().equals("SYSTEM"),
                    "el historial no debe guardar mensajes de sistema: " + message.getText());
        }
    }

    @Test
    void everyRequestHasExactlyOneSystemMessageAcrossTenTurnsWithChangingTemplates() {
        ScriptedChatModel model = answering("a");
        SpringAiModelService service = serviceOver(model);
        String[] templates = {null, "concise", "tutor", "extractor", "default"};

        for (int turn = 1; turn <= 10; turn++) {
            String template = templates[turn % templates.length];
            askWith(service, "q" + turn, "s1", template);

            List<Message> messages = model.lastPrompt().getInstructions();
            long systems = messages.stream().filter(m -> m.getMessageType().name().equals("SYSTEM")).count();
            assertEquals(1, systems, "turno " + turn);
            String expected = rendered(template == null ? "default" : template);
            assertEquals(expected, messages.get(0).getText(), "turno " + turn);
        }
    }
}
