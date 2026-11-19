package com.example.chat.dto;

/**
 * Metricas de uso de una respuesta. Cualquier campo en null significa "no disponible":
 * nunca se sustituye por 0 ni se estima.
 */
public class UsageMetrics {
    private final Integer promptTokens;
    private final Integer completionTokens;
    private final Integer totalTokens;
    private final String model;
    private final String finishReason;
    private final Double tokensPerSecond;

    public UsageMetrics(Integer promptTokens, Integer completionTokens, Integer totalTokens,
                        String model, String finishReason, Double tokensPerSecond) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.totalTokens = totalTokens;
        this.model = model;
        this.finishReason = finishReason;
        this.tokensPerSecond = tokensPerSecond;
    }

    public Integer getPromptTokens() { return promptTokens; }
    public Integer getCompletionTokens() { return completionTokens; }
    public Integer getTotalTokens() { return totalTokens; }
    public String getModel() { return model; }
    public String getFinishReason() { return finishReason; }
    public Double getTokensPerSecond() { return tokensPerSecond; }
}
