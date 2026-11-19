package com.example.chat.memory;

import com.example.chat.ModelResult;
import com.example.chat.prompt.PromptTemplateRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Memoria de conversacion: ventana de mensajes de Spring AI mas lo que el asesor estandar no hace.
 * Cada turno de una sesion se ejecuta con el candado de esa sesion y, si falla o devuelve una
 * respuesta en blanco, el historial vuelve a como estaba (el asesor guarda la pregunta antes de llamar
 * al modelo, asi que sin esto una pregunta fallida se quedaria sin respuesta). Ver research R2 y R4.
 *
 * Los mensajes de sistema nunca se guardan: la plantilla de cada turno llega en la propia peticion.
 * Nunca se escribe en el log el texto de una pregunta, de una respuesta ni del historial.
 */
@Component
public class ConversationMemory {

    private static final Logger log = LoggerFactory.getLogger(ConversationMemory.class);

    /** Lo que se hace dentro del turno: construir la peticion con el asesor y llamar al modelo. */
    @FunctionalInterface
    public interface TurnAction {
        ModelResult run(Advisor memoryAdvisor, int historyMessages);
    }

    private final ChatMemoryRepository repository;
    private final SessionLocks locks;
    private final Advisor advisor;

    @Autowired
    public ConversationMemory(MemorySettings settings, ChatMemoryRepository repository) {
        this(settings, repository, new SessionLocks());
    }

    /** Las pruebas pasan los candados para comprobar que no queda ninguno al terminar. */
    ConversationMemory(MemorySettings settings, ChatMemoryRepository repository, SessionLocks locks) {
        this.repository = repository;
        this.locks = locks;
        ChatMemory window = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(settings.getMaxMessages())
                .build();
        this.advisor = MessageChatMemoryAdvisor.builder(window)
                .order(Advisor.DEFAULT_CHAT_MEMORY_PRECEDENCE_ORDER)
                .build();
    }

    /**
     * Ejecuta un turno de la sesion. El resultado lleva en historyMessages cuantos mensajes de
     * historial habia al empezar el turno, que son exactamente los que el asesor antepone.
     */
    public ModelResult runTurn(String sessionId, TurnAction action) {
        try (SessionLocks.Handle ignored = locks.acquire(sessionId)) {
            List<Message> snapshot = new ArrayList<>(repository.findByConversationId(sessionId));
            int history = snapshot.size();
            log.debug("Turno de sesion. sessionId={} historyMessages={}", PromptTemplateRegistry.abbreviate(sessionId), history);

            ModelResult result;
            try {
                result = action.run(advisor, history);
            } catch (RuntimeException | Error e) {
                restore(sessionId, snapshot);
                log.debug("Historial restaurado tras un fallo del turno. sessionId={}", PromptTemplateRegistry.abbreviate(sessionId));
                throw e;
            }
            if (result == null || result.answer() == null || result.answer().isBlank()) {
                restore(sessionId, snapshot);
                log.debug("Historial restaurado tras una respuesta en blanco. sessionId={}", PromptTemplateRegistry.abbreviate(sessionId));
                return new ModelResult(result == null ? null : result.answer(), result == null ? null : result.usage(), history);
            }
            return new ModelResult(result.answer(), result.usage(), history, result.sources());
        }
    }

    private void restore(String sessionId, List<Message> snapshot) {
        if (snapshot.isEmpty()) {
            repository.deleteByConversationId(sessionId);
        } else {
            repository.saveAll(sessionId, snapshot);
        }
    }
}
