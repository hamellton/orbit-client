# Врата галактики (GG): как устроены, как их проходят AstraBot и DarkBot, как делаем мы

Задача пользователя (2026-11-14): модуль врат по примеру AstraBot и DarkBot на VPS; сначала проверяем ABG (α, β, γ),
Зету, Каппу и Trinity Trials, потом остальное; тест — только локально на gb2.

Источники: клиент (`reference/decompiled/2026-11-04`, `GalaxyGateBuilderModel.as`, окно `trinityTrials`), протокол
(`reference/protocol/packets-2026-11-04.json`), запись gb2 `recordings/session-20261114-183923-2.dorec` (окно постройки:
спины, части, «подготовить прыжок»), DarkBot (`StarManager`, `GalaxyBuilderProxy`), плагины DarkBot на VPS (SharedPlugin
`SimpleGalaxyGate` — читаемый; CarnagePluginPlus `GalaxyModule`, `TrinityModule`; KekkaReloaded), профили CARNAGE
(`ABG.json`, `TT.json`), AstraBot (`GALAXY_GATES`, `TRINITY`, логи заходов G1/GE3/killship). Кода не копируем, только факты.

## 1. Как устроены врата в игре

### Постройка (окно `ggBuilder`, всё по игровому сокету)

| врата | номер (`gateId`) | частей | волн | карта | портал на x-1 (`designId`) |
|---|---|---|---|---|---|
| α Alpha | 1 | 34 | 40 | 51 GG α | 2 |
| β Beta | 2 | 48 | 40 | 52 GG β | 3 |
| γ Gamma | 3 | 82 | 40 | 53 GG γ | 4 |
| δ Delta | 4 | 128 | 29 | 55 | 5 |
| ε Epsilon | 5 | 99 | 30 | 70 | 53 |
| ζ Zeta | 6 | 111 | 46 | 71 → 72 → 73 (ζ1, ζ2, ζ3) | 54 (не в центре карты) |
| κ Kappa | 7 | 120 | 29 | 74 | 70 |
| λ Lambda | 8 | 45 | 21 | 75 | 71 |
| Kronos | 12 | 21 | 50 | 76 | 72 |
| Hades | 13 | 45 | 12 | 203 | 74 |
| Kuiper (ς) | 19 | 100 | 54 | 300–304 | 82 |
| Trinity Trials | — (своё окно) | — | ~20 | Hellfire I–V 499–503, Genome I–V 504–508 (Fiesta 494–498) | 304 |

Номера, части и волны — из клиента; карты — `maps.json`; типы порталов — `StarManager` DarkBot (тип портала DarkBot =
наш `JumpgateCreateCommand.designId`: на 3-1 в записях видны 54, 55, 300, 304, 307 — ζ, события и Trinity). Портал врат
появляется на домашних x-1 (1-1, 2-1, 3-1), когда врата поставлены. Портал «эскорта» тоже 54, но ровно в центре
(10 500 / 6 500) — вход в ζ — тот 54, что не в центре.

Пакеты (клиент сначала открывает окно: `UIWindowUpdateRequest{itemId="ggBuilder", maximized=true}`):
- `GalaxyGateBuilderInfoRequest` → `GalaxyGateBuilderInfoCommand`: `money` (уридий), `samples` (свободная энергия для
  спинов), `energy_cost`, `spinAmounts` (1/5/10/100), `spinamount_selected`, `gates[]` — у каждых врат `id`, `mode`
  ("alpha"…), `state` (0 идут, 1 пройдены), `currPart`, `currWave`, `livesLeft` и `lifePrice` (−1 — не поставлены),
  `multiplier`. Мы шлём запрос в `StartupSequence`, ответ приходит (gb2 14.11: уридий 297 546, энергии 9 546, α 5 частей).
- `GalaxyGateBuilderGetGatePartsRequest{gateId}` → `…GetGatePartsCommand{gatePartIds}` — какие части собраны (gb2: α 5/34,
  β 45/48, γ 6/82, δ 0).
