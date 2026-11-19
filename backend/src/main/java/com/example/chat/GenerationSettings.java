package com.example.chat;

/**
 * Parametros de generacion opcionales de una peticion. Un campo en null significa
 * "no enviado": se usa el valor por defecto configurado (o el del modelo). El valor 0 / 0.0
 * es un valor real y no equivale a "no enviado".
 */
public record GenerationSettings(Double temperature, Double topP, Integer topK, Integer numPredict, Integer seed) {

    /** Sin ningun parametro: el comportamiento es el mismo que antes de existir esta clase. */
    public static GenerationSettings none() {
        return new GenerationSettings(null, null, null, null, null);
    }

    public boolean isEmpty() {
        return temperature == null && topP == null && topK == null && numPredict == null && seed == null;
    }
}
