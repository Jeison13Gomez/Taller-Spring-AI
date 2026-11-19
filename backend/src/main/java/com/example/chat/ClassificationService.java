package com.example.chat;

import com.example.chat.classification.FormatStrategy;
import com.example.chat.dto.ClassifyResponse;

public interface ClassificationService {
    /**
     * Classify a text into category, confidence and justification using the given format strategy.
     * {@code temperature} null keeps the configured default; 0.0 is a real value.
     *
     * @throws com.example.chat.exception.StructuredOutputException when the model never meets the format
     */
    ClassifyResponse classify(String text, FormatStrategy strategy, Double temperature);
}
