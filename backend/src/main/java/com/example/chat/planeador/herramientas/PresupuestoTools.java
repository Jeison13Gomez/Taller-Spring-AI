package com.example.chat.planeador.herramientas;

import com.example.chat.planeador.datos.DatosPlaneador;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.ai.chat.model.ToolContext;
import com.example.chat.planeador.RegistroHerramientas;

import static com.example.chat.planeador.datos.DatosPlaneador.pesos;

/** Tools del AGENTE DE PRESUPUESTO: las cuentas las hace Java, no el modelo. */
@Component
public class PresupuestoTools {

    private final DatosPlaneador datos;

    public PresupuestoTools(DatosPlaneador datos) {
        this.datos = datos;
    }

    @Tool(description = "Calcula el costo total de ir a un destino (transporte, entrada y alimentación) "
            + "para un número de personas.")
    public String estimarCosto(
            @ToolParam(description = "Nombre del destino") String destino,
            @ToolParam(description = "Número de personas del grupo") int personas, ToolContext context) {
        return RegistroHerramientas.guardar(context, estimarCosto(destino, personas));
    }

    public String estimarCosto(String destino, int personas) {
        String clave = datos.buscarDestino(destino);
        if (clave == null) {
            return "No conozco el destino '%s'. Destinos válidos: %s.".formatted(destino, datos.destinosValidos());
        }
        if (personas <= 0) {
            return "El número de personas debe ser mayor que cero.";
        }
        DatosPlaneador.Destino c = datos.destino(clave);
        int total = c.costoPorPersona() * personas;
        return "%s: por persona transporte %s + entrada %s + alimentación %s = %s. Costo total para %d personas: %s."
                .formatted(c.nombre(), pesos(c.transporte()), pesos(c.entrada()), pesos(c.alimentacion()),
                        pesos(c.costoPorPersona()), personas, pesos(total));
    }

    @Tool(description = "Compara el presupuesto total del grupo con el costo del destino para esas personas. "
            + "La herramienta obtiene el costo real de los datos: no necesitas calcularlo ni adivinarlo.")
    public String calcularSaldo(
            @ToolParam(description = "Nombre del destino") String destino,
            @ToolParam(description = "Número de personas del grupo") int personas,
            @ToolParam(description = "Presupuesto TOTAL del grupo en pesos, sin puntos ni signos") int presupuesto, ToolContext context) {
        return RegistroHerramientas.guardar(context, calcularSaldo(destino, personas, presupuesto));
    }

    public String calcularSaldo(String destino, int personas, int presupuesto) {
        String clave = datos.buscarDestino(destino);
        if (clave == null) {
            return "No conozco el destino '%s'. Destinos válidos: %s.".formatted(destino, datos.destinosValidos());
        }
        if (personas <= 0) {
            return "El número de personas debe ser mayor que cero.";
        }
        int costoTotal = datos.destino(clave).costoPorPersona() * personas;
        return calcularSaldo(presupuesto, costoTotal);
    }

    /** Cálculo puro conservado para las pruebas; el modelo usa la variante por destino. */
    public String calcularSaldo(int presupuesto, int costoTotal) {
        if (presupuesto < 0 || costoTotal < 0) {
            return "El presupuesto y el costo total no pueden ser negativos.";
        }
        int saldo = presupuesto - costoTotal;
        if (saldo >= 0) {
            return "Sí alcanza: presupuesto %s - costo %s = sobran %s."
                    .formatted(pesos(presupuesto), pesos(costoTotal), pesos(saldo));
        }
        return "No alcanza: presupuesto %s - costo %s = faltan %s."
                .formatted(pesos(presupuesto), pesos(costoTotal), pesos(-saldo));
    }
}
