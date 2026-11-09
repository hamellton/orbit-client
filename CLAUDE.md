# my-bot

Packet bot for DarkOrbit (Java 21, Gradle Kotlin DSL, multi-module). Personal use only.

Status: Stage 1 (sniffer) done, awaiting review. See `docs/PLAN.md` for the full task list and
`docs/recon/` for protocol findings (`REPORT.md` = stage 0, `STAGE1.md` = live traffic).

## Rules
- Never use AstraBot code or binaries. `reference/astra-config.json` is only a reference of user-visible options.
- DarkBot (GPL): take facts and logic, do not copy code wholesale.
- Do not touch binaries in `../DO-files/lib` and `../DO-files/plugins`.
- UI, logs, README in Russian; code and comments in English.
- Commit small and often without asking; never push unless asked.
- Git: development happens on `dev` (base branch); `main` is updated only on request.
  Remote `origin` = `git@github-hamellton:hamellton/orbit-client.git` (personal account, private).
  Repo-local identity `hamellton <hamelltonn@gmail.com>` — never commit here with the work email.
- Never log passwords, dosid, sessionID or keys.

## Tools
- JPEXS FFDec: `~/tools/ffdec-26.4.0/ffdec.jar` (CLI: `java -jar ffdec.jar -help`).
- JDK 21 (Temurin) for the build; JDK 17 also installed.

## Modules
- `net` — framing (uint24 length), downstream LZ4 envelopes (`DOB3`/`DOL4`, CRC32).
- `sniffer` — SOCKS5 + direct-port relay, session recording (`.dorec`), JSON-driven field decoder
  (temporary until stage 2 codegen), `session-report` tool.

## Build
Gradle needs JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`; the default `java` here is 17).
- `./gradlew test` — all tests.
- `./gradlew :sniffer:installDist` — builds `sniffer/build/install/sniffer/bin/{sniffer,session-report}`.
- `scripts/sniff-session.sh` — sniffer + official client (direct mode via `--host-resolver-rules`).
  Terminals inside VSCode set `ELECTRON_RUN_AS_NODE=1`; the script unsets it.

## Protocol facts worth remembering
- Flash ignores the browser proxy; game sockets are captured only by redirecting `*.prod.darkorbit.com`.
- GE1 = cluster 22, `170.33.12.50`, ports 6001–6007. Map change = `ReloginCommand` + reconnect.
- `super.write` writes the parent's id again before the parent's fields.
- Website login is protected by hCaptcha.
