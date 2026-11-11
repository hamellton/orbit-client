# my-bot

Packet bot for DarkOrbit (Java 21, Gradle Kotlin DSL, multi-module). Personal use only.

Status: Stage 2 done; stage 3 in progress (login, game connection, vault, client-like startup). See `docs/PLAN.md` for the full task list and
`docs/recon/` for protocol findings (`REPORT.md` = stage 0, `STAGE1.md` = live traffic,
`STAGE3.md` = website login, client startup sequence, world model).

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
- `net` — framing (uint24 length), downstream LZ4 envelopes (`DOB3`/`DOL4`, CRC32), `.dorec` reader.
- `protocol-gen` — generator: `reference/protocol/{meta,packets}-*.json` → Java sources (newest meta wins).
- `protocol` — runtime (`PacketInput/Output`, rotations, `PacketCodec`, `ProtocolOutdatedException`) +
  generated `orbit.protocol.gen.*` (build output, not in git). Generated classes mirror client
  inheritance; field codecs are static `writeFields/readFields` to replay `super.write`.
- `secrets` — encrypted account vault `local/vault.bin` (AES-GCM, PBKDF2 master password,
  `ORBIT_MASTER_PASSWORD` for headless), `SecretMask` + logback converters, `vault` CLI.
- `auth` — website login (`AuthClient`), `AccountLogin` (per server: its saved dosid, else another server's
  dosid + the site's server switch, the password last; stores every new dosid). Accounts `name@server`.
  `orbit.auth.proxy`: per-account `ProxyRoute` — own SOCKS5/HTTP CONNECT `ProxyConnector` (proxy resolves
  names) for game sockets, `LocalProxy` loopback relay for `HttpURLConnection`/`HttpClient`, `verify()`
  before every login (no proxy reachable = no login, never direct). `ProxySpec` (parsing) is in `secrets`.
- `world` — map model from server packets (`World.apply`) and own `MoveRequest`s (`applyOutgoing`);
  immutable `WorldSnapshot` with interpolated positions. Hero is dead-reckoned, beacons only fix jumps.
  `orbit.world.maps`: `MapCatalog` (sizes from client maps-config, portal graph from DarkBot facts,
  `reference/maps/*.json` bundled as resources), `ZoneGeometry` (POI zone point tests).
- `config` — named bot configs `local/configs/<name>.json` (`BotConfig`, schema version + `Migrations`),
  `ConfigStore`, `ConfigCheck` (pre-start checks), `AccountSettings` (`local/accounts.json`: config and
  `Schedule` per account), `DarkBotImport` (user's own DarkBot profiles → `BotConfig`), `configs` CLI.
- `game` — game connection (`GameConnection`), client-like `StartupSequence` (cold start vs map change,
  checked against recordings by `StartupSequenceTest`), `ClientProfile` (UI state replayed from a
  recording: `client-profile` → `local/client-profile.json`), `game-check` live tool.
- `app` — the bot application `orbit`; without arguments the account manager (`ManagerWindow`: master
  password in `VaultDialog`, accounts add/edit/remove with proxy check, start/stop each or all through
  `AccountRunner`, a tab per started account). Otherwise modes (FlatLaf window that fits any size: per account a header with a
  live config switch, the minimap on the game's own pictures, cards beside or under it, the log; «Все»
  grid; `MainWindow.snapshot` renders it offscreen; modes bot / observe / replay / render; `--headless`). Logging config `orbit/app/logback-app.xml` (masking, `logs/orbit.log`).
- `sniffer` — SOCKS5 + direct-port relay, session recording, `session-report` tool; uses generated classes.

## Build
Gradle needs JDK 21 (`export JAVA_HOME=$(/usr/libexec/java_home -v 21)`; the default `java` here is 17).
- `./gradlew test` — all tests.
- `./gradlew :sniffer:installDist` — builds `sniffer/build/install/sniffer/bin/{sniffer,session-report}`.
- `./gradlew :app:installDist` — `app/build/install/orbit/bin/orbit`.
- `scripts/sniff-session.sh [--observe]` — sniffer (or the bot window in observer mode) + official client (direct mode via `--host-resolver-rules`).
  Terminals inside VSCode set `ELECTRON_RUN_AS_NODE=1`; the script unsets it.
- `scripts/update-maps.sh` — refresh `reference/maps/maps.json` from the client's maps-config.
- `scripts/update-npcs.sh` — refresh `reference/maps/npcs.json` (game NPC number → name, 13 = Cubikon)
  from the client's embedded `game.xml`; the pet locator's targets are these numbers.
- `scripts/update-protocol.sh [--force]` — after a game patch: download swf, decompile selected
  packages, parse, validate, diff, regenerate, build. Logic in `tools/update_protocol.py`.
- `RecordedTrafficTest` reads `recordings/` (local only) and requires byte-exact round trips.

## Protocol facts worth remembering
- Flash ignores the browser proxy; game sockets are captured only by redirecting `*.prod.darkorbit.com`.
- GE1 = cluster 22, `170.33.12.50`, ports 6001–6007. Map change = `ReloginCommand` + reconnect.
- `super.write` writes the parent's id again before the parent's fields.
- Website login is protected by hCaptcha; ~4 password logins in a few minutes trigger it per IP.
- The site's CDN sets `acw_tc` with `Max-Age`; `java.net.CookieManager` then emits RFC 2965 cookies
  (quoted values) and the site rejects `dosid` with `loginError=95`. `HttpSession` builds the header itself.
- The site gives every visitor a `dosid`; only the game page (flashvars) proves a login.
- A dosid opens only its own server. Servers of a login: `POST ajax/instances.php command=getInstanceList`;
  each entry links `GameAPI.php?req=…` (base64 PHP: `portal.loginByCryptId` = pilot exists,
  `portal.addInstanceByCryptId` = would create one — never follow that).
- `maps.php` must come from the flashvar `host` (the account server); the CDN copy lists int1's game
  hosts, and a login there fails with `LoginResponse.status` WrongInstanceId. ru1 = instance 68.
- Minimap pictures: `<cdn>spacemap/graphics/minimaps/minimap-<mapId>-700.jpg` (700×436).
