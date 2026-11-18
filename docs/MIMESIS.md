# Мимесис (Mimesis Mutiny, эскорт EM Freighter)

Разбор события 2026-11-18: как оно устроено, его NPC и как его делают DarkBot (ядро, SharedPlugin, DonorPlugin,
PikaPika) и AstraBot. У нас модуля нет; справочники и протокол готовы (см. «Что есть у нас»).
Источники: форум DarkOrbit (анонс и FAQ ноября 2026, FAQ марта 2026, отзывы, статы NPC 2021), darkorbitwiki.com,
гайд DarkBot ES; DarkBot `DB/src` и байткод плагинов (`javap`, декомпилятора нет); профили друга
`DB|CARNAGE|one/configs/MIMESIS.json`; AstraBot GE3 и killship (`configs/mimesis.json`, `backup/mimesis_backup.json`,
логи 08.11–12.11, `deaths_*.txt`), строки из бинарника AstraBot.

## Событие

- Текущее издание: 11.11.2026 10:00 – 25.11.2026 23:59 (LST). Рецепты крафтятся до 02.12, Byte Remnant удаляются через
  сутки после конца. Прошлые издания: март 2026, март 2021 и другие.
- **Ключи (Escort Key)** — один на вход:
  - ежедневная миссия «Freighter Protection» (уровень 6+): пролететь 10 000 единиц в радиации, 1 ключ;
  - Мимесисы на 4-1…4-4 и отряды на x-2…x-4 (чем сложнее NPC, тем выше шанс). AstraBot на 2-4: 619 строк «You received
    1 Escort Key» за лог, ключи 62 → 84 за час;
  - ящики-награды Terror и Reflector (тройке лучших по урону).
- **Вход**: портал тип 54 в точке (10500, 6500) на x-1 и x-8 (DarkBot: `addGG(-3, "GG Escort").accessOnlyBy(54, 10500,
  6500, BASE_MAPS)`). Сервер ставит на случайную из 10 карт эскорта своей фирмы; группа может попасть вместе.
- **Карты** (21 000 × 13 100, группа `PayloadEscort` в `maps.json`; выход — портал тип 1):

  | Фирма | 1–3 | 4–10 |
  |---|---|---|
  | VRU | 430–432 | 1439–1445 |
  | MMO | 433–435 | 1446–1452 |
  | EIC | 436–438 | 1453–1459 |

  В декабре–январе номера 430–445 заняты Frozen Labyrinth (DarkBot `StarManager.java:170-187`).
- **Расписание**: фрейтер каждый час, по FAQ 10:00–23:59 серверного времени; вход открыт первые 5 минут, рейс до 45 минут.
  По часам AstraBot (локальным) последний рейс 22:00, следующий 09:00 — часы сдвинуты, верить только таймерам сервера.
- **Рейс**: EM Freighter летит к маяку, только пока игрок в его зоне; вне зоны радиация. Мимесисы и турели бьют фрейтер и
  игроков. Фрейтер подбирает ящики на пути (огневая мощь). У цели взрывает маяк, сам взрывается через 5 с, появляется
  портал выхода. Провал: 45 минут прошли или фрейтер уничтожен. После рейса вся карта — радиация; оставшихся к следующему
  спавну выкидывает.
- **Смерть**: воскрешение на базе — на эту карту больше не пустят; на месте — полное HP. Разрыв связи по отзывам
  игроков обнуляет прогресс и выкидывает с карты (не проверено).
- **Очки (Escort Score)**: за убийства, контрольные точки и бонус в конце по % HP фрейтера; очки проваленного рейса
  сохраняются. Сколько за NPC — не опубликовано. AstraBot 12.11: `runScore` ~325 за рейс, потом +2850 бонусом в 20:29.
- **Награда за рейс по очкам**:

  | Очки | Награда |
  |---|---|
  | 1000–1699 | 20 Byte Remnant, 1800 EMAA-20 |
  | 1700–2899 | 30 Byte, + 800 PLT-2026 |
  | 2900–4799 | 50 Byte, + 160 PLD-8 |
  | 4800–7999 | 90 Byte, + 200 SAR-02 |
  | 8000+ | 120 Byte, + 10 Extra Energy, 100 UCB-100 |

