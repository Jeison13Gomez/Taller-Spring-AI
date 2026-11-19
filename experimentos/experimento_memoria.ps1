# Experimento de memoria (feature 006, T043): usa el modelo real el historial de la sesion? Es informativo: el
# resultado NO hace pasar ni fallar SC-001 (que se comprueba con un modelo simulado, T014); SC-002 si se mira aqui.
#   Grupo 1 "con_memoria": 10 pares de turnos dependientes en 10 sesiones nuevas (se da un dato y luego se pregunta por
#     el dato: nombre, color, numero, ciudad, mascota...). Cuenta cuantas segundas respuestas usan el dato.
#   Grupo 2 "sin_memoria": las mismas 10 segundas preguntas solas, cada una en una sesion nueva (no hay dato que recordar:
#     es la linea base de cuantas veces la respuesta contiene el dato por casualidad).
#   Grupo 3 "intercaladas": dos sesiones de 20 turnos cada una, alternando peticiones (A1, B1, A2, B2...), con una palabra
#     secreta distinta por sesion; ninguna respuesta de una sesion puede contener la palabra de la otra (SC-002).
# numPredict 60 y temperature 0.0. Cada peticion es una fila de experimento_memoria.csv; cada fallo se imprime como FALLO.
# Una respuesta "usa el dato" si contiene la palabra clave, sin distinguir mayusculas ni tildes.
param(
    [string]$OutDir = (Join-Path $PSScriptRoot 'resultados')
)
. (Join-Path $PSScriptRoot 'comun.ps1')

$params = @{ temperature = 0.0; numPredict = 60 }

function Normalizar([string]$s) {
    if ($null -eq $s) { return '' }
    $d = $s.Normalize([Text.NormalizationForm]::FormD)
    return ([regex]::Replace($d, '\p{Mn}', '')).ToLowerInvariant()
}

# La respuesta contiene la palabra (sin distinguir mayusculas ni tildes).
function Contiene([string]$respuesta, [string]$palabra) {
    return (Normalizar $respuesta).Contains((Normalizar $palabra))
}

