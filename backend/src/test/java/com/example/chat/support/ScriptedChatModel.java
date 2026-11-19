package com.example.chat.support;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * ChatModel de prueba: devuelve respuestas guionizadas (con sus tokens y su tiempo de generacion,
 * como las de Ollama) y guarda cada Prompt que recibe. Cuando se acaban las respuestas, repite la ultima.
 * Seguro con varios hilos: las pruebas de memoria llaman al modelo desde varias sesiones a la vez.
 */
public class ScriptedChatModel implements ChatModel {

    /** Una respuesta guionizada del modelo. */
    public record Answer(String text, int promptTokens, int completionTokens, String model, Duration evalDuration) {

        public static Answer of(String text) {
            return new Answer(text, 32, 118, "llama3.2:3b", Duration.ofSeconds(4));
        }
    }

    private final Deque<Answer> pending = new ArrayDeque<>();
    private Answer last;
    private final List<Prompt> prompts = Collections.synchronizedList(new ArrayList<>());

    public ScriptedChatModel(Answer... answers) {
        pending.addAll(List.of(answers));
    }

    public static ScriptedChatModel answering(String text) {
        return new ScriptedChatModel(Answer.of(text));
    }

    /** Prompts recibidos, en orden. */
    public List<Prompt> prompts() {
        return prompts;
    }

    public synchronized Prompt lastPrompt() {
        return prompts.get(prompts.size() - 1);
    }

    /** Como OllamaChatModel: sus opciones por defecto son de Ollama, para que el esquema (format) no se pierda al fusionar. */
    @Override
    public ChatOptions getOptions() {
        return OllamaChatOptions.builder().build();
    }

    @Override
    public synchronized ChatResponse call(Prompt prompt) {
        prompts.add(prompt);
        if (!pending.isEmpty()) {
            last = pending.poll();
        }
        if (last == null) {
            throw new IllegalStateException("ScriptedChatModel sin respuestas guionizadas");
        }
        Generation generation = new Generation(
                AssistantMessage.builder().content(last.text()).build(),
                ChatGenerationMetadata.builder().finishReason("stop").build());
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(last.promptTokens(), last.completionTokens()))
                .model(last.model())
                .keyValue("eval-duration", last.evalDuration())
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }
}