- **Рейтинг** по сумме очков, награды места 1–100 (1-е: 1,5 млн уридия, 100k Seprom, 400k UCB-100, 40k RSB-75, 15k
  EMAA-20, 500 SR-5, 40 Indoctrine Oil; 51–100: 25k уридия, 20k Seprom, 20k UCB, 2,5k RSB, 750 EMAA). Дизайн Guardian
  Goliath Plus — места 1–10; титулы «Supreme/Elite Mimesis Defender 2026». По отзыву — потолок 51k очков рейтинга (не
  проверено).
- **Battle Pass** (200k уридия): 27 задач, всего 159 Battle Keys — потратить 5/15/30 ключей, выстрелить 25k/55k/80k
  EMAA-20, собрать 15k/25k/40k Byte, набрать 30k/80k/120k очков, продержаться на карте эскорта 20:50 / 2:13:20 / 5:00:00,
  урон по NPC (22 млн по Hexor и т. п.). Обмен ключей: Citadel (30), Guardian Citadel Plus (25), LF-5 Mortifier (215),
  Odysseus (45), Asmodeus (35), модули X3/XV03/XT10/ZPVE. Booty Box Battle Pass — на x-2…x-8, боевых и пиратских картах,
  1 Battle Key. Календарь входа 11–25.11.
- **Mimesis Chest** (новое в ноябре 2026, вместо Mimesis Box): награда + с шансом 30 % эффект:

  | Эффект | Шанс | Что |
  |---|---|---|
  | Heal | 25 % | +120k HP |
  | Heal Field | 25 % | +50k HP себе и игрокам в 500 |
  | Shield | 15 % | +80k щита (нужен генератор щита) |
  | Flux | 10 % | +10 % скорости 15 с |
  | Frost Field | 10 % | Frost Field |
  | Revenge | 10 % | Revenge 10 с |
  | Assimilate | 5 % | Assimilate 10 с |

  Одинаковые эффекты продлеваются, а не складываются; не срабатывает, если тот же навык корабля уже активен; сбрасываются
  при уходе с карты и разрыве. Лечит ли Heal Field фрейтер — источники расходятся. Вход на карту эскорта выключает
  Peace Ambassador CPU.
- **Предметы**: EMAA-20 — ×4 урона, ×7 по Мимесисам; лазер AA-1 (305 по ИИ / 180 по прочим), AAP-1 только питомцу
  (525 / 225); бустер очков RM-B00 (2 ч). Byte Remnant тратятся в сборке: AA-1 / AAP-1 по 1000; 10k EMAA-20 за 3000 один
  раз или 1500 + 15k уридия повторно (`recipe_blueprint-emaa-20-10k-repeat`); модули HP-HAM01, DMG-LEO01, SHD-DIM01,
  SPC-C01, DMG-SG01 по 1500; ZPVE по 4500. Дизайн Tyrannos Disruptor — из ящиков сложных NPC.
- Способности `ability_mimesis_hologram/_scramble/_phase-out` в панели — корабля Mimesis, не события (Scramble: +65 %
  уклонения, +25 % урона лазеров, +25 % скорости за 10 % щита в секунду; Phase Out: прыжок на 50 в случайную сторону,
  нельзя во вратах). Ни один бот их не использует; эффект `MIMESIS_PHASE_OUT(95)` в DarkBot только объявлен.

## NPC

Имена на сервере с цифрами вместо букв (`Hexor M1mesis`, `Raging Mimes1s`, `Obscured M1mes1s`, `Mim3si5 Turret`,
`-=[ Medic Mim3sis ]=-`, `-={EM Freighter}=-`) — сравнивать только буквы и цифры, как `NpcRadius`. Номера — из
`reference/maps/npcs.json`.

**На обычных картах** (x-3, x-4, 4-1…4-4; ключи падают здесь). HP/щит — вики сейчас, [в скобках] 2021:

| NPC (номер) | HP / щит | Макс. урон | Опыт / честь / кредиты / уридий / Byte | Поведение |
|---|---|---|---|---|
| Hexor (233) | 495k / 200k [300k] | 6 500 | 25,6k / 128 / 128k / 100 / 5 | агрессивный, медленные и ледяные ракеты |
| Raging (234) | 1,045M [900k] / 600k | 20 000 | 102,4k / 512 / 512k / 300 / 10 | пассивный, 7–9k/с |
| Cloning (235) | 2,2M [1,8M] / 1,2M | 75 000 | 205,3k / 1024 / 1,024M / 600 / 25 | пассивный, 30–38k/с, иногда 70k+; один раз выпускает 3 Cloned |
| Cloned (236) | 495k / 200k | 6 500 | как Hexor, без Byte | пассивный |
| Kamikaze (238) | 495k / 200k | бомба 20k | как Hexor, без Byte | цепляется к ближайшему игроку и взрывается рядом; урон делится обратно поглощению щита; EMP или маскировка сбрасывает захват; не исчезает |
| Terror (237) | 3,0M [2,7M] / 1,8M | 65 000 | 409,6k / — / 2,048M / 900 / 35 | 60–80k/с; ниже 50 % убегает, чинится, выпускает до 3×2 Kamikaze, ниже 25 % — до 3×3 |
| Reflector (239) | 4,5M [3,75M] / 2,5M | 50 000 | 819,2k / — / 4,096M / 1250 / 50 | облако 18k/с 10 с сквозь щит, перезарядка 6 с, EMP снимает; ниже 50 % разворачивает движение игроков рядом |

