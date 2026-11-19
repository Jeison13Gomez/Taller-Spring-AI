# Experimento E: como crece promptTokens a lo largo de una conversacion de 20 turnos (feature 006, memoria).
# La misma conversacion se hace dos veces: "memoria" (todos los turnos con el mismo sessionId) y "base" (sin
# sessionId, cada peticion es independiente). Las instrucciones son largas y piden respuestas largas (plantilla
# tutor, numPredict 400): cada intercambio ocupa unos 500 tokens, asi que con la ventana de 20 mensajes el prompt
# llega a unos 5000 tokens. Ese tamano es deliberado: supera num-ctx 2048 hacia el turno 5 y 4096 hacia el 9 o 10,
# y no supera 8192, lo que permite ver tres sucesos distintos (ver specs/006-conversation-memory/research.md R10).
# Con preguntas cortas todo cabria en cualquier contexto y las tres corridas saldrian iguales.
#
# El script NO puede cambiar el num-ctx del backend: -NumCtx solo se anota en cada fila y debe coincidir con
# SPRING_AI_OLLAMA_CHAT_NUM_CTX del contenedor (hay que reiniciar el backend entre corridas, ver el quickstart).
# Cuando el prompt supera num-ctx, Ollama quita mensajes antiguos de uno en uno (unos 525 tokens por paso) y responde
# 200 sin avisar: la senal es una meseta, promptTokens deja de crecer (variacion menor al 1%) mientras historyMessages
# sigue subiendo. El script avisa cuando ocurre.
# Escribe experimento_e_ctx<NumCtx>.csv y reconstruye experimento_e_resumen.csv con todos los ctx que haya.
# -SoloResumen no llama al backend: solo reconstruye el resumen a partir de los CSV que ya existan en OutDir.
param(
    [Parameter(Mandatory = $true)][int]$NumCtx,
    [int]$Turnos = 20,
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados'),
    [switch]$SoloResumen
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$umbralMeseta = 0.01   # promptTokens varia menos del 1% respecto al turno anterior

function Entero($v) { if ($null -eq $v -or "$v" -eq '') { $null } else { [int]$v } }

# Meseta: promptTokens varia menos del 1% mientras historyMessages sigue subiendo.
function Hay-Meseta($ant, $act) {
    $pa = Entero $ant.promptTokens; $pc = Entero $act.promptTokens
    $ha = Entero $ant.historyMessages; $hc = Entero $act.historyMessages
    return ($null -ne $pa -and $null -ne $pc -and $null -ne $ha -and $null -ne $hc -and $pa -gt 0 -and [math]::Abs($pc - $pa) -lt ($umbralMeseta * $pa) -and $hc -gt $ha)
}

function PromptEn($filas, [int]$turno) {
    $f = $filas | Where-Object { [int]$_.turno -eq $turno } | Select-Object -First 1
    if ($f) { Entero $f.promptTokens } else { $null }
}

function Porcentaje($desde, $hasta) {
    if ($null -eq $desde -or $null -eq $hasta -or $desde -eq 0) { return $null }
    [math]::Round(($hasta - $desde) / $desde * 100, 1)
}

if (-not $SoloResumen) {
    # 20 instrucciones de unas 70 palabras, una por pais, que piden una respuesta de unas 300 palabras.
    $plantilla = Texto 'Quiero que me expliques con detalle y de forma ordenada todo lo importante sobre {0}. Cu\u00e9ntame primero su geograf\u00eda y su clima, despu\u00e9s su historia desde la antig\u00fcedad hasta hoy, luego su econom\u00eda y sus principales industrias, y por \u00faltimo su cultura, su gastronom\u00eda y sus tradiciones m\u00e1s conocidas. Es para preparar una presentaci\u00f3n en clase. Escribe varios p\u00e1rrafos con un lenguaje claro y no resumas: necesito una respuesta larga, de unas 300 palabras.'
    $paises = (Texto 'Francia,Jap\u00f3n,Brasil,Egipto,Canad\u00e1,India,M\u00e9xico,Italia,Per\u00fa,Kenia,Noruega,Australia,Turqu\u00eda,Argentina,Vietnam,Marruecos,Grecia,Chile,Tailandia,Portugal') -split ','
    $instrucciones = @($paises | ForEach-Object { $plantilla -f $_ })

    $stamp = Get-Date -Format 'yyyyMMddHHmmss'
    $sessionId = "exp-e-$stamp-$NumCtx"
    $params = @{ temperature = 0.0; numPredict = 400; seed = 42 }
    $inicio = Get-Date

    Calentar
    $rows = @()
    foreach ($run in 'memoria', 'base') {
        $anterior = $null
        foreach ($i in 1..$Turnos) {
            $sid = if ($run -eq 'memoria') { $sessionId } else { '' }
            $r = Invoke-Chat $instrucciones[($i - 1) % $instrucciones.Count] $params 'tutor' $sid
            Write-Host ("num-ctx {0}  {1}  turno {2}/{3}  http {4}  promptTokens {5}  completionTokens {6}  historyMessages {7}  {8} ms" -f `
                $NumCtx, $run, $i, $Turnos, $r.http, $r.promptTokens, $r.completionTokens, $r.historyMessages, $r.elapsedMs)
            $fila = [pscustomobject]@{
                numCtx           = $NumCtx
                run              = $run
                turno            = $i
                http             = $r.http
                promptTokens     = $r.promptTokens
                completionTokens = $r.completionTokens
                totalTokens      = $r.totalTokens
                elapsedMs        = $r.elapsedMs
                historyMessages  = $r.historyMessages
            }
            $rows += $fila
            if ($r.http -ne 200) {
                Write-Host "AVISO: turno $i de la corrida $run con http $($r.http): no es una respuesta valida."
            }
            if ($run -eq 'memoria' -and $anterior -and (Hay-Meseta $anterior $fila)) {
                Write-Host ("AVISO: turno {0}: promptTokens se estanca en {2} (antes {1}, variacion menor al 1%) mientras historyMessages sube ({3} a {4}): el prompt no cabe en num-ctx y Ollama esta quitando mensajes antiguos." -f `
                    $i, $anterior.promptTokens, $fila.promptTokens, $anterior.historyMessages, $fila.historyMessages)
            }
            $anterior = $fila
        }
    }
    Guardar $rows (Join-Path $OutDir "experimento_e_ctx$NumCtx.csv")
    Write-Host ("Duracion total: {0:N1} minutos" -f ((Get-Date) - $inicio).TotalMinutes)
}

