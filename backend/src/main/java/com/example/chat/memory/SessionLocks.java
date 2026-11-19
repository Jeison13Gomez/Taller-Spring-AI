package com.example.chat.memory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Un candado por sesion: dos turnos de la misma sesion se ejecutan uno tras otro (el segundo depende
 * de la respuesta del primero) y las demas sesiones no se bloquean. Cada candado cuenta sus usuarios
 * y se retira del mapa cuando nadie lo usa, asi el mapa no crece.
 */
public class SessionLocks {

    private static final class Slot {
        private final ReentrantLock lock = new ReentrantLock();
        private int users;
    }

    /** Candado tomado; se libera con close() (try-with-resources). */
    public final class Handle implements AutoCloseable {
        private final String id;
        private final Slot slot;
        private boolean closed;

        private Handle(String id, Slot slot) {
            this.id = id;
            this.slot = slot;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            slot.lock.unlock();
            synchronized (slots) {
                if (--slot.users == 0) {
                    slots.remove(id);
                }
            }
        }
    }

    private final Map<String, Slot> slots = new HashMap<>();

    /** Espera hasta tener el candado de la sesion. */
    public Handle acquire(String sessionId) {
        Slot slot;
        synchronized (slots) {
            slot = slots.computeIfAbsent(sessionId, id -> new Slot());
            slot.users++;
        }
        slot.lock.lock();
        return new Handle(sessionId, slot);
    }

    /** Numero de candados en uso (para las pruebas). */
    int size() {
        synchronized (slots) {
            return slots.size();
        }
    }
}
