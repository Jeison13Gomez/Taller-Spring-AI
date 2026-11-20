# Parte 5: resultados después de corregir

19 de noviembre de 2026. Modelo qwen2.5:3b, temperatura 0. API real POST /api/v1/chat.
**Seis de seis preguntas correctas en esta ejecución. Las 34 pruebas aisladas del planeador pasan.**

Las reglas Java corrigen la selección del supervisor, validan las condiciones de reserva con la pregunta original y conservan los resultados exactos de las herramientas.
El modelo puede seguir proponiendo una ruta equivocada: en la pregunta 2 eligió RESERVAS y Java lo sustituyó por PRESUPUESTO. No se atribuye esa corrección al modelo.

| # | Antes | Después | Especialistas ejecutados | Tiempo final (s) |
|---|---|---|---|---:|
| 1 | Correcta | Correcta | CLIMA | 33.41 |
| 2 | Incorrecta | Correcta | PRESUPUESTO | 64.80 |
| 3 | Parcial | Correcta | PRESUPUESTO → CLIMA | 63.19 |
| 4 | Incorrecta | Correcta | PRESUPUESTO → CLIMA → RESERVAS | 173.60 |
| 5 | Incorrecta | Correcta | PRESUPUESTO → RESERVAS | 78.43 |
| 6 | Incorrecta | Correcta | ninguno | 0.01 |

Promedio final: 68.91 s. El caso con tres especialistas tardó 173.60 s, dentro de los 300 s configurados.
El promedio inicial fue 34,31 s, pero varias consultas omitían agentes o no completaban la operación; no es una comparación de rendimiento entre flujos equivalentes.

## Evidencia del arreglo de reservas

- Pregunta 4: se ejecutaron presupuesto, clima y reservas; crearReserva confirmó RES-001 para Suesca, sábado, tres personas, Laura. Costo $210.000, sobran $40.000, lluvia 15 %.
- Pregunta 5: el modelo llamó crearReserva, pero la herramienta devolvió «No se creó una reserva: costo total $210.000, presupuesto $150.000; faltan $60.000.». La validación ocurre antes de descontar cupos.
- Las pruebas unitarias comprueban que el caso rechazado mantiene seis cupos el domingo y que el permitido deja cinco el sábado. También prueban que una llamada repetida en la misma petición no descuenta dos veces.

## Cambios respecto a la primera ejecución

1. Selección validada: un agente debe corresponder a las intenciones reconocidas y no haberse ejecutado. Reservas queda detrás de clima y presupuesto cuando se necesitan.
2. ToolContext: conserva la petición original y la evidencia por solicitud; ese objeto no forma parte del esquema que el modelo puede rellenar.
3. Guardia de reservas: comprueba la solicitud explícita, los datos del grupo y las condiciones con precios y pronósticos Java. No confía en el resumen de otro agente ni en montos inventados.
4. Regla explícita del ejercicio: «no llueve mucho» acepta hasta 40 % de probabilidad; «no llueve» exige 0 %. Las condiciones desconocidas requieren aclaración.
5. Síntesis validada: si una reformulación no conserva íntegramente la evidencia, se devuelven los textos de las tools. Es una política conservadora que sacrifica libertad de redacción para no omitir ni cambiar datos.

## Límites de lo verificado

Esta batería cubre las seis preguntas del taller, no todas las formulaciones posibles en español. La extracción por patrones tiene vocabulario limitado y no interpreta cualquier paráfrasis.
Los tests del chat anterior siguen fuera de esta batería por su constructor RAG incompatible. Las 34 pruebas corresponden solo al planeador.
El reinicio del backend al reconstruir restableció los datos simulados antes de la ejecución final. No hay persistencia ni memoria conversacional.
La protección contra repetición de reservas se aplica a una sola petición; una petición distinta puede crear otra reserva.
La clasificación de fuera de alcance y la corrección de ruta están apoyadas en reglas Java; el supervisor conserva la propuesta LLM y su JSON estructurado.

## Respuestas y trazas

### Pregunta 1

