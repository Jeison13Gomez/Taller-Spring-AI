package com.example.chat.planeador;

import com.example.chat.planeador.datos.DatosPlaneador;
import com.example.chat.planeador.herramientas.ClimaTools;
import com.example.chat.planeador.herramientas.PresupuestoTools;
import com.example.chat.planeador.herramientas.ReservasTools;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Las tools son métodos Java normales: se prueban SIN modelo y SIN levantar Spring.
 * Si estas pruebas fallan, el agente nunca podrá responder bien.
 */


class HerramientasTest {

    private final DatosPlaneador datos = new DatosPlaneador();

    @Test
    void pronosticoAceptaTildesYMayusculas() {
        String r = new ClimaTools(datos).consultarPronostico("Guatavita", "Domingo");
        assertTrue(r.contains("lluvioso"), r);
    }

    @Test
    void costoDeZipaquiraParaCuatro() {
        String r = new PresupuestoTools(datos).estimarCosto("Zipaquirá", 4);
        assertTrue(r.contains("$400.000"), r);
    }

    @Test
    void saldoNegativoDiceCuantoFalta() {
        String r = new PresupuestoTools(datos).calcularSaldo(350000, 400000);
        assertTrue(r.contains("faltan $50.000"), r);
    }

    @Test
    void noReservaSinCupos() {
        String r = new ReservasTools(datos).crearReserva("Chicaque", "sabado", 2, "Ana");
        assertTrue(r.startsWith("No hay cupo"), r);
    }

    @Test
    void destinoDesconocidoListaLosValidos() {
        String r = new ClimaTools(datos).consultarPronostico("Medellín", "sabado");
        assertTrue(r.contains("Destinos válidos"), r);
    }

    @Test
    void reservaValidaDescuentaCuposYGeneraCodigo() {
        String r = new ReservasTools(datos).crearReserva("Suesca", "sábado", 3, " Laura ");
        assertTrue(r.contains("RES-001"), r);
        assertTrue(r.contains("a nombre de Laura"), r);
        assertEquals(5, datos.cupos("suesca", "sabado"));
    }

    @Test
    void reservaRechazaCantidadesNoPositivasSinCambiarCupos() {
        var tools = new ReservasTools(datos);
        for (int personas : new int[]{0, -3}) {
            assertTrue(tools.crearReserva("Suesca", "sabado", personas, "Laura").contains("mayor que cero"));
        }
        assertEquals(8, datos.cupos("suesca", "sabado"));
    }

    @Test
    void reservaRechazaResponsableVacioYDiaInvalido() {
        var tools = new ReservasTools(datos);
        assertTrue(tools.crearReserva("Suesca", "sabado", 2, " ").contains("Falta el nombre"));
        assertTrue(tools.crearReserva("Suesca", "lunes", 2, "Laura").contains("Solo se puede reservar"));
        assertEquals(8, datos.cupos("suesca", "sabado"));
    }

    @Test
    void presupuestoValidaPersonasYMontos() {
        var tools = new PresupuestoTools(datos);
        assertTrue(tools.estimarCosto("Suesca", 0).contains("mayor que cero"));
        assertTrue(tools.calcularSaldo(-1, 70000).contains("no pueden ser negativos"));
        assertTrue(tools.calcularSaldo(70000, -1).contains("no pueden ser negativos"));
        assertTrue(tools.calcularSaldo(250000, 210000).contains("sobran $40.000"));
        assertTrue(tools.calcularSaldo(70000, 70000).contains("sobran $0"));
    }

    @Test
    void reservasConcurrentesNoVendenMasCuposDeLosDisponibles() throws Exception {
        var tools = new ReservasTools(datos);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(10)) {
            var tareas = new java.util.ArrayList<java.util.concurrent.Callable<String>>();
            for (int i = 0; i < 20; i++) {
                tareas.add(() -> tools.crearReserva("Suesca", "sabado", 1, "Laura"));
            }
            int confirmadas = 0;
            var codigos = new java.util.HashSet<String>();
            for (var resultado : executor.invokeAll(tareas)) {
                String respuesta = resultado.get();
                if (respuesta.startsWith("Reserva confirmada")) {
                    confirmadas++;
                    codigos.add(respuesta.split(":", 2)[0]);
                } else {
                    assertTrue(respuesta.startsWith("No hay cupo"), respuesta);
                }
            }
            assertEquals(8, confirmadas);
            assertEquals(8, codigos.size());
            assertEquals(0, datos.cupos("suesca", "sabado"));
        }
    }
    @Test
    void saldoDelAgenteObtieneElCostoRealDelDestino() {
        String r = new PresupuestoTools(datos).calcularSaldo("Zipaquirá", 4, 350000);
        assertTrue(r.contains("costo $400.000"), r);
        assertTrue(r.contains("faltan $50.000"), r);
    }

}
