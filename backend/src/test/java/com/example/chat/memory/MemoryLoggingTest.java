package com.example.chat.memory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.chat.GenerationSettings;
import com.example.chat.impl.SpringAiModelService;
import com.example.chat.support.MutableClock;
import com.example.chat.support.ScriptedChatModel;
import com.example.chat.support.ScriptedChatModel.Answer;
import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-015: el codigo nuevo de la memoria no escribe en el log el texto de ninguna pregunta, respuesta ni
 * historial. Solo puede aparecer el identificador abreviado y numeros.
 */
class MemoryLoggingTest {

    private static final List<String> SECRETS = List.of(
            "PREGUNTA-SECRETA-1", "PREGUNTA-SECRETA-2", "PREGUNTA-SECRETA-3", "PREGUNTA-SECRETA-4",
            "PREGUNTA-SECRETA-5", "PREGUNTA-SECRETA-6", "RESPUESTA-SECRETA-1", "RESPUESTA-SECRETA-2",
            "RESPUESTA-SECRETA-3", "RESPUESTA-SECRETA-5", "RESPUESTA-SECRETA-6");

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final List<Logger> loggers = new ArrayList<>();
    private final List<Level> previousLevels = new ArrayList<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        for (String name : List.of("com.example.chat.memory", SpringAiModelService.class.getName())) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            loggers.add(logger);
            previousLevels.add(logger.getLevel());
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }
    }

    @AfterEach
    void detachAppender() {
        for (int i = 0; i < loggers.size(); i++) {
            loggers.get(i).detachAppender(appender);
            loggers.get(i).setLevel(previousLevels.get(i));
        }
        appender.stop();
    }

    /** Falla en la cuarta llamada: simula un fallo del modelo en medio de la conversacion. */
    private static final class FailingOnFourthCall extends ScriptedChatModel {
        private int calls;

        FailingOnFourthCall(Answer... answers) {
            super(answers);
        }

        @Override
        public synchronized ChatResponse call(Prompt prompt) {
            if (++calls == 4) {
                throw new IllegalStateException("el modelo no responde");
            }
            return super.call(prompt);
        }
    }

    @Test
    void noLogLineOfTheMemoryPathCarriesQuestionAnswerOrHistoryText() {
        MutableClock clock = new MutableClock();
        MemorySettings settings = new MemorySettings();
        BoundedChatMemoryRepository repository = new BoundedChatMemoryRepository(settings, clock);
        ConversationMemory memory = new ConversationMemory(settings, repository);
        ScriptedChatModel model = new FailingOnFourthCall(
                Answer.of("RESPUESTA-SECRETA-1"), Answer.of("RESPUESTA-SECRETA-2"), Answer.of("RESPUESTA-SECRETA-3"),
                Answer.of("RESPUESTA-SECRETA-4"), Answer.of("   "));
        SpringAiModelService service = new SpringAiModelService(ChatClient.builder(model), TestPrompts.registry(), memory);

        // Tres turnos de una sesion.
        service.generateResponse("PREGUNTA-SECRETA-1", "sesion-larga-" + "x".repeat(60), GenerationSettings.none(), null);
        service.generateResponse("PREGUNTA-SECRETA-2", "sesion-larga-" + "x".repeat(60), GenerationSettings.none(), null);
        service.generateResponse("PREGUNTA-SECRETA-3", "sesion-larga-" + "x".repeat(60), GenerationSettings.none(), null);
        // Un fallo del modelo: el historial se restaura.
        assertThrows(IllegalStateException.class, () ->
                service.generateResponse("PREGUNTA-SECRETA-4", "sesion-larga-" + "x".repeat(60), GenerationSettings.none(), null));
        // Una caducidad: tras 31 minutos la sesion empieza vacia.
        clock.advance(Duration.ofMinutes(31));
        service.generateResponse("PREGUNTA-SECRETA-5", "sesion-larga-" + "x".repeat(60), GenerationSettings.none(), null);
        // Una respuesta en blanco (la ultima guionizada): tambien restaura.
        service.generateResponse("PREGUNTA-SECRETA-6", "sesion-larga-" + "x".repeat(60), GenerationSettings.none(), null);

        assertFalse(appender.list.isEmpty(), "el appender debe haber capturado lineas, si no la prueba no prueba nada");
        boolean sawHistoryCount = false;
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage();
            if (event.getThrowableProxy() != null) {
                text += " " + event.getThrowableProxy().getMessage();
            }
            for (String secret : SECRETS) {
                assertFalse(text.contains(secret), "el log contiene '" + secret + "': " + text);
            }
            assertFalse(text.contains("SECRETA"), "el log contiene texto de una pregunta o respuesta: " + text);
            sawHistoryCount |= text.contains("historyMessages=");
        }
        assertTrue(sawHistoryCount, "el log debe poder mostrar el numero de mensajes de historial");
    }

    @Test
    void theSessionIdIsAbbreviatedInTheMemoryLogs() {
        ConversationMemory memory = new ConversationMemory(new MemorySettings(),
                new BoundedChatMemoryRepository(new MemorySettings(), new MutableClock()));
        String longId = "y".repeat(64);

        memory.runTurn(longId, (advisor, history) -> new com.example.chat.ModelResult("ok", null));

        for (ILoggingEvent event : appender.list) {
            assertFalse(event.getFormattedMessage().contains(longId),
                    "el identificador de 64 caracteres no debe escribirse entero: " + event.getFormattedMessage());
        }
    }
}
