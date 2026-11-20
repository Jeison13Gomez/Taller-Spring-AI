package com.example.chat.planeador;

import com.example.chat.planeador.Decision.Agente;
import com.example.chat.planeador.datos.DatosPlaneador;
import com.example.chat.planeador.herramientas.ActividadesTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class ActividadesToolsTest {
    private final ActividadesTools tools = new ActividadesTools(new DatosPlaneador());

    @Test
    void suescaDevuelveElCatalogoSimulado() {
        String r = tools.recomendarActividades("Suesca");
        assertTrue(r.contains("Actividades simuladas"), r);
        assertTrue(r.contains("senderismo"), r);
        assertTrue(r.contains("fotografía del paisaje"), r);
    }

    @Test
    void reconoceAliasYTildes() {
        assertTrue(tools.recomendarActividades("rocas").contains("Rocas de Suesca"));
        assertTrue(tools.recomendarActividades("Zipaquirá").contains("recorrido cultural"));
    }

    @Test
    void destinoDesconocidoNoGeneraActividadesInventadas() {
        assertTrue(tools.recomendarActividades("Medellín").contains("Destinos válidos"));
        assertTrue(tools.recomendarActividades(null).contains("Destinos válidos"));
    }

    @Test
    void losCincoDestinosTienenActividades() {
        for (String destino : List.of("Guatavita", "Zipaquirá", "Villa de Leyva", "Suesca", "Chicaque")) {
            assertTrue(tools.recomendarActividades(destino).contains("Actividades simuladas"), destino);
        }
    }

    @Test
    void registraLaEvidenciaQueLlegaraALaRespuestaFinal() {
        var registro = new RegistroHerramientas("¿Qué actividades hay en Suesca?");
        String r = tools.recomendarActividades("Suesca", new ToolContext(registro.contexto()));
        assertEquals(r, registro.respuestaVerificada());
    }

    @Test
    void detectaActividadesSinActivarOtrosAgentes() {
        for (String pregunta : List.of("¿Qué actividades hay en Suesca?", "¿Qué puedo hacer en Suesca?", "Recomiéndame actividades en Suesca")) {
            assertEquals(List.of(Agente.ACTIVIDADES), SolicitudPlaneador.analizar(pregunta).pendientes(Set.of()), pregunta);
        }
    }

    @Test
    void actividadesNoAlteraLasIntencionesDelTallerOriginal() {
        assertEquals(List.of(Agente.PRESUPUESTO), SolicitudPlaneador.analizar("Somos 4 con 350000 pesos. ¿Alcanza para Zipaquirá?").pendientes(Set.of()));
        assertEquals(List.of(Agente.CLIMA), SolicitudPlaneador.analizar("¿Cómo estará el clima en Guatavita el domingo?").pendientes(Set.of()));
    }
}
