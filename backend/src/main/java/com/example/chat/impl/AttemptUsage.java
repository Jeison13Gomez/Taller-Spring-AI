package com.example.chat.impl;

import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;

/**
 * Uso de una llamada al modelo. Todo valor que el modelo no aporte es null ("no disponible");
 * nunca se estima ni se sustituye por 0. OllamaChatModel convierte un conteo ausente en 0, por eso
 * un conteo <= 0 se trata como no disponible, y lo mismo un tiempo de generacion <= 0.
 */
record AttemptUsage(Integer promptTokens, Integer completionTokens, String model, String finishReason,
                    Duration evalDuration) {

    /** Clave con la que OllamaChatModel guarda el tiempo de generacion (Duration). */
    private static final String METADATA_EVAL_DURATION = "eval-duration";

    /** Extrae el uso de la respuesta de Spring AI (nombre completo: el DTO ChatResponse comparte nombre simple). */
    static AttemptUsage from(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null) {
            return new AttemptUsage(null, null, null, null, null);
        }

        Integer prompt = null;
        Integer completion = null;
        String model = null;
        Duration evalDuration = null;

        ChatResponseMetadata metadata = response.getMetadata();
        if (metadata != null) {
            Usage usage = metadata.getUsage();
            if (usage != null) {
                prompt = positiveOrNull(usage.getPromptTokens());
                completion = positiveOrNull(usage.getCompletionTokens());
            }
            model = blankToNull(metadata.getModel());

            // Solo el tiempo de generacion de Ollama (eval-duration), no el tiempo total.
            Object duration = metadata.get(METADATA_EVAL_DURATION);
            if (duration instanceof Duration d && d.toNanos() > 0) {
                evalDuration = d;
            }
        }

        String finishReason = null;
        Generation result = response.getResult();
        if (result != null && result.getMetadata() != null) {
            finishReason = blankToNull(result.getMetadata().getFinishReason());
        }

        return new AttemptUsage(prompt, completion, model, finishReason, evalDuration);
    }

    static Integer positiveOrNull(Integer value) {
        return (value != null && value > 0) ? value : null;
    }

    static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