Уридий Terror и Reflector делится между атакующими. Их ящик: Escort Key, Byte Remnant, EMAA-20, PLD-8.

**На картах эскорта** (опыт/честь/кредиты по ступеням 102,4k / 204,8k / 409,6k / 819,2k):

| NPC (номер) | HP / щит | Макс. урон | Уридий / Byte | Заметки |
|---|---|---|---|---|
| Raider (241), Assailant (242) | 900k / 600k | 9 500 | 300 / 4 | |
| Medic (243) | 2,45M / 1,625M | 15 500 | 1250 / 17 | лекарь (по задачам квестов, не точно) |
| Inspirit (286) | 2,8M / 1,75M | 15 500 | 1250 / — | баффер (не точно); ящика не даёт |
| Hardy (244) | 3M / 6M | 0 | 1500 / 20 | не стреляет |
| Piercing (245), Hounding (247) | 5,25M / 3,5M | 25 500 | 1700 / 70 | |
| Sniper (250) | 5,25M / 3,5M | 28 500 | 1700 / 70 | |
| Obscured (246) | 4,2M / 2,8M | 12 000 | 2200 / 90 | иногда отстаёт от фрейтера; главный убийца ботов AstraBot |
| Mirror (248, 249) | 3M / 2M | 12 500 | 2200 / 90 | клоны выглядят так же; клон — макс. HP < 3M (SharedPlugin) |
| Marker (251) | 6,75M / 4,5M | 30k, бомба 300k | 2200 / 90 | |
| Mimesis Turret (287) | 2,1M / 0 | ракета 6k | — | |
| EM Freighter (240) | — | — | — | охраняемый, не бить |

Способности NPC эскорта нигде не описаны. Kamikaze и Cloned появляются снова и снова (отзывы). Ящики: обычный — Byte
Remnant; элитный — Byte, EMAA-20, Extra Energy, Log Disk, PLT-2021, SAR-02, SR-5, Tyrannos Disruptor. Тройка по урону
получает доп. ящики; по Piercing, Obscured, Hounding, Mirror, Sniper, Marker — пятёрка (Extra Energy, патроны, дизайн).
Рекомендуемые радиусы DarkBot (`reference/maps/npc-radius.json`): Raging 590, Kamikaze 580, Hexor 590, Reflector 670.

## Пакеты

- `PayloadEscortInfoCommand{isInSession, playerRankRanges[RankRewardRangeModule{from,to}], timeInfoCommand,
  keyInfoCommand{keyCount}, totalScore}` — при каждом входе и смене карты. Диапазоны рейтинга 1, 2, 3, 4–5, 6–10, 11–25,
  26–50, 51–100.
- `PayloadEscortTimeInfoCommand{timeToNextSpawn, timeToGateClose, timeToSessionEnd}` — в начале часа 3600 / 299 / 2699;
  в XX:04:59 `timeToGateClose=1`; в XX:45 `timeToSessionEnd=0`. Последний рейс дня: `timeToNextSpawn=39600`.
- `PayloadEscortKeyInfoCommand{keyCount}`, `PayloadEscortScoreCommand{totalScore, runScore}`.
- На домашней карте с XX:58 до XX:00 сервер каждые ~2 с пишет «The EM Freighter has started its journey towards the
  signalling beacon. Wait for the next one to be ready.»

## DarkBot

**Ядро** (`DB/src`): 30 карт эскорта и виртуальная −3 (`StarManager.java:39, 189-220`, все `addGG`, выход `exitBy(1)`,
домой — −2 «Home Map»); `EscortProxy` (фасад `payload_escort`, `FacadeManager.java:78`) читает из клиента ключи и таймер
(`EscortAPI.getKeys()/getTime()`). Модуля в ядре нет.

