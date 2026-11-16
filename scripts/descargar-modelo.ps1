# Descarga el modelo pequeño de español de Vosk (~39 MB) y lo deja en
# app/src/main/assets/model-es, que es donde la app lo busca.
# Uso (desde la carpeta del proyecto):  powershell -ExecutionPolicy Bypass -File scripts\descargar-modelo.ps1

$ErrorActionPreference = 'Stop'
$nombre  = 'vosk-model-small-es-0.42'
$url     = "https://alphacephei.com/vosk/models/$nombre.zip"
$raiz    = Split-Path -Parent $PSScriptRoot
$assets  = Join-Path $raiz 'app\src\main\assets'
$destino = Join-Path $assets 'model-es'
$tmp     = Join-Path ([IO.Path]::GetTempPath()) "$nombre.zip"

if (Test-Path (Join-Path $destino 'uuid')) {
    Write-Host "El modelo ya está en $destino"
    exit 0
}

New-Item -ItemType Directory -Force $assets | Out-Null
Write-Host "Descargando $url ..."
Invoke-WebRequest -Uri $url -OutFile $tmp

Write-Host 'Descomprimiendo...'
$extraer = Join-Path ([IO.Path]::GetTempPath()) "$nombre-extraido"
if (Test-Path $extraer) { Remove-Item -Recurse -Force $extraer }
Expand-Archive -Path $tmp -DestinationPath $extraer
if (Test-Path $destino) { Remove-Item -Recurse -Force $destino }
Move-Item (Join-Path $extraer $nombre) $destino

# Vosk (StorageService.unpack) usa el archivo "uuid" para saber si ya copió el modelo
[guid]::NewGuid().ToString() | Set-Content -Encoding ascii -NoNewline (Join-Path $destino 'uuid')

Remove-Item -Force $tmp
Remove-Item -Recurse -Force $extraer
Write-Host "Listo: modelo en $destino"
