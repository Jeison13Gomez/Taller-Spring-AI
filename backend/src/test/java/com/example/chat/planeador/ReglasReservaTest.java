package com.example.chat.planeador;

import com.example.chat.planeador.Decision.Agente;
import com.example.chat.planeador.datos.DatosPlaneador;
import com.example.chat.planeador.herramientas.ReservasTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.support.ToolCallbacks;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ReglasReservaTest {
    private final DatosPlaneador datos = new DatosPlaneador();
    private final ReservasTools tools = new ReservasTools(datos);
    private static final String POSITIVA = "Somos 3 y tenemos 250000. Si no llueve mucho y nos alcanza, reserva Suesca para el sábado a nombre de Laura.";
    private static final String NEGATIVA = "Somos 3 con 150000. Si nos alcanza, reserva Suesca el domingo a nombre de Laura.";

    private static ToolContext contexto(RegistroHerramientas r) { return new ToolContext(r.contexto()); }

    @Test
    void seleccionaLosAgentesDeLasSeisPreguntas() {
        assertEquals(List.of(Agente.CLIMA), SolicitudPlaneador.analizar("¿Cómo estará el clima en Guatavita el domingo?").pendientes(Set.of()));
        assertEquals(List.of(Agente.PRESUPUESTO), SolicitudPlaneador.analizar("Somos 4 amigos con 350000 pesos. ¿Nos alcanza para ir a Zipaquirá?").pendientes(Set.of()));
        assertEquals(List.of(Agente.CLIMA, Agente.PRESUPUESTO), SolicitudPlaneador.analizar("Somos 2 y queremos ir a Villa de Leyva el sábado. ¿Cómo estará el clima y cuánto nos cuesta?").pendientes(Set.of()));
        assertEquals(List.of(Agente.CLIMA, Agente.PRESUPUESTO), SolicitudPlaneador.analizar(POSITIVA).pendientes(Set.of()));
        assertEquals(List.of(Agente.RESERVAS), SolicitudPlaneador.analizar(POSITIVA).pendientes(Set.of(Agente.CLIMA, Agente.PRESUPUESTO)));
        assertEquals(List.of(Agente.PRESUPUESTO), SolicitudPlaneador.analizar(NEGATIVA).pendientes(Set.of()));
        assertTrue(SolicitudPlaneador.analizar("¿Quién ganó el mundial de 2022?").pendientes(Set.of()).isEmpty());
    }

    @Test
    void presupuestoInsuficienteBloqueaAunqueElModeloIntenteReservar() {
        var registro = new RegistroHerramientas(NEGATIVA);
        String r = tools.crearReserva("Suesca", "domingo", 3, "Laura", contexto(registro));
        assertTrue(r.contains("faltan $60.000"), r);
        assertEquals(6, datos.cupos("suesca", "domingo"));
        assertNull(registro.reservaConfirmada());
    }

    @Test
    void presupuestoYClimaAdecuadosPermitenLaReservaRealEnMemoria() {
        var registro = new RegistroHerramientas(POSITIVA);
        String r = tools.crearReserva("Suesca", "sábado", 3, "Laura", contexto(registro));
        assertTrue(r.contains("RES-001"), r);
        assertEquals(5, datos.cupos("suesca", "sabado"));
        assertEquals(r, registro.reservaConfirmada());
    }

    @Test
    void llamadasRepetidasEnUnaPeticionNoDescuentanDosVeces() {
        var registro = new RegistroHerramientas(POSITIVA);
        String primera = tools.crearReserva("Suesca", "sábado", 3, "Laura", contexto(registro));
        String segunda = tools.crearReserva("Suesca", "sábado", 3, "Laura", contexto(registro));
        assertEquals(primera, segunda);
        assertEquals(5, datos.cupos("suesca", "sabado"));
    }

    @Test
    void faltaDePresupuestoNoSeInterpretaComoCondicionCumplida() {
        var registro = new RegistroHerramientas("Somos 3. Si nos alcanza, reserva Suesca el domingo a nombre de Laura.");
        assertTrue(tools.crearReserva("Suesca", "domingo", 3, "Laura", contexto(registro)).contains("falta el presupuesto"));
        assertEquals(6, datos.cupos("suesca", "domingo"));
    }

    @Test
    void argumentosDelModeloDebenCoincidirConLaSolicitudOriginal() {
        var registro = new RegistroHerramientas(POSITIVA);
        assertTrue(tools.crearReserva("Guatavita", "sábado", 3, "Laura", contexto(registro)).contains("destino"));
        assertTrue(tools.crearReserva("Suesca", "domingo", 3, "Laura", contexto(registro)).contains("día"));
        assertTrue(tools.crearReserva("Suesca", "sábado", 1, "Laura", contexto(registro)).contains("personas"));
        assertTrue(tools.crearReserva("Suesca", "sábado", 3, "Ana", contexto(registro)).contains("responsable"));
        assertEquals(8, datos.cupos("suesca", "sabado"));
    }

    @Test
    void climaPorEncimaDelUmbralBloqueaLaReserva() {
        var registro = new RegistroHerramientas("Somos 2 con 250000. Si no llueve mucho, reserva Suesca el domingo a nombre de Laura.");
        String r = tools.crearReserva("Suesca", "domingo", 2, "Laura", contexto(registro));
        assertTrue(r.contains("lluvia 70 %"), r);
        assertEquals(6, datos.cupos("suesca", "domingo"));
    }

    @Test
    void sinContextoOriginalLaToolNoPuedeCrearReservas() {
        assertTrue(tools.crearReserva("Suesca", "sábado", 3, "Laura", null).contains("falta el contexto"));
        assertEquals(8, datos.cupos("suesca", "sabado"));
    }

    @Test
    void preguntaDeDisponibilidadNoAutorizaUnaReserva() {
        var registro = new RegistroHerramientas("¿Hay cupos en Suesca el sábado para 3 personas a nombre de Laura?");
        assertTrue(tools.crearReserva("Suesca", "sábado", 3, "Laura", contexto(registro)).contains("solicitud explícita"));
        assertEquals(8, datos.cupos("suesca", "sabado"));
    }

    @Test
    void springInyectaElContextoFueraDelEsquemaQueVeElModelo() {
        var callback = Arrays.stream(ToolCallbacks.from(tools))
                .filter(c -> c.getToolDefinition().name().equals("crearReserva")).findFirst().orElseThrow();
        assertFalse(callback.getToolDefinition().inputSchema().contains("context"));
        String r = callback.call("{\"destino\":\"Suesca\",\"dia\":\"domingo\",\"personas\":3,\"responsable\":\"Laura\"}",
                contexto(new RegistroHerramientas(NEGATIVA)));
        assertTrue(r.contains("60.000"), r);
        assertEquals(6, datos.cupos("suesca", "domingo"));
    }

    @Test
    void formatosDePesosConSeparadoresSeReconocen() {
        assertEquals(150000, SolicitudPlaneador.analizar("Somos 3 con $150.000. Si nos alcanza, reserva Suesca el domingo a nombre de Laura.").presupuesto());
        assertEquals(150000, SolicitudPlaneador.analizar("Somos 3 con 150,000 pesos.").presupuesto());
    }
    @Test
    void condicionDesconocidaJuntoAlPresupuestoNoSeDaPorCumplida() {
        var registro = new RegistroHerramientas("Somos 3 con 250000. Si todos confirman y nos alcanza, reserva Suesca el sábado a nombre de Laura.");
        String r = tools.crearReserva("Suesca", "sábado", 3, "Laura", contexto(registro));
        assertTrue(r.contains("debe aclararse"), r);
        assertEquals(8, datos.cupos("suesca", "sabado"));
    }

    @Test
    void alConfirmarNoPresentaComoActualesLosCuposAnteriores() {
        var registro = new RegistroHerramientas(POSITIVA);
        tools.consultarDisponibilidad("Suesca", "sábado", contexto(registro));
        tools.crearReserva("Suesca", "sábado", 3, "Laura", contexto(registro));
        assertFalse(registro.respuestaVerificada().contains("quedan 8 cupos"));
        assertTrue(registro.respuestaVerificada().contains("RES-001"));
    }
}
