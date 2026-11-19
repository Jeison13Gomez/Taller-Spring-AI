package com.example.chat.exception;

import com.example.chat.dto.UsageMetrics;

/**
 * El modelo no cumplio el formato pedido tras agotar los intentos permitidos. Lleva lo necesario para
 * informar del fallo y del coste de los intentos fallidos. No contiene el texto del modelo ni el del usuario.
 */
public class StructuredOutputException extends RuntimeException {

    private final String check;
    private final String failureMessage;
    private final String strategy;
    private final int attempts;
    private final int retries;
    private final long elapsedMs;
    private final UsageMetrics consumed;

    public StructuredOutputException(String check, String failureMessage, String strategy, int attempts,
                                     int retries, long elapsedMs, UsageMetrics consumed) {
        super("El modelo no cumplio el formato: chequeo=" + check + " estrategia=" + strategy + " intentos=" + attempts);
        this.check = check;
        this.failureMessage = failureMessage;
        this.strategy = strategy;
        this.attempts = attempts;
        this.retries = retries;
        this.elapsedMs = elapsedMs;
        this.consumed = consumed;
    }

    public String getCheck() { return check; }
    public String getFailureMessage() { return failureMessage; }
    public String getStrategy() { return strategy; }
    public int getAttempts() { return attempts; }
    public int getRetries() { return retries; }
    public long getElapsedMs() { return elapsedMs; }
    public UsageMetrics getConsumed() { return consumed; }
}
