# Experimento A: temperatura y variabilidad.
# Prompt fijo, 4 temperaturas, varias ejecuciones por temperatura, sin seed.
param(
    [int]$Runs = 10,
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados')
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$question = Texto 'Propón un nombre para una aplicación de estudio. Responde solo con el nombre.'
$temperatures = 0.0, 0.3, 0.7, 1.0

Calentar
$rows = @()
foreach ($t in $temperatures) {
    foreach ($i in 1..$Runs) {
        $r = Invoke-Chat $question @{ temperature = [double]$t }
        Write-Host ("temperatura {0}  ejecucion {1}/{2}  http {3}  {4}" -f (Num ([double]$t)), $i, $Runs, $r.http, $r.answer)
        $rows += [pscustomobject]@{
            temperature      = Num ([double]$t)
            run              = $i
            http             = $r.http
            answer           = $r.answer
            completionTokens = $r.completionTokens
            elapsedMs        = $r.elapsedMs
            tokensPerSecond  = $r.tokensPerSecond
        }
    }
}
Guardar $rows (Join-Path $OutDir 'experimento_a.csv')

$resumen = foreach ($g in ($rows | Group-Object temperature)) {
    $ok = @($g.Group | Where-Object { $_.http -eq 200 })
    $tok = Estadistica ($ok | ForEach-Object { $_.completionTokens })
    $ms = Estadistica ($ok | ForEach-Object { $_.elapsedMs })
    [pscustomobject]@{
        temperature          = $g.Name
        ejecuciones_validas  = $ok.Count
        respuestas_unicas    = Unicas ($ok | ForEach-Object { $_.answer })
        completionTokens_media = $tok.Media
        completionTokens_desv  = $tok.Desv
        elapsedMs_media      = $ms.Media
    }
}
$resumen | Format-Table -AutoSize
Guardar $resumen (Join-Path $OutDir 'experimento_a_resumen.csv')