**SharedPlugin `MimesisMutinyGate`** (`simple_galaxy_gate/gate/`; версии 07-06 и 08-08-2026 по логике одинаковы).
Ворота `MapInfo(-3, "Mimesis Mutiny", BASE_MAPS)`. Встроенная таблица (`NpcParam(радиус, приоритет, флаги)`, меньше
приоритет — раньше; имя по `contains`):

| NPC | Радиус | Приоритет | Флаги |
|---|---|---|---|
| Medic | 600 | −90 | держаться цели |
| Obscured, Mirror, Marker | 650 | −80 | держаться цели |
| Sniper | 600 | −70 | держаться цели |
| Piercing | 600 | −60 | держаться цели |
| Hounding | 600 | −50 | держаться цели |
| Inspirit | 600 | −30 | держаться цели |
| Hardy | 600 | −10 | держаться цели |
| Raider, Assailant | 600 | 0 | |
| Warhead | 560 | 10 | |
| Seeker Rocket | 560 | 20 | |
| Mimesis Turret | 560 | 50 | `NO_CIRCLE` |
| EM Freighter | 560 | 100 | `NO_CIRCLE`, `PASSIVE` |

- Если у пользователя NPC уже `kill=true`, радиус пользователя главнее; иначе плагин ставит kill, радиус, приоритет,
  флаги. Клон Mirror (макс. HP < 3M) не «держится цели».
- Константы: `MAX_RADIUS` 1800, `TOLERANCE_DISTANCE` и `FAR_TARGET_DISTANCE` 1200, `REPAIR_RADIUS` 900,
  `START_EARLY_SECONDS` 20; цели ищутся вокруг фрейтера (`useGuardableNpcAsSearchLocation`).
- Во вратах: порталы видны → режим бега, собрать ящики, уйти; фрейтер есть → центр карты = фрейтер, NPC и ящики дальше
  1800 от него не трогать, фрейтер не бить; кроме фрейтера в 1800 никого → ящики, иначе охрана (`moveToTarget`); есть
  враги → бой; фрейтера нет → ящики.
- Расписание по серверному времени (`ServerTimeHelper`): ждать 0, если час 10–23 и минута ≤ 4, иначе до следующего
  часа (10–22) или до 10:00; старт за 20 с. Пока ждёт — к станции или в безопасную точку, «start in … (N keys)»; ждать
  > 60 с → останавливает бота (таймер 180 с при ожидании > 1 ч, иначе 60 с) и сам запускает за 60 с до старта.

**DonorPlugin `MimesisGateModule`** (наследник `LootCollectorModule`, для донатеров; выбран в профилях друга).
- Настройки `config.mimesis`: `START_TRAVEL` (0–300 шагом 30, по умолчанию 30), `PAUSE_AND_WAIT` (да), «после смерти
  фрейтера» `AFTER`: `STAY_INSIDE` (% HP, 0,8), `IGNORE_NPC`, `IGNORE_BOXES` (нет).
- Карты: вход −3, выход −2; «на эскорте» — имя карты начинается с `Escort`.
- Часы — **локальные** (`LocalTime.now()`): окно — HH:59:55…HH:05:05. Неактивен = не на эскорте и (нет ключей или
  таймер > (в окне ? 305 : `START_TRAVEL`)).
- Шаги: неактивен — без `PAUSE_AND_WAIT` фармит рабочую карту, с ним — 5 с без питомца, стоп, пауза бота до активности;
  активен вне эскорта — лететь к −3; порталы видны и пора уходить — к −2; иначе охрана фрейтера.
- Охрана: поводок 2500 (ящик в 2500 от фрейтера) / 2000 (есть враги) / 600; дальше поводка или стоит без дела ближе 100
  → точка на орбите 350–900 от фрейтера по углу фрейтер → герой ± 0,05 рад (без врагов + случайно до 6 рад);
  «далеко» держится до 1700. Без дела или далеко — только лететь, иначе бой и сбор.
- Уходить: порталы видны, фрейтера нет, и (HP < `STAY_INSIDE` или (нет NPC или `IGNORE_NPC`) и (нет ящика или
  `IGNORE_BOXES`)).
- Цели (`MimesisGateLootModule`): не фрейтер, не в чёрном списке, в 2000 от живого фрейтера (после его смерти — если не
  `IGNORE_NPC`). Порядок: приоритет таблицы (текущей цели бонус 20 − hp%·10, если в 600 или захвачена) → бьёт фрейтер →
  никем не атакован → меньше HP → ближе. Радиус ×0,75, если NPC не бьёт героя; плохая цель — в чёрный список на 2 с.
  Оценка точки: −1000 недостижима, минус перекрытие с радиусами NPC, бьющих героя, минус (расстояние до фрейтера − 1600)·10.
