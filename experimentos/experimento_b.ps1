# Experimento B: top-k y top-p.
# Prompt corto, numPredict 15, varias ejecuciones por configuracion, sin seed.
#   bloque a: temperatura 0.8 fija, topK = 1, 5, 40, 100 (topP no se envia)
#   bloque b: temperatura 0.8 fija, topK = 0 (desactivado), topP = 0.1, 0.5, 0.9, 1.0
#   bloque c: temperatura 0.2 con topP 0.95 contra temperatura 1.2 con topP 0.2 (topK = 0)
param(
    [int]$Runs = 5,
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados')
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$question = Texto 'El gato se acostó en el'
$numPredict = 15

$configs = @()
foreach ($k in 1, 5, 40, 100) { $configs += @{ block = 'a'; temperature = 0.8; topK = $k; topP = $null } }
foreach ($p in 0.1, 0.5, 0.9, 1.0) { $configs += @{ block = 'b'; temperature = 0.8; topK = 0; topP = [double]$p } }
$configs += @{ block = 'c'; temperature = 0.2; topK = 0; topP = 0.95 }
$configs += @{ block = 'c'; temperature = 1.2; topK = 0; topP = 0.2 }

Calentar
$rows = @()
foreach ($c in $configs) {
    foreach ($i in 1..$Runs) {
        $params = @{ numPredict = $numPredict; temperature = [double]$c.temperature; topK = $c.topK }
        if ($null -ne $c.topP) { $params.topP = [double]$c.topP }
        $r = Invoke-Chat $question $params
        $topPText = if ($null -ne $c.topP) { Num ([double]$c.topP) } else { 'no enviado' }
        Write-Host ("bloque {0}  temp {1}  topK {2}  topP {3}  ejecucion {4}/{5}  http {6}  {7}" -f `
            $c.block, (Num ([double]$c.temperature)), $c.topK, $topPText, $i, $Runs, $r.http, $r.answer)
        $rows += [pscustomobject]@{
            block            = $c.block
            temperature      = Num ([double]$c.temperature)
            topK             = $c.topK
            topP             = $topPText
            run              = $i
            http             = $r.http
            answer           = $r.answer
            completionTokens = $r.completionTokens
            elapsedMs        = $r.elapsedMs
            tokensPerSecond  = $r.tokensPerSecond
        }
    }
}
Guardar $rows (Join-Path $OutDir 'experimento_b.csv')

$resumen = foreach ($g in ($rows | Group-Object block, temperature, topK, topP)) {
    $ok = @($g.Group | Where-Object { $_.http -eq 200 })
    $first = $g.Group[0]
    $tok = Estadistica ($ok | ForEach-Object { $_.completionTokens })
    $ms = Estadistica ($ok | ForEach-Object { $_.elapsedMs })
    [pscustomobject]@{
        block                  = $first.block
        temperature            = $first.temperature
        topK                   = $first.topK
        topP                   = $first.topP
        ejecuciones_validas    = $ok.Count
        respuestas_unicas      = Unicas ($ok | ForEach-Object { $_.answer })
        completionTokens_media = $tok.Media
        elapsedMs_media        = $ms.Media
    }
}
$resumen | Format-Table -AutoSize
Guardar $resumen (Join-Path $OutDir 'experimento_b_resumen.csv')
