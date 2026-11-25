#!/usr/bin/env bash
# Справочник квестов docs/QUESTS.md: все квесты клиента по номеру (названия reference/maps/quest-titles-{ru,en}.json),
# условия и награды от сервера (local/quests-seen.json и копии с других машин аргументами), остальное из
# reference/quests/research-ru.json, NPC — reference/quests/npc-notes-ru.json и reference/maps/npc-maps.json.
#   scripts/update-quest-doc.sh [другой quests-seen.json ...]
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I tools/quest_doc.py "$@"