- Ящики (`MimesisCollectorModule`): пока фрейтер жив и порталов нет — только в 2500 от него; потом — все, если не
  `IGNORE_BOXES`. Своей таблицы NPC нет — берёт профиль.
- Известные беды (гайд DarkBot ES): после врат не выходит из игры (только по бездействию); после смерти фрейтера не
  собирает ящики и при HP < 50 % уходит вопреки настройке; «пауза после выхода» — отключается.

**PikaPika `PressKeyMimesis`** (обфусцирован): работает на GG-картах 430–438 — новые 1439–1459, похоже, мимо (через
обфускацию не подтвердить). Имена по `contains`: Raider, Assailant, Medic, Hardy, Piercing, Hounding, Sniper, Obscured,
Mirror, Marker, Inspirit.
- «NPC есть»: HP героя < `MIN_HP` (0,25), NPC от 5 до 25, есть один из списка, раз в 10 с, предмет готов → нажать
  клавишу. «NPC нет» — наоборот (HP выше, никого из списка; для способностей вроде Solaris).
- Питомец: HP Link, если у питомца есть, HP питомца ≥ 0,7, HP героя < 0,7, есть цель (≤ 600); иначе ремонт питомца
  при HP < 0,9.

**Профиль друга** (`DB/configs/MIMESIS.json` = `CARNAGE/...`; `one/...` с отличиями в скобках): модуль Donor, рабочая
карта 14 (4); конфиг атаки 1 + формация 9, бег 2; RSB вкл. (выкл.); ремонт 0,3–0,6, воскрешение на месте; ящики
AGATUS_BOX и TREASURE; питомец GUARD. Donor: `START_TRAVEL` 240, `PAUSE_AND_WAIT` нет, `STAY_INSIDE` 0,2, `IGNORE_NPC`
да. Pika: клавиша 6 при HP < 0,7 (0,5), 5–31 NPC, раз в 10 с, если есть Hounding, Inspirit, Obscured, Mirror, Marker,
Sniper, Piercing; HP Link выкл. (вкл.). Плюс Penguin HealingAbility, DmPlugin AutoBestRocket и SmartX1Travel, Optimoth
CustomTechs и SimpleItems, SharedPlugin RepairPet и SimpleHealing. Таблица (радиус 560, Cloning 550):

| NPC | Приоритет | Патроны | Флаги |
|---|---|---|---|
| Obscured | −150 | 1 | |
| Sniper | −120 (−150) | | |
| Inspirit, Piercing | −100 | 1 | RSB |
| Terror, Reflector | −50 | 3 (1) | TECH1, ITEM2, бить чужих, RSB |
| Medic | −10 | 3 | RSB |
| Cloning | −4 | 3 | пеленгатор питомца, RSB |
| Raider, Hexor | 0 | 3 | RSB |
| Hardy, Turret | 0 | 1 / — | RSB |
| Raging, Kamikaze, Cloned | 1, 2, 3 | 1 | RSB |

Не бить: EM Freighter, Assailant, Warhead, Seeker Rocket, Barrage Seeker Rocket, Marker (в `one` ещё Hounding и Mirror).

## AstraBot

Бинарник — нативный образ GraalVM, имена обфусцированы; видно `MimesisModule.java`, `MimesisPanel.java`, пакеты
`PayloadEscort*`, состояния `ON_ESCORT_MAP`, `ON_ESCORT_OR_BATTLE_MAP`. Настройки панели: «Use working map»,
«Deactivate deflector», «Min distance to npcs», «Use Solaris+ ability», «Energy leech percent», «Craft EMAA-20», «Craft
booster», «Min npc to use ability», «Change target after seconds», «Back to gate after minutes».

**Конфиг** `configs/mimesis.json` (GE3 и killship одинаковы в разделе MIMESIS_MUTINY): модуль «Mimesis Mutiny», рабочая
карта 8 (2-4; у MMO — 4, 1-4); формации атака 15, бег/роум 18; HP 50/80 %; воскрешение у портала; не убегать при атаке.
`backToGateAfterMinutes 3`, `changeTargetAfterSeconds 60`, `craftEmaa20` да, `craftBooster` нет, питомец, ISH 45 %, EMP
50 %, leech 30 %, `minNpcToUseAbility 5`, `minDistanceToNpcs 580`, `goToWorkingMap` да, Solaris нет (в старом
MMO-варианте да). Патроны по умолчанию UCB-100 (GE3) / LCB-10 (killship), ракеты PLT-2021, радиус NPC 590.

