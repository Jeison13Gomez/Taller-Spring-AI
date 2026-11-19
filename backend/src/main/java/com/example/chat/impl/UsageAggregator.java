package com.example.chat.impl;

import com.example.chat.dto.UsageMetrics;

import java.util.List;
import java.util.function.Function;

/**
 * Suma el uso de varios intentos. Un dato solo se informa si todos los intentos lo aportan:
 * nunca una suma parcial. Con un unico intento da los mismos valores que su respuesta.
 */
final class UsageAggregator {

    private UsageAggregator() { }

    static UsageMetrics sum(List<AttemptUsage> attempts) {
        Integer prompt = sumOrNull(attempts, AttemptUsage::promptTokens);
        Integer completion = sumOrNull(attempts, AttemptUsage::completionTokens);
        // El total solo se informa si se conocen ambas partes.
        Integer total = (prompt != null && completion != null) ? prompt + completion : null;

        // Tokens por segundo: tokens generados entre la suma de los tiempos de generacion.
        Double tokensPerSecond = null;
        if (completion != null && !attempts.isEmpty() && attempts.stream().allMatch(a -> a.evalDuration() != null)) {
            long nanos = attempts.stream().mapToLong(a -> a.evalDuration().toNanos()).sum();
            if (nanos > 0) {
                double seconds = nanos / 1_000_000_000.0;
                tokensPerSecond = Math.round(completion / seconds * 100.0) / 100.0;
            }
        }

        // Modelo y motivo de terminacion: los del ultimo intento.
        AttemptUsage last = attempts.isEmpty() ? null : attempts.get(attempts.size() - 1);
        String model = last == null ? null : AttemptUsage.blankToNull(last.model());
        String finishReason = last == null ? null : AttemptUsage.blankToNull(last.finishReason());

        return new UsageMetrics(prompt, completion, total, model, finishReason, tokensPerSecond);
    }

    private static Integer sumOrNull(List<AttemptUsage> attempts, Function<AttemptUsage, Integer> field) {
        if (attempts.isEmpty()) {
            return null;
        }
        int sum = 0;
        for (AttemptUsage attempt : attempts) {
            Integer value = field.apply(attempt);
            if (value == null) {
                return null;
            }
            sum += value;
        }
        return sum;
    }
}
