# Experimento de plantillas (feature 005): efecto de templateId en el prompt y en la respuesta.
#   parte 1: promptTokens de cada plantilla (default, concise, tutor, extractor) con la misma pregunta,
#            temperature 0.0 y numPredict 1. Mide cuantos tokens anade cada plantilla, no la respuesta.
#   parte 2: concise contra tutor, con una pregunta cuya respuesta no se trunque (numPredict 400).
#            Compara completionTokens y elapsedMs, y calcula si concise es al menos 30% menor que tutor.
# Las peticiones alternan plantillas (en vez de repetir la misma seguida) para que la cache de prompt
# de Ollama no reduzca el promptTokens que informa de una peticion repetida.
param(
    [int]$RunsPrompt = 3,
    [int]$RunsLong = 20,
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados')
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$question = Texto 'Qu\u00e9 es una API?'
$templates = 'default', 'concise', 'tutor', 'extractor'
$limiteReduccion = 30

Calentar
$rows = @()

# --- Parte 1: promptTokens por plantilla ---------------------------------------------------------
Write-Host '== Parte 1: promptTokens por plantilla =='
foreach ($i in 1..$RunsPrompt) {
    foreach ($t in $templates) {
        $r = Invoke-Chat $question @{ temperature = 0.0; numPredict = 1 } $t
        Write-Host ("parte 1  plantilla {0}  ejecucion {1}/{2}  http {3}  promptTokens {4}" -f $t, $i, $RunsPrompt, $r.http, $r.promptTokens)
        $rows += [pscustomobject]@{
            parte            = 1
            templateId       = $t
            run              = $i
            http             = $r.http
            promptTokens     = $r.promptTokens
            completionTokens = $r.completionTokens
            elapsedMs        = $r.elapsedMs
            finishReason     = $r.finishReason
        }
    }
}

# --- Parte 2: concise contra tutor ---------------------------------------------------------------
Write-Host '== Parte 2: concise contra tutor (numPredict 400) =='
foreach ($i in 1..$RunsLong) {
    foreach ($t in 'concise', 'tutor') {
        $r = Invoke-Chat $question @{ numPredict = 400 } $t
        $aviso = if ($r.finishReason -eq 'length') { '  AVISO: truncada (finishReason length)' } else { '' }
        Write-Host ("parte 2  plantilla {0}  ejecucion {1}/{2}  http {3}  tokens {4}  {5} ms{6}" -f $t, $i, $RunsLong, $r.http, $r.completionTokens, $r.elapsedMs, $aviso)
        $rows += [pscustomobject]@{
            parte            = 2
            templateId       = $t
            run              = $i
            http             = $r.http
            promptTokens     = $r.promptTokens
            completionTokens = $r.completionTokens
            elapsedMs        = $r.elapsedMs
            finishReason     = $r.finishReason
        }
    }
}
Guardar $rows (Join-Path $OutDir 'experimento_plantillas.csv')

# --- Resumen -------------------------------------------------------------------------------------
$resumen = @()

$media1 = @{}
foreach ($t in $templates) {
    $ok = @($rows | Where-Object { $_.parte -eq 1 -and $_.templateId -eq $t -and $_.http -eq 200 })
    $media1[$t] = (Estadistica ($ok | ForEach-Object { $_.promptTokens })).Media
}
$minimo = ($media1.Values | Where-Object { $null -ne $_ } | Measure-Object -Minimum).Minimum
foreach ($t in $templates) {
    $ok = @($rows | Where-Object { $_.parte -eq 1 -and $_.templateId -eq $t -and $_.http -eq 200 })
    $dif = if ($null -ne $media1[$t] -and $null -ne $minimo) { [math]::Round($media1[$t] - $minimo, 2) } else { $null }
    $resumen += [pscustomobject]@{
        parte                  = 1
        templateId             = $t
        ejecuciones_validas    = $ok.Count
        promptTokens_media     = $media1[$t]
        dif_vs_mas_corta       = $dif
        completionTokens_media = $null
        elapsedMs_media        = $null
        truncadas              = $null
        reduccion_pct_vs_tutor = $null
        cumple_30pct           = $null
    }
}

$media2 = @{}
foreach ($t in 'concise', 'tutor') {
    $ok = @($rows | Where-Object { $_.parte -eq 2 -and $_.templateId -eq $t -and $_.http -eq 200 })
    $media2[$t] = [pscustomobject]@{
        Ok        = $ok.Count
        Tokens    = (Estadistica ($ok | ForEach-Object { $_.completionTokens })).Media
        Ms        = (Estadistica ($ok | ForEach-Object { $_.elapsedMs })).Media
        Truncadas = @($ok | Where-Object { $_.finishReason -eq 'length' }).Count
    }
}
$reduccion = $null
$cumple = $null
if ($media2['tutor'].Tokens -gt 0 -and $null -ne $media2['concise'].Tokens) {
    $reduccion = [math]::Round(($media2['tutor'].Tokens - $media2['concise'].Tokens) / $media2['tutor'].Tokens * 100, 1)
    $cumple = $reduccion -ge $limiteReduccion
}
foreach ($t in 'concise', 'tutor') {
    $m = $media2[$t]
    $resumen += [pscustomobject]@{
        parte                  = 2
        templateId             = $t
        ejecuciones_validas    = $m.Ok
        promptTokens_media     = $null
        dif_vs_mas_corta       = $null
        completionTokens_media = $m.Tokens
        elapsedMs_media        = $m.Ms
        truncadas              = $m.Truncadas
        reduccion_pct_vs_tutor = if ($t -eq 'concise') { $reduccion } else { $null }
        cumple_30pct           = if ($t -eq 'concise') { $cumple } else { $null }
    }
}

$resumen | Format-Table -AutoSize
$truncadasTotal = $media2['concise'].Truncadas + $media2['tutor'].Truncadas
if ($truncadasTotal -gt 0) {
    Write-Host "AVISO: $truncadasTotal respuestas llegaron al limite de 400 tokens (finishReason length); la comparacion de la parte 2 no es limpia."
}
if ($null -ne $reduccion) {
    Write-Host ("concise reduce completionTokens un {0}% respecto a tutor (minimo {1}%): {2}" -f (Num $reduccion), $limiteReduccion, $(if ($cumple) { 'SI cumple' } else { 'NO cumple' }))
} else {
    Write-Host 'No hay datos suficientes para comparar concise con tutor.'
}
Guardar $resumen (Join-Path $OutDir 'experimento_plantillas_resumen.csv')
