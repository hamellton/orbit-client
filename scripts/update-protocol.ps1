# Обновление протокола после патча игры (Windows). Подробности: tools/update_protocol.py
# Требуется: Python 3, JDK 21 (JAVA_HOME), JPEXS FFDec (FFDEC_JAR), git.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$py = Get-Command py -ErrorAction SilentlyContinue
if ($py) { & py -3 -I tools/update_protocol.py @args } else { & python -I tools/update_protocol.py @args }
exit $LASTEXITCODE
