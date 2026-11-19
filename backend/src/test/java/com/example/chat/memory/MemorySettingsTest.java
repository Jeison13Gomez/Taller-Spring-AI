package com.example.chat.memory;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemorySettingsTest {

    private static MemorySettings bind(Map<String, Object> properties) {
        return new Binder(new MapConfigurationPropertySource(properties))
                .bind("app.memory", Bindable.of(MemorySettings.class))
                .orElseGet(MemorySettings::new);
    }

    /** Mensajes de la excepcion y de todas sus causas, para comprobar que se nombra la propiedad. */
    private static String chain(Throwable error) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append(" | ");
        }
        return text.toString();
    }

    private static void assertRejected(String property, String value, String expectedInMessage) {
        BindException error = assertThrows(BindException.class, () -> bind(Map.of(property, value)),
                property + "=" + value + " debe rechazarse");
        String text = chain(error);
        assertTrue(text.contains(property), "el error debe nombrar la propiedad " + property + ": " + text);
        if (expectedInMessage != null) {
            assertTrue(text.contains(expectedInMessage), "el error debe decir '" + expectedInMessage + "': " + text);
        }
    }

    @Test
    void defaultsAreTwentyMessagesThirtyMinutesAndHundredSessions() {
        MemorySettings settings = bind(Map.of());
        assertEquals(20, settings.getMaxMessages());
        assertEquals(Duration.ofMinutes(30), settings.getIdleTimeout());
        assertEquals(100, settings.getMaxSessions());
    }

    @Test
    void validValuesAreBound() {
        MemorySettings settings = bind(Map.of(
                "app.memory.max-messages", "2",
                "app.memory.idle-timeout", "5m",
                "app.memory.max-sessions", "1"));
        assertEquals(2, settings.getMaxMessages());
        assertEquals(Duration.ofMinutes(5), settings.getIdleTimeout());
        assertEquals(1, settings.getMaxSessions());
    }

    @Test
    void windowSmallerThanTwoIsRejectedAndTheErrorSaysTheMinimum() {
        for (String value : new String[] {"1", "0", "-1"}) {
            assertRejected("app.memory.max-messages", value, "2");
        }
    }

    @Test
    void windowThatIsNotANumberIsRejectedNamingTheProperty() {
        assertRejected("app.memory.max-messages", "abc", null);
    }

    @Test
    void zeroSessionsIsRejected() {
        assertRejected("app.memory.max-sessions", "0", "1");
    }

    @Test
    void zeroOrNegativeIdleTimeoutIsRejected() {
        assertRejected("app.memory.idle-timeout", "0s", null);
        assertRejected("app.memory.idle-timeout", "-5m", null);
    }
}
