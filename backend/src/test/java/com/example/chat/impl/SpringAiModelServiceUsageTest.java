package com.example.chat.impl;

import com.example.chat.dto.UsageMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.Generation;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpringAiModelServiceUsageTest {

    static org.springframework.ai.chat.model.ChatResponse response(
            ChatGenerationMetadata generationMetadata, ChatResponseMetadata metadata) {
        Generation generation = new Generation(AssistantMessage.builder().content("hola").build(), generationMetadata);
        return new org.springframework.ai.chat.model.ChatResponse(List.of(generation), metadata);
    }

    @Test
    void mapsAllSixValuesFromProviderMetadata() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(32, 118))
                .model("llama3.2:3b")
                .keyValue("eval-duration", Duration.ofSeconds(4))
                .build();
        var generationMetadata = ChatGenerationMetadata.builder().finishReason("stop").build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(generationMetadata, metadata));

        assertEquals(32, usage.getPromptTokens());
        assertEquals(118, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals("llama3.2:3b", usage.getModel());
        assertEquals("stop", usage.getFinishReason());
        assertEquals(29.5, usage.getTokensPerSecond());   // 118 tokens / 4 s
    }

    @Test
    void tokensPerSecondIsRoundedToTwoDecimals() {
        var metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(10, 100))
                .keyValue("eval-duration", Duration.ofSeconds(3))
                .build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, metadata));

        assertEquals(33.33, usage.getTokensPerSecond());  // 100 / 3 = 33.333...
    }

    @Test
    void totalIsAlwaysPromptPlusCompletion() {
        var metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(7, 11)).build();

        UsageMetrics usage = SpringAiModelService.toUsageMetrics(response(ChatGenerationMetadata.NULL, metadata));

        assertEquals(usage.getPromptTokens() + usage.getCompletionTokens(), usage.getTotalTokens());
    }
}
