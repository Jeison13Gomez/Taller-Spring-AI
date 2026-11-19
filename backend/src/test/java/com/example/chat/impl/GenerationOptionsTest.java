package com.example.chat.impl;

import com.example.chat.GenerationSettings;
import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GenerationOptionsTest {

    @Test
    void allFiveValuesAreMappedIncludingZeros() {
        OllamaChatOptions options = SpringAiModelService
                .toOptionsBuilder(new GenerationSettings(0.0, 0.0, 0, 64, 42))
                .build();

        assertEquals(0.0, options.getTemperature());
        assertEquals(0.0, options.getTopP());
        assertEquals(0, options.getTopK());
        assertEquals(64, options.getNumPredict());
        assertEquals(42, options.getSeed());
    }

    @Test
    void onlySentValuesAreSet() {
        OllamaChatOptions options = SpringAiModelService
                .toOptionsBuilder(new GenerationSettings(null, null, 40, null, null))
                .build();

        assertEquals(40, options.getTopK());
        assertNull(options.getTemperature());
        assertNull(options.getTopP());
        assertNull(options.getNumPredict());
        assertNull(options.getSeed());
    }

    @Test
    void unsentValuesKeepTheConfiguredDefaults() {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .temperature(0.7)
                .numPredict(1024)
                .combineWith(SpringAiModelService.toOptionsBuilder(new GenerationSettings(null, null, 40, null, null)))
                .build();

        assertEquals(0.7, options.getTemperature());
        assertEquals(1024, options.getNumPredict());
        assertEquals(40, options.getTopK());
    }

    @Test
    void sentZeroOverridesTheDefault() {
        OllamaChatOptions options = OllamaChatOptions.builder()
                .temperature(0.7)
                .combineWith(SpringAiModelService.toOptionsBuilder(new GenerationSettings(0.0, null, null, null, null)))
                .build();

        assertEquals(0.0, options.getTemperature());
    }

    @Test
    void noSettingsMeansNoBuilder() {
        assertNull(SpringAiModelService.toOptionsBuilder(GenerationSettings.none()));
        assertNull(SpringAiModelService.toOptionsBuilder(null));
    }
}