$pares = @(
    @{ Dato = (Texto 'Hola, me llamo Ana.'); Pregunta = (Texto '\u00bfC\u00f3mo me llamo?'); Clave = (Texto 'Ana') },`
    @{ Dato = (Texto 'Mi color favorito es el turquesa.'); Pregunta = (Texto '\u00bfCu\u00e1l es mi color favorito?'); Clave = (Texto 'turquesa') },`
    @{ Dato = (Texto 'Mi n\u00famero de la suerte es el 47.'); Pregunta = (Texto '\u00bfCu\u00e1l es mi n\u00famero de la suerte?'); Clave = (Texto '47') },`
    @{ Dato = (Texto 'Vivo en Valladolid.'); Pregunta = (Texto '\u00bfEn qu\u00e9 ciudad vivo?'); Clave = (Texto 'Valladolid') },`
    @{ Dato = (Texto 'Mi perro se llama Rex.'); Pregunta = (Texto '\u00bfC\u00f3mo se llama mi perro?'); Clave = (Texto 'Rex') },`
    @{ Dato = (Texto 'Mi comida favorita es la paella.'); Pregunta = (Texto '\u00bfCu\u00e1l es mi comida favorita?'); Clave = (Texto 'paella') },`
    @{ Dato = (Texto 'Trabajo como carpintero.'); Pregunta = (Texto '\u00bfEn qu\u00e9 trabajo?'); Clave = (Texto 'carpintero') },`
    @{ Dato = (Texto 'Soy del Betis, mi equipo de f\u00fatbol.'); Pregunta = (Texto '\u00bfDe qu\u00e9 equipo de f\u00fatbol soy?'); Clave = (Texto 'Betis') },`
    @{ Dato = (Texto 'Toco el viol\u00edn desde ni\u00f1o.'); Pregunta = (Texto '\u00bfQu\u00e9 instrumento toco?'); Clave = (Texto 'viol\u00edn') },`
    @{ Dato = (Texto 'Nac\u00ed en 1990.'); Pregunta = (Texto '\u00bfEn qu\u00e9 a\u00f1o nac\u00ed?'); Clave = (Texto '1990') })

$palabraA = 'ornitorrinco'
$palabraB = 'cardamomo'
$afirmacionA = Texto 'Mi palabra secreta es ornitorrinco. Recu\u00e9rdala.'
$afirmacionB = Texto 'Mi palabra secreta es cardamomo. Recu\u00e9rdala.'
$preguntaSecreta = Texto '\u00bfCu\u00e1l es mi palabra secreta?'
$seguimientoA = @(
    (Texto '\u00bfQu\u00e9 come un elefante?'),`
    (Texto 'Dime un color primario.'),`
    (Texto '\u00bfCu\u00e1ntas patas tiene una ara\u00f1a?'),`
    (Texto '\u00bfCu\u00e1l es la capital de Italia?'),`
    (Texto '\u00bfCu\u00e1l es mi palabra secreta?'),`
    (Texto 'Dime el nombre de un r\u00edo.'),`
    (Texto '\u00bfQu\u00e9 sonido hace un gato?'),`
    (Texto '\u00bfDe qu\u00e9 color es el cielo?'),`
    (Texto 'Nombra un planeta.'),`
    (Texto '\u00bfCu\u00e1nto es 6 por 7?'),`
    (Texto '\u00bfCu\u00e1l es mi palabra secreta?'),`
    (Texto 'Dime un animal que vuele.'),`
    (Texto '\u00bfCu\u00e1l es el oc\u00e9ano m\u00e1s grande?'),`
    (Texto 'Nombra un instrumento de cuerda.'),`
    (Texto '\u00bfQu\u00e9 d\u00eda viene despu\u00e9s del lunes?'),`
    (Texto '\u00bfCu\u00e1l es mi palabra secreta?'),`
    (Texto 'Dime una fruta roja.'),`
    (Texto '\u00bfCu\u00e1ntos meses tiene un a\u00f1o?'),`
    (Texto 'Nombra un deporte con pelota.'))
$seguimientoB = @(
    (Texto '\u00bfQu\u00e9 es la harina?'),`
    (Texto 'Dime una verdura verde.'),`
    (Texto '\u00bfCu\u00e1ntos huevos hay en una docena?'),`
    (Texto '\u00bfCon qu\u00e9 se hace el pan?'),`
    (Texto '\u00bfCu\u00e1l es mi palabra secreta?'),`
    (Texto 'Nombra una sopa fr\u00eda.'),`
    (Texto '\u00bfQu\u00e9 sabor tiene el lim\u00f3n?'),`
    (Texto 'Dime un postre con chocolate.'),`
    (Texto '\u00bfQu\u00e9 se bebe en el desayuno?'),`
    (Texto 'Nombra un queso.'),`
    (Texto '\u00bfCu\u00e1l es mi palabra secreta?'),`
    (Texto '\u00bfA qu\u00e9 temperatura hierve el agua?'),`
    (Texto 'Dime una legumbre.'),`
    (Texto '\u00bfQu\u00e9 es un horno?'),`
    (Texto 'Nombra una salsa.'),`
    (Texto '\u00bfCu\u00e1l es mi palabra secreta?'),`
    (Texto '\u00bfQu\u00e9 fruta es amarilla y alargada?'),`
    (Texto 'Dime un cereal.'),`
    (Texto '\u00bfCu\u00e1ntas cucharadas son un cuarto de taza?'))

$stamp = Get-Date -Format 'yyyyMMddHHmmss'
$rows = @()
$fallos = @()
$inicio = Get-Date

function Pedir([string]$grupo, [string]$sesion, [int]$turno, [string]$pregunta, [string]$clave, [string]$otraPalabra) {
    $r = Invoke-Chat $pregunta $params '' $sesion
    $texto = ([string]$r.answer) -replace '[\r\n]+', ' '
    $usa = $null
    if ($clave -ne '') { $usa = (Contiene $texto $clave) }
    $contamina = $null
    if ($otraPalabra -ne '') { $contamina = (Contiene $texto $otraPalabra) }
    $script:rows += [pscustomobject]@{
        grupo           = $grupo
        sesion          = $sesion
        turno           = $turno
        http            = $r.http
        pregunta        = $pregunta
        respuesta       = $texto
        usaDato         = $usa
        contamina       = $contamina
        historyMessages = $r.historyMessages
    }
    Write-Host ("{0}  {1}  turno {2}  http {3}  usaDato {4}  contamina {5}  historyMessages {6}" -f $grupo, $sesion, $turno, $r.http, $usa, $contamina, $r.historyMessages)
    if ($r.http -ne 200) {
        $script:fallos += "FALLO: $grupo $sesion turno ${turno}: http $($r.http) (no es una respuesta valida)"
    } elseif ($null -ne $usa -and -not $usa -and $grupo -eq 'con_memoria') {
        $script:fallos += "FALLO: $grupo $sesion turno ${turno}: la respuesta no usa el dato '$clave'. Pregunta: $pregunta  Respuesta: $texto"
    } elseif ($contamina) {
        $script:fallos += "FALLO: $grupo $sesion turno ${turno}: la respuesta contiene la palabra de la otra sesion '$otraPalabra'. Respuesta: $texto"
    }
    return $r
}

Calentar

# Grupo 1: con memoria
$aciertosCon = 0
$i = 0
foreach ($p in $pares) {
    $i++
    $sid = "mem-con-$stamp-$i"
    [void](Pedir 'con_memoria' $sid 1 $p.Dato '' '')
    [void](Pedir 'con_memoria' $sid 2 $p.Pregunta $p.Clave '')
    if ($rows[-1].usaDato) { $aciertosCon++ }
}

# Grupo 2: las mismas segundas preguntas solas
$aciertosSin = 0
$i = 0
foreach ($p in $pares) {
    $i++
    $sid = "mem-sin-$stamp-$i"
    [void](Pedir 'sin_memoria' $sid 1 $p.Pregunta $p.Clave '')
    if ($rows[-1].usaDato) { $aciertosSin++ }
}

# Grupo 3: dos sesiones intercaladas de 20 turnos
$sidA = "mem-int-a-$stamp"
$sidB = "mem-int-b-$stamp"
$contaminadas = 0
$secretasA = 0; $secretasB = 0; $preguntasSecretasA = 0; $preguntasSecretasB = 0
foreach ($t in 1..20) {
    $qa = if ($t -eq 1) { $afirmacionA } elseif ($t -le 19) { $seguimientoA[$t - 2] } else { $preguntaSecreta }
    $qb = if ($t -eq 1) { $afirmacionB } elseif ($t -le 19) { $seguimientoB[$t - 2] } else { $preguntaSecreta }
    $claveA = if ($qa -eq $preguntaSecreta) { $palabraA } else { '' }
    $claveB = if ($qb -eq $preguntaSecreta) { $palabraB } else { '' }
    [void](Pedir 'intercaladas' $sidA $t $qa $claveA $palabraB)
    if ($rows[-1].contamina) { $contaminadas++ }
    if ($claveA -ne '') { $preguntasSecretasA++; if ($rows[-1].usaDato) { $secretasA++ } }
    [void](Pedir 'intercaladas' $sidB $t $qb $claveB $palabraA)
    if ($rows[-1].contamina) { $contaminadas++ }
    if ($claveB -ne '') { $preguntasSecretasB++; if ($rows[-1].usaDato) { $secretasB++ } }
}

Guardar $rows (Join-Path $OutDir 'experimento_memoria.csv')

Write-Host ''
Write-Host '=== Resumen (informativo: no hace pasar ni fallar SC-001) ==='
Write-Host ("Aciertos con memoria:  {0} de {1} (la segunda respuesta usa el dato dado en el turno anterior)" -f $aciertosCon, $pares.Count)
Write-Host ("Aciertos sin memoria:  {0} de {1} (las mismas preguntas solas: la respuesta contiene el dato por casualidad)" -f $aciertosSin, $pares.Count)
Write-Host ("Sesiones intercaladas: palabra propia recordada {0} de {1} (A) y {2} de {3} (B)" -f $secretasA, $preguntasSecretasA, $secretasB, $preguntasSecretasB)
Write-Host ("Contaminacion entre sesiones (SC-002): {0} respuestas de {1} contienen la palabra de la otra sesion" -f $contaminadas, @($rows | Where-Object { $_.grupo -eq 'intercaladas' }).Count)
$noValidas = @($rows | Where-Object { [int]$_.http -ne 200 }).Count
Write-Host ("Peticiones: {0}, no validas (http distinto de 200): {1}" -f $rows.Count, $noValidas)
if ($fallos.Count -gt 0) {
    Write-Host ''
    Write-Host "Fallos ($($fallos.Count)):"
    $fallos | ForEach-Object { Write-Host $_ }
} else {
    Write-Host 'Sin fallos.'
}
Write-Host ("Duracion total: {0:N1} minutos" -f ((Get-Date) - $inicio).TotalMinutes)