| NPC | Бить | Приоритет | Патроны |
|---|---|---|---|
| Terror, Reflector (обычные карты) | да | −50 | EMAA-20; игнор. атакованных, агрессивное преследование, способность корабля |
| Cloning | да | −4 | EMAA-20 |
| Hexor, Raging, Kamikaze, Cloned | да | 0, 1, 2, 3 | UCB |
| Inspirit (эскорт) | да | −100 | EMAA-20 |
| Medic | да | −10 | EMAA-20 |
| Hardy | да | 0 | EMAA-20 |
| Raider, Turret | да | 0 | UCB (турель в killship MCB-50) |
| Assailant, Hounding, Mirror, Piercing, Obscured, EM Freighter, Warhead, Seeker Rocket | нет | | |

**Ход по логам** (GE3, 11–12.11):
1. Между рейсами — Kill & Collect на 2-4 (ключи).
2. За ~155 с до спавна летит 2-4 → 2-2 → 2-1 (MMO: 1-4 → 1-2 → 1-1).
3. В XX:00:00,35 прыжок (`jump iniate 5 436`), ключ −1. Карты EIC 1/2/3 = 436/437/438, MMO 433–435; почему порой 2-я
   или 3-я — по логу не видно.
4. В рейсе — следовать за фрейтером (переход к точке), сбор ящиков; Solaris+ раз в минуту (MMO), крафт EMAA-20 по рецепту
   (42 раза за лог).
5. Выход порталом через 1–2 минуты после бонуса (XX:30–XX:38, `jump iniate 436 5`), обратно на 2-4. Не ушёл — сервер
   в XX:45 ставит на 2-1.
6. Смерть — воскрешение на месте, на карте эскорта. killship: 34 из 38 и 36 из 40 смертей — на эскорте, больше всего от
   Obscured (его не бьёт), дальше Inspirit, Medic, Assailant, Piercing. GE3 — 1–2 смерти в день.

**Беды**: на каждом эскорте `ArrayIndexOutOfBoundsException: Index 1 out of bounds for length 1` в чтении пакетов
(«readall while hata») и быстрый переход каждые 3–40 с — около 1000 переподключений за лог; очки всё же идут.
Частые прыжки 2-4 ↔ 2-3 при фарме не объяснены.

## Как сделано у нас (2026-11-18)

Решение пользователя: «поведение берём с даркбота, автоматизацию с астработа; время — как лучше».

- Модуль `MIMESIS` (`orbit.bot.mod.MimesisModule`, «Мимесис (эскорт фрейтера)» во вкладке «Общее»), настройки
  `modules.mimesis`, вкладка «Мимесис» (`MimesisSection`), Default «Mimesis EIC 2-4», «Mimesis MMO 1-4», «Mimesis VRU 3-4».
- Мир: `WorldSnapshot.Gates.escort()` — ключи, `inSession`, три таймера (секунды от прихода пакета), очки рейса и
  всего — из четырёх `PayloadEscort*`.
- `GateCatalog.ESCORT_MAPS` (30 карт) и `GateCatalog.instanced(map)` — карта эскорта считается «вратами» для ремонта
  (кружит у цели, а не летит к порталу), выхода из игры (только через портал домой), запоминания порталов, радиуса NPC
  (без подстройки) и отступа от края. Карта (`MinimapPanel`) не тронута — заморожена.
- **Время**: таймеры сервера (`timeToGateClose` > 0 — вход открыт; `timeToNextSpawn` ≤ `startTravelSeconds` — лететь
  ко входу). Пока таймеров нет — часы компьютера, как DonorPlugin (HH:00–HH:05).
- **Между рейсами** (`between`): `farm` — Kill & Collect на рабочей карте (ключи), как AstraBot; `wait` — ждать на x-1;
  `logout` — выйти из игры и войти за `startTravelSeconds` + 2 мин до фрейтера (DonorPlugin «pause and wait»). Ключей
  меньше `minKeys` — не входить.
- **Вход**: на x-1 своей компании к порталу типа 54 у (10 500, 6 500), ждать рядом; вход открыт — прыжок (не больше 4
  попыток за час). После рейса в тот же час — не входить снова.
