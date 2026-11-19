package com.example.chat.classification;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Estrategia para obtener el formato estructurado de una clasificacion. maxRetries es el numero
 * maximo de reintentos tras el primer intento (0 = un solo intento).
 */
public enum FormatStrategy {

    /** Solo se anade la instruccion de formato al prompt. */
    INSTRUCTION("instruction", 0),
    /** Igual que INSTRUCTION, pero si el resultado no cumple se vuelve a preguntar. */
    VALIDATE_RETRY("validate-retry", 2),
    /** Se envia el esquema al proveedor (formato nativo de Ollama). */
    NATIVE_SCHEMA("native-schema", 0);

    private final String id;
    private final int maxRetries;

    FormatStrategy(String id, int maxRetries) {
        this.id = id;
        this.maxRetries = maxRetries;
    }

    public String getId() { return id; }
    public int getMaxRetries() { return maxRetries; }

    /** Coincidencia exacta: mayusculas y espacios importan. */
    public static Optional<FormatStrategy> fromId(String id) {
        return Arrays.stream(values()).filter(s -> s.id.equals(id)).findFirst();
    }

    /** Ids validos en el orden en que se declaran. */
    public static List<String> ids() {
        return Arrays.stream(values()).map(FormatStrategy::getId).toList();
    }
}
