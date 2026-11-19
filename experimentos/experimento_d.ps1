# Experimento D: fiabilidad del formato. El mismo texto se clasifica Runs veces en cada configuracion:
#   A  instruction     temperatura 0.2
#   B  instruction     temperatura 1.0
#   C  validate-retry  temperatura 0.2
#   D  native-schema   temperatura 0.2
# Las peticiones alternan configuraciones (A, B, C, D, A, B, ...) para que la carga del modelo no
# favorezca a ninguna. Un HTTP 502 es un fallo de formato (dato del experimento, no del script); cualquier
# otro codigo distinto de 200 se cuenta aparte como "error de servicio".
param(
    [int]$Runs = 20,
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados')
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$text = Texto 'Hice un pedido hace tres semanas y todav\u00eda no ha llegado. He llamado dos veces al servicio de atenci\u00f3n al cliente y nadie me da una soluci\u00f3n. Quiero que me devuelvan el dinero.'

$configs = @(
    @{ config = 'A'; strategy = 'instruction';    temperature = 0.2 },
    @{ config = 'B'; strategy = 'instruction';    temperature = 1.0 },
    @{ config = 'C'; strategy = 'validate-retry'; temperature = 0.2 },
    @{ config = 'D'; strategy = 'native-schema';  temperature = 0.2 }
)
$checks = 'json', 'category', 'confidence', 'justification'

Calentar
$rows = @()
foreach ($i in 1..$Runs) {
    foreach ($c in $configs) {
        $r = Invoke-Classify $text $c.strategy ([double]$c.temperature)
        Write-Host ("config {0}  {1}  temp {2}  ejecucion {3}/{4}  http {5}  {6}  categoria {7}  intentos {8}  {9} ms  {10}" -f `
            $c.config, $c.strategy, (Num ([double]$c.temperature)), $i, $Runs, $r.http, $r.tipo, $r.category, $r.attempts, $r.elapsedMs, $r.failureCheck)
        $rows += [pscustomobject]@{
            config           = $c.config
            run              = $i
            strategy         = $c.strategy
            temperature      = Num ([double]$c.temperature)
            http             = $r.http
            tipo             = $r.tipo
            category         = $r.category
            confidence       = $r.confidence
            justification    = $r.justification
            retried          = $r.retried
            retries          = $r.retries
            attempts         = $r.attempts
            elapsedMs        = $r.elapsedMs
            totalTokens      = $r.totalTokens
            promptTokens     = $r.promptTokens
            completionTokens = $r.completionTokens
            failureCheck     = $r.failureCheck
            failureMessage   = $r.failureMessage
        }
    }
}
Guardar $rows (Join-Path $OutDir 'experimento_d.csv')

# tasa_fallo_pct = fallos de formato / (ok + fallos de formato): las llamadas con error de servicio no
# llegaron a dar un veredicto de formato y no entran en el denominador. Las medias de intentos, tokens y
# tiempo usan esas mismas llamadas con veredicto (200 y 502); totalTokens_media_solo_ok solo los 200.
$resumen = foreach ($c in $configs) {
    $g = @($rows | Where-Object { $_.config -eq $c.config })
    $ok = @($g | Where-Object { $_.tipo -eq 'ok' })
    $fmt = @($g | Where-Object { $_.tipo -eq 'formato' })
    $serv = @($g | Where-Object { $_.tipo -eq 'servicio' })
    $veredicto = @($ok) + @($fmt)

    $porCheck = ($checks | ForEach-Object { $n = $_; '{0}={1}' -f $n, @($fmt | Where-Object { $_.failureCheck -eq $n }).Count }) -join '; '
    $tasa = if ($veredicto.Count -gt 0) { [math]::Round($fmt.Count / $veredicto.Count * 100, 1) } else { $null }
    $intentos = Estadistica ($veredicto | ForEach-Object { $_.attempts })
    $tokTodos = Estadistica ($veredicto | ForEach-Object { $_.totalTokens })
    $tokOk = Estadistica ($ok | ForEach-Object { $_.totalTokens })
    $ms = Estadistica ($veredicto | ForEach-Object { $_.elapsedMs })

    [pscustomobject]@{
        config                    = $c.config
        strategy                  = $c.strategy
        temperature               = Num ([double]$c.temperature)
        ejecuciones               = $g.Count
        parsean_ok                = $ok.Count
        fallos_formato            = $fmt.Count
        fallos_por_check          = $porCheck
        tasa_fallo_pct            = $tasa
        requirieron_reintento     = @($veredicto | Where-Object { $_.retried -eq $true }).Count
        reintentos_totales        = ($veredicto | ForEach-Object { [int]$_.retries } | Measure-Object -Sum).Sum
        attempts_media            = $intentos.Media
        totalTokens_media         = $tokTodos.Media
        totalTokens_media_solo_ok = $tokOk.Media
        elapsedMs_media           = $ms.Media
        elapsedMs_desv            = $ms.Desv
        errores_servicio          = $serv.Count
    }
}
# Format-List: son demasiadas columnas para una tabla; asi no se cortan.
$resumen | Format-List
Guardar $resumen (Join-Path $OutDir 'experimento_d_resumen.csv')
