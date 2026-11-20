package com.example.chat.planeador.herramientas;

import com.example.chat.planeador.RegistroHerramientas;
import com.example.chat.planeador.datos.DatosPlaneador;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** Catálogo ficticio del reto: no consulta servicios externos ni asigna costos adicionales. */
@Component
public class ActividadesTools {
    private final DatosPlaneador datos;
    private final Map<String, List<String>> actividades = Map.of(
            "guatavita", List.of("caminata interpretativa", "observación del paisaje", "fotografía"),
            "zipaquira", List.of("recorrido cultural", "fotografía arquitectónica", "paseo por el casco urbano"),
            "villa de leyva", List.of("caminata por el casco histórico", "visita a la plaza principal", "fotografía"),
            "suesca", List.of("senderismo", "observación de formaciones rocosas", "fotografía del paisaje"),
            "chicaque", List.of("caminata por senderos", "observación de aves", "fotografía de naturaleza")
    );

    public ActividadesTools(DatosPlaneador datos) { this.datos = datos; }

    @Tool(description = "Recomienda actividades de un catálogo SIMULADO para un destino. No calcula precios ni crea reservas.")
    public String recomendarActividades(
            @ToolParam(description = "Nombre del destino, por ejemplo Suesca") String destino,
            ToolContext context) {
        return RegistroHerramientas.guardar(context, recomendarActividades(destino));
    }

    public String recomendarActividades(String destino) {
        String clave = datos.buscarDestino(destino);
        if (clave == null) {
            return "No conozco el destino '%s'. Destinos válidos: %s.".formatted(destino, datos.destinosValidos());
        }
        return "%s. Actividades simuladas: %s."
                .formatted(datos.destino(clave).nombre(), String.join("; ", actividades.get(clave)));
    }
}
