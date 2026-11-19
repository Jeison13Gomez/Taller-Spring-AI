# Backend (Spring Boot)

This directory will contain the Spring Boot 3.5 backend (Java 25) with Spring AI
integration. Use Gradle (wrapper) as the build tool. The ChatController and ModelService live under src/main/java/com/example/chat/.


## Metricas de uso (feature 003)

`POST /api/v1/chat` devuelve, ademas de `answer`, `elapsedMs` y `status`, un objeto `usage`
con las metricas de esa respuesta:

| Campo | Significado |
|-------|-------------|
| `promptTokens` | Tokens de entrada de la consulta |
| `completionTokens` | Tokens generados en la respuesta |
| `totalTokens` | `promptTokens + completionTokens` (null si falta alguno de los dos) |
| `model` | Modelo que produjo la respuesta |
| `finishReason` | Motivo de terminacion (`stop`, `length`, ...) |
| `tokensPerSecond` | `completionTokens` / tiempo de generacion del modelo (sin carga del modelo ni procesamiento del prompt), con 2 decimales |

`null` en cualquier campo significa "no disponible"; nunca se sustituye por 0. En las respuestas
de error (`status: "error"`), `usage` es `null`. Contrato completo:
[specs/003-token-usage-metrics/contracts/chat-api.md](../specs/003-token-usage-metrics/contracts/chat-api.md).

## Parametros de generacion por peticion (feature 004)

`POST /api/v1/chat` acepta, ademas de `question` y `sessionId`, cinco campos opcionales para
controlar la generacion de esa peticion (no afectan a otras y no requieren reiniciar el backend):

| Campo | Valores permitidos (inclusivos) | Si no se envia |
|-------|---------------------------------|----------------|
| `temperature` | numero 0.0 - 2.0 | 0.7 |
| `topP` | numero 0.0 - 1.0 | valor del modelo |
| `topK` | entero 0 - 200 | valor del modelo |
| `numPredict` | entero 1 - 2048 | 1024 |
| `seed` | entero de 32 bits con signo | valor del modelo (aleatorio) |

Un campo ausente o `null` significa "no enviado"; `0` / `0.0` es un valor real. Con una peticion sin
estos campos todo se comporta como antes.

Un valor fuera de rango o no entero devuelve HTTP 400 antes de llamar al modelo, con `status: "error"`,
`usage: null` y una lista `errors` con un elemento `{ "field", "message" }` por cada campo invalido:

```json
{ "status": "error", "usage": null,
  "errors": [ { "field": "temperature", "message": "temperature must be between 0.0 and 2.0" } ] }
```

Limitacion: si un valor es de tipo incorrecto (por ejemplo `"temperature": "hot"`) o el JSON esta mal
formado, solo se informa del primer campo ilegible (o de `body`); los demas errores aparecen al corregirlo.
Contrato completo:
[specs/004-chat-sampling-params/contracts/chat-api.md](../specs/004-chat-sampling-params/contracts/chat-api.md).

## Clasificacion estructurada (feature 005)

`POST /api/v1/classify` recibe `{ "text": "...", "strategy": "validate-retry", "temperature": 0.0 }`
(`strategy` y `temperature` son opcionales) y devuelve `result` con `category` (`consulta`, `queja`,
`incidencia` u `otro`), `confidence` (0.0 a 1.0) y `justification`, ademas de `strategy`, `retried`,
`retries`, `attempts`, `usage` y `elapsedMs`. El texto admite hasta 5000 caracteres. Un valor fuera de
rango, una estrategia desconocida, un texto vacio o demasiado largo devuelve HTTP 400 con la lista `errors`
(en el orden `text`, `strategy`, `temperature`).

| `strategy` | Que hace | Reintentos |
|------------|----------|------------|
| `instruction` (por defecto) | Pide el formato en el prompt y valida la respuesta | 0 (`attempts` 1) |
| `validate-retry` | Igual, pero si la respuesta no es valida se lo dice al modelo y repite | hasta 2 (`attempts` = `retries` + 1) |
| `native-schema` | Envia el esquema JSON a Ollama (`outputSchema`) sin instruccion de formato en el prompt | 0 (`attempts` 1) |

