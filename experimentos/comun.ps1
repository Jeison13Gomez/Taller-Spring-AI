# Funciones comunes de los experimentos. El archivo es solo ASCII: los textos con tildes se
# escriben con secuencias \uXXXX y se convierten con Texto().

$script:Url = 'http://localhost:8080/api/v1/chat'
$script:Inv = [Globalization.CultureInfo]::InvariantCulture
# Punto decimal en los CSV y en la consola, sea cual sea la configuracion regional de Windows.
[Threading.Thread]::CurrentThread.CurrentCulture = $script:Inv

function Texto([string]$s) { [regex]::Unescape($s) }

# Formatea un numero con punto decimal, sin depender de la configuracion regional.
function Num($v) {
    if ($v -is [double] -or $v -is [single]) { $v.ToString('0.0###', $script:Inv) } else { [string]$v }
}

# Envia una pregunta al chat. $Params admite temperature, topP, topK, numPredict y seed;
# lo que no se pasa no se envia (rige el valor por defecto del servicio).
# $TemplateId y $SessionId son opcionales: vacio = no se envia (rige la plantilla default y no hay memoria).
# Van al final para que las llamadas de los experimentos A, B y C, que solo pasan pregunta y parametros, no cambien.
function Invoke-Chat([string]$Question, [hashtable]$Params = @{}, [string]$TemplateId = '', [string]$SessionId = '') {
    $parts = @('"question":' + ($Question | ConvertTo-Json -Compress))
    if ($SessionId -ne '') { $parts += ('"sessionId":' + ($SessionId | ConvertTo-Json -Compress)) }
    if ($TemplateId -ne '') { $parts += ('"templateId":' + ($TemplateId | ConvertTo-Json -Compress)) }
    foreach ($k in 'temperature', 'topP', 'topK', 'numPredict', 'seed') {
        if ($Params.ContainsKey($k) -and $null -ne $Params[$k]) {
            $parts += ('"{0}":{1}' -f $k, (Num $Params[$k]))
        }
    }
    $body = '{' + ($parts -join ',') + '}'
    $bytes = [Text.Encoding]::UTF8.GetBytes($body)

    $status = 0
    $text = ''
    try {
        $r = Invoke-WebRequest -Method Post -Uri $script:Url -ContentType 'application/json; charset=utf-8' `
            -Body $bytes -UseBasicParsing -TimeoutSec 600
        $status = [int]$r.StatusCode
        $text = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            $text = (New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)).ReadToEnd()
        } else {
            $text = $_.Exception.Message
        }
    }

    $o = $null
    try { $o = $text | ConvertFrom-Json } catch { }
    [pscustomobject]@{
        http             = $status
        answer           = $o.answer
        promptTokens     = $o.usage.promptTokens
        completionTokens = $o.usage.completionTokens
        totalTokens      = $o.usage.totalTokens
        elapsedMs        = $o.elapsedMs
        tokensPerSecond  = $o.usage.tokensPerSecond
        finishReason     = $o.usage.finishReason
        historyMessages  = $o.historyMessages
    }
}

$script:UrlClassify = 'http://localhost:8080/api/v1/classify'

# POST de un cuerpo JSON. Devuelve el codigo HTTP (0 si no hubo respuesta: timeout, conexion) y el texto.
# Un codigo de error HTTP no es una excepcion del script: se devuelve igual que un 200.
function Send-Json([string]$Uri, [string]$Body) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
    $status = 0
    $text = ''
    try {
        $r = Invoke-WebRequest -Method Post -Uri $Uri -ContentType 'application/json; charset=utf-8' `
            -Body $bytes -UseBasicParsing -TimeoutSec 600
        $status = [int]$r.StatusCode
        $text = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
    } catch {
        $resp = $_.Exception.Response
        if ($resp) {
            $status = [int]$resp.StatusCode
            $text = (New-Object IO.StreamReader($resp.GetResponseStream(), [Text.Encoding]::UTF8)).ReadToEnd()
        } else {
            $text = $_.Exception.Message
        }
    }
    [pscustomobject]@{ Status = $status; Text = $text }
}

# Clasifica un texto en POST /api/v1/classify. $Strategy vacio = no se envia (rige instruction);
# $Temperature $null = no se envia. Distingue tres desenlaces en el campo "tipo":
#   ok       HTTP 200: resultado valido; los tokens salen de usage.
#   formato  HTTP 502 con objeto failure: el modelo no cumplio el formato; los tokens salen de consumed.
#   servicio cualquier otro codigo (500, 400, timeout = 0): fallo del servicio, no del formato.
# Un 502 no es un error del script: es un dato del experimento.
function Invoke-Classify([string]$Text, [string]$Strategy = '', $Temperature = $null) {
    $parts = @('"text":' + ($Text | ConvertTo-Json -Compress))
    if ($Strategy -ne '') { $parts += ('"strategy":' + ($Strategy | ConvertTo-Json -Compress)) }
    if ($null -ne $Temperature) { $parts += ('"temperature":' + (Num ([double]$Temperature))) }
    $reply = Send-Json $script:UrlClassify ('{' + ($parts -join ',') + '}')

    $o = $null
    try { $o = $reply.Text | ConvertFrom-Json } catch { }

    $tipo = 'servicio'
    $tokens = $null
    if ($reply.Status -eq 200) { $tipo = 'ok'; $tokens = $o.usage }
    elseif ($reply.Status -eq 502 -and $null -ne $o.failure) { $tipo = 'formato'; $tokens = $o.consumed }

    [pscustomobject]@{
        http             = $reply.Status
        tipo             = $tipo
        category         = $o.result.category
        confidence       = $o.result.confidence
        justification    = $o.result.justification
        strategy         = $o.strategy
        retried          = $o.retried
        retries          = $o.retries
        attempts         = $o.attempts
        elapsedMs        = $o.elapsedMs
        totalTokens      = $tokens.totalTokens
        promptTokens     = $tokens.promptTokens
        completionTokens = $tokens.completionTokens
        failureCheck     = $o.failure.check
        failureMessage   = $o.failure.message
    }
}

# Una peticion corta para que el modelo ya este cargado y el primer tiempo medido no se infle.
function Calentar {
    Write-Host 'Calentando el modelo...'
    [void](Invoke-Chat 'hola' @{ numPredict = 1 })
}

# Numero de respuestas distintas (sensible a mayusculas, sin espacios en los extremos).
function Unicas($answers) {
    $set = New-Object 'Collections.Generic.HashSet[string]' ([StringComparer]::Ordinal)
    foreach ($a in $answers) { [void]$set.Add(([string]$a).Trim()) }
    $set.Count
}

# Media y desviacion estandar muestral (n-1) de una lista de numeros; $null si no hay datos.
function Estadistica($values) {
    $v = @($values | Where-Object { $null -ne $_ } | ForEach-Object { [double]$_ })
    if ($v.Count -eq 0) { return [pscustomobject]@{ Media = $null; Desv = $null } }
    $m = ($v | Measure-Object -Average).Average
    $sd = 0.0
    if ($v.Count -gt 1) {
        $sum = ($v | ForEach-Object { ($_ - $m) * ($_ - $m) } | Measure-Object -Sum).Sum
        $sd = [math]::Sqrt($sum / ($v.Count - 1))
    }
    [pscustomobject]@{ Media = [math]::Round($m, 2); Desv = [math]::Round($sd, 2) }
}

function Guardar($rows, [string]$path) {
    $dir = Split-Path $path -Parent
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force $dir | Out-Null }
    $rows | Export-Csv -Path $path -NoTypeInformation -Encoding UTF8
    Write-Host "Guardado: $path"
}