# --- Resumen de todos los experimento_e_ctx*.csv de OutDir ---------------------------------------------------
$todas = @()
foreach ($f in (Get-ChildItem -Path $OutDir -Filter 'experimento_e_ctx*.csv' | Sort-Object Name)) {
    $todas += @(Import-Csv -Path $f.FullName -Encoding UTF8)
}
$resumen = @()
foreach ($ctx in ($todas | ForEach-Object { [int]$_.numCtx } | Sort-Object -Unique)) {
    foreach ($run in 'memoria', 'base') {
        $g = @($todas | Where-Object { [int]$_.numCtx -eq $ctx -and $_.run -eq $run } | Sort-Object { [int]$_.turno })
        if ($g.Count -eq 0) { continue }
        $ok = @($g | Where-Object { [int]$_.http -eq 200 })
        $maxHist = ($g | ForEach-Object { Entero $_.historyMessages } | Measure-Object -Maximum).Maximum
        $llena = $null
        $meseta = $null
        if ($run -eq 'memoria') {
            $llena = ($g | Where-Object { (Entero $_.historyMessages) -eq $maxHist } | Select-Object -First 1).turno
            for ($k = 1; $k -lt $g.Count; $k++) {
                if (Hay-Meseta $g[$k - 1] $g[$k]) { $meseta = $g[$k].turno; break }
            }
        }
        $p1 = PromptEn $g 1; $p11 = PromptEn $g 11; $p20 = PromptEn $g 20
        $resumen += [pscustomobject]@{
            numCtx                     = $ctx
            run                        = $run
            turnos_ok                  = $ok.Count
            primer_turno_ventana_llena = $llena
            turno_meseta               = $meseta
            promptTokens_t1            = $p1
            promptTokens_t11           = $p11
            promptTokens_t20           = $p20
            crecimiento_1_11_pct       = Porcentaje $p1 $p11
            variacion_11_20_pct        = Porcentaje $p11 $p20
            promptTokens_max           = ($g | ForEach-Object { Entero $_.promptTokens } | Measure-Object -Maximum).Maximum
            errores                    = $g.Count - $ok.Count
        }
    }
}
$resumen | Format-List
Guardar $resumen (Join-Path $OutDir 'experimento_e_resumen.csv')
