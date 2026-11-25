#!/usr/bin/env bash
# Справочник предметов (docs/ITEMS.md, docs/items/*.md) из последней разведки сайта (scripts/site-recon.sh) и текстов игры.
#   scripts/update-items-doc.sh [папка разведки]
set -euo pipefail
cd "$(dirname "$0")/.."
dir="${1:-$(ls -d local/recon/*/ | sort | tail -1)}"
python3 -I tools/items_doc.py "$dir" .
