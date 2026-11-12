#!/usr/bin/env bash
# Названия квестов из текстов игры (resource_quest.xml на CDN) → reference/maps/quest-titles-ru.json.
#   scripts/update-quest-titles.sh [язык]
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I tools/update_quest_titles.py "${1:-ru}"
