package com.example.chat.planeador;

import com.example.chat.planeador.Decision.Agente;
import com.example.chat.planeador.datos.DatosPlaneador;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Reglas conservadoras para el vocabulario del taller; los datos faltantes no se adivinan. */
public record SolicitudPlaneador(String original, boolean clima, boolean dinero, boolean reservas, boolean actividades,
        boolean crear, Integer personas, Integer presupuesto, String dia, String responsable,
        boolean condicionClima, Integer lluviaMaxima, boolean condicionDinero) {

    public static SolicitudPlaneador analizar(String pregunta) {
        String t = DatosPlaneador.normalizar(pregunta);
        Integer personas = numero(t, "(?:somos|grupo de|para)\\s+(\\d+)\\b");
        if (personas == null) personas = numero(t, "(\\d+)\\s+(?:personas|amigos)\\b");
        Integer presupuesto = numero(t,
                "(?:tenemos|con|presupuesto(?: total)?(?: de| es)?|contamos con)\\s*\\$?\\s*(\\d{1,3}(?:[.,]\\d{3})+|\\d+)\\b");
        if (presupuesto == null) presupuesto = numero(t, "\\$\\s*(\\d{1,3}(?:[.,]\\d{3})+|\\d+)\\b");
        if (presupuesto == null) presupuesto = numero(t, "(\\d{1,3}(?:[.,]\\d{3})+|\\d+)\\s+pesos\\b");
        boolean clima = coincide(t, "\\b(?:clima|pronostico|ll(?:uv|uev)\\w*|soleado|temperatur\\w*)\\b");
        boolean dinero = presupuesto != null || coincide(t, "\\b(?:presupuest\\w*|cuest\\w*|costo\\w*|precio\\w*|alcan\\w*|dinero|pesos|saldo)\\b");
        boolean reservas = coincide(t, "\\b(?:reserv\\w*|cupos?|disponibilidad)\\b");
        boolean actividades = coincide(t, "\\b(?:actividad(?:es)?|que (?:(?:puedo|podemos|se puede) )?hacer|recomiend\\w*|visitar)\\b");
        boolean crear = coincide(t, "(?:^|[,;.!?]\\s*|por favor\\s+)reserva(?:me|nos)?\\b")
                || coincide(t, "\\b(?:quiero|queremos|puedes|podrias|necesito)\\s+(?:reservar|hacer una reserva)\\b");
        if (coincide(t, "\\b(?:no\\s+(?:quiero\\s+)?reserv\\w*|sin reservar)\\b")) crear = false;
        boolean condicion = coincide(t, "\\bsi\\b");
        boolean condicionClima = condicion && clima;
        Integer lluvia = null;
        if (t.contains("no llueve mucho") || t.contains("no llueve demasiado")) lluvia = 40;
        else if (t.contains("no llueve")) lluvia = 0;
        String dia = t.contains("sabado") == t.contains("domingo") ? null
                : t.contains("sabado") ? "sabado" : "domingo";
        var nombre = Pattern.compile("(?iu)a nombre de\\s+([\\p{L}]+(?:[ \\t]+[\\p{L}]+){0,4})")
                .matcher(pregunta);
        String responsable = nombre.find() ? nombre.group(1).trim() : null;
        return new SolicitudPlaneador(pregunta, clima, dinero, reservas, actividades, crear, personas,
                presupuesto, dia, responsable, condicionClima, lluvia,
                condicion && coincide(t, "\\b(?:alcan\\w*|presupuest\\w*|dinero)\\b"));
    }

    /** Reservas solo queda disponible después de consultar los especialistas de información solicitados. */
    public List<Agente> pendientes(Set<Agente> consultados) {
        List<Agente> pendientes = new ArrayList<>();
        if (clima && !consultados.contains(Agente.CLIMA)) pendientes.add(Agente.CLIMA);
        if (dinero && !consultados.contains(Agente.PRESUPUESTO)) pendientes.add(Agente.PRESUPUESTO);
        if (actividades && !consultados.contains(Agente.ACTIVIDADES)) pendientes.add(Agente.ACTIVIDADES);
        if (pendientes.isEmpty() && reservas && !consultados.contains(Agente.RESERVAS)) pendientes.add(Agente.RESERVAS);
        return pendientes;
    }

    private static boolean coincide(String t, String regex) { return Pattern.compile(regex).matcher(t).find(); }

    private static Integer numero(String t, String regex) {
        var matcher = Pattern.compile(regex).matcher(t);
        if (!matcher.find()) return null;
        try { return Integer.valueOf(matcher.group(1).replace(".", "").replace(",", "")); }
        catch (NumberFormatException e) { return null; }
    }
}
