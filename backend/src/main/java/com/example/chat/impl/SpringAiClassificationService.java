package com.example.chat.impl;

import com.example.chat.ClassificationService;
import com.example.chat.GenerationSettings;
import com.example.chat.classification.Classification;
import com.example.chat.classification.ClassificationChecks;
import com.example.chat.classification.FormatStrategy;
import com.example.chat.dto.ClassifyResponse;
import com.example.chat.dto.FailureInfo;
import com.example.chat.dto.UsageMetrics;
import com.example.chat.exception.StructuredOutputException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.template.NoOpTemplateRenderer;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Clasificacion estructurada sobre Spring AI.
 *
 * Cada llamada al modelo se hace con call().chatResponse() y la respuesta se convierte y se comprueba
 * aqui, en lugar de usar entity(): asi el uso de cada intento (tambien el de un intento fallido) sigue
 * disponible para informar del coste. Las tres estrategias comparten el mismo bucle de intentos.
 * Nunca se escribe en el log el texto clasificado ni la respuesta del modelo.
 */
@Service
public class SpringAiClassificationService implements ClassificationService {

    private static final Logger log = LoggerFactory.getLogger(SpringAiClassificationService.class);

    private static final String SYSTEM_PROMPT_FILE = "prompts/internal/classification-system.st";

    private final ChatClient chatClient;
    private final String systemPrompt;

    public SpringAiClassificationService(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
        this.systemPrompt = loadSystemPrompt();
    }

    @Override
    public ClassifyResponse classify(String text, FormatStrategy strategy, Double temperature) {
        long start = System.nanoTime();
        BeanOutputConverter<Classification> converter = new BeanOutputConverter<>(Classification.class);
        List<AttemptUsage> usages = new ArrayList<>();
        int maxAttempts = strategy.getMaxRetries() + 1;

        Classification classification = null;
        FailureInfo failure = null;
        while (usages.size() < maxAttempts) {
            // En un reintento se anade al mensaje el chequeo que fallo en el intento anterior.
            org.springframework.ai.chat.model.ChatResponse response =
                    callModel(text, converter, strategy, temperature, failure);
            usages.add(AttemptUsage.from(response));

            classification = null;
            String answer = (response == null || response.getResult() == null)
                    ? null
                    : response.getResult().getOutput().getText();
            if (answer == null) {
                failure = ClassificationChecks.fromConversionError(new IllegalArgumentException("empty answer"));
            } else {
                try {
                    classification = converter.convert(answer);
                    failure = ClassificationChecks.check(classification).orElse(null);
                } catch (RuntimeException conversionError) {
                    failure = ClassificationChecks.fromConversionError(conversionError);
                }
            }
            if (failure == null) {
                break;
            }
        }

        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        UsageMetrics usage = UsageAggregator.sum(usages);
        int attempts = usages.size();
        int retries = attempts - 1;

        if (failure != null) {
            log.warn("Clasificacion fallida estrategia={} intentos={} chequeo={} elapsedMs={}",
                    strategy.getId(), attempts, failure.check(), elapsedMs);
            throw new StructuredOutputException(failure.check(), failure.message(), strategy.getId(),
                    attempts, retries, elapsedMs, usage);
        }

        log.info("Clasificacion estrategia={} intentos={} reintentos={} elapsedMs={}",
                strategy.getId(), attempts, retries, elapsedMs);
        ClassifyResponse result = new ClassifyResponse();
        result.setRequestId(UUID.randomUUID().toString());
        result.setStatus("ok");
        result.setResult(classification);
        result.setStrategy(strategy.getId());
        result.setRetried(retries > 0);
        result.setRetries(retries);
        result.setAttempts(attempts);
        result.setElapsedMs(elapsedMs);
        result.setUsage(usage);
        return result;
    }

    /**
     * Una llamada al modelo: instrucciones fijas en el sistema, y el texto en el usuario. INSTRUCTION y
     * VALIDATE_RETRY anaden ademas la instruccion de formato al mensaje; NATIVE_SCHEMA no la anade y
     * envia el esquema a Ollama en las opciones. Si previousFailure no es null, es un reintento.
     */
    private org.springframework.ai.chat.model.ChatResponse callModel(
            String text, BeanOutputConverter<Classification> converter, FormatStrategy strategy,
            Double temperature, FailureInfo previousFailure) {
        // Siempre "\n": el mensaje debe ser el mismo en Windows y en el contenedor Linux. El texto de formato
        // de Spring AI trae los saltos de linea de la plataforma, asi que tambien se normaliza.
        String userMessage = strategy == FormatStrategy.NATIVE_SCHEMA
                ? text
                : text + "\n" + converter.getFormat().replace("\r\n", "\n");
        if (previousFailure != null) {
            userMessage += "\n\nTu respuesta anterior no es valida (" + previousFailure.check() + "): "
                    + previousFailure.message() + ". Responde de nuevo solo con el JSON corregido.";
        }
        // El texto ya viene listo: el renderer de la peticion no hace nada para que no se interprete como plantilla.
        ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                .system(systemPrompt)
                .user(userMessage)
                .templateRenderer(new NoOpTemplateRenderer());
        OllamaChatOptions.Builder options =
                SpringAiModelService.toOptionsBuilder(new GenerationSettings(temperature, null, null, null, null));
        if (strategy == FormatStrategy.NATIVE_SCHEMA) {
            if (options == null) {
                options = OllamaChatOptions.builder();
            }
            options.outputSchema(converter.getJsonSchema());
        }
        if (options != null) {
            request = request.options(options);
        }
        return request.call().chatResponse();
    }

    /** Lee las instrucciones y las renderiza una vez en modo estricto (valida que no haya variables sin valor). */
    private static String loadSystemPrompt() {
        try {
            String raw = new ClassPathResource(SYSTEM_PROMPT_FILE)
                    .getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
            StTemplateRenderer renderer = StTemplateRenderer.builder()
                    .startDelimiterToken('<')
                    .endDelimiterToken('>')
                    .validationMode(ValidationMode.THROW)
                    .build();
            return renderer.apply(raw, Map.of()).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + SYSTEM_PROMPT_FILE, e);
        }
    }
}
