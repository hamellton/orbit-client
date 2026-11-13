# Чужие боты для справки: что где лежит

Копия папки «DarkOrbit BOTS» с VPS друга (2026-11-13, только чтение): `vps-copy/DarkOrbit BOTS/` — дальше ROOT.
В git не идёт (`.gitignore`: `vps-copy/`, `vps.txt`). Можно изучать: логику, настройки, факты об игре.

**Пароли и ключи.** Файлы с учётными данными — не открывать без нужды и никогда не переписывать их содержимое в
код, документы или чат: `CARNAGE|DB|one/credentials.json`, `BeheFarmBySpearhed/lib/credentials.json`,
`CARNAGE/login.properties`, `CARNAGE/carnage-data/carnage_license.*`, `DB|one/proxies.json`, в каждой папке AstraBot
`users.dat`, `socks5.txt`, `license-debug.txt`, `DarkOrbit BOTS/PikaBot-/credentials.json.bak` и `accounts/`; поля
с паролями встречаются в `CARNAGE/configs/BeheSolo.json`, `one/configs/invokes.json`.

## Где что

| Папка | Что это | Куда смотреть |
|---|---|---|
| `DB/` | DarkBot **с полными исходниками** | `DB/src/main/java/com/github/manolo8/darkbot/…`, `DB/src/main/java/eu/darkbot/api/…` — как DarkBot делает что угодно (кружение, безопасность, порталы, ремонт). Первое место для сверки «как в DarkBot» |
| `CARNAGE/` | форк DarkBot, 22 плагина, 37 профилей | `configs/*.json` — профили (BL, бегемот, инвоки, врата); `config.json` → `CUSTOM_CONFIGS` — настройки плагинов; `carnage-data/blcycle_*` — логи и состояние плагина BLCycle (таймеры бегемота) |
| `one/` | DarkBot, 19 плагинов | `configs/` (в т.ч. `invokes.json`) |
| `BeheFarmBySpearhed/` | исходники плагина DarkBot для фарма бегемота (v1.2.0) | `src/main/java/com/darkichabynick/behemoth/BeheFarmBySpearhed.java`, `BeheFarmConfig.java` |
| `AstraBot - G1/`, `AstraBot - GE3/`, `killship/`, `dr srange/` | AstraBot (exe + свой JRE) | `config.json` (текущий профиль), `configs/*.json` (~23 профиля), `logs/` (сессии, `deaths_*`, `ingameLogs_*`) |
| `AstraTool/` | помощник для PvP | `config.json` |
| `DarkOrbit BOTS/` (вложенная) | копии тех же папок + `PikaBot-/` | `PikaBot-/` — отдельный пакетный бот (вход через Unity, SOCKS5, `rotation_plans/`, `rule_library.json`, `dse_threat.json`); `CARNAGE/configs/quests.json` — только здесь |
| `logs/` | логи PikaBot | падение `crash-20260806-153451.log` |

Плагины в CARNAGE (в `DB` те же без BLCycle и CarnagePluginPlus): BLCycle, BeheFarmBySpearhed, CarnagePluginPlus,
DarkBotPanel, DarkPlane, DefaultPlugin, DksPlugin, DmPlugin, DonorPlugin, KekkaReloaded, Launch, LeanPlugin,
MaolPlugin, MapCyclePlugin, Optimoth, OrbitHelper, Osiris, PenguinPlugin, PikaPika, SharedPlugin, TYXPlugin,
VoTPlugin. Код большинства закрыт или обфусцирован — видны только их настройки.

## По темам: где подсмотреть

| Тема | Файлы |
|---|---|
| Кружение, выбор стороны, «нельзя» | `DB/src/.../modules/LootModule.java`, `eu/darkbot/.../SharedLootModule.java` (сделано у нас как в DarkBot, `docs/FIGHT-MOVEMENT.md`) |
| Бегемот (Mindfire Behemoth) | профили `CARNAGE/configs/{BEHE SPEARHEAD,BEHE-CLOAK,BeheSolo,Behe_Clear,Behemoth,Berti-optimoth-behe+clearing}.json`; `AstraBot - GE3/configs/behe.json` (раздел BEHEMOTH); BLCycle: `CARNAGE/carnage-data/blcycle_debug.txt` (таймеры спавна ~15 мин по каждой BL-карте, перелёты, ~5 600 уридия за бегемота), `blcycle_state.properties`; исходники BeheFarm (точки ожидания и спавна, EMP → ISH, A-BL, HP Link) |
| Инвоки (Invoke XVI) | `CARNAGE/configs/{invokes,Invokes-optimoth-Berti}.json`, `one/configs/invokes.json`, `AstraBot*/configs/MAP - Invoke XVI.json` |
| BL-мобы (Impulse, Attend, Abide, Steadfast, Strokelight, Seeker Rocket) | таблицы `NPC_INFOS` в BL-профилях CARNAGE; `npcList` в профилях AstraBot (там же номера NPC: Impulse 175, Attend 176, Invoke 177, Strokelight 266, Abide 268, Seeker Rocket 159) |
| Квесты | `DarkOrbit BOTS/CARNAGE/configs/quests.json` (DailyOps), профили AstraBot `QUESTS`, `MAP - Quest` |
| Врата (ABG, EBG, LoW, GoP, Hades, VoT, Astral, QZ, Labyrinth) | одноимённые профили CARNAGE и AstraBot; раздел ETERNAL_BLACKLIGHT в AstraBot |
| Кубикон | `AstraBot*/configs/cubi 2-6.json`, раздел CUBIKON |
| Настройки AstraBot целиком | любой `AstraBot*/configs/*.json` (разделы GENERAL, KILL, COLLECTOR, PET, BEHEMOTH, STROKELIGHT, ETERNAL_BLACKLIGHT, CUBIKON, QUEST_MODULE, GALAXY_GATES, ZONES…); дополняет `reference/astra-config.json` |

