#!/usr/bin/env bash
# Разведка сайта только чтением (docs/LEVELING.md): магазин, аукцион, ангары аккаунта → local/recon/<дата>-<аккаунт>/.
# Ничего не покупает и не ставит. Мастер-пароль спрашивается в терминале (или ORBIT_MASTER_PASSWORD).
#   scripts/site-recon.sh [аккаунт] [страница …]
# Работает с классами сборки, не трогая установленную программу (её можно не останавливать).
set -euo pipefail
cd "$(dirname "$0")/.."
unset ELECTRON_RUN_AS_NODE
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
./gradlew -q :app:compileJava :app:processResources
CP="app/build/classes/java/main:app/build/resources/main"
for jar in app/build/install/orbit/lib/*.jar; do
  case "$jar" in */app-*.jar) ;; *) CP="$CP:$jar" ;; esac
done
exec "$JAVA_HOME/bin/java" -cp "$CP" orbit.app.SiteRecon "${@:-7}"
