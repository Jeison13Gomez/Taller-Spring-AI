package com.example.chat.planeador;

import com.example.chat.planeador.datos.DatosPlaneador;
import static com.example.chat.planeador.datos.DatosPlaneador.pesos;

/** Valida datos originales y condiciones antes de permitir cualquier descuento de cupos. */
public final class ReglasReserva {
    private ReglasReserva() { }

    public static String rechazar(DatosPlaneador datos, SolicitudPlaneador s,
            String destino, String dia, int personas, String responsable) {
        if (!s.crear()) return "No se creó una reserva: falta una solicitud explícita de reservar.";
        String solicitado = datos.buscarDestino(s.original());
        String clave = datos.buscarDestino(destino);
        if (solicitado == null || !solicitado.equals(clave))
            return "No se creó una reserva: el destino no coincide con la solicitud original o falta aclararlo.";
        if (s.dia() == null || !s.dia().equals(DatosPlaneador.normalizarDia(dia)))
            return "No se creó una reserva: falta un día único o no coincide con el solicitado.";
        if (s.personas() == null || s.personas() <= 0 || s.personas() != personas)
            return "No se creó una reserva: falta el número de personas o no coincide con el solicitado.";
        if (s.responsable() == null || !DatosPlaneador.normalizar(s.responsable()).equals(DatosPlaneador.normalizar(responsable)))
            return "No se creó una reserva: falta el responsable o no coincide con el solicitado.";
        if (s.condicionDinero() && s.presupuesto() == null)
            return "No se creó una reserva: falta el presupuesto para comprobar la condición.";
        long costo = (long) datos.destino(clave).costoPorPersona() * personas;
        if (costo > Integer.MAX_VALUE) return "No se creó una reserva: el tamaño del grupo supera el rango permitido.";
        if (s.presupuesto() != null && s.presupuesto() < costo)
            return "No se creó una reserva: costo total %s, presupuesto %s; faltan %s."
                    .formatted(pesos((int) costo), pesos(s.presupuesto()), pesos((int) costo - s.presupuesto()));
        if (s.condicionClima()) {
            if (s.lluviaMaxima() == null)
                return "No se creó una reserva: aclara el máximo de lluvia aceptable. 'No llueve mucho' significa hasta 40 %.";
            int lluvia = datos.pronostico(clave, s.dia()).lluvia();
            if (lluvia > s.lluviaMaxima())
                return "No se creó una reserva: lluvia %d %%, superior al máximo permitido de %d %%."
                        .formatted(lluvia, s.lluviaMaxima());
        }
        // Todas las condiciones de una frase deben ser conocidas, incluso si hay varias.
        String t = DatosPlaneador.normalizar(s.original());
        var condiciones = java.util.regex.Pattern.compile("\\bsi\\s+([^,.;]+)").matcher(t);
        while (condiciones.find()) {
            String resto = condiciones.group(1)
                    .replaceAll("no llueve mucho|no llueve demasiado|no llueve|nos alcanza|me alcanza|el dinero alcanza", "")
                    .replaceAll("\\b(?:y|e|ademas|tambien)\\b", "").trim();
            if (!resto.isEmpty()) return "No se creó una reserva: hay una condición que debe aclararse antes de reservar.";
        }
        return null;
    }
}