## Что уже видно из профилей (2026-11-13)

- Бегемот: вплотную (радиус 150–300), без кружения (`NO_CIRCLE`, `LEECH_OPTIMAL`), A-BL, EMP-01 → ISH-01, питомец
  HP Link; формация в атаке — Drill.
- Invoke XVI: радиус 500–580, приоритет высокий, A-BL, пеленгатор питомца, EMP/ISH/HP Link.
- Impulse II / Attend IX: радиус 550–600, обычные патроны; Attend часто не бьют (`kill=false`, `PASSIVE`).
- Abide I / Steadfast III: почти везде не бьют (`kill=false`, `PASSIVE`, радиус 150–350).
- Формации: атака Drill, бег и роум Star/Wheel, сбор ящиков — конфигурация 2 + Veteran.

Не разобрано: закрытый код плагинов и AstraBot (не декомпилировали), предпочтительные зоны в профилях (base64-сетки),
`PikaBot-/rule_library.json`, `dse_threat.json`, файлы `deaths_*` AstraBot.

## Бегемот при чужих «поездах» и сами поезда (CARNAGE, 2026-11-13)

**Фарм бегемота, когда рядом группы-поезда и бьют нас** — плагин BLCycle в профиле `CARNAGE/configs/BeheSolo.json`
(`CUSTOM_CONFIGS` → `eu.darkbot.blcycle.behaviours.BLCycleBehavior`; включён в `PLUGIN_INFOS`):
- `enemyEscape.behemothPolicy = IGNORE_ATTACKED_USE_COUNT` при `attackedSeconds = 0`, `enemyCount = 0` — у бегемота не
  убегать ни от атак, ни от числа врагов;
- `behemoth.avoidEnemyOrbit = true` — не вставать в кольцо, по которому вокруг бегемота кружат чужие группы;
- прилёт к самому спавну: `approxLeadSeconds 0`, `preHoldSeconds 1`, `recoveryLeadSeconds 30`; таймеры спавна по
  каждой BL-карте (`carnage-data/blcycle_state.properties`), обход 1→2→3BL, `travelSeconds 130`, `giveUpAfterSeconds 60`;
- маскировка: `autoCraftCloak` и `useCloak` при формации Heart (`has-formation(heart, hero())`), питомец на HP Link
  (`PetLinkBehaviour`);
- в логе `carnage-data/blcycle_debug.txt` побегов нет вовсе; 67 бегемотов по ~5 600 уридия.

Защита «от поездов» есть, но в профилях бегемота **выключена** (настроена, не включена): DmPlugin `AntiTrain`
(`maxEnemies 3`, `ignoreDistance 1500`, `run true` — 3+ врага ближе 1500 → бежать) и Shared `CrowdAvoidance` (5 в радиусе
300, NPC и враги, `avoidDrawFire`). То есть у бегемота нарочно не убегают.

**Поезда (группа идёт за лидером):**
- `CARNAGE/configs/CitaFollow by Patron.json`, `Behemoth_CITA.json` — модуль `com.punikekk.modules.follow.FollowModule`
  (Kekka): идти за лидером, лидер отмечен тегом игрока `maintrainleader` (`PLAYER_TAGS`); поезд Цитаделей на бегемота;
- `eu.darkbot.drmalito.minicore.MiniTrain` — настроен в `Behemoth_CITA`, `Berti-optimoth-behe+clearing` (защита RSB/
  инста/ПЭМ, ожидание на BL), не включён;
- PikaPika `GroupManager` / `GroupFollower` / `HadesLeader` — в `Berti-optimoth-behe+clearing`, `Invokes-optimoth-Berti`;
- Penguin `modules.follow` — `HADES`, `VoT`, `Prots`, `InmortalUnion`, `astralGate` (`follow_id`, `follow_flags`,
  `follow_master_map`);
- AstraBot: раздел BEHEMOTH `followLeader` + `leaderId`; в KILL — `avoidTrain`.

Код этих плагинов закрыт — видны только их настройки и логи.
