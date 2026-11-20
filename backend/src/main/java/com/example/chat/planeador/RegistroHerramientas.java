package com.example.chat.planeador;

import org.springframework.ai.chat.model.ToolContext;
import java.util.LinkedHashSet;
import java.util.Map;

/** Un registro por petición: Spring lo entrega a las tools, fuera de los argumentos del modelo. */
public final class RegistroHerramientas {
    public static final String CLAVE = "planeador";
    private final SolicitudPlaneador solicitud;
    private final LinkedHashSet<String> resultados = new LinkedHashSet<>();
    private String reservaConfirmada;

    public RegistroHerramientas(String pregunta) { this.solicitud = SolicitudPlaneador.analizar(pregunta); }
    public SolicitudPlaneador solicitud() { return solicitud; }
    public Map<String, Object> contexto() { return Map.of(CLAVE, this); }
    public synchronized String registrar(String resultado) { resultados.add(resultado); return resultado; }
    public synchronized String respuestaVerificada() { return String.join("\n", resultados); }
    public synchronized boolean tieneResultados() { return !resultados.isEmpty(); }
    public synchronized String reservaConfirmada() { return reservaConfirmada; }
    public synchronized void confirmar(String resultado) {
        reservaConfirmada = resultado;
        // La disponibilidad anterior al descuento no describe los cupos que quedan después.
        resultados.removeIf(r -> r.contains(": quedan ") && r.endsWith(" cupos."));
    }

    public static RegistroHerramientas desde(ToolContext context) {
        if (context == null) return null;
        Object value = context.getContext().get(CLAVE);
        return value instanceof RegistroHerramientas registro ? registro : null;
    }

    public static String guardar(ToolContext context, String resultado) {
        RegistroHerramientas registro = desde(context);
        return registro == null ? resultado : registro.registrar(resultado);
    }
}
