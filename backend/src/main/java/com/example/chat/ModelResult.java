package com.example.chat;

import com.example.chat.dto.SourceInfo;
import com.example.chat.dto.UsageMetrics;

import java.util.List;

/**
 * Resultado de una consulta al modelo: texto de la respuesta, sus metricas de uso, cuantos mensajes
 * de historial se enviaron al modelo (0 si la peticion no lleva sesion o es el primer turno) y los
 * fragmentos que el RAG recupero (Reto 3; lista vacia si no hubo ninguno).
 * Es distinto del DTO ChatResponse para no chocar con el ChatResponse de Spring AI.
 */
public record ModelResult(String answer, UsageMetrics usage, Integer historyMessages, List<SourceInfo> sources) {

    public ModelResult {
        sources = sources == null ? List.of() : sources;
    }

    /** Sin fuentes recuperadas. */
    public ModelResult(String answer, UsageMetrics usage, Integer historyMessages) {
        this(answer, usage, historyMessages, List.of());
    }

    /** Sin memoria: ningun mensaje de historial. */
    public ModelResult(String answer, UsageMetrics usage) {
        this(answer, usage, 0, List.of());
    }
}
