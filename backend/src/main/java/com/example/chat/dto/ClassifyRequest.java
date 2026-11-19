package com.example.chat.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class ClassifyRequest {

    @NotBlank(message = "text must be provided")
    @Size(max = 5000, message = "text must be at most 5000 characters")
    private String text;

    // null o "" = no enviada (rige la estrategia por defecto, instruction); se valida en el controlador.
    private String strategy;

    // null = no enviada (rige la temperatura por defecto); 0.0 es un valor real.
    @DecimalMin(value = "0.0", message = "temperature must be between 0.0 and 2.0")
    @DecimalMax(value = "2.0", message = "temperature must be between 0.0 and 2.0")
    private Double temperature;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String strategy) { this.strategy = strategy; }
    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }
}
