package com.example.chat.support;

import com.example.chat.ClassificationService;
import com.example.chat.ModelService;
import com.example.chat.prompt.PromptTemplateRegistry;

import java.util.Map;

/** Piezas de apoyo para las pruebas del controlador: plantillas reales y servicios que no deben usarse. */
public final class TestPrompts {

    private TestPrompts() { }

    /** Registro con las plantillas reales del classpath y valores de variables fijos. */
    public static PromptTemplateRegistry registry() {
        return new PromptTemplateRegistry("classpath:prompts/*.st",
                Map.of("max_sentences", "3", "audience", "estudiante"));
    }

    /** Servicio de chat para pruebas de la clasificacion: si se invoca, la prueba esta mal. */
    public static ModelService unusedModel() {
        return (question, sessionId, settings, templateId) -> {
            throw new UnsupportedOperationException("el chat no debe usarse en esta prueba");
        };
    }

    /** Servicio de clasificacion para pruebas del chat: si se invoca, la prueba esta mal. */
    public static ClassificationService unusedClassification() {
        return (text, strategy, temperature) -> {
            throw new UnsupportedOperationException("la clasificacion no debe usarse en esta prueba");
        };
    }
}