Si tras los intentos el modelo no cumple el formato, la respuesta es **HTTP 502** con un objeto `failure`
(`check`: `json`, `category`, `confidence` o `justification`, y `message`) y un objeto `consumed` con los
tokens y el tiempo de los intentos fallidos, sumados. Un fallo del servicio del modelo (no accesible, error
HTTP) sigue respondiendo como en el chat, sin objeto `failure`: asi se distingue un fallo de formato de un
fallo de servicio.
Contrato completo:
[specs/005-prompt-templates-structured-output/contracts/chat-api.md](../specs/005-prompt-templates-structured-output/contracts/chat-api.md).

Limitacion: en `/api/v1/classify`, un valor de tipo incorrecto (por ejemplo `"temperature": "hot"`) o un JSON
mal formado devuelve HTTP 400 con el cuerpo de error **del chat** (`requestId`, `answer`, `elapsedMs`, `status`,
`usage` y `errors`) y no con la forma de la clasificacion, porque la peticion no llega a leerse y responde el
manejador comun. Hay que leer `status` y `errors` e ignorar `answer`, `elapsedMs` y `usage`. Solo se informa del
primer campo ilegible (o de `body`).
## Plantillas de prompt (feature 005)

Las plantillas viven en `src/main/resources/prompts/*.st` (`default`, `concise`, `tutor`, `extractor`); el id
es el nombre del archivo sin extension y debe cumplir `[a-z0-9-]+`. Usan los delimitadores `<` y `>`
(`<max_sentences>`). Se cargan y validan al arrancar: si falta `default`, hay un id repetido o invalido, o una
plantilla tiene una variable sin valor, el backend no arranca y el error nombra la plantilla.

**Un cambio en una plantilla exige reconstruir la imagen del backend** (`docker compose -f docker/docker-compose.yml up -d --build`):
los archivos van dentro del jar, no se leen del disco en tiempo de ejecucion.

Los valores de las variables salen solo de `application.properties` (`app.prompts.variables.*`) y ninguna
peticion puede enviarlos:

| Propiedad | Valor actual |
|-----------|--------------|
| `app.prompts.variables.max_sentences` | `2` |
| `app.prompts.variables.audience` | `estudiante de secundaria` |

`POST /api/v1/chat` acepta el campo opcional `templateId` con uno de los ids registrados. Si se omite se usa
`default` (mismo comportamiento de siempre) y la respuesta 200 no cambia nunca: no dice que plantilla se uso.
Un id desconocido devuelve HTTP 400 antes de llamar al modelo, con `errors` `{ "field": "templateId", "message":
"unknown template 'pirata'; available: concise, default, extractor, tutor" }`. La comparacion es exacta
(`Concise` y `concise ` no existen) y el id se repite en el mensaje con un maximo de 40 caracteres.

Limitacion: **una plantilla no puede contener un `<` suelto** (que no cierre una variable `<nombre>`): el motor
lo interpreta como el inicio de una variable y la validacion de arranque rechaza la plantilla. Hay que evitar
el simbolo o reescribir el texto (por ejemplo "menor que").
Contrato completo:
[specs/005-prompt-templates-structured-output/contracts/chat-api.md](../specs/005-prompt-templates-structured-output/contracts/chat-api.md).

## Memoria de conversacion (feature 006)

`POST /api/v1/chat` recuerda los turnos de una conversacion si la peticion lleva el campo opcional `sessionId`;
todas las peticiones con el mismo `sessionId` comparten historial y las de ids distintos no se mezclan nunca.

- **Regla del `sessionId`**: de 1 a 64 caracteres entre letras, digitos, guion y guion bajo
  (`[A-Za-z0-9_-]{1,64}`); distingue mayusculas (`Ana` y `ana` son sesiones distintas). Si falta o es `null`, la
  peticion funciona como antes: **sin memoria**. Una cadena vacia, de solo espacios, de mas de 64 caracteres o con
  otros caracteres devuelve HTTP 400 antes de llamar al modelo, con `errors` `{ "field": "sessionId", ... }`
  (`sessionId` es el primer campo en el orden de `errors`).
- **`historyMessages`**: la respuesta 200 incluye este campo nuevo, el numero de mensajes de historial
  (preguntas y respuestas, sin las instrucciones de la plantilla ni la pregunta actual) enviados al modelo en esa
  peticion. Es 0 sin `sessionId` y en el primer turno de una sesion, y nunca supera la ventana. Los demas campos
  no cambian; `promptTokens` sigue siendo lo que el modelo proceso, que ahora incluye el historial.
