package com.example.chat.classification;

import com.example.chat.dto.FailureInfo;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

import java.util.List;
import java.util.Optional;

/**
 * Comprobaciones del resultado de una clasificacion que el conversor de Spring AI no hace: campos que
 * faltan, confianza fuera de 0.0-1.0 y justificacion vacia. Tambien traduce los errores de conversion
 * al chequeo que fallo: "json", "category", "confidence" o "justification".
 */
public final class ClassificationChecks {

    static final String JSON = "json";
    static final String CATEGORY = "category";
    static final String CONFIDENCE = "confidence";
    static final String JUSTIFICATION = "justification";

    private static final String JSON_MESSAGE = "the answer is not valid JSON";
    private static final String CATEGORY_MESSAGE = "category must be one of consulta, queja, incidencia, otro";
    private static final String CONFIDENCE_MESSAGE = "confidence must be a number between 0.0 and 1.0";
    private static final String JUSTIFICATION_MESSAGE = "justification must be a non-empty text";

    private ClassificationChecks() { }

    /** Primer chequeo que falla, en el orden category, confidence, justification; vacio si todo esta bien. */
    public static Optional<FailureInfo> check(Classification classification) {
        if (classification == null) {
            return Optional.of(new FailureInfo(JSON, JSON_MESSAGE));
        }
        if (classification.category() == null) {
            return Optional.of(new FailureInfo(CATEGORY, CATEGORY_MESSAGE));
        }
        Double confidence = classification.confidence();
        if (confidence == null || confidence.isNaN() || confidence < 0.0 || confidence > 1.0) {
            return Optional.of(new FailureInfo(CONFIDENCE, CONFIDENCE_MESSAGE));
        }
        String justification = classification.justification();
        if (justification == null || justification.isBlank()) {
            return Optional.of(new FailureInfo(JUSTIFICATION, JUSTIFICATION_MESSAGE));
        }
        return Optional.empty();
    }

    /**
     * Traduce un error del conversor: si Jackson indica el campo con el valor ilegible y es uno de los
     * tres, ese es el chequeo; cualquier otro caso (no es JSON, esta vacio, es nulo) es "json".
     */
    public static FailureInfo fromConversionError(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof DatabindException databind) {
                List<JacksonException.Reference> path = databind.getPath();
                if (!path.isEmpty()) {
                    String field = path.get(path.size() - 1).getPropertyName();
                    if (CATEGORY.equals(field)) {
                        return new FailureInfo(CATEGORY, CATEGORY_MESSAGE);
                    }
                    if (CONFIDENCE.equals(field)) {
                        return new FailureInfo(CONFIDENCE, CONFIDENCE_MESSAGE);
                    }
                    if (JUSTIFICATION.equals(field)) {
                        return new FailureInfo(JUSTIFICATION, JUSTIFICATION_MESSAGE);
                    }
                }
            }
        }
        return new FailureInfo(JSON, JSON_MESSAGE);
    }
}
