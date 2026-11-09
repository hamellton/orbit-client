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
# Game servers of cluster 22 (GE1): one IP, ports 6001-6007. Resolved via public DNS so a
# hosts-file redirect cannot make the sniffer connect to itself.
GAME_HOST="${GAME_HOST:-gameserver-22-01-public.prod.darkorbit.com}"
GAME_PORTS="${GAME_PORTS:-6001-6007}"
GAME_IP="${GAME_IP:-$(dig +short @1.1.1.1 "$GAME_HOST" | tail -1)}"
[[ -n "$GAME_IP" ]] || { echo "Не удалось узнать IP игрового сервера $GAME_HOST" >&2; exit 1; }

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
  sniffer/build/install/sniffer/bin/sniffer --port "$PORT" --direct "$GAME_IP:$GAME_PORTS" &
  SNIFFER_PID=$!
  # stop the sniffer on Ctrl+C or any exit, not only after the client closes
  trap 'kill "$SNIFFER_PID" 2>/dev/null || true' EXIT INT TERM
  sleep 2
fi

echo "Запускаю клиент: сайт через socks5://127.0.0.1:$PORT, игра через 127.0.0.1:$GAME_PORTS → $GAME_IP"
# Terminals inside VSCode/Electron apps inherit ELECTRON_RUN_AS_NODE=1, which turns the
# client into a plain Node process that rejects Chromium switches.
env -u ELECTRON_RUN_AS_NODE "$APP/Contents/MacOS/DarkOrbit Client" \
  --proxy-server="socks5://127.0.0.1:$PORT" \
  --proxy-bypass-list="<-loopback>" \
  --host-resolver-rules="MAP *.prod.darkorbit.com 127.0.0.1" || true

echo "Готово. Записи: $ROOT/recordings"
