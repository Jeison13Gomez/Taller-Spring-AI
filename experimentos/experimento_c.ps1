# Experimento C: truncamiento. Una explicacion larga con numPredict = 10, 50, 200 y 1000.
# Registra finishReason tal como llega del endpoint, sin interpretarlo.
param(
    [int]$Runs = 3,
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados')
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$question = Texto 'Explica en detalle cómo funciona la fotosíntesis, incluyendo la fase luminosa y la fase oscura.'
$limits = 10, 50, 200, 1000

Calentar
$rows = @()
foreach ($n in $limits) {
    foreach ($i in 1..$Runs) {
        $r = Invoke-Chat $question @{ numPredict = $n }
        # Heuristica: la respuesta "parece completa" si termina en un signo de cierre de frase.
        $termina = ([string]$r.answer).TrimEnd() -match '[.!?)"]$'
        Write-Host ("numPredict {0}  ejecucion {1}/{2}  http {3}  finishReason {4}  tokens {5}  termina en puntuacion: {6}" -f `
            $n, $i, $Runs, $r.http, $r.finishReason, $r.completionTokens, $termina)
        $rows += [pscustomobject]@{
            numPredict           = $n
            run                  = $i
            http                 = $r.http
            finishReason         = $r.finishReason
            completionTokens     = $r.completionTokens
            elapsedMs            = $r.elapsedMs
            tokensPerSecond      = $r.tokensPerSecond
            terminaEnPuntuacion  = $termina
            answer               = $r.answer
        }
    }
}
Guardar $rows (Join-Path $OutDir 'experimento_c.csv')

$resumen = foreach ($g in ($rows | Group-Object numPredict)) {
    $ok = @($g.Group | Where-Object { $_.http -eq 200 })
    $tok = Estadistica ($ok | ForEach-Object { $_.completionTokens })
    $ms = Estadistica ($ok | ForEach-Object { $_.elapsedMs })
    $razones = ($ok | Group-Object finishReason | ForEach-Object { "{0} x{1}" -f $_.Name, $_.Count }) -join ', '
    [pscustomobject]@{
        numPredict             = $g.Name
        ejecuciones_validas    = $ok.Count
        finishReason           = $razones
        completionTokens_media = $tok.Media
        elapsedMs_media        = $ms.Media
        terminan_en_puntuacion = @($ok | Where-Object { $_.terminaEnPuntuacion }).Count
    }
}
$resumen | Format-Table -AutoSize
Guardar $resumen (Join-Path $OutDir 'experimento_c_resumen.csv')
