# my-bot

Packet bot for DarkOrbit (Java 21, Gradle Kotlin DSL, multi-module). Personal use only.

Status: Stage 0 (reconnaissance). See `docs/PLAN.md` for the full task list and `docs/recon/` for protocol findings.

## Rules
- Never use AstraBot code or binaries. `reference/astra-config.json` is only a reference of user-visible options.
- DarkBot (GPL): take facts and logic, do not copy code wholesale.
- Do not touch binaries in `../DO-files/lib` and `../DO-files/plugins`.
- UI, logs, README in Russian; code and comments in English.
- Commit small and often without asking; never push unless asked.
- Never log passwords, dosid, sessionID or keys.

## Tools
- JPEXS FFDec: `~/tools/ffdec-26.4.0/ffdec.jar` (CLI: `java -jar ffdec.jar -help`).
- JDK 21 (Temurin) for the build; JDK 17 also installed.

## Build
Not yet scaffolded (Stage 1).
