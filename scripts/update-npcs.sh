#!/usr/bin/env bash
# Обновление таблицы NPC (номер -> имя) из клиента после патча игры. Подробности: tools/update_npcs.py
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec python3 -I tools/update_npcs.py "$@"
