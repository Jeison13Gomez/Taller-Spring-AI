# Proyección de costos (punto 2.6)

Estimación con las mediciones reales de los experimentos, aplicada al precio público de un modelo comercial de gama media. El modelo local (`llama3.2:3b`) no tiene costo por token; este cálculo es solo comparativo.

## 1. Precio usado

| Modelo | Entrada | Salida |
|---|---|---|
| Claude Sonnet 5.5 (gama media) | 2 USD por millón de tokens | 10 USD por millón de tokens |

- **Fuente:** tabla "Model pricing" de la página oficial de precios de Anthropic, https://platform.claude.com/docs/en/about-claude/pricing
- **Fecha de consulta:** 2026-10-26.
- Son los precios base (columnas "Base input tokens" y "Output tokens"), sin caché de prompt, sin descuento por lotes y con enrutamiento global.
- Se eligió Sonnet 5.5 por ser el modelo de gama media de esa familia (Haiku es la gama baja y Opus/Fable las altas).

## 2. Mediciones usadas

| Dato | Valor | Origen |
|---|---|---|
| Tokens fijos por petición (prompt de sistema + plantilla del chat) | 68 | `promptTokens` de un mensaje de 1 token ("a") = 69, repetido 3 veces; 69 − 1 |
| Pregunta larga del experimento C | 93 tokens de entrada (25 propios + 68 fijos) | `promptTokens` medido 3 veces |
| Respuesta larga (experimento C, `numPredict` 1000) | 627 tokens de salida de media (583, 626 y 672) | `experimento_c.csv` |
| Pregunta corta del experimento A | 85 tokens de entrada (17 propios + 68 fijos) | `promptTokens` medido 3 veces |
| Respuesta corta (experimento A) | 4,55 tokens de salida de media | `experimento_a.csv`, 40 ejecuciones |

Los 68 tokens incluyen el texto del prompt de sistema y las marcas de la plantilla del chat; no se pueden separar con estas mediciones, por lo que el porcentaje del prompt de sistema es un máximo.

## 3. Supuestos

- Una conversación son **10 turnos**; el mes tiene **30 días**: 1000 usuarios × 5 conversaciones/día × 30 = **150.000 conversaciones al mes**.
- **Escenario largo:** cada turno es como el del experimento C (93 tokens de entrada, 627 de salida).
- **Escenario corto:** cada turno es como el del experimento A (85 de entrada, 4,55 de salida).
- **Sin memoria (como está hoy la aplicación):** cada turno se envía solo, sin historial.
- **Con memoria (hipótesis, aún no implementada):** cada turno vuelve a enviar todo el historial. La entrada del turno k es 68 + 25 + (k − 1) × (25 + 627) tokens; en 10 turnos suma 30.270 tokens de entrada.
- Los tokens se midieron con el tokenizador de Llama. Otro modelo cuenta distinto el mismo texto, así que el costo real puede variar; es una aproximación.

## 4. Costo de una conversación de 10 turnos y costo mensual

Fórmula: costo = (tokens de entrada × 2 + tokens de salida × 10) / 1.000.000 USD.

| Escenario | Entrada (10 turnos) | Salida (10 turnos) | Costo por conversación | Costo mensual (150.000 conv.) |
|---|---|---|---|---|
| Largo, sin memoria | 930 | 6.270 | **0,06456 USD** | **9.684 USD** |
| Corto, sin memoria | 850 | 45,5 | **0,002155 USD** | **323,25 USD** |
| Largo, con memoria (hipótesis) | 30.270 | 6.270 | **0,12324 USD** | **18.486 USD** |

Casi todo el costo está en los tokens de salida: en el escenario largo, la salida es 0,0627 USD de los 0,06456 USD de la conversación.

## 5. Parte del costo que se lleva el prompt de sistema repetido

Se repiten 68 tokens en cada petición (máximo, ver sección 2).

| Escenario | Parte de los tokens de entrada | Parte del costo del turno |
|---|---|---|
| Largo, sin memoria | 68 / 93 = **73,1 %** | 136 / 6.456 µUSD = **2,1 %** |
| Corto, sin memoria | 68 / 85 = **80,0 %** | 136 / 215,5 µUSD = **63,1 %** |
| Largo, con memoria | 680 / 30.270 = **2,2 %** | 1.360 / 123.240 µUSD = **1,1 %** |

El prompt de sistema es casi todo lo que se paga en la entrada, pero solo pesa en el costo total cuando las respuestas son muy cortas. Con respuestas largas, la salida lo oculta.

## 6. Bajar `numPredict` de 1024 a 512

Las tres respuestas largas medidas (583, 626 y 672 tokens) superan los 512, así que **las tres quedarían cortadas** (`finishReason: "length"`). Con 1024 el modelo terminaba solo, con `stop`.

| Escenario | Salida por turno | Costo por conversación | Ahorro por conversación | Ahorro mensual |
|---|---|---|---|---|
| Largo, sin memoria | 627 → 512 (−115 tokens, −18,3 %) | 0,06456 → 0,05306 USD | 0,0115 USD (17,8 %) | **1.725 USD** |
| Largo, con memoria | 627 → 512 | 0,12324 → 0,10139 USD | 0,02185 USD (17,7 %) | **3.277,50 USD** |
| Corto | 4,55 (no llega al límite) | sin cambio | 0 | 0 |

- **Qué se pierde:** el final de cada respuesta larga. La explicación llega cortada, sin la conclusión, y el usuario no ve que está incompleta salvo por el `finishReason`.
- **Qué no se pierde:** nada en las respuestas cortas, que no llegan a 512.
- **Riesgo:** si el usuario pide que continúe o repite la pregunta, se paga otra petición y el ahorro se reduce.
- El ahorro real depende de cuántas respuestas superen 512 tokens; los valores de la tabla son para el caso en que todas lo hacen.

## 7. Cómo reproducir

Los datos salen de `experimentos/resultados/experimento_a.csv` y `experimento_c.csv`. Los `promptTokens` se midieron enviando cada pregunta con `numPredict: 1` y leyendo `usage.promptTokens`.
