package com.example.chat.support;

import com.example.chat.memory.BoundedChatMemoryRepository;
import com.example.chat.memory.ConversationMemory;
import com.example.chat.memory.MemorySettings;
import org.springframework.ai.chat.memory.ChatMemoryRepository;

import java.time.Clock;

/** Piezas de apoyo para las pruebas que necesitan la memoria de conversacion real (sin Spring). */
public final class TestMemory {

    private TestMemory() { }

    /** Repositorio acotado con los ajustes por defecto y el reloj del sistema. */
    public static BoundedChatMemoryRepository repository() {
        return new BoundedChatMemoryRepository(new MemorySettings(), Clock.systemUTC());
    }

    /** Memoria con los ajustes por defecto (ventana de 20 mensajes) sobre un repositorio nuevo. */
    public static ConversationMemory conversationMemory() {
        return new ConversationMemory(new MemorySettings(), repository());
    }

    public static ConversationMemory conversationMemory(MemorySettings settings, ChatMemoryRepository repository) {
        return new ConversationMemory(settings, repository);
    }
}
