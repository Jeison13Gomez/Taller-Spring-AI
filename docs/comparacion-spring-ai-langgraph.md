# Parte 6: comparación de Spring AI y LangGraph

## Fuentes y criterio de comparación

- LangGraph: tabla aportada por el usuario, conservada en [la imagen original](capturas/langgraph-resultados-aportados.png). Se transcribieron sus ocho filas y no se reprodujeron esos ensayos aquí.
- Para los seis resultados finales de LangGraph se toman V2 en las preguntas 4 y 6. Las V1 no se descartan del registro: se mantienen como fallos anteriores.
- Spring AI: [seis peticiones reales verificadas](pruebas-parte5-corregida-final.json), con trazas por pregunta, y 34 pruebas aisladas.
- Las valoraciones de LangGraph son las reportadas en la tabla. Faltan las respuestas completas para reevaluarlas con el mismo detalle que Spring AI.
- El código de LangGraph configura qwen2.5:3b y temperatura 0; el modelo puede cambiar con MODELO_OLLAMA. Declara langgraph, langchain y langchain-ollama >=1.0,<2, sin fijar versiones exactas. Eso no confirma el modelo, versiones o hardware usados para los tiempos de la tabla. Spring usa tiempo del cliente HTTP; los scripts LangGraph cronometran invoke/stream con perf_counter, sin confirmar qué script originó cada fila.
- Código LangGraph revisado: [TallerLangGraph, revisión d9fc39fa6cd9](https://github.com/JuanDProg123/TallerLangGraph/tree/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e). Fue una revisión estática, sin ejecutar agentes ni usar su entorno .venv. No se ha vinculado esta revisión con los ensayos de la tabla.

## Resultados finales por pregunta

| # | LangGraph: respuesta | LangGraph: tiempo (s) | Spring AI: respuesta | Spring AI: tiempo (s) |
|---|---|---:|---|---:|
| 1 | Correcta | 23.97 | Correcta | 33.41 |
| 2 | Correcta | 24.30 | Correcta | 64.80 |
| 3 | Parcial | 39.44 | Correcta | 63.19 |
| 4 | Correcta (V2) | 36.70 | Correcta | 173.60 |
| 5 | Correcta | 36.34 | Correcta | 78.43 |
| 6 | Correcta (V2) | 18.19 | Correcta | 0.01 |
| **Promedio** | **5 correctas, 1 parcial** | **29,82** | **6 correctas** | **68,91** |

En esta muestra, los tiempos registrados de LangGraph fueron menores en las preguntas 1 a 5. Spring resolvió la pregunta 6 mediante reglas de alcance, sin llamar al modelo, lo que explica sus 0,014 s.
La implementación Spring final valida rutas y condiciones en Java y devuelve evidencia íntegra cuando la síntesis cambia datos. Esas diferencias de implementación impiden atribuir por sí solas los tiempos y aciertos al framework.

## Evolución de los intentos

| Implementación | Primer intento de cada pregunta | Resultado final registrado |
|---|---|---|
| LangGraph | 3 correctas, 1 parcial, 2 incorrectas | 5 correctas, 1 parcial |
| Spring AI | 1 correcta, 1 parcial, 4 incorrectas | 6 correctas |

En LangGraph, la pregunta 4 V1 terminó tras presupuesto y omitió clima y reservas; V2 consultó los tres y se reportó correcta. En la 6 V1 se llamó innecesariamente a clima y se inventó una respuesta; V2 no consultó especialistas y se reportó correcta.
En la pregunta 3 se conserva la valoración parcial: hubo información innecesaria al final. En la 5 se reporta respuesta correcta, aunque se consultó clima además de presupuesto; conviene separar calidad de respuesta de eficiencia del enrutamiento.

## Tabla de criterios del taller

| Criterio | LangGraph en el repositorio aportado | Spring AI (Parte 6, antes del reto) |
|---|---|---|
| Líneas del flujo y sus validaciones | **762 físicas**, 578 no vacías: grafo.py, agentes.py y config.py | **442 físicas**, 399 no vacías: Decision, AgentesConfig, PlaneadorOrquestador y tres clases de reglas/registro |
| Definición y visibilidad del flujo | StateGraph con cinco nodos, START/END, aristas condicionales y retorno de especialistas al supervisor; el plan se genera una vez y luego se consume | Ciclo while, enum, propuesta del supervisor en cada paso, validación Java y máximo de tres especialistas |
| Manejo del estado | EstadoPlaneador, un TypedDict con consulta, resultados, consultados, siguiente, respuesta_final y plan; operator.add acumula resultados/consultados. compile() no configura checkpointer | List y Set locales por petición y RegistroHerramientas en ToolContext; no hay memoria conversacional ni persistencia del planeador |
| Depuración y pruebas | Scripts con grafo.stream(stream_mode="updates"), impresión de nodos y agentes, perf_counter y comprobaciones de reservas/cupos con assert; no ejecutados en esta revisión | Logs de supervisor, tools y guardias; seis trazas reales y 34 pruebas aisladas aprobadas; los tests heredados de RAG siguen pendientes |
| Tiempo promedio por pregunta | 29,82 s (V2 para 4 y 6), según tabla aportada | 68,91 s, incluyendo fuera de dominio resuelto sin LLM |
| Aciertos en las seis preguntas | 5 correctas y 1 parcial, según tabla aportada | 6 correctas en la ejecución final, con validaciones Java |
| Integración con sistemas existentes | FastAPI expone POST /api/v1/chat y GET /health/llm; requiere adaptar la respuesta de estado y CORS para usar nuestro frontend sin cambios | Implementa ModelService mediante @Primary e inyección de dependencias; conserva el contrato del chat existente |

### Evidencia del flujo y del estado

En [grafo.py, líneas 597–630](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/grafo.py#L597-L630) se registran supervisor, clima, presupuesto, reservas y sintetizador. Los especialistas vuelven al supervisor y fin conduce al sintetizador. La [función supervisor](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/grafo.py#L276-L318) clasifica el dominio y crea un plan en la primera visita; después selecciona agentes pendientes sin volver a pedir una decisión LLM.

El [TypedDict EstadoPlaneador](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/grafo.py#L30-L36) declara el estado y sus reducers. El grafo se compila sin checkpointer, y [api.py](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/api.py#L59-L85) inicia un estado nuevo para cada petición. No se observa memoria conversacional ni persistencia de ejecución en esta configuración. Los datos de reservas y cupos son globales en memoria del proceso, igual que los datos simulados del componente Java se mantienen en memoria del backend Spring.

Los [scripts de la pregunta 4](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/prueba4.py) y [de la pregunta 6](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/prueba6.py) permiten seguir actualizaciones por nodo y comprobar el efecto en reservas y cupos. Esto acredita que existen esos mecanismos de prueba, pero no que se hayan ejecutado aquí ni que hayan producido exactamente los tiempos de la captura.

### Diferencias de políticas y llamadas al modelo

La [reserva protegida de LangGraph](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/grafo.py#L369-L539) extrae datos con un modelo Pydantic, exige presupuesto y aplica una probabilidad máxima de lluvia del **30 %** en ese camino de creación. El agente de reservas de [agentes.py](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/agentes.py) solo tiene consultar_disponibilidad; crear_reserva se llama desde código después de validar condiciones.

En Spring, el modelo puede proponer crearReserva, pero ToolContext conserva la petición original y Java valida antes de descontar cupos. La frase «no llueve mucho» usa un umbral del **40 %**, y se exige presupuesto para comprobar una condición de dinero. La verificación climática depende de que el usuario la haya solicitado. Estas políticas no son idénticas; las dos aceptan el 15 % de Suesca el sábado y rechazan el presupuesto insuficiente del caso 5.

LangGraph usa llamadas LLM para clasificar dominio y planificar inicialmente; Spring usa reglas para detectar intenciones y pide propuestas al supervisor en cada paso. Además, Spring puede devolver la evidencia completa si la síntesis cambia u omite datos. Por eso las diferencias de tiempo o aciertos no pueden atribuirse solo al framework.

### Compatibilidad real con el frontend

Ambos comparten la ruta POST /api/v1/chat y campos requestId, answer y elapsedMs, pero [api.py, línea 78](https://github.com/JuanDProg123/TallerLangGraph/blob/d9fc39fa6cd9fb8afdda8c3505a0fd82991bbb2e/planeador-langgraph/api.py#L76-L80) devuelve **status=success**, mientras nuestro frontend exige **status=ok**. El endpoint de salud LangGraph devuelve estado/modelo/respuesta, y el frontend actual espera ok/result.models. En api.py tampoco se declara CORSMiddleware. Para alternar ambos backends con esta página habría que ajustar ese contrato y permitir el origen del frontend; compartir una ruta no garantiza compatibilidad completa. No se modificó el repositorio LangGraph.

### Método de conteo

El conteo y los tiempos de Spring corresponden a la versión evaluada en la Parte 6, antes de añadir el agente de actividades de la Parte 7. Esa comparación se conserva como una medición de aquella etapa.

En ambos se cuentan líneas físicas, incluidas líneas en blanco, comentarios y prompts, de los archivos que concentran definición de agentes, flujo y validaciones. Se excluyen datos simulados, herramientas, tests, controladores REST, dependencias y .venv. El conteo describe los archivos seleccionados; no mide instrucciones ejecutables, esfuerzo ni calidad. Las validaciones están dentro de grafo.py en LangGraph y en archivos separados en Java, por eso se incluyen esos tres archivos adicionales de Spring.

| LangGraph | Líneas físicas | No vacías |
|---|---:|---:|
| grafo.py | 655 | 498 |
| agentes.py | 96 | 73 |
| config.py | 11 | 7 |

| Spring AI | Líneas físicas | No vacías |
|---|---:|---:|
| planeador/Decision.java | 14 | 12 |
| planeador/PlaneadorOrquestador.java | 154 | 140 |
| planeador/agentes/AgentesConfig.java | 127 | 114 |
| planeador/SolicitudPlaneador.java | 61 | 55 |
| planeador/RegistroHerramientas.java | 37 | 32 |
| planeador/ReglasReserva.java | 49 | 46 |

Conteos, hashes de archivos y referencias de código: [revision-codigo-langgraph.json](revision-codigo-langgraph.json).

Como contexto general, las capacidades de los frameworks están documentadas en [LangGraph](https://docs.langchain.com/oss/python/langgraph/overview) y [Spring AI: Building Effective Agents](https://docs.spring.io/spring-ai/reference/api/effective-agents.html); la tabla anterior se refiere a las implementaciones concretas revisadas.

## Conclusión propuesta para la bitácora

Elegiríamos Spring AI para extender una aplicación empresarial ya construida en Java y Spring, como pruebachat: pudimos conservar el contrato del chat e incorporar reglas de negocio y pruebas Java. Elegiríamos LangGraph para un proyecto centrado en controlar explícitamente el estado y el recorrido de agentes, especialmente si requiere pausar, reanudar o inspeccionar un flujo. Estas posibilidades están documentadas en [LangGraph](https://docs.langchain.com/oss/python/langgraph/overview), y Spring AI también ofrece [patrones de workflows y orquestación](https://docs.spring.io/spring-ai/reference/api/effective-agents.html). En los ensayos aportados LangGraph tuvo menor tiempo promedio y Spring AI logró seis respuestas correctas tras añadir validaciones. La elección debe considerar el sistema existente y el control requerido; estos seis casos no permiten concluir superioridad general de un framework.

## Alcance final de la Parte 6

La comparación de código, definición del flujo, estado, pruebas e integración ya está completada con el repositorio aportado. Los tiempos y las valoraciones de LangGraph siguen siendo los reportados por el grupo; la revisión estática no los reproduce. Para un experimento controlado quedarían por igualar hardware, versiones, modelo efectivo, prompts, reglas de reserva y método de medición. Esto no impide presentar la comparación del taller con sus límites expresamente indicados.