- Спин: `GalaxyGateBuilderSaveSpinAmountRequest{spinAmount}` (выбор 1/5/10/100), затем
  `GalaxyGateBuilderMultiEnergyRequest{gateId, spinAmount, useMultiplier}` → `…MultiEnergyCommand{errorCode (0, 1 — нет
  денег), money, samples, multiplier, items[], gatePartIds}`. gb2: каждый спин списал 1 энергию (`samples` 9546 → 9545…),
  уридий не тронут; выпадают батареи, ракеты, мины, руда, части, множитель (`items[].type`). Клиент спинит раз в 0,3–0,8 с.
- Поставить врата («подготовить прыжок»): `GalaxyGateBuilderSetupGateRequest{gateId}` →
  `…SetupGateCommand{id, livesLeft, lifePrice, errorCode}` (gb2 14.11: Kuiper, 5 жизней, жизнь 5 000). После этого на x-1
  появляется портал.
- Купить жизнь: `GalaxyGateBuilderBuyLifeRequest{gateId}` → `…BuyLifeCommand{livesLeft, lifePrice, money, errorCode}`.

### Во вратах

Сервер шлёт: `GalaxyGateBuilderGateWaveUpdateCommand{gateId, currWave}`, `…GateLifeLostCommand{gateId, livesLeft}`,
`…GateCompleteCommand{gateId, currPart}`. В игре видно (AstraBot ingameLogs): отсчёт `-=- 15 -=-` … `-=- 0 -=-`,
`5 lives left`, `Wave 1` … и `Gate completed.` с наградой. Как это выглядит в пакетах (текстовые строки?) — **не записано**.

- ABG: 40 волн = 10 этапов по 4 волны; после каждого этапа появляется портал, который ведёт на **ту же карту**
  (`jump iniate 53 53`), там снова отсчёт 15 с. Одна волна ~11 с; γ целиком ~16 мин.
- ζ: три карты, портал 54 внутри ведёт ζ1 → ζ2 → ζ3.
- Смерть во вратах: −1 жизнь, возрождение только на базе (AstraBot: варианты [1]); вход снова через портал на x-1, волны
  продолжаются (`Wave 33` после смерти). Прогресс хранит сервер — после перезахода врата продолжаются с той же волны.
- Конец: `Gate completed.` + награда (γ: 60 000 UCB-100, 600 ксеномита, 10 лог-файлов, 12 Schism, 3 000 люминия,
  60 000 уридия). AstraBot сразу перезаходит в игру («kapı bitti yeniden giriş yapılıyor») — попадает на x-1 и идёт
  в следующие врата; DarkBot уходит порталом типа 1 (выход домой).
- Trinity: вход без портала: окно `trinitytrials_difficultyselect` (`TrinityTrialsGateWindowCommand{attributeType,
  openAttributes[] (param0 — карта, param1 "1" — бесплатно), openDifficulty, lootId, curLootAmount, isFree}`), вход —
  `TrinityTrialsGateEnterRequest{attributeType, difficulty}`. AstraBot: «kapı bilgisi alınıyor» (раз в 5 с) → «Giriyor attr 1
  difficulty 4» → через 1 с прыжок на 502 Hellfire IV. attr 1 = Hellfire, 2 = Genome; сложность N = номер карты I–V.
  20 волн за 5–6 мин, жизней нет, в конце `Gate completed.`, назад на x-1, сразу снова.

## 2. Как это делает AstraBot (`GALAXY_GATES`, `TRINITY`)

- Модуль «Galaxy Gates»; врата `gateInfo.<имя> {gateId, jump, priority, livesLeft, exit}` (G1 Gate: α 3, β 2, γ 1, все
  jump). В логах порядок смешанный — входит в те, что поставлены, приоритет при равенстве. `minLivesToJump` 1–2.
