package com.example.chat;

import com.example.chat.dto.ChatRequest;
import com.example.chat.classification.FormatStrategy;
import com.example.chat.dto.ChatResponse;
import com.example.chat.dto.ClassifyRequest;
import com.example.chat.dto.ClassifyResponse;
import com.example.chat.dto.UsageMetrics;
import com.example.chat.dto.ValidationError;
import com.example.chat.prompt.PromptTemplateRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@CrossOrigin(origins = "http://localhost:4200")
@RequestMapping("/api/v1")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final List<String> FIELD_ORDER =
            List.of("sessionId", "question", "templateId", "temperature", "topP", "topK", "numPredict", "seed");
    private static final List<String> CLASSIFY_FIELD_ORDER = List.of("text", "strategy", "temperature");
    private final ModelService modelService;
    private final PromptTemplateRegistry templates;
    private final ClassificationService classificationService;

    public ChatController(ModelService modelService, PromptTemplateRegistry templates,
                          ClassificationService classificationService) {
        this.modelService = modelService;
        this.templates = templates;
        this.classificationService = classificationService;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@Valid @RequestBody ChatRequest req, BindingResult bindingResult) {
        List<ValidationError> errors = toValidationErrors(bindingResult, req);
        if (!errors.isEmpty()) {
            log.warn("Validation failed for chat request: fields={}", errors.stream().map(ValidationError::field).toList());
            ChatResponse bad = new ChatResponse(UUID.randomUUID().toString(), "", 0, "error", null);
            bad.setErrors(errors);
            return ResponseEntity.badRequest().body(bad);
        }

        log.info("Received chat request sessionId={} question={}", req.getSessionId(), req.getQuestion());
        GenerationSettings settings = req.toGenerationSettings();
        // Solo valores numericos: sin texto de la pregunta.
        log.info("Chat settings sessionId={} temperature={} topP={} topK={} numPredict={} seed={}",
                req.getSessionId(), settings.temperature(), settings.topP(), settings.topK(),
                settings.numPredict(), settings.seed());
        // Solo el identificador (recortado): nunca el texto de la plantilla.
        log.info("Chat template sessionId={} templateId={}", req.getSessionId(),
                PromptTemplateRegistry.abbreviate(isBlankId(req.getTemplateId()) ? PromptTemplateRegistry.DEFAULT_ID : req.getTemplateId()));
        long start = Instant.now().toEpochMilli();
        ModelResult result = modelService.generateResponse(req.getQuestion(), req.getSessionId(), settings, req.getTemplateId());
        long elapsed = Instant.now().toEpochMilli() - start;

        String answer = result == null ? null : result.answer();
        String status = (answer == null || answer.isBlank()) ? "error" : "ok";
        // Las respuestas fallidas nunca llevan metricas (FR-010).
        UsageMetrics usage = status.equals("ok") ? result.usage() : null;
        ChatResponse resp = new ChatResponse(UUID.randomUUID().toString(), answer == null ? "" : answer, elapsed, status, usage);
        if (status.equals("ok")) {
            resp.setHistoryMessages(result.historyMessages());
            resp.setSources(result.sources());
        }

        if (usage != null) {
            // Solo valores numericos/tecnicos: sin texto de la pregunta ni de la respuesta.
            log.info("Chat usage sessionId={} promptTokens={} completionTokens={} totalTokens={} model={} finishReason={} tokensPerSecond={}",
                    req.getSessionId(), usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens(),
                    usage.getModel(), usage.getFinishReason(), usage.getTokensPerSecond());
        }
        log.info("Chat response for sessionId={} elapsedMs={} status={} response={}", req.getSessionId(), elapsed, status, resp);
        return status.equals("ok") ? ResponseEntity.ok(resp) : ResponseEntity.status(502).body(resp);
    }

    private static boolean isBlankId(String templateId) {
        return templateId == null || templateId.isEmpty();
    }

    /**
     * Un error por campo (gana el primero), en el orden en que los campos aparecen en la peticion.
     * Incluye el identificador de plantilla desconocido junto a los errores de validacion del cuerpo.
     */
    private List<ValidationError> toValidationErrors(BindingResult bindingResult, ChatRequest req) {
        Map<String, String> byField = fieldErrors(bindingResult);
        String templateId = req.getTemplateId();
        if (!isBlankId(templateId) && !templates.contains(templateId)) {
            byField.putIfAbsent("templateId", "unknown template '" + PromptTemplateRegistry.abbreviate(templateId)
                    + "'; available: " + String.join(", ", templates.ids()));
        }
        return ordered(byField, FIELD_ORDER);
    }

    private static Map<String, String> fieldErrors(BindingResult bindingResult) {
        Map<String, String> byField = new LinkedHashMap<>();
        for (FieldError fieldError : bindingResult.getFieldErrors()) {
            byField.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return byField;
    }

    private static List<ValidationError> ordered(Map<String, String> byField, List<String> order) {
        return byField.entrySet().stream()
                .map(e -> new ValidationError(e.getKey(), e.getValue()))
                .sorted(Comparator.comparingInt(e -> {
                    int index = order.indexOf(e.field());
                    return index < 0 ? order.size() : index;
                }))
                .toList();
    }

    /**
     * Clasifica un texto en categoria, confianza y justificacion con la estrategia pedida
     * (por defecto instruction).
     */
    @PostMapping("/classify")
    public ResponseEntity<ClassifyResponse> classify(@Valid @RequestBody ClassifyRequest req, BindingResult bindingResult) {
        Map<String, String> byField = fieldErrors(bindingResult);
        String strategyId = req.getStrategy();
        FormatStrategy strategy = FormatStrategy.INSTRUCTION;
        if (strategyId != null && !strategyId.isEmpty()) {
            Optional<FormatStrategy> found = FormatStrategy.fromId(strategyId);
            if (found.isPresent()) {
                strategy = found.get();
            } else {
                byField.putIfAbsent("strategy", "unknown strategy '" + PromptTemplateRegistry.abbreviate(strategyId)
                        + "'; available: " + String.join(", ", FormatStrategy.ids()));
            }
        }
        List<ValidationError> errors = ordered(byField, CLASSIFY_FIELD_ORDER);
        if (!errors.isEmpty()) {
            log.warn("Validation failed for classify request: fields={}", errors.stream().map(ValidationError::field).toList());
            ClassifyResponse bad = new ClassifyResponse();
            bad.setRequestId(UUID.randomUUID().toString());
            bad.setStatus("error");
            bad.setErrors(errors);
            return ResponseEntity.badRequest().body(bad);
        }

        // Solo longitud, estrategia y temperatura: nunca el texto clasificado.
        log.info("Classify request textLength={} strategy={} temperature={}",
                req.getText().length(), strategy.getId(), req.getTemperature());
        return ResponseEntity.ok(classificationService.classify(req.getText(), strategy, req.getTemperature()));
    }
}
