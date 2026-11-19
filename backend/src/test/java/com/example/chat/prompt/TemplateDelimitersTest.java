package com.example.chat.prompt;

import org.junit.jupiter.api.Test;
import org.springframework.ai.template.st.StTemplateRenderer;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evidencia reproducible de por que las plantillas usan los delimitadores < y > (research R6).
 * Usa StTemplateRenderer directamente, sin el registro. Si una actualizacion de Spring AI cambia
 * el mensaje de error, esta prueba es donde se nota.
 */
class TemplateDelimitersTest {

    private static final String STRING_TEMPLATE_ERROR = "The template string is not valid.";
    private static final String JSON_EXAMPLE = "{\"category\": \"otro\", \"confidence\": 0.5}";

    private static StTemplateRenderer angleBrackets() {
        return StTemplateRenderer.builder().startDelimiterToken('<').endDelimiterToken('>').build();
    }

    @Test
    void withDefaultBracesAJsonExampleBreaksTheTemplate() {
        String template = "Responde solo con este JSON: " + JSON_EXAMPLE + " para {tema}";

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> StTemplateRenderer.builder().build().apply(template, Map.of("tema", "x")));

        assertEquals(STRING_TEMPLATE_ERROR, error.getMessage());
    }

    @Test
    void withAngleBracketsTheJsonExampleStaysLiteralAndTheVariableIsReplaced() {
        String template = "Responde solo con este JSON: " + JSON_EXAMPLE + " para <tema>";

        String rendered = angleBrackets().apply(template, Map.of("tema", "x"));

        assertTrue(rendered.contains(JSON_EXAMPLE), rendered);
        assertTrue(rendered.endsWith(" para x"), rendered);
    }

    @Test
    void withAngleBracketsALoneLessThanSignBreaksTheTemplate() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> angleBrackets().apply("si a < b entonces <tema>", Map.of("tema", "x")));

        assertEquals(STRING_TEMPLATE_ERROR, error.getMessage());
    }
}