¿Cómo estará el clima en Guatavita el domingo?

Laguna de Guatavita el domingo: lluvioso, entre 7 °C y 15 °C, probabilidad de lluvia 80 %.

Propuestas del supervisor: CLIMA.
Correcciones Java: ninguna.
Traza completa: [trazas/parte5-corregida-final/pregunta-1.log](trazas/parte5-corregida-final/pregunta-1.log).

### Pregunta 2

Somos 4 amigos con 350000 pesos. ¿Nos alcanza para ir a Zipaquirá?

Catedral de Sal de Zipaquirá: por persona transporte $20.000 + entrada $50.000 + alimentación $30.000 = $100.000. Costo total para 4 personas: $400.000.
No alcanza: presupuesto $350.000 - costo $400.000 = faltan $50.000.

Propuestas del supervisor: RESERVAS.
Correcciones Java: [('RESERVAS', 'PRESUPUESTO')].
Traza completa: [trazas/parte5-corregida-final/pregunta-2.log](trazas/parte5-corregida-final/pregunta-2.log).

### Pregunta 3

Somos 2 y queremos ir a Villa de Leyva el sábado. ¿Cómo estará el clima y cuánto nos cuesta?

Villa de Leyva: por persona transporte $70.000 + entrada $0 + alimentación $45.000 = $115.000. Costo total para 2 personas: $230.000.
Villa de Leyva el sabado: soleado, entre 12 °C y 25 °C, probabilidad de lluvia 5 %.

Propuestas del supervisor: PRESUPUESTO → CLIMA.
Correcciones Java: ninguna.
Traza completa: [trazas/parte5-corregida-final/pregunta-3.log](trazas/parte5-corregida-final/pregunta-3.log).

### Pregunta 4

Somos 3 y tenemos 250000. Si no llueve mucho y nos alcanza, reserva Suesca para el sábado a nombre de Laura.

Rocas de Suesca: por persona transporte $25.000 + entrada $15.000 + alimentación $30.000 = $70.000. Costo total para 3 personas: $210.000.
Sí alcanza: presupuesto $250.000 - costo $210.000 = sobran $40.000.
Rocas de Suesca el sabado: soleado, entre 7 °C y 21 °C, probabilidad de lluvia 15 %.
Reserva confirmada RES-001: Rocas de Suesca, sabado, 3 personas, a nombre de Laura.

Propuestas del supervisor: PRESUPUESTO → CLIMA → RESERVAS.
Correcciones Java: ninguna.
Traza completa: [trazas/parte5-corregida-final/pregunta-4.log](trazas/parte5-corregida-final/pregunta-4.log).

### Pregunta 5

Somos 3 con 150000. Si nos alcanza, reserva Suesca el domingo a nombre de Laura.

Rocas de Suesca: por persona transporte $25.000 + entrada $15.000 + alimentación $30.000 = $70.000. Costo total para 3 personas: $210.000.
No alcanza: presupuesto $150.000 - costo $210.000 = faltan $60.000.
Rocas de Suesca el domingo: quedan 6 cupos.
No se creó una reserva: costo total $210.000, presupuesto $150.000; faltan $60.000.

Propuestas del supervisor: PRESUPUESTO → RESERVAS.
Correcciones Java: ninguna.
Traza completa: [trazas/parte5-corregida-final/pregunta-5.log](trazas/parte5-corregida-final/pregunta-5.log).

### Pregunta 6

¿Quién ganó el mundial de 2022?

Puedo ayudarte con clima, presupuesto y reservas para Guatavita, Zipaquirá, Villa de Leyva, Suesca y Chicaque.

Propuestas del supervisor: no se llamó al modelo.
Correcciones Java: ninguna.
Traza completa: [trazas/parte5-corregida-final/pregunta-6.log](trazas/parte5-corregida-final/pregunta-6.log).

Datos completos: [pruebas-parte5-corregida-final.json](pruebas-parte5-corregida-final.json).
La primera ejecución se conserva en [resultados-parte5-inicial.md](resultados-parte5-inicial.md).