- **Рейс** (DonorPlugin): поводок 600 / 2 000 при NPC / 2 500 при ящике у фрейтера, орбита 350–900 (угол на героя
  ± 0,05, без NPC — случайный), «далеко» — только назад, пока не ближе 1 700. Цели — только в 2 000 от фрейтера:
  приоритет правил (текущей цели бонус 20 − HP·10 в 600 или захваченной), затем бьющие фрейтер, никем не захваченные,
  меньше HP, ближе; NPC, не бьющий корабль, — на ¾ радиуса; круг боя тянется к фрейтеру дальше 1 600. Ящики — в 2 500
  от фрейтера, в бою — в 400 от корабля. Фрейтера нет и портал есть — выход, когда HP < `after.stayHp` или нет NPC
  (или `ignoreNpc`) и ящиков (или `ignoreBoxes`). Фрейтер пропал из вида — к месту, где был.
- **Таблица целей** для NPC, которых нет в конфиге: эскорт — SharedPlugin (Medic −90, Obscured/Mirror/Marker −80, Sniper
  −70, Piercing −60, Hounding −50, Inspirit −30, Hardy −10, Raider/Assailant 0, Warhead 10, Seeker Rocket 20, Turret 50
  без круга, фрейтер не бить; сложные — «держать цель»); обычные карты — профиль друга (Terror и Reflector −50, бить
  чужих; Cloning −4; Hexor 0, Raging 1, Kamikaze 2, Cloned 3; радиус — рекомендуемый DarkBot). Имена с цифрами сводятся
  к буквам (`M1m3si5` → `mimesis`). Свои правила конфига главнее.
- **Автоматизация** (AstraBot): EMAA-20 (`emaa.mode`: `heavy` по умолчанию — как AstraBot на Terror, Reflector, Cloning,
  Medic, Inspirit, Hardy и тяжёлых эскорта Obscured, Mirror, Marker, Piercing, Hounding, Sniper, мелким — патроны конфига
  UCB-100; `all` — всем Мимесисам), пока его не меньше `emaa.min` (1 000), нет — патроны конфига (в журнале: «EMAA-20
  есть / нет»); RSB по Мимесисам не включается (решение пользователя 2026-11-18); крафт EMAA-20
  (`craftEmaa`, рецепт `recipe_blueprint-emaa-20-10k-repeat`, через `CraftWatch`); в рейсе EMP-01 при HP < 50 %, ISH-01
  < 45 % (лимиты расхода — вкладка «Атака»), пиявка < 30 %, Solace < 60 %, боевая способность (сначала Solaris+) при
  5 NPC в 800; HP Link питомца в бою при HP корабля < 70 % и питомца ≥ 70 % (PikaPika).
- Окно: карточка «Мимесис» на месте «Врат» (ключи, очки рейса и всего, «вход открыт ещё» / «фрейтер через»); журнал — ◈.
- Проверка конфига: воскрешение не «на месте» — предупреждение. Импорт DarkBot: DonorPlugin `MimesisGateModule`
  (START_TRAVEL, PAUSE_AND_WAIT, AFTER) и SharedPlugin `gateId −3` → модуль `MIMESIS`.
- Тесты: `MimesisModuleTest` (таймеры, имена, фарм → x-1, вход, цель у фрейтера, орбита, выход, крафт),
  `DarkBotImportTest`.

- **Как DarkBot и вне рейса** (живой тест 2026-11-18 на 3-4: цели бросались, бои с Reflector рвались): при фарме ключей
  цель держится, пока жива и доступна (`KillModule.keepTarget`, DarkBot `LootModule`), — не бросается ради более
  важной; ремонт только ниже порога (`BotModule.earlyRepair` = false, DarkBot `SafetyFinder`: `REPAIR_HP_RANGE.min`, без
  цели — `REPAIR_HP_NO_NPC`), не раньше из-за быстрой потери HP (облако Reflector — 4 %/с сквозь щит); бой с Мимесисами
  — всегда по кругу (`CIRCLE`, у DarkBot нет «держать дистанцию»). Default: ремонт 30 → 60 %, без NPC 25 % — профиль друга.
  Пересадка на корабль обслуживания (Скайлаб) — только вне эскорта и не позже чем за 10 мин до вылета; длинный привоз
  может съесть один рейс (решение пользователя 2026-11-18: оставить так).

