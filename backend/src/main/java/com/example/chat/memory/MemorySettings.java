package com.example.chat.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Ajustes de la memoria de conversacion (app.memory.*). La ventana cuenta mensajes, no turnos: una
 * pregunta y su respuesta son 2, por eso el minimo es 2. Un valor invalido hace fallar el arranque
 * con un mensaje que nombra la propiedad.
 */
@ConfigurationProperties(prefix = "app.memory")
public class MemorySettings {

    private int maxMessages = 20;
    private Duration idleTimeout = Duration.ofMinutes(30);
    private int maxSessions = 100;

    public int getMaxMessages() { return maxMessages; }

    public void setMaxMessages(int maxMessages) {
        if (maxMessages < 2) {
            throw new IllegalArgumentException("app.memory.max-messages must be at least 2 (a question and its answer), but was " + maxMessages);
        }
        this.maxMessages = maxMessages;
    }

    public Duration getIdleTimeout() { return idleTimeout; }

    public void setIdleTimeout(Duration idleTimeout) {
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()) {
            throw new IllegalArgumentException("app.memory.idle-timeout must be greater than zero, but was " + idleTimeout);
        }
        this.idleTimeout = idleTimeout;
    }

    public int getMaxSessions() { return maxSessions; }

    public void setMaxSessions(int maxSessions) {
        if (maxSessions < 1) {
            throw new IllegalArgumentException("app.memory.max-sessions must be at least 1, but was " + maxSessions);
        }
        this.maxSessions = maxSessions;
    }
}