- **Plantillas**: el mensaje de sistema sale de la plantilla de cada turno (`templateId`) y no se guarda como
  historial; cambiar de plantilla a mitad de sesion conserva el historial y aplica solo las instrucciones de la nueva.
- **Propiedades** (en `application.properties`):

| Propiedad | Por defecto | Que hace |
|-----------|-------------|----------|
| `app.memory.max-messages` | `20` | mensajes de historial que se conservan por sesion; los mas antiguos se descartan primero. El minimo es 2: un valor menor impide arrancar el backend con un error que nombra la propiedad |
| `app.memory.idle-timeout` | `30m` | una sesion sin uso durante ese tiempo se olvida |
| `app.memory.max-sessions` | `100` | numero maximo de sesiones; al superarlo se descarta la menos usada |

- **Solo en RAM**: el historial no se guarda en disco y **se pierde al reiniciar el backend**.
- **Limite explicito: no hay autenticacion.** La unica proteccion de una sesion es que su id no se adivine:
  ningun endpoint devuelve el historial ni lista los ids, pero quien conozca un id puede continuar esa
  conversacion, y `demo` es facil de adivinar. Elegir ids dificiles (por ejemplo un UUID) es cosa del cliente. La
  autenticacion real queda para la feature de guardrails.
- **`/api/v1/classify` no tiene memoria**: no usa `sessionId` y cada clasificacion es independiente.

### Ventana de contexto del modelo (`num-ctx`)

La ventana de 20 mensajes no garantiza que el historial quepa en el contexto del modelo. El tamano del contexto es
`spring.ai.ollama.chat.num-ctx`, que aqui vale **8192** por defecto; se cambia con la variable de entorno
`SPRING_AI_OLLAMA_CHAT_NUM_CTX` (la lee `docker/docker-compose.yml`) y hay que reiniciar el backend:

```
$env:SPRING_AI_OLLAMA_CHAT_NUM_CTX = '4096'
docker compose -f docker/docker-compose.yml up -d
```

El valor propio de Ollama parece ser 4096 (es una inferencia, no una cifra publicada: sin `num-ctx` un prompt muy
largo dio la misma cifra que con 4096 explicito).

Que pasa cuando el prompt no cabe (medido en la feature, experimentos E y de truncado, con `llama3.2:3b`):

- En una conversacion que crece, Ollama **quita mensajes antiguos de uno en uno** (unos 525 tokens por paso) y
  responde 200 **sin avisar**. La senal esta en las cifras de la respuesta: `promptTokens` **se estanca** (meseta,
  variacion menor al 1% entre turnos) mientras `historyMessages` **sigue subiendo**. En el experimento E la meseta
  empezo en el turno 5 con 2048 (1791 tokens) y en el turno 9 con 4096 (3885 tokens).
- Un unico prompt enorme es otro caso: se reduce a la mitad de `num-ctx` mas 2 tokens (1026 con 2048, 2050 con 4096,
  4098 con 8192; dato de T013) y tambien responde 200 sin aviso.
- Con `num-ctx` 8192 y la ventana de 20 mensajes **no hubo truncamiento en nuestras pruebas**: en el experimento E
  `promptTokens` crecio hasta 5458 en el turno 11 y se estabilizo solo porque la ventana de 20 mensajes se lleno
  (`historyMessages` dejo de subir tambien). Con mensajes mucho mas largos que los del experimento, la meseta
  por contexto podria aparecer tambien con 8192.
- `historyMessages` es lo que el backend envio, no lo que Ollama proceso: no baja cuando Ollama recorta.

Reserva sobre el mensaje de sistema (T047): con la plantilla `extractor` y 2048, 4096 y 8192, todas las respuestas
(incluido un turno final corto despues de la meseta) siguieron teniendo las claves JSON que exige el mensaje de
sistema. Es **compatible con que el mensaje de sistema se conserve, pero no lo demuestra**: el historial reciente
contiene respuestas JSON previas y el modelo pudo imitar el patron aunque hubiera perdido la instruccion. Ademas, con
4096 solo hay dos turnos desde la meseta. Tampoco se sabe que parte del prompt conserva Ollama mas alla de las
cifras. Detalle en la nota "System message and truncation" de
[quickstart.md](../specs/006-conversation-memory/quickstart.md).

Contrato completo:
[specs/006-conversation-memory/contracts/chat-api.md](../specs/006-conversation-memory/contracts/chat-api.md).
