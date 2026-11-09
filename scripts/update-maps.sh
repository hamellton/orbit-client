#!/usr/bin/env bash
# Обновление данных карт (размеры, типы) из клиента после патча игры. Подробности: tools/update_maps.py
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec python3 -I tools/update_maps.py "$@"
