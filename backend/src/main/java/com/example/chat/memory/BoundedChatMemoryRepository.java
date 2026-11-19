package com.example.chat.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Repositorio de conversaciones solo en RAM y acotado: olvida las sesiones sin uso durante
 * app.memory.idle-timeout y, al superar app.memory.max-sessions, descarta la menos usada.
 * El almacen que trae Spring AI no tiene ninguna de las dos politicas.
 *
 * Un unico candado protege el mapa; las operaciones son breves (nada lento ocurre dentro).
 * Las listas se copian al guardar y al leer: ningun llamante puede modificar el historial almacenado.
 * findConversationIds existe porque lo exige la interfaz; ningun endpoint lo expone.
 */
public class BoundedChatMemoryRepository implements ChatMemoryRepository {

    private record Entry(List<Message> messages, Instant lastUsed) {
    }

    private final MemorySettings settings;
    private final Clock clock;
    // Orden de acceso: el primero es siempre la sesion usada hace mas tiempo.
    private final Map<String, Entry> sessions = new LinkedHashMap<>(16, 0.75f, true);

    public BoundedChatMemoryRepository(MemorySettings settings, Clock clock) {
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public synchronized List<String> findConversationIds() {
        dropExpired();
        return new ArrayList<>(sessions.keySet());
    }

    @Override
    public synchronized List<Message> findByConversationId(String conversationId) {
        dropExpired();
        Entry entry = sessions.get(conversationId);
        if (entry == null) {
            return new ArrayList<>();
        }
        sessions.put(conversationId, new Entry(entry.messages(), clock.instant()));
        return new ArrayList<>(entry.messages());
    }

    @Override
    public synchronized void saveAll(String conversationId, List<Message> messages) {
        dropExpired();
        boolean isNew = !sessions.containsKey(conversationId);
        sessions.put(conversationId, new Entry(new ArrayList<>(messages), clock.instant()));
        if (isNew) {
            Iterator<String> leastRecentlyUsed = sessions.keySet().iterator();
            while (sessions.size() > settings.getMaxSessions() && leastRecentlyUsed.hasNext()) {
                leastRecentlyUsed.next();
                leastRecentlyUsed.remove();
            }
        }
    }

    @Override
    public synchronized void deleteByConversationId(String conversationId) {
        sessions.remove(conversationId);
    }

    /** Quita las sesiones sin uso durante mas del tiempo configurado (las mas antiguas van primero). */
    private void dropExpired() {
        Instant limit = clock.instant().minus(settings.getIdleTimeout());
        Iterator<Entry> iterator = sessions.values().iterator();
        while (iterator.hasNext() && iterator.next().lastUsed().isBefore(limit)) {
            iterator.remove();
        }
    }
}
