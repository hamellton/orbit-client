# Этап 3: вход на сайт и старт игровой сессии

Наблюдения от 2026-11-09: официальный клиент (macOS, 2D), аккаунт на GE1. Шесть записей сниффера:
`session-…-2` — холодный запуск клиента, `-4 … -9` — смены карты внутри одного процесса клиента
(каждая предыдущая сессия кончается `ReloginCommand`).

## Вход на сайт

- Форма `bgcdw_login_form` → `sas.bpsecure.com/Sas/Authentication/Bigpoint` → `www.darkorbit.com/ProjectApi/Authentication`
  → `int1.darkorbit.com/ProjectApi/Authentication` (ставит `lastLogin`) → `indexInternal.es`.
- `dosid` сайт выдаёт любому посетителю, в том числе без входа. Вход подтверждает только страница
  игры (`indexInternal.es?action=internalMapRevolution` с параметрами Flash). Отказ: переадресация
  на `index.es?…&loginError=95`.
- CDN (Alibaba) ставит cookie `acw_tc` с `Max-Age`. Из-за неё `java.net.CookieManager` переходит на
  формат RFC 2965 (`$Version`, значения в кавычках), и сайт отклоняет `dosid` в кавычках
  (`loginError=95`). `HttpSession` собирает заголовок `Cookie` сам.
- Примерно 4 входа по паролю за несколько минут с одного IP включают hCaptcha в форме входа (видно
  уже на обычном GET главной страницы). Поэтому бот хранит `dosid` и входит по нему.

## Первые пакеты после входа

Ответ сервера одинаков во всех записях: через ~0,4 с после `LoginRequest` пачкой приходят
`UserSettingsCommand`, `UserKeyBindingsUpdate`, `UILayoutCommand`, `ShipInitializationCommand`…,
`LoginResponse` — ещё через ~0,4 с. Клиент начинает отвечать, когда загружены корабль и карта.

Паузы — от `LoginResponse` (первая строка) и от предыдущего пакета клиента (остальные).

**Холодный запуск** (UI ещё нет):

| пакет | пауза, мс |
|---|---|
| `HangarListRequest` | ~9 200 (загрузка клиента) |
| `SpaceSearchRequest` action 0, 4, 2 | 25–45, 30–45, 0–10 |
| `LabUpdateRequest` | 5–12 |
| `ReadyRequest` 0 (`MAP_LOADED_2D`), 2 (`UI_READY`) | 1–5, 45–65 |
| `LegacyModule "LF\|w"` | 150–220 |
| `AudioSettingsRequest` (значения из `UserSettingsCommand`) | 35–60 |
| `CommandCenterGetDontShowRequest` | 8–20 |
| `UserKeyBindingsUpdate` (полная таблица клиента) | 35–60 |
| `GalaxyGateBuilderInfoRequest` | 20–35 |
| `KeepAlive` | ~700 |

**Смена карты / переподключение** (UI уже есть):

| пакет | пауза, мс |
|---|---|
| `SpaceSearchRequest` action 0, 4, 2 | 700–1 800, 12–45, 1–45 |
| `WindowSettingsRequest` (раскладка панелей клиента) | 15–65 |
| `ReadyRequest` 0, 2 | 0–30 (редко до 280), 15–70 |
| `LegacyModule "LF\|w"` | 5–40 (редко до 280) |
| `AudioSettingsRequest` | 0–40 |
| `UserKeyBindingsUpdate` | 10–60 (редко до 265) |
| `KeepAlive` | 10–45 (редко до 475) |
| `CommandCenterGetDontShowRequest` | 0–60 (редко до 480) |

«Редко» — подвисания клиента (отрисовка, GC); бот воспроизводит их с вероятностью 15 %.

Таблицы выше — первые записи. По 14 сессиям (3 холодных старта, 11 смен карты, 2 прыжка) порядок не
фиксирован: запросы настроек шлют независимые части интерфейса по мере загрузки. Холодный старт:
загрузка 1–9 с, затем строго ангар → `SpaceSearch` ×3 → лаборатория → `Ready` ×2 → `LF|w`, потом звук
(не всегда) и `CommandCenter`/клавиши/`GalaxyGate` в любом порядке, затем `KeepAlive`. Смена карты: хвост
«клавиши, KeepAlive, CommandCenter» или «KeepAlive, CommandCenter, клавиши»; первая пауза 0,07–2,2 с от
`LoginResponse`. Прыжок: три `SpaceSearch` или один. Бот выбирает один из допустимых вариантов,
паузы — по всем замерам; `StartupSequenceTest` проверяет, что каждая запись совпадает с одним из них.

