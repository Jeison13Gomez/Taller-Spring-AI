package com.example.chat.prompt;

import com.example.chat.support.TestPrompts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptTemplateRegistryTest {

    /** El prompt de sistema que tenia SpringAiModelService antes de esta feature, palabra por palabra. */
    private static final String ORIGINAL_SYSTEM_PROMPT =
            "Eres un asistente conciso y directo.\n"
                    + "Responde siempre en el mismo idioma en el que te escriba el usuario.\n"
                    + "Si no sabes algo, dilo en lugar de inventar.\n";

    private final PromptTemplateRegistry registry = TestPrompts.registry();

    @Test
    void registeredIdsAreTheFourTemplatesSorted() {
        assertEquals(List.of("concise", "default", "extractor", "tutor"), List.copyOf(registry.ids()));
        assertTrue(registry.contains("default"));
        assertFalse(registry.contains("internal"), "la carpeta internal/ no es una plantilla de chat");
        assertFalse(registry.contains("classification-system"));
    }

    @Test
    void defaultTemplateIsTheOriginalSystemPromptWordForWord() {
        assertEquals(ORIGINAL_SYSTEM_PROMPT, registry.render("default"));
    }

    @Test
    void variablesAreReplacedAndNoPlaceholderIsLeft() {
        String concise = registry.render("concise");
        String tutor = registry.render("tutor");

        assertTrue(concise.contains("3"), concise);
        assertFalse(concise.contains("<"), concise);
        assertFalse(concise.contains("max_sentences"), concise);
        assertTrue(tutor.contains("estudiante"), tutor);
        assertFalse(tutor.contains("<"), tutor);
    }

    @Test
    void extraVariablesAreHarmlessForATemplateThatDoesNotUseThem() {
        // El registro pasa todas las variables configuradas a cada plantilla, aunque no las use.
        var withExtras = new PromptTemplateRegistry("classpath:prompts/*.st",
                Map.of("max_sentences", "2", "audience", "x", "otra_variable", "y"));

        assertEquals(ORIGINAL_SYSTEM_PROMPT, withExtras.render("default"));
    }

    @Test
    void extractorKeepsItsJsonBracesLiteral() {
        String extractor = registry.render("extractor");

        assertTrue(extractor.contains("{"), extractor);
        assertTrue(extractor.contains("}"), extractor);
        assertFalse(extractor.contains("<"), extractor);
    }

    @Test
    void unknownIdIsNotRegisteredAndCannotBeRendered() {
        assertFalse(registry.contains("pirata"));
        assertFalse(registry.contains("Concise"));
        assertFalse(registry.contains("concise "));
        assertThrows(IllegalArgumentException.class, () -> registry.render("pirata"));
    }

    @Test
    void abbreviateKeepsShortIdsAndCutsLongOnesAt40Characters() {
        assertEquals("tutor", PromptTemplateRegistry.abbreviate("tutor"));
        assertEquals("x".repeat(40) + "…", PromptTemplateRegistry.abbreviate("x".repeat(100)));
        assertEquals("x".repeat(40), PromptTemplateRegistry.abbreviate("x".repeat(40)));
    }

    // --- Validacion al arrancar (US6, T050) -----------------------------------------------------

    private static final Map<String, String> VARIABLES = Map.of("max_sentences", "3", "audience", "estudiante");

    @TempDir
    Path dir;

    private void write(String relativePath, String content) throws IOException {
        Path file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** Registro sobre la carpeta temporal (toUri() de un directorio termina en "/"). */
    private PromptTemplateRegistry load(String glob) {
        return new PromptTemplateRegistry(dir.toUri() + glob, VARIABLES);
    }

    private IllegalStateException startupFailure(String glob) {
        return assertThrows(IllegalStateException.class, () -> load(glob));
    }

    @Test
    void startupFailsWhenThereIsNoDefaultTemplate() throws IOException {
        write("concise.st", "Responde en <max_sentences> frases.\n");

        IllegalStateException error = startupFailure("*.st");

        assertTrue(error.getMessage().contains("default"), error.getMessage());
    }

    @Test
    void startupFailsNamingTheTemplateAndTheVariableThatHasNoValue() throws IOException {
        write("default.st", "Hola.\n");
        write("roto.st", "Responde sobre <unknown_variable>.\n");

        IllegalStateException error = startupFailure("*.st");

        assertTrue(error.getMessage().contains("roto"), error.getMessage());
        assertTrue(error.getMessage().contains("unknown_variable"), error.getMessage());
    }

    @Test
    void startupFailsNamingTheTemplateThatHasALoneAngleBracket() throws IOException {
        write("default.st", "Hola.\n");
        write("menor.st", "Si a < b, responde.\n");

        IllegalStateException error = startupFailure("*.st");

        assertTrue(error.getMessage().contains("menor"), error.getMessage());
    }

    @Test
    void startupFailsNamingAnIdThatIsNotLowercaseLettersDigitsAndHyphens() throws IOException {
        write("default.st", "Hola.\n");
        write("Tutor.st", "Explica.\n");

        assertTrue(startupFailure("*.st").getMessage().contains("Tutor"));
    }

    @Test
    void startupFailsForAnIdWithSpaces() throws IOException {
        write("default.st", "Hola.\n");
        write("my template.st", "Explica.\n");

        assertTrue(startupFailure("*.st").getMessage().contains("my template"));
    }

    @Test
    void startupFailsNamingAnIdThatIsDefinedByTwoFiles() throws IOException {
        write("default.st", "Hola.\n");
        write("a/repetida.st", "Uno.\n");
        write("b/repetida.st", "Dos.\n");

        IllegalStateException error = startupFailure("**/*.st");

        assertTrue(error.getMessage().contains("repetida"), error.getMessage());
    }

    @Test
    void aTemplateWithAJsonExampleAndValidVariablesLoads() throws IOException {
        write("default.st", "Hola.\n");
        write("extractor-2.st", "Extrae para <audience> en <max_sentences> frases: {\"a\": 1}\n");

        PromptTemplateRegistry loaded = load("*.st");

        assertEquals(List.of("default", "extractor-2"), List.copyOf(loaded.ids()));
        assertEquals("Extrae para estudiante en 3 frases: {\"a\": 1}\n", loaded.render("extractor-2"));
    }
}