- Постройка: `spin` да, `spinAmount` 1, `spinMultiplier` 2, `spinDelay` 150–500 мс, `maxSpinCost` 55–100,
  `keepMinUriSpin` 1 000 000, `buyLife` нет (`maxLifeToBuy` 2). Спинит **и во время врат** (в игре «You received −140
  uridium» ~раз в секунду).
- Бой: NPC из `KILL.npcList` с именами вида `-=[ Streuner ]=- α1` (символ врат + номер волны): радиус 450–610,
  патроны (MCB-50, LCB-10), формации, опции «Aggressive Follow», «Use RSB» и т. п.; `dragShipToCenter`,
  `dontLetNpcToCorner` + `cornerAmmo`, `lockDiff`, `afRadius`; в конце врат — `endGateConfig` 2 + `endGateFormation`
  Veteran; `collectResources`, `collectBoxRadius` 400–2 500; камикадзе питомца (`minNpcsToUseKamikaze` 8);
  `suicideIfGateBugged`.
- Цикл: вход с 2-1 через ~15 с после входа в игру → волны → «Gate completed» → перезаход → следующие врата; смерть →
  база → снова во врата.
- Trinity: `TRINITY {jumpingMap X-1|X-8, attributeType Hellfire|Genome, difficulty Easy|Expert|Nightmare,
  useSecondConfig/secondConfig}`; переключение конфигов по расписанию.

## 3. Как это делает DarkBot (плагины)

SharedPlugin `SimpleGalaxyGate` (логика читается), по тикам:
1. Во вратах (карта не `x-y` и не `xBL`): есть NPC из списка → бой; нет — ящики; нет ящиков, но есть портал (кроме типов
   1 и 302) — прыжок в него (ζ1→2→3, Kuiper); иначе ждать в центре (10 000 / 6 000). Застрял (`stuckInGateTimerMinutes` 1)
   — перезаход, в центр.
2. Вышел из врат (был «внутри») — врата засчитаны, можно сменить профиль.
3. Не во вратах: на x-1 ищет видимый портал врат (ABG — γ, β, α по порядку) и летит в него.
4. Постройка (`GateBuilder`): энергия есть, или (`useExtraEnergyOnly` нет) цена спина ≤ `maxSpinCost` и уридий ≥
  `minUriBalance`; спины 5/10/100 по прогрессу; множитель при ≥ `useMultiAt`; все части собраны и врат нет на карте →
  поставить; поставил, а портала нет — перезаход.
5. NPC: подстрока имени → радиус; флаги KAMIKAZE, STICK_TO_TARGET, FINISH_OFF (у ζ — ящики, когда Devourer ниже 25 %).
CARNAGE `ABG.json`: α приоритет 3, β 2, γ −1; ремонт 0,3–0,8; конфиг 1 + Moth, конец врат — конфиг 2 + Veteran;
`MAX_DEATHS` 10, возрождение на базе, ожидание 90 с; TT — `difficulty MAX`, `npcRadius` 580, держать цель до смерти.

## 4. NPC по вратам (радиусы из профилей, бить всех)

- ABG (номера волн): Streuner 1–4 (450), Lordakia 5–8 (500), Mordon 9–12 (550), Saimon 13–16 (580), Devolarium 17–20 (560),
  Kristallin 21–24 (605), Sibelon 25–28 (570), Sibelonit 29–32 (605), Kristallon 33–36 (610), Protegit 37–40 (630).
  Камикадзе питомца — на Kristallin, Saimon, Sibelonit.
- ζ: Infernal 550, Scorcher 590, Melter 590, Devourer 590 (приоритет), Streuner/StreuneR/Boss/Uber 550–590, Lordakia,
  Saimon, Sibelonit, Kristallin (Boss/Uber) 550–590.
