package com.example.chat.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Piezas de la memoria de conversacion. MemorySettings se registra solo, por el
 * @ConfigurationPropertiesScan de Application (igual que PromptVariables).
 */
@Configuration
public class MemoryConfig {

    @Bean
    public Clock memoryClock() {
        return Clock.systemUTC();
    }

    @Bean
    public ChatMemoryRepository chatMemoryRepository(MemorySettings settings, Clock memoryClock) {
        return new BoundedChatMemoryRepository(settings, memoryClock);
    }
}
