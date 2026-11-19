package com.example.chat.exception;

import com.example.chat.dto.ChatResponse;
import com.example.chat.dto.ClassifyResponse;
import com.example.chat.dto.FailureInfo;
import com.example.chat.dto.ValidationError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final Set<String> NUMERIC_FIELDS = Set.of("temperature", "topP", "topK", "numPredict", "seed");

    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<ChatResponse> handleHttpClient(HttpClientErrorException ex) {
        log.error("HTTP client error when calling model", ex);
        ChatResponse resp = new ChatResponse(null, "", 0, "error", null);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
    }

    /** Cuerpo ilegible (JSON mal formado o un valor de tipo incorrecto): 400 en lugar del 500 generico. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ChatResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        ValidationError error = describe(ex);
        // Sin el cuerpo de la peticion: puede contener la pregunta del usuario.
        log.warn("Unreadable chat request body: field={}", error.field());
        ChatResponse resp = new ChatResponse(UUID.randomUUID().toString(), "", 0, "error", null);
        resp.setErrors(List.of(error));
        return ResponseEntity.badRequest().body(resp);
    }

    /**
     * El modelo no cumplio el formato pedido: 502 (el servicio de arriba respondio algo inutilizable) con el
     * chequeo que fallo, la estrategia, los intentos y el coste de los intentos fallidos. Spring elige este
     * manejador antes que el de RuntimeException porque es mas especifico.
     */
    @ExceptionHandler(StructuredOutputException.class)
    public ResponseEntity<ClassifyResponse> handleStructuredOutput(StructuredOutputException ex) {
        // Sin el texto clasificado ni la respuesta del modelo.
        log.warn("Salida estructurada invalida: estrategia={} intentos={} chequeo={}",
                ex.getStrategy(), ex.getAttempts(), ex.getCheck());
        ClassifyResponse resp = new ClassifyResponse();
        resp.setRequestId(UUID.randomUUID().toString());
        resp.setStatus("error");
        resp.setStrategy(ex.getStrategy());
        resp.setRetried(ex.getRetries() > 0);
        resp.setRetries(ex.getRetries());
        resp.setAttempts(ex.getAttempts());
        resp.setElapsedMs(ex.getElapsedMs());
        resp.setFailure(new FailureInfo(ex.getCheck(), ex.getFailureMessage()));
        resp.setConsumed(ex.getConsumed());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(resp);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ChatResponse> handleRuntime(RuntimeException ex) {
        log.error("Runtime exception in chat service", ex);
        ChatResponse resp = new ChatResponse(null, "", 0, "error", null);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(resp);
    }

    /** Jackson solo informa del primer valor ilegible; se nombra ese campo, o "body" si no hay uno. */
    private static ValidationError describe(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof DatabindException databind && !databind.getPath().isEmpty()) {
                List<JacksonException.Reference> path = databind.getPath();
                String field = path.get(path.size() - 1).getPropertyName();
                if (field != null) {
                    return NUMERIC_FIELDS.contains(field)
                            ? new ValidationError(field, field + " must be a number")
                            : new ValidationError(field, field + " has an invalid value");
                }
            }
        }
        return new ValidationError("body", "request body is not valid JSON");
    }
}
