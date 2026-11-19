"""Genera las graficas del punto 2 (experimentos A y C) y la grafica 3 de la memoria (experimento E).

Uso (desde la raiz del proyecto):  py experimentos\\graficas.py
La grafica 3 lee los experimento_e_ctx*.csv que existan; si no hay ninguno, no se dibuja. Opcionalmente:
    py experimentos\\graficas.py [carpeta_con_los_csv_de_E [carpeta_de_salida_de_la_grafica_3]]
"""
import csv
import sys
from collections import defaultdict
from pathlib import Path

import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

BASE = Path(__file__).resolve().parent
RESULTADOS = BASE / "resultados"
SALIDA = BASE / "graficas"
SALIDA.mkdir(exist_ok=True)


def leer(nombre):
    # Export-Csv de PowerShell escribe UTF-8 con BOM.
    with open(RESULTADOS / nombre, newline="", encoding="utf-8-sig") as f:
        return list(csv.DictReader(f))


# --- Grafica 1: variabilidad frente a la temperatura (experimento A) ---------------------
filas_a = [f for f in leer("experimento_a.csv") if f["http"] == "200"]
por_temperatura = defaultdict(list)
for f in filas_a:
    por_temperatura[float(f["temperature"])].append(f["answer"].strip())

temperaturas = sorted(por_temperatura)
unicas = [len(set(por_temperatura[t])) for t in temperaturas]
ejecuciones = max(len(por_temperatura[t]) for t in temperaturas)

fig, ax = plt.subplots(figsize=(7, 4.5))
ax.plot(temperaturas, unicas, marker="o", linewidth=2, color="#1f77b4")
for t, u in zip(temperaturas, unicas):
    ax.annotate(str(u), (t, u), textcoords="offset points", xytext=(0, 8), ha="center")
ax.set_xlabel("Temperatura")
ax.set_ylabel(f"Respuestas únicas (de {ejecuciones} ejecuciones)")
ax.set_ylim(0, ejecuciones + 1)
ax.set_xticks(temperaturas)
ax.set_title("Variabilidad frente a la temperatura\n(experimento A, sin seed)")
ax.grid(alpha=0.3)
fig.tight_layout()
fig.savefig(SALIDA / "grafica_1_variabilidad_vs_temperatura.png", dpi=150)
plt.close(fig)

# --- Grafica 2: tokens generados frente a numPredict (experimento C) ---------------------
filas_c = [f for f in leer("experimento_c.csv") if f["http"] == "200"]
fig, ax = plt.subplots(figsize=(7, 4.5))
limites = sorted({int(f["numPredict"]) for f in filas_c})
ax.plot([min(limites), max(limites)], [min(limites), max(limites)], linestyle="--", color="gray",
        label="tokens = numPredict (límite)")
colores = {"length": "#d62728", "stop": "#2ca02c"}
rotulos = {"length": "Cortada (finishReason = length)", "stop": "Terminó sola (finishReason = stop)"}
for razon in ("length", "stop"):
    xs = [int(f["numPredict"]) for f in filas_c if f["finishReason"] == razon]
    ys = [int(f["completionTokens"]) for f in filas_c if f["finishReason"] == razon]
    if xs:
        ax.scatter(xs, ys, s=60, color=colores[razon], label=rotulos[razon], zorder=3)
# Ambos ejes en escala logaritmica: asi la recta tokens = numPredict se dibuja recta y los puntos
# que llegaron al limite quedan sobre ella.
ax.set_xscale("log")
ax.set_yscale("log")
ax.set_xticks(limites)
ax.set_xticklabels([str(n) for n in limites])
ax.set_yticks(limites)
ax.set_yticklabels([str(n) for n in limites])
ax.minorticks_off()
ax.set_xlabel("numPredict (tokens, escala logarítmica)")
ax.set_ylabel("Tokens generados (completionTokens, escala logarítmica)")
ax.set_title("Tokens generados frente a numPredict\n(experimento C, 3 ejecuciones por valor)")
ax.grid(alpha=0.3)
ax.legend(loc="upper left")
fig.tight_layout()
fig.savefig(SALIDA / "grafica_2_tokens_vs_numpredict.png", dpi=150)
plt.close(fig)