- κ: Streuner, Vagrant, Infernal, Marauder, Scorcher, Boss Mordon, Outcast, Devolarium, Icy, Boss Sibelonit, Corsair,
  Hooligan, Kristallin, Melter, Interceptor, Barracuda, Annihilator, Boss Lordakium, Boss Sibelon, Protegit, Kucurbium,
  UberSaimon, Convict, Boss Kristallin, demaNeR, BossKucurbium, Century Falcon — радиус 560 (никто не настраивал).
- Trinity (`⦓1…⦓24`): обычные пришельцы + Magma/Spore/Veil Stalker (580, RSB), Pyrospire/Vinespire (620), Chronospire,
  P.E.T. Stalker — 560–620.

## 5. Как делаем мы

Модуль `GALAXY_GATE` (`orbit.bot.mod.GateModule`) + справочник врат (`GateCatalog`: номер, карты, тип портала, части,
волны, NPC с радиусами по умолчанию). `World` уже читает пакеты постройки и врат (`WorldSnapshot.Gates`) и убирает
исчезнувшие порталы (`JumpGateRemoveCommand`).

Настройки (`modules.gate`):
- `gates` — список по порядку: `{gate: "alpha"|"beta"|"gamma"|"zeta"|"kappa"|"trinity"…, on, minLives}`;
- постройка: `build` (спинить), `spinAmount`, `useMultiplier`, `energyOnly` (только бесплатная энергия), `keepUridium`,
  `place` (ставить, когда части собраны), `buyLives` + `maxLivesToBuy`;
- бой: NPC из таблицы конфига, а кого там нет — из справочника (радиус, бить); `boxRadius`; `toCenter` (ждать в центре);
  `endGateConfig`/`endGateFormation` на последних волнах;
- Trinity: `trinityAttribute` (Hellfire/Genome), `trinityDifficulty` 1–5.

Цикл модуля:
1. **Подготовка**: окно постройки (как клиент), информация о вратах; выбрать первые по списку, у которых есть жизни
   (поставлены) и жизней ≥ `minLives`. Не поставлены, а части собраны — поставить. Частей не хватает и `build` — спины
   (с паузой 0,3–0,8 с, пока есть энергия или уридий выше `keepUridium`); нечего делать — следующие врата или ждать.
2. **Перелёт**: на свою x-1; там портал с `designId` врат (ζ — не в центре); подлететь и прыгнуть.
3. **Во вратах**: бить NPC (обычный `Attacker`, цели — как в Kill), между волнами — ящики в `boxRadius`, иначе к центру.
   Портал внутри (этап ABG, ζ1→2→3) — когда NPC и ящиков нет, прыгнуть.
4. **Конец** (`GateCompleteCommand` этих врат): собрать ящики, перезайти (как AstraBot) — на x-1, следующие врата.
5. **Смерть**: возрождение по конфигу (на базе), жизни есть — снова во врата; жизней нет — врата кончились.
6. **Trinity**: на x-1 запросить окно (`TrinityTrialsUIRequest`), по ответу — `TrinityTrialsGateEnterRequest(атрибут,
   сложность)`; внутри — как во вратах; в конце — назад на x-1 и снова.

## 6. Что не известно — смотрим первым тестом (снифер, официальный клиент, gb2)

1. Вход в поставленные врата: `designId` портала на x-1 (ждём α 2, β 3, γ 4, ζ 54, κ 70, Trinity 304).
2. Пакеты внутри: отсчёт, волны, «lives left», портал этапа, «Gate completed» — в каком виде (пакеты или текст).
3. Выход: портал типа 1 или перезаход; что шлёт клиент.
4. Trinity: значения `attributeType` и `openAttributes`, ответ на вход.

## 7. Тест на gb2 (локально)

Сначала одна запись снифером с официальным клиентом: поставить α (у gb2 5/34 частей — сначала спины; β 45/48 почти
собраны), войти, пройти 1–2 этапа, выйти. По записи сверить пп. 6.1–6.3 и доделать модуль. Потом бот: α → β → γ, затем
ζ и κ (нужны части), затем Trinity (нужен пропуск/ключ — `curLootAmount`).
