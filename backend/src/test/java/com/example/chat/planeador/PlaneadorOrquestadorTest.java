package com.example.chat.planeador;

import com.example.chat.support.ScriptedChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class PlaneadorOrquestadorTest {
    private final ScriptedChatModel clima = ScriptedChatModel.answering("Lluvioso, 7–15 °C, 80 % de lluvia.");
    private final ScriptedChatModel presupuesto = ScriptedChatModel.answering("Costo $400.000; faltan $50.000.");
    private final ScriptedChatModel reservas = ScriptedChatModel.answering("Quedan 12 cupos.");
    private final ScriptedChatModel actividades = ScriptedChatModel.answering("Actividades simuladas: senderismo.");
    private final ScriptedChatModel sintetizador = ScriptedChatModel.answering("Respuesta final basada en los resultados.");

    private static ScriptedChatModel decisiones(String... agentes) {
        return new ScriptedChatModel(Arrays.stream(agentes)
                .map(a -> ScriptedChatModel.Answer.of("{\"siguiente\":\"" + a + "\",\"motivo\":\"prueba\"}"))
                .toArray(ScriptedChatModel.Answer[]::new));
    }

    private PlaneadorOrquestador crear(ChatModel supervisor, ChatModel sintesis, ChatModel agenteClima) {
        return new PlaneadorOrquestador(ChatClient.create(supervisor), ChatClient.create(sintesis),
                ChatClient.create(agenteClima), ChatClient.create(presupuesto), ChatClient.create(reservas),
                ChatClient.create(actividades));
    }

    @Test
    void consultaSoloClimaYComparteElResultadoConElSintetizador() {
        var supervisor = decisiones("CLIMA", "FIN");
        String pregunta = "¿Clima? {dato} <etiqueta>";
        String r = crear(supervisor, sintetizador, clima).planear(pregunta);
        assertEquals("Respuesta final basada en los resultados.", r);
        assertEquals(1, clima.prompts().size());
        assertTrue(presupuesto.prompts().isEmpty());
        assertTrue(reservas.prompts().isEmpty());
        assertTrue(sintetizador.lastPrompt().getUserMessage().getText().contains("[CLIMA] Lluvioso"));
        assertTrue(clima.lastPrompt().getUserMessage().getText().contains(pregunta));
        assertTrue(supervisor.lastPrompt().getUserMessage().getText().contains("Agentes permitidos ahora: [CLIMA]"));
    }

    @Test
    void corrigeUnaDecisionRepetidaSinOmitirElPresupuesto() {
        var supervisor = decisiones("CLIMA", "CLIMA", "PRESUPUESTO");
        crear(supervisor, sintetizador, clima).planear("Consulta el clima y el costo");
        assertEquals(2, supervisor.prompts().size());
        assertEquals(1, clima.prompts().size());
        assertEquals(1, presupuesto.prompts().size());
    }

    @Test
    void ejecutaComoMaximoTresEspecialistas() {
        var supervisor = decisiones("CLIMA", "PRESUPUESTO", "RESERVAS", "CLIMA");
        crear(supervisor, sintetizador, clima).planear("Clima, costo y cupos");
        assertEquals(3, supervisor.prompts().size());
        assertEquals(1, clima.prompts().size());
        assertEquals(1, presupuesto.prompts().size());
        assertEquals(1, reservas.prompts().size());
        assertEquals(1, sintetizador.prompts().size());
    }

    @Test
    void unaDecisionInvalidaPermiteCerrarLaConsulta() {
        var supervisor = ScriptedChatModel.answering("esto no es JSON");
        assertFalse(crear(supervisor, sintetizador, clima).planear("Consulta el clima").isBlank());
        assertEquals(1, clima.prompts().size());
        assertEquals(1, sintetizador.prompts().size());
    }

    @Test
    void unaDecisionSinAgenteNoProvocaError() {
        var supervisor = ScriptedChatModel.answering("{\"siguiente\":null,\"motivo\":\"vacío\"}");
        assertFalse(crear(supervisor, sintetizador, clima).planear("Consulta el clima").isBlank());
        assertEquals(1, clima.prompts().size());
    }

    @Test
    void finNoInvocaEspecialistas() {
        crear(decisiones("FIN"), sintetizador, clima).planear("¿Quién ganó el mundial?");
        assertTrue(clima.prompts().isEmpty());
        assertTrue(presupuesto.prompts().isEmpty());
        assertTrue(reservas.prompts().isEmpty());
    }

    @Test
    void falloDeEspecialistaSeComunicaAlSintetizador() {
        var falla = new ModeloFallido();
        crear(decisiones("CLIMA", "FIN"), sintetizador, falla).planear("Consulta el clima");
        assertTrue(sintetizador.lastPrompt().getUserMessage().getText().contains("No se pudo completar esta consulta"));
        assertEquals(1, falla.intentos);
    }

    @Test
    void falloDelSintetizadorConservaLosResultadosDisponibles() {
        var falla = new ModeloFallido();
        String r = crear(decisiones("CLIMA", "FIN"), falla, clima).planear("Consulta el clima");
        assertTrue(r.contains("[CLIMA] Lluvioso"), r);
    }
    @Test
    void corrigeUnEspecialistaQueNoCorrespondeALaPregunta() {
        crear(decisiones("RESERVAS"), sintetizador, clima).planear("¿Cuánto cuesta Zipaquirá para 4 personas?");
        assertEquals(1, presupuesto.prompts().size());
        assertTrue(clima.prompts().isEmpty());
        assertTrue(reservas.prompts().isEmpty());
    }

    @Test
    void reservasNoSeEjecutaAntesDeLasCondicionesSolicitadas() {
        var supervisor = decisiones("RESERVAS", "RESERVAS", "RESERVAS");
        crear(supervisor, sintetizador, clima).planear(
                "Somos 3 y tenemos 250000. Si no llueve mucho y nos alcanza, reserva Suesca para el sábado a nombre de Laura.");
        assertEquals(1, clima.prompts().size());
        assertEquals(1, presupuesto.prompts().size());
        assertEquals(1, reservas.prompts().size());
        String contexto = reservas.lastPrompt().getUserMessage().getText();
        assertTrue(contexto.contains("[CLIMA]"));
        assertTrue(contexto.contains("[PRESUPUESTO]"));
    }

    @Test
    void actividadesYCostosUsanSoloLosDosEspecialistasNecesarios() {
        crear(decisiones("ACTIVIDADES", "ACTIVIDADES"), sintetizador, clima)
                .planear("Somos 2. ¿Qué actividades podemos hacer en Suesca y cuánto nos cuesta?");
        assertEquals(1, actividades.prompts().size());
        assertEquals(1, presupuesto.prompts().size());
        assertTrue(clima.prompts().isEmpty());
        assertTrue(reservas.prompts().isEmpty());
    }

    @Test
    void retoPermiteCuatroEspecialistasSinRepetirYDejaReservasAlFinal() {
        var supervisor = decisiones("CLIMA", "PRESUPUESTO", "ACTIVIDADES", "RESERVAS", "ACTIVIDADES");
        crear(supervisor, sintetizador, clima).planear("Consulta clima, costo, actividades y cupos de Suesca.");
        assertEquals(4, supervisor.prompts().size());
        assertEquals(1, clima.prompts().size());
        assertEquals(1, presupuesto.prompts().size());
        assertEquals(1, actividades.prompts().size());
        assertEquals(1, reservas.prompts().size());
        assertTrue(reservas.lastPrompt().getUserMessage().getText().contains("[ACTIVIDADES]"));
        assertEquals(1, sintetizador.prompts().size());
    }

    private static class ModeloFallido extends ScriptedChatModel {
        int intentos;

        @Override
        public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
            intentos++;
            throw new IllegalStateException("fallo simulado");
        }
    }
}
