#!/usr/bin/env bash
# Обновление протокола после патча игры (macOS/Linux). Подробности: tools/update_protocol.py
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${JAVA_HOME:-}" ]] && [[ -x /usr/libexec/java_home ]]; then
  JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  export JAVA_HOME
fi
cd "$ROOT"
exec python3 -I tools/update_protocol.py "$@"
