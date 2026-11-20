#!/usr/bin/env bash
# Названия предметов из текстов игры (resource_items.xml на CDN) → reference/maps/item-names-ru.json.
#   scripts/update-item-names.sh [язык]
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I tools/update_item_names.py "${1:-ru}"
