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
  sleep 2
fi

echo "Запускаю клиент через socks5://127.0.0.1:$PORT"
"$APP/Contents/MacOS/DarkOrbit Client" \
  --proxy-server="socks5://127.0.0.1:$PORT" \
  --proxy-bypass-list="<-loopback>" || true

if [[ -n "$SNIFFER_PID" ]]; then
  kill "$SNIFFER_PID" 2>/dev/null || true
fi
echo "Готово. Записи: $ROOT/recordings"
