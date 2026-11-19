package com.example.chat.prompt;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * Valores de las variables de las plantillas de prompt (app.prompts.variables.*).
 * Solo el servidor los define: ninguna peticion puede enviarlos ni cambiarlos.
 */
@ConfigurationProperties(prefix = "app.prompts")
public class PromptVariables {

    private Map<String, String> variables = new HashMap<>();

    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }
}
