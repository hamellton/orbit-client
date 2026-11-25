#!/usr/bin/env bash
# Справочник крафта docs/CRAFT.md: рецепты из декомпилированного клиента (ItemDataProvider.as, самый новый в
# reference/decompiled/), названия из текстов игры на CDN, источники ресурсов из reference/craft/sources-ru.json.
#   scripts/update-craft-doc.sh [ItemDataProvider.as] [язык] [resource_items.xml]
set -euo pipefail
cd "$(dirname "$0")/.."
python3 -I tools/craft_doc.py "$@"
