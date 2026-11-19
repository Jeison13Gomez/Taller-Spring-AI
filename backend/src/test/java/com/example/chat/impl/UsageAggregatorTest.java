package com.example.chat.impl;

import com.example.chat.dto.UsageMetrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class UsageAggregatorTest {

    private static AttemptUsage attempt(Integer prompt, Integer completion, String model, String finish, Duration eval) {
        return new AttemptUsage(prompt, completion, model, finish, eval);
    }

    @Test
    void oneAttemptGivesTheSameValuesAsTheSingleResponseMetrics() {
        UsageMetrics usage = UsageAggregator.sum(List.of(
                attempt(32, 118, "llama3.2:3b", "stop", Duration.ofSeconds(4))));

        assertEquals(32, usage.getPromptTokens());
        assertEquals(118, usage.getCompletionTokens());
        assertEquals(150, usage.getTotalTokens());
        assertEquals("llama3.2:3b", usage.getModel());
        assertEquals("stop", usage.getFinishReason());
        assertEquals(29.5, usage.getTokensPerSecond());
    }

    @Test
    void severalAttemptsSumTokensAndGenerationTime() {
        UsageMetrics usage = UsageAggregator.sum(List.of(
                attempt(100, 40, "m", "stop", Duration.ofSeconds(2)),
                attempt(120, 60, "m", "stop", Duration.ofSeconds(3))));

        assertEquals(220, usage.getPromptTokens());
        assertEquals(100, usage.getCompletionTokens());
        assertEquals(320, usage.getTotalTokens());
        assertEquals(20.0, usage.getTokensPerSecond());   // 100 tokens / 5 s
    }

    @Test
    void tokensPerSecondIsRoundedToTwoDecimals() {
        UsageMetrics usage = UsageAggregator.sum(List.of(
                attempt(10, 100, "m", "stop", Duration.ofSeconds(3))));

        assertEquals(33.33, usage.getTokensPerSecond());
    }

    @Test
    void aTokenFigureMissingInAnyAttemptIsNotAvailableNeverAPartialSum() {
        UsageMetrics usage = UsageAggregator.sum(List.of(
                attempt(100, 40, "m", "stop", Duration.ofSeconds(2)),
                attempt(null, 60, "m", "stop", Duration.ofSeconds(3))));

        assertNull(usage.getPromptTokens());
        assertEquals(100, usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());
    }

    @Test
    void aMissingCompletionCountMakesCompletionTotalAndSpeedNotAvailable() {
        UsageMetrics usage = UsageAggregator.sum(List.of(
                attempt(100, 40, "m", "stop", Duration.ofSeconds(2)),
                attempt(120, null, "m", "stop", Duration.ofSeconds(3))));

        assertEquals(220, usage.getPromptTokens());
        assertNull(usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());
        assertNull(usage.getTokensPerSecond());
    }

    @Test
    void aMissingOrZeroGenerationTimeInAnyAttemptMakesSpeedNotAvailable() {
        assertNull(UsageAggregator.sum(List.of(
                attempt(100, 40, "m", "stop", Duration.ofSeconds(2)),
                attempt(120, 60, "m", "stop", null))).getTokensPerSecond());
        assertNull(UsageAggregator.sum(List.of(
                attempt(100, 40, "m", "stop", Duration.ZERO))).getTokensPerSecond());
    }

    @Test
    void modelAndFinishReasonComeFromTheLastAttempt() {
        UsageMetrics usage = UsageAggregator.sum(List.of(
                attempt(1, 1, "primero", "length", Duration.ofSeconds(1)),
                attempt(1, 1, "ultimo", "stop", Duration.ofSeconds(1))));

        assertEquals("ultimo", usage.getModel());
        assertEquals("stop", usage.getFinishReason());
    }

    @Test
    void blankModelOrFinishReasonIsNotAvailable() {
        UsageMetrics usage = UsageAggregator.sum(List.of(attempt(1, 1, " ", "", Duration.ofSeconds(1))));

        assertNull(usage.getModel());
        assertNull(usage.getFinishReason());
    }

    @Test
    void noAttemptsGivesAnAllNullMetrics() {
        UsageMetrics usage = UsageAggregator.sum(List.of());

        assertNull(usage.getPromptTokens());
        assertNull(usage.getCompletionTokens());
        assertNull(usage.getTotalTokens());
        assertNull(usage.getModel());
        assertNull(usage.getFinishReason());
        assertNull(usage.getTokensPerSecond());
    }
}
