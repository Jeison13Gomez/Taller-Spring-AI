package com.example.chat.planeador;

import com.example.chat.planeador.Decision.Agente;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.ai.template.NoOpTemplateRenderer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Orquestador multiagente (patrón SUPERVISOR / orchestrator-workers).
 *
 * Es el equivalente en Java del grafo de LangGraph:
 *
 *    pregunta -> [supervisor] --(decide)--> clima | presupuesto | actividades | reservas --> vuelve al supervisor
 *                     |
 *                    FIN --> [sintetizador] -> respuesta
 *
 * En LangGraph el flujo se declara como nodos y aristas; en Spring AI lo escribimos
 * como código Java normal (un ciclo while). El "estado" son las variables locales
 * pregunta, resultados y consultados.
 */
@Service
public class PlaneadorOrquestador {

    private static final Logger log = LoggerFactory.getLogger(PlaneadorOrquestador.class);

    /** Límite de autonomía: máximo de especialistas por pregunta. */
    private static final int MAX_PASOS = 4; // Reto: cuatro especialistas, sin repeticiones.

    private final ChatClient supervisor;
    private final ChatClient sintetizador;
    private final Map<Agente, ChatClient> especialistas = new EnumMap<>(Agente.class);

    public PlaneadorOrquestador(@Qualifier("supervisor") ChatClient supervisor,
                                @Qualifier("sintetizador") ChatClient sintetizador,
                                @Qualifier("agenteClima") ChatClient agenteClima,
                                @Qualifier("agentePresupuesto") ChatClient agentePresupuesto,
                                @Qualifier("agenteReservas") ChatClient agenteReservas,
                                @Qualifier("agenteActividades") ChatClient agenteActividades) {
        this.supervisor = supervisor;
        this.sintetizador = sintetizador;
        especialistas.put(Agente.CLIMA, agenteClima);
        especialistas.put(Agente.PRESUPUESTO, agentePresupuesto);
        especialistas.put(Agente.RESERVAS, agenteReservas);
        especialistas.put(Agente.ACTIVIDADES, agenteActividades);
    }

    public String planear(String pregunta) {
        List<String> resultados = new ArrayList<>();
        RegistroHerramientas registro = new RegistroHerramientas(pregunta);
        SolicitudPlaneador solicitud = registro.solicitud();
        Set<Agente> consultados = EnumSet.noneOf(Agente.class);

        while (consultados.size() < MAX_PASOS) {
            List<Agente> disponibles = solicitud.pendientes(consultados);
            if (disponibles.isEmpty()) break;
            Agente propuesto = decidir(pregunta, resultados, consultados, disponibles);
            Agente siguiente = disponibles.contains(propuesto) ? propuesto : disponibles.get(0);
            if (siguiente != propuesto) {
                log.info("[SUPERVISOR_VALIDADO] {} -> {} (disponibles: {})", propuesto, siguiente, disponibles);
            }
            if (siguiente == null || siguiente == Agente.FIN || consultados.contains(siguiente)) {
                break;                                   // evita ciclos infinitos
            }
            try {
                String respuesta = especialistas.get(siguiente).prompt()
                        .user(contexto(pregunta, resultados)
                                + "\n\nLas condiciones de reserva las comprueba Java en crearReserva. "
                                + "No rechaces por tu interpretación del clima: no llueve mucho significa hasta 40 %. "
                                + "Si el usuario pide reservar, usa crearReserva con sus datos; la herramienta autoriza o rechaza.")
                        .toolContext(registro.contexto())
                        .templateRenderer(new NoOpTemplateRenderer())
                        .call()
                        .content();
                log.info("[{}] {}", siguiente, respuesta);
                resultados.add("[" + siguiente + "] " + respuesta);
                if (registro.tieneResultados()) resultados.add("Evidencia de herramientas:\n" + registro.respuestaVerificada());
            } catch (RuntimeException e) {
                log.warn("[{}] consulta fallida", siguiente, e);
                resultados.add("[" + siguiente + "] No se pudo completar esta consulta. Si se intentó una reserva, su estado no está confirmado; no se debe reintentar automáticamente.");
            }
            consultados.add(siguiente);
        }

        if (consultados.isEmpty()) {
            return "Puedo ayudarte con clima, presupuesto y reservas, además de actividades simuladas, para Guatavita, Zipaquirá, Villa de Leyva, Suesca y Chicaque.";
        }
        try {
            String sintesis = sintetizador.prompt()
                    .user(registro.tieneResultados()
                            ? "Reproduce estos resultados verificados sin omisiones:\n" + registro.respuestaVerificada()
                            : contexto(pregunta, resultados))
                    .templateRenderer(new NoOpTemplateRenderer())
                    .call()
                    .content();
            // Un resumen que cambie u omita datos se sustituye por la evidencia completa.
            if (registro.tieneResultados()) {
                String verificada = registro.respuestaVerificada();
                if (!normalizarRespuesta(verificada).equals(normalizarRespuesta(sintesis))) {
                    log.info("[SINTESIS_VALIDADA] se devuelve evidencia completa sin alterar cifras");
                    return verificada;
                }
            }
            return sintesis;
        } catch (RuntimeException e) {
            log.warn("[SINTETIZADOR] fallo; se devuelven los resultados disponibles", e);
            if (registro.tieneResultados()) return registro.respuestaVerificada();
            return resultados.isEmpty()
                    ? "No se pudo completar la consulta. Inténtalo de nuevo."
                    : String.join("\n", resultados);
        }
    }

    /** Nodo "supervisor": pide al modelo una Decision (salida estructurada). */
    private Agente decidir(String pregunta, List<String> resultados, Set<Agente> consultados, List<Agente> disponibles) {

        String yaConsultados = consultados.isEmpty() ? "ninguno"
                : consultados.stream().map(Enum::name).collect(Collectors.joining(", "));
        try {
            Decision decision = supervisor.prompt()
                    .user(contexto(pregunta, resultados) + "\n\nAgentes ya consultados: " + yaConsultados
                            + "\nAgentes permitidos ahora: " + disponibles + ". Elige solo uno de esta lista.")
                    .templateRenderer(new NoOpTemplateRenderer())
                    .call()
                    .entity(Decision.class);
            if (decision == null || decision.siguiente() == null) {
                log.warn("[SUPERVISOR] decisión vacía; se usará la selección validada");
                return Agente.FIN;
            }
            log.info("[SUPERVISOR] -> {} ({})", decision.siguiente(), decision.motivo());
            return decision.siguiente();
        } catch (RuntimeException e) {                   // el modelo no devolvió un JSON válido
            log.warn("[SUPERVISOR] decisión inválida ({}); se usará la selección validada", e.getMessage());
            return Agente.FIN;
        }
    }

    private static String normalizarRespuesta(String texto) {
        return texto == null ? "" : texto.strip().replaceAll("\\s+", " ");
    }

    private static String contexto(String pregunta, List<String> resultados) {
        String previos = resultados.isEmpty() ? "(todavía no hay resultados)" : String.join("\n", resultados);
        return "Pregunta del usuario: " + pregunta + "\n\nResultados de otros agentes:\n" + previos;
    }
}