# --- Grafica 3: promptTokens frente al turno, con y sin memoria (experimento E) ----------
# Una linea continua por tamano de contexto (num-ctx) para la corrida con memoria, una discontinua para la
# corrida sin memoria, y una marca punteada en cada num-ctx. Abajo, historyMessages de las corridas con memoria.
CARPETA_E = Path(sys.argv[1]) if len(sys.argv) > 1 else RESULTADOS
SALIDA_E = Path(sys.argv[2]) if len(sys.argv) > 2 else SALIDA
archivos_e = sorted(CARPETA_E.glob("experimento_e_ctx*.csv"))
if archivos_e:
    por_contexto = {}
    for archivo in archivos_e:
        with open(archivo, newline="", encoding="utf-8-sig") as f:
            for fila in csv.DictReader(f):
                if fila["promptTokens"] == "":
                    continue
                por_contexto.setdefault(int(fila["numCtx"]), []).append(fila)

    def serie(filas, corrida, columna):
        datos = sorted((int(f["turno"]), int(f[columna])) for f in filas if f["run"] == corrida)
        return [t for t, _ in datos], [v for _, v in datos]

    paleta = plt.get_cmap("tab10")
    fig, (ax, ax_hist) = plt.subplots(2, 1, figsize=(8, 7), sharex=True, gridspec_kw={"height_ratios": [3, 1]})
    maximo = 0
    for i, contexto in enumerate(sorted(por_contexto)):
        filas = por_contexto[contexto]
        color = paleta(i)
        turnos_m, prompt_m = serie(filas, "memoria", "promptTokens")
        turnos_b, prompt_b = serie(filas, "base", "promptTokens")
        ax.plot(turnos_m, prompt_m, marker="o", linewidth=2, color=color, label=f"con memoria, num-ctx {contexto}")
        ax.plot(turnos_b, prompt_b, linestyle="--", linewidth=1.5, color=color, alpha=0.6,
                label=f"sin memoria, num-ctx {contexto}")
        ax.axhline(contexto, linestyle=":", color=color, alpha=0.7)
        ax.annotate(f"num-ctx {contexto}", (max(turnos_m + turnos_b), contexto), textcoords="offset points",
                    xytext=(0, 3), ha="right", fontsize=8, color=color)
        turnos_h, historial = serie(filas, "memoria", "historyMessages")
        ax_hist.plot(turnos_h, historial, marker="o", color=color, linewidth=1.5,
                     markersize=8 - 2 * i if i < 3 else 3, label=f"num-ctx {contexto}")
        maximo = max([maximo, contexto] + prompt_m + prompt_b)
    # Turno en que la ventana se llena: el primero con historyMessages igual al maximo de la corrida con memoria.
    llenas = set()
    for filas in por_contexto.values():
        turnos_h, historial = serie(filas, "memoria", "historyMessages")
        llenas.add(turnos_h[historial.index(max(historial))])
    for turno in sorted(llenas):
        ax.axvline(turno, linestyle="-.", color="gray", alpha=0.8)
        ax_hist.axvline(turno, linestyle="-.", color="gray", alpha=0.8)
        ax.annotate(f"ventana llena (turno {turno})", (turno, maximo * 1.05), textcoords="offset points",
                    xytext=(4, 0), fontsize=8, color="dimgray", va="top")
    ax.set_ylim(0, maximo * 1.08)
    ax.set_ylabel("promptTokens (tokens procesados por el modelo)")
    ax.set_title("Crecimiento de promptTokens a lo largo de la conversación\n(experimento E, con y sin memoria)")
    ax.grid(alpha=0.3)
    ax.legend(loc="upper left", fontsize=8)
    ax_hist.set_xlabel("Turno de la conversación")
    ax_hist.set_ylabel("historyMessages")
    ax_hist.set_ylim(-1, 22)
    ax_hist.grid(alpha=0.3)
    ax_hist.set_xticks(sorted({int(f["turno"]) for filas in por_contexto.values() for f in filas}))
    fig.tight_layout()
    SALIDA_E.mkdir(parents=True, exist_ok=True)
    fig.savefig(SALIDA_E / "grafica_3_prompttokens_vs_turno.png", dpi=150)
    plt.close(fig)

print("Graficas guardadas en", SALIDA)
print("Respuestas unicas por temperatura:", dict(zip(temperaturas, unicas)))
