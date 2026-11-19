package com.example.chat.dto;

import com.example.chat.GenerationSettings;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

public class ChatRequest {
    // Identifica la conversacion (memoria, feature 006). null o ausente = sin memoria. @Pattern no se aplica a null.
    // Distingue mayusculas; "" y los espacios no cumplen la regla y dan 400.
    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "sessionId must be 1 to 64 characters: letters, digits, hyphen or underscore")
    private String sessionId;

    @NotBlank(message = "question must be provided")
    private String question;

    // Identificador de plantilla de prompt (solo el id, nunca el texto). null o "" = plantilla por defecto.
    private String templateId;

    // Parametros de generacion opcionales: null = no enviado (rige el valor por defecto).
    // topK, numPredict y seed son BigDecimal para que un valor no entero o enorme llegue a la
    // validacion en lugar de truncarse o fallar al deserializar.
    @DecimalMin(value = "0.0", message = "temperature must be between 0.0 and 2.0")
    @DecimalMax(value = "2.0", message = "temperature must be between 0.0 and 2.0")
    private Double temperature;

    @DecimalMin(value = "0.0", message = "topP must be between 0.0 and 1.0")
    @DecimalMax(value = "1.0", message = "topP must be between 0.0 and 1.0")
    private Double topP;

    @Digits(integer = 3, fraction = 0, message = "topK must be a whole number between 0 and 200")
    @DecimalMin(value = "0", message = "topK must be a whole number between 0 and 200")
    @DecimalMax(value = "200", message = "topK must be a whole number between 0 and 200")
    private BigDecimal topK;

    @Digits(integer = 4, fraction = 0, message = "numPredict must be a whole number between 1 and 2048")
    @DecimalMin(value = "1", message = "numPredict must be a whole number between 1 and 2048")
    @DecimalMax(value = "2048", message = "numPredict must be a whole number between 1 and 2048")
    private BigDecimal numPredict;

    @Digits(integer = 10, fraction = 0, message = "seed must be a whole number between -2147483648 and 2147483647")
    @DecimalMin(value = "-2147483648", message = "seed must be a whole number between -2147483648 and 2147483647")
    @DecimalMax(value = "2147483647", message = "seed must be a whole number between -2147483648 and 2147483647")
    private BigDecimal seed;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getTemplateId() { return templateId; }
    public void setTemplateId(String templateId) { this.templateId = templateId; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }
    public BigDecimal getTopK() { return topK; }
    public void setTopK(BigDecimal topK) { this.topK = topK; }
    public BigDecimal getNumPredict() { return numPredict; }
    public void setNumPredict(BigDecimal numPredict) { this.numPredict = numPredict; }
    public BigDecimal getSeed() { return seed; }
    public void setSeed(BigDecimal seed) { this.seed = seed; }

    /** Solo debe llamarse tras validar: intValueExact lanza si el valor no es entero o no cabe en int. */
    public GenerationSettings toGenerationSettings() {
        return new GenerationSettings(
                temperature,
                topP,
                topK == null ? null : topK.intValueExact(),
                numPredict == null ? null : numPredict.intValueExact(),
                seed == null ? null : seed.intValueExact());
    }
}