- **Ящики как DarkBot** (первый рейс 2026-11-19 00:00–00:33 на Escort VRU 1: 58 ящиков, 7 раз долетел к уже взятому):
  только типы из списка конфига (Default: все `MIMESIS_AI_*` — AstraBot, `TREASURE` −10000 с ожиданием 1 с и
  `AGATUS_BOX` — профиль друга), не те, к которым летит другой игрок (`ignoreContested`), в бою — в радиусе сбора конфига
  (1 000, профиль друга); пока фрейтер жив — в 2 500 от него, после — любые в виду.
- **Чужие NPC на эскорте**: сервер серит NPC за первым ударившим, а награда — по урону; DonorPlugin владельца не
  спрашивает — у нас на картах эскорта всем правилам `IGNORE_OWNERSHIP` (было «чужая — бросаю» каждые 0,1 с).
- Формации как AstraBot `mimesis.json`: бой — Drill (15), полёт и бегство — Wheel (18), ремонт — конфигурация 2 + Ring.

## Проверено по записям и ботам (2026-11-18)

- **Портал входа виден всё время**, не только в окне: наш сниффер видел `JumpgateCreateCommand{designId=54, x=10500,
  y=6500}` на 2-8 в 19:14 (11.11), 15:13 (12.11), 18:39 и 19:16 (14.11) — вне HH:00–HH:05. Значит, «портал есть» ≠
  «вход открыт», открытость — только по `timeToGateClose`. Он стоит и на x-8 (DarkBot: BASE_MAPS x-1 и x-8).
- **Выход ведёт на x-1 своей компании**: логи AstraBot — `jump iniate 436 5` (EIC 1 → 2-1) 36 раз, 437 → 5 14 раз,
  438 → 5 2 раза, `433/434/435 → 1` (MMO → 1-1) 20 раз. Тип портала — 1 (DarkBot `exitBy(1)`).
- **Ящики**: у AstraBot в `COLLECTOR.boxList` (выучены им на эскорте и картах) — `MIMESIS_AI_TERROR_BOX`,
  `MIMESIS_AI_REFLECTOR_BOX`, `MIMESIS_AI_RAIDER_BOX`, `MIMESIS_AI_MEDIC_BOX`, `MIMESIS_AI_ASSAILANT_BOX`,
  `MIMESIS_AI_HARDY_BOX`, `MIMESIS_AI_MINI_BOSS_BOX`, `MIMESIS_AI_BOSS_BOX`; у профиля друга DarkBot — только
  `AGATUS_BOX` и `TREASURE` (событийный сундук). В Default «Mimesis …» все `MIMESIS_AI_*` включены (фарм ключей);
  на эскорте бот берёт любые ящики у фрейтера, кроме мин.
- **Клон Mirror**: SharedPlugin отличает — Mirror с макс. HP < 3 000 000 не «держать цель»; у нас так же
  (`MimesisModule.clone`). AstraBot Mirror не бьёт вовсе.

## Не сделано / проверить живым тестом

- Что сервер отвечает на прыжок без ключа или вне окна (бот пробует 4 раза за час и ждёт следующего).
- Выпускает ли сервер из игры на карте эскорта (бот, как во вратах, уходит через портал домой).
- Не мешаем ли фрейтеру собирать его ящики (он от них сильнее) — DonorPlugin собирает тоже.
- Способности NPC эскорта не описаны; бомба Marker (300k) и Kamikaze — не уклоняемся.
- Крафт бустера (AstraBot «Craft booster»), «Deactivate deflector», RM-B00 — не сделаны.
- Вход только с x-1 (x-8 не используется).

## Ссылки

- Анонс ноября 2026: https://board-en.darkorbit.com/threads/defend-against-the-mimesis-mutiny-november-2026.130500/
- FAQ ноября 2026: https://board-en.darkorbit.com/threads/f-a-q-mimesis-mutiny-event-november-2026.130498/
- Отзывы: https://board-en.darkorbit.com/threads/mimesis-mutiny-event-november-2026-feedback.130499/
- FAQ марта 2026: https://board-en.darkorbit.com/threads/mimesis-mutiny-event-faq.130137/
- Статы NPC 2021: https://board-en.darkorbit.com/threads/sc-mimesis-mutiny-npc-stats-efficiency-abilities.127094/
- Вики: https://darkorbitwiki.com/?p=4001, https://darkorbitwiki.com/galaxy-gates/mimesis-mutiny-gate/,
  https://darkorbitwiki.com/ships/mimesis
- Гайд DarkBot ES: https://darkbotes.gitbook.io/darkbot-es-guide/como-cofigurar-el-bot/tutoriales/mimesis-event-gate
