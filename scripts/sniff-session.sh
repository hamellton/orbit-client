#!/usr/bin/env bash
# Запуск сниффера и официального клиента DarkOrbit через него (macOS).
#
#   scripts/sniff-session.sh [путь к DarkOrbit Client.app]
#
# Сниффер пишет журнал в logs/sniffer.log и записи сессий в recordings/.
# Закройте клиент, чтобы остановить сниффер.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="${1:-$ROOT/../DO Client/DarkOrbit Client.app}"
PORT="${SNIFFER_PORT:-1080}"

JAVA_HOME="$(/usr/libexec/java_home -v 21 2>/dev/null)" || {
  echo "Не найдена Java 21. Установите Temurin 21." >&2; exit 1; }
export JAVA_HOME

cd "$ROOT"
./gradlew -q :sniffer:installDist
mkdir -p logs

if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Порт $PORT уже занят — считаю, что сниффер уже запущен."
  SNIFFER_PID=""
else
  sniffer/build/install/sniffer/bin/sniffer --port "$PORT" &
  SNIFFER_PID=$!
  # stop the sniffer on Ctrl+C or any exit, not only after the client closes
  trap 'kill "$SNIFFER_PID" 2>/dev/null || true' EXIT INT TERM
  sleep 2
fi

echo "Запускаю клиент через socks5://127.0.0.1:$PORT"
# Terminals inside VSCode/Electron apps inherit ELECTRON_RUN_AS_NODE=1, which turns the
# client into a plain Node process that rejects Chromium switches.
env -u ELECTRON_RUN_AS_NODE "$APP/Contents/MacOS/DarkOrbit Client" \
  --proxy-server="socks5://127.0.0.1:$PORT" \
  --proxy-bypass-list="<-loopback>" || true

echo "Готово. Записи: $ROOT/recordings"
