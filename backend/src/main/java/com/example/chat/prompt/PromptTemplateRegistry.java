package com.example.chat.prompt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.ai.template.ValidationMode;
import org.springframework.ai.template.st.StTemplateRenderer;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Plantillas de prompt de sistema, cargadas de archivos prompts/*.st del classpath.
 *
 * El identificador es el nombre del archivo sin ".st". Se renderizan con los delimitadores
 * &lt; y &gt; (modo estricto) para que una plantilla pueda contener llaves, por ejemplo un
 * ejemplo JSON. Los valores de las variables salen solo de la configuracion del servidor.
 */
@Component
public class PromptTemplateRegistry {

    /** Plantilla que se usa cuando la peticion no indica ninguna. */
    public static final String DEFAULT_ID = "default";

    private static final int MAX_ID_ECHO = 40;
    private static final java.util.regex.Pattern VALID_ID = java.util.regex.Pattern.compile("[a-z0-9-]+");

    private final Map<String, String> templates = new TreeMap<>();
    private final Map<String, String> variables;
    private final StTemplateRenderer renderer = StTemplateRenderer.builder()
            .startDelimiterToken('<')
            .endDelimiterToken('>')
            .validationMode(ValidationMode.THROW)
            .build();

    @Autowired
    public PromptTemplateRegistry(PromptVariables promptVariables) {
        this("classpath:prompts/*.st", promptVariables.getVariables());
    }

    /** Publico para que las pruebas puedan usar otro conjunto de archivos u otros valores. Valida al construirse. */
    public PromptTemplateRegistry(String locationPattern, Map<String, String> variables) {
        this.variables = Map.copyOf(variables);
        try {
            for (Resource resource : new PathMatchingResourcePatternResolver().getResources(locationPattern)) {
                String filename = resource.getFilename();
                if (filename == null || !filename.endsWith(".st")) {
                    continue;
                }
                String id = filename.substring(0, filename.length() - ".st".length());
                if (!VALID_ID.matcher(id).matches()) {
                    throw new IllegalStateException("Identificador de plantilla no valido '" + id + "' (" + filename
                            + "): solo letras minusculas, digitos y guiones");
                }
                // Los saltos de linea se normalizan: el mismo archivo debe dar el mismo prompt en Windows y Linux.
                String text = resource.getContentAsString(StandardCharsets.UTF_8).replace("\r\n", "\n");
                if (templates.put(id, text) != null) {
                    throw new IllegalStateException("Identificador de plantilla repetido '" + id
                            + "': lo definen dos archivos");
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudieron leer las plantillas de prompt en " + locationPattern, e);
        }
        validate();
    }

    /**
     * Comprobacion al arrancar: debe existir "default" y cada plantilla se renderiza una vez en modo
     * estricto con las variables configuradas. Cualquier fallo detiene la aplicacion y nombra la plantilla.
     */
    private void validate() {
        if (!templates.containsKey(DEFAULT_ID)) {
            throw new IllegalStateException("Falta la plantilla obligatoria '" + DEFAULT_ID + "' (" + DEFAULT_ID + ".st)");
        }
        for (String id : templates.keySet()) {
            try {
                render(id);
            } catch (RuntimeException e) {
                throw new IllegalStateException("La plantilla '" + id + "' (" + id + ".st) no es valida: "
                        + e.getMessage(), e);
            }
        }
    }

    /** Identificadores registrados, ordenados. */
    public Set<String> ids() {
        return java.util.Collections.unmodifiableSet(templates.keySet());
    }

    /** Coincidencia exacta: mayusculas y espacios importan. */
    public boolean contains(String id) {
        return id != null && templates.containsKey(id);
    }

    /** Devuelve el texto de la plantilla con sus variables ya sustituidas. */
    public String render(String id) {
        String template = templates.get(id);
        if (template == null) {
            throw new IllegalArgumentException("Plantilla desconocida: " + abbreviate(id));
        }
        // StringTemplate escribe los saltos de linea de la plataforma (\r\n en Windows): se normalizan
        // a \n para que el prompt sea el mismo en cualquier sistema operativo.
        return renderer.apply(template, variables).replace("\r\n", "\n");
    }

    /** Recorta un identificador a 40 caracteres para citarlo en errores y logs. */
    public static String abbreviate(String id) {
        if (id == null || id.length() <= MAX_ID_ECHO) {
            return id;
        }
        return id.substring(0, MAX_ID_ECHO) + "…";
    }
}
