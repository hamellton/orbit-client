# Обновление данных карт (размеры, типы) из клиента после патча игры (Windows). Подробности: tools/update_maps.py
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$py = Get-Command py -ErrorAction SilentlyContinue
if ($py) { & py -3 -I tools/update_maps.py @args } else { & python -I tools/update_maps.py @args }
exit $LASTEXITCODE