Откуда что берётся (код клиента 2026-11-04):
- `ReadyRequest` ×2 и `LF|w` — `ConnectionManager`, когда `isHeroLoaded && isMapLoaded`.
- `WindowSettingsRequest` — `SaveBarsSettingsCommand`, только когда панели UI уже созданы (поэтому
  его нет при холодном запуске). Значения — локальное состояние UI клиента, не от сервера:
  `slotmenuPosition` уходит раньше, чем сервер присылает его в `MenuInitializationCommand`.
- `UserKeyBindingsUpdate` — полная таблица клавиш клиента (35 записей, свой порядок, действия 8 и 16
  без клавиш), а не эхо серверных 25.
- Эти два пакета и строку `ReadyRequest` бот берёт из записи клиента этого аккаунта
  (`client-profile` → `local/client-profile.json`) и повторяет байт в байт.

Не отправляем (пока):
- `ChatAuthenticateUserRequest` — уходит, только когда клиент подключён к отдельному чат-серверу;
  `endpointId` выдаёт чат-сервер (при обрыве чата клиент шлёт пустой). Бот к чату не подключается.
- `CommandCenterNewsInfoRequest` (список новостей с сайта), `UIWindowUpdateRequest`,
  `ReturneeLoginHideUIItemRequest`, `LegacyModule "i|<id>"` (наведение на объекты) — реакции UI и
  действия пользователя.

## Реакции на сервер

- `LegacyModule "0|7|HS"` (`CLIENT_SETTING`) приходит около `ReadyRequest`. Клиент отвечает
  `"JCPU|GET"` всегда после своего `LF|w`: через 0,15–1,3 с, иногда до 3,6 с.
- `ReloginCommand` → клиент через 4–9 мс шлёт `ChannelCloseRequest{close=true}` и переподключается
  (иногда сервер закрывает соединение раньше).
- Повторный `ShipInitializationCommand` посреди сессии — прыжок через портал на карту того же сервера
  (см. ниже); бот отвечает тёплой последовательностью.

## Модель мира (`world`)

- Корабли, NPC (`ShipCreateCommand.npc`), питомцы (`PetActivationCommand`, свой — `PetHeroActivationCommand`)
  движутся по `MoveCommand`: прямая к точке за `timeToTarget` мс. Удаление — `ShipRemoveCommand`,
  уничтожение — `ShipDestroyedCommand`.
- Ящики и руда — `AddBoxCommand`/`AddOreCommand` по `hash`, сбор/исчезновение — `RemoveCollectableCommand`.
  Порталы — `JumpgateCreateCommand`, станции и прочие объекты — `AssetCreateCommand`, зоны — `MapAddPOICommand`
  (`shape`: 0 круг x,y,r; 1 многоугольник; 2 прямоугольник; `inverted` — зона снаружи фигуры).
- Содержимое карты сервер присылает после `ReadyRequest` клиента; `ShipInitializationCommand` (вход,
  возрождение) и `ReloginCommand` очищают карту.
- Для героя сервер `MoveCommand` не шлёт: клиент ведёт корабль сам (скорость из
  `ShipInitializationCommand`/`AttributeShipSpeedUpdateCommand`, единиц в секунду), а сервер раз в ~1 с
  присылает `BeaconCommand` с `position`, `positionAhead` и флагами зон. Маяки приходят с неровной
  задержкой. Сверка с положением, которое клиент сам сообщает в каждом `MoveRequest` (438 точек):
  вести героя только по своим `MoveRequest` — медиана 7, p90 46, p99 157 единиц; поправлять по
  `position` маяка — 12 / 86 / 368; по `positionAhead` — 12 / 226 / 329. Поэтому маяк учитывается только
  при расхождении больше 1000 единиц (прыжок, телепорт, старое положение после смены карты).
- Груз: `ShipInitializationCommand.cargoSpace` — **свободное** место (продали 108 руды — `cargoSpace`
  вырос ровно на 108), `cargoSpaceMax` и `0|A|c|<max>` — вместимость. Руда в трюме —
  `AttributeOreCountUpdateCommand` (полный список), ресурсы — `InventoryItemUpdatedCommand`.
- Предметы (боеприпасы, ракеты, мины, CPU, технологии, способности): полный список с категориями — в
  `MenuInitializationCommand` при входе, дальше изменения в `MenuItemStatusCommand` (`counterValue`,
  `selected`, `available`…). Перезарядка: `MenuItemTimerCommand` для одного предмета и
  `MenuItemCooldownGroupTimerCommand` для всех предметов с тем же `cooldownGroup`
  (`UpdateMenuItemCooldownGroupTimerCommand`). `time` — миллисекунды: клиент передаёт его во флэшевый
  `Timer` и сравнивает с `getTimer()` (`ItemTimerVO`, `CountDownTimer`). Вживую не проверено: в записях
  только нулевые таймеры; `LiveFactsReplayTest` проверит, как только появится запись с перезарядкой.
