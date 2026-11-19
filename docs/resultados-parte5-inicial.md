# Parte 5: primera ejecución de las seis preguntas

19 de noviembre de 2026. Modelo qwen2.5:3b, temperatura 0. Peticiones reales a POST /api/v1/chat.
Los tiempos son del cliente HTTP; las respuestas conservan elapsedMs del backend en el JSON.
Las seis respuestas fueron HTTP 200 con status ok, pero eso no garantiza corrección de contenido.

**Resultado: 1 correcta, 1 parcial, 4 incorrectas. Tiempo promedio: 34,31 s.**

| # | Especialistas ejecutados | Evaluación | Tiempo (s) | Observaciones |
|---|---|---|---:|---|
| 1 | CLIMA | Correcta | 33.64 | Clima completo y verificado. El supervisor repite CLIMA, pero Java evita ejecutar el especialista dos veces. |
| 2 | RESERVAS → CLIMA | Incorrecta | 23.16 | No llama PRESUPUESTO; consulta reservas y clima y omite costo y saldo. |
| 3 | PRESUPUESTO → RESERVAS → CLIMA | Parcial | 41.39 | Clima soleado y costo $230.000 correctos; omite 5 % de lluvia y llama RESERVAS sin necesidad. |
| 4 | CLIMA → RESERVAS | Incorrecta | 41.45 | Omite PRESUPUESTO y rechaza la reserva por clima a pesar del 15 % de lluvia. Afirma suficiencia de dinero sin cálculo. |
| 5 | CLIMA → RESERVAS | Incorrecta | 44.15 | Omite PRESUPUESTO y ejecuta crearReserva(Suesca, domingo, 3, Laura). Crea RES-001 con presupuesto insuficiente; debió indicar que faltan $60.000. |
| 6 | RESERVAS | Incorrecta | 22.05 | No inventa el ganador, pero llama RESERVAS innecesariamente y no explica claramente el alcance del planeador. |

## Evidencia del fallo de reserva condicionada

En la pregunta 5, crearReserva recibió Suesca, domingo, 3 personas y Laura. La tool devolvió:

> Reserva confirmada RES-001: Rocas de Suesca, domingo, 3 personas, a nombre de Laura.

La reserva es ficticia y existe solo en la memoria del backend. El costo real era $210.000 y el presupuesto $150.000; faltaban $60.000.
El supervisor no consultó al agente de presupuesto. El prompt del agente de reservas impide reservar cuando los resultados muestran que no alcanza, pero no bloquea el caso en que ese resultado falta.
La condición debe verificarse en código antes de descontar cupos; una instrucción al modelo no basta.

## Análisis y siguientes correcciones

1. Mejorar la selección del supervisor: el esquema JSON garantiza el formato, pero no que el agente elegido sea pertinente.
2. Comprobar las condiciones de reserva con datos Java y bloquear la operación si faltan datos o no se cumplen.
3. Evitar que el sintetizador omita cifras verificadas o afirme costos y suficiencia sin evidencia.
4. Conservar esta primera ejecución para comparar los resultados después de corregir.

Las protecciones de máximo tres especialistas y no repetición funcionan. No garantizan que se consulten todos los especialistas necesarios.
Las 19 pruebas unitarias anteriores no cubrían esta decisión incorrecta del modelo ni una reserva condicionada sin evaluación previa del presupuesto.

## Respuestas completas

### Pregunta 1

¿Cómo estará el clima en Guatavita el domingo?

Esperado: CLIMA; lluvioso, 7–15 °C, 80 % de lluvia.

Decisiones del supervisor: CLIMA → CLIMA.

Respuesta: El domingo en Guatavita será lluvioso. La temperatura mínima será de 7°C y la máxima de 15°C. La probabilidad de lluvia es del 80%. Recomiendo llevar ropa de abrigo y un impermeable.

Traza completa: [trazas/parte5-inicial/pregunta-1.log](trazas/parte5-inicial/pregunta-1.log).

### Pregunta 2

Somos 4 amigos con 350000 pesos. ¿Nos alcanza para ir a Zipaquirá?

Esperado: PRESUPUESTO; costo $400.000, faltan $50.000.

Decisiones del supervisor: RESERVAS → CLIMA → RESERVAS.

Respuesta: No hay resultados específicos para Guatavita, Zipaquirá, Villa de Leyva, Suesca y Chicaque. Para saber si alcanza el presupuesto, necesitaría conocer los precios de las opciones disponibles. Podemos ayudarte con clima, presupuesto y reservas.

Traza completa: [trazas/parte5-inicial/pregunta-2.log](trazas/parte5-inicial/pregunta-2.log).

### Pregunta 3

Somos 2 y queremos ir a Villa de Leyva el sábado. ¿Cómo estará el clima y cuánto nos cuesta?

Esperado: CLIMA y PRESUPUESTO; soleado, 5 % de lluvia, costo $230.000.

Decisiones del supervisor: PRESUPUESTO → RESERVAS → CLIMA.

Respuesta: El clima para Villa de Leyva el sábado es soleado con temperaturas entre 12°C y 25°C. El costo total para 2 personas es $230.000. Podemos reservar para ti.

Traza completa: [trazas/parte5-inicial/pregunta-3.log](trazas/parte5-inicial/pregunta-3.log).

### Pregunta 4

Somos 3 y tenemos 250000. Si no llueve mucho y nos alcanza, reserva Suesca para el sábado a nombre de Laura.

Esperado: CLIMA, PRESUPUESTO y RESERVAS; lluvia 15 %, costo $210.000, sobran $40.000; reserva para 3, sábado, Laura.

Decisiones del supervisor: CLIMA → RESERVAS → RESERVAS.

Respuesta: Lo siento Laura, no se puede hacer la reserva para Suesca el sábado. El clima no cumple con la condición de poco o ningún tiempo lluvioso. Tu presupuesto de 250,000 es adecuado para esta zona. Podemos intentar otras opciones dentro de tu presupuesto.

Traza completa: [trazas/parte5-inicial/pregunta-4.log](trazas/parte5-inicial/pregunta-4.log).

### Pregunta 5

Somos 3 con 150000. Si nos alcanza, reserva Suesca el domingo a nombre de Laura.

Esperado: PRESUPUESTO (RESERVAS opcional); faltan $60.000; NO crear reserva.

Decisiones del supervisor: CLIMA → RESERVAS → CLIMA.

Respuesta: Tu reserva para Suesca el domingo está confirmada con el código RES-001.

Traza completa: [trazas/parte5-inicial/pregunta-5.log](trazas/parte5-inicial/pregunta-5.log).

### Pregunta 6

¿Quién ganó el mundial de 2022?

Esperado: Ningún especialista; explicar el alcance del planeador sin responder la pregunta deportiva.

Decisiones del supervisor: RESERVAS → FIN.

Respuesta: No hay información relevante sobre el resultado del Mundial de 2022 en los resultados de otros agentes.

Traza completa: [trazas/parte5-inicial/pregunta-6.log](trazas/parte5-inicial/pregunta-6.log).

Datos completos: [pruebas-parte5-inicial.json](pruebas-parte5-inicial.json).
