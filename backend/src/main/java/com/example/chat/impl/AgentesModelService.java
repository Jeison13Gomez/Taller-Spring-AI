package com.example.chat.impl;

import com.example.chat.GenerationSettings;
import com.example.chat.ModelResult;
import com.example.chat.ModelService;
import com.example.chat.planeador.PlaneadorOrquestador;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/** Conecta el chat existente con el planeador; conserva el servicio RAG para comparar. */
@Service
@Primary
public class AgentesModelService implements ModelService {

    private static final Logger log = LoggerFactory.getLogger(AgentesModelService.class);
    private final PlaneadorOrquestador orquestador;

    public AgentesModelService(PlaneadorOrquestador orquestador) {
        this.orquestador = orquestador;
    }

    @Override
    public ModelResult generateResponse(String question, String sessionId,
                                        GenerationSettings settings, String templateId) {
        log.info("Planeador multiagente. sessionId={}", sessionId);
        // Esta etapa usa prompts y opciones fijos; aún no tiene memoria entre turnos.
        String respuesta = orquestador.planear(question);
        // No presentar métricas parciales como si fueran el costo de todo el recorrido.
        return new ModelResult(respuesta == null ? "" : respuesta, null, 0);
    }
}