- Урон: `AttackHitCommand` несёт абсолютные HP, щит и нанокорпус цели (для героя тоже, `AttackHitHandler`),
  `AttackHitNoLockCommand` — ещё и максимумы. `AttributeHitpointUpdateCommand` клиент не обрабатывает.
  Максимум щита героя меняется `AttributeShieldUpdateCommand` (смена конфигурации: 263 900 ↔ 343 070);
  попадание может прийти на доли миллисекунды раньше нового максимума. Сверено на записи `-8`: 39
  попаданий по герою до его гибели.
- `sessionID` клиент повторяет открытым текстом при смене конфигурации (`S|CFG|<n>|<userID>|<sessionID>`).
  Сниффер маскирует его во всех исходящих пакетах соединения (до исправления — только в `LoginRequest`).

### Сообщения `LegacyModule` в модели

| сообщение | смысл (обработчик клиента) |
|---|---|
| `0\|A\|c\|N` | вместимость трюма (`CARGO_CHANGE`) |
| `0\|A\|CC\|N`, `0\|S\|CFG\|N` | выбранная конфигурация (оба меняют `Settings.selectedConfiguration`) |
| `0\|A\|v\|N` | скорость героя (`VELOCITY_UPDATE`) |
| `0\|A\|BK…\|N` | ключи (`BOOTY_KEYS_*`) |
| `0\|n\|w\|N` | число врагов для предупреждения (`ENEMY_WARNING`) |
| `0\|n\|INV\|id\|0/1` | корабль невидим (`TARGET_INVISIBLE`) |
| `0\|n\|LSH\|id\|attacker` | цель атакует другой игрок (не герой и не группа) — клиент делает её серой (`TARGET_FADE_TO_GRAY`) |
| `0\|n\|USH\|id` | цель снова свободна |

Не в модели (интерфейс): `0|n|t` титулы, `0|n|d` дроны, `0|n|MSG`, `0|LM` журнал наград, `0|UI|MM` метки
мини-карты. `0|l` — сервер завершает сессию (клиент закрывается).

Внимание: клиент меняет конфигурацию командой `S|CFG|<n>|<userID>|<sessionID>` — в ней `sessionID`,
такие пакеты нельзя писать в журнал как есть (бот регистрирует `sessionID` в `SecretMask`).

### Прыжок через портал на том же сервере

`JumpRequest` → `JumpInitiatedCommand{mapId}` → (~3 с) `JumpToServerCommand` и полная повторная
инициализация в том же соединении (`UserSettingsCommand`, …, `ShipInitializationCommand`), без
`LoginResponse`. Клиент через ~30 мс отправляет тёплую последовательность (без `KeepAlive` и
`CommandCenterGetDontShowRequest`); `JCPU|GET` снова после своего `LF|w`. Если карта на другом сервере —
`ReloginCommand`.

## Данные карт (3.7)

- **Размер** — в клиенте `Map.init`: `21000 × 13100 × scaleFactor`, `scaleFactor` из
  `graphics/maps-config.xml` (по хэшу из `xml/resources.xml`; `<reference … children="true">` копирует
  только дочерние элементы, не атрибуты). Обычные карты 21000×13100, 4-4/4-5/5-1/5-3 — 42000×26200,
  экспериментальные зоны и JP — 10500×6550. `tools/update_maps.py` → `reference/maps/maps.json`
  (243 карты). Сверено: 223 маяка героя в записях лежат внутри размеров своих карт (на 4-4 — y до 25 175).
- **Порталы**: сервер присылает только положение (`JumpgateCreateCommand`), куда портал ведёт — нет ни в
  пакетах, ни в клиенте. Граф обычных карт (108 карт, 124 портала: 1-x…5-x, BL, экспериментальные зоны) —
  факты из DarkBot `StarManager` (`tools/import_darkbot_portals.py` → `reference/maps/portals.json`, коммит
  DarkBot записан в файле). Сверено с записями: все 24 обычных портала (`designId = 1`) совпали до единицы,
  все 9 переходов (`ReloginCommand`/`JumpInitiatedCommand`) ведут туда, куда указывает граф. Порталы с другим
  `designId` (на 3-1: 54, 55, 300, 304, 307) — врата GG и события, в граф не входят.
- **Препятствия** — POI-зоны сервера: клиент проверяет движение через `POIZoneManager.checkCollisions`.
  Прямоугольник — рамка всех точек (`RectangleZone`). На картах из записей запретных зон и зон радиации
  нет — только круги объектов (`ENTER_LEAVE`) и зона станции (`EQUIPPABLE_ZONE`).
- **Радиация** — в модели «за пределами карты или в зоне `POITYPE_RADIATION`»; не проверено: в записях
  маяки ни разу не сообщали `inRadiationZone`.
- **Мирная зона** — флаг `BeaconCommand.inPeaceArea` (24 маяка у станции); зоной её сервер не присылает.
