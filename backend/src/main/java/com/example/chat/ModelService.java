package com.example.chat;

public interface ModelService {
    /**
     * Generate a response from the chosen model.
     * Implementations should handle communication with Spring AI / model client.
     * {@code settings} carries the optional per-request generation settings; every null
     * field keeps the configured default. {@code templateId} selects the system prompt
     * template; null means the default template.
     */
    ModelResult generateResponse(String question, String sessionId, GenerationSettings settings, String templateId);
}
