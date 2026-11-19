package com.example.chat.impl;

import com.example.chat.dto.UsageMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;

import java.time.Duration;

import static com.example.chat.impl.SpringAiModelServiceUsageTest.response;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Valores que el modelo no aporta: deben quedar en null, nunca en 0. */
class UsageMetricsMissingValuesTest {

    @Test
    void nullResponseGivesAllNull() {
        UsageMetrics usage = SpringAiModelService.toUsageMetrics(null);

        assertNull(usage.getPromptTokens());
        assertNull(usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());
        assertNull(usage.getModel());
        assertNull(usage.getFinishReason());
        assertNull(usage.getTokensPerSecond());
    }

    @Test
    void absentUsageGivesNullTokensButKeepsModel() {
        // Ollama convierte un conteo ausente en 0: se trata como "no disponible".
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(0, 0)).model("llama3.2:3b").build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, metadata));

        assertNull(usage.getPromptTokens());
        assertNull(usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());
        assertNull(usage.getTokensPerSecond());
        assertEquals("llama3.2:3b", usage.getModel());
    }

    @Test
    void missingFinishReasonIsNull() {
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(5, 5)).build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, metadata));

        assertNull(usage.getFinishReason());
    }

    @Test
    void zeroCompletionTokensGivesNullTokensPerSecondNotZero() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(5, 0))
                .keyValue("eval-duration", Duration.ofSeconds(2))
                .build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, metadata));

        assertNull(usage.getTokensPerSecond());
    }

    @Test
    void missingOrZeroGenerationDurationGivesNullTokensPerSecond() {
        var without = ChatResponseMetadata.builder().usage(new DefaultUsage(5, 50)).build();
        var zero = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(5, 50))
                .keyValue("eval-duration", Duration.ZERO)
                .build();

        assertNull(SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, without)).getTokensPerSecond());
        assertNull(SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, zero)).getTokensPerSecond());
    }

    @Test
    void totalIsNullWhenOnlyOnePartIsKnown() {
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(0, 40)).build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, metadata));

        assertNull(usage.getPromptTokens());
        assertEquals(40, usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());
    }

    @Test
    void lengthFinishReasonIsPassedThrough() {
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(5, 1024)).build();
        var generation = ChatGenerationMetadata.builder().finishReason("length").build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(generation, metadata));

        assertEquals("length", usage.getFinishReason());
    }
}
