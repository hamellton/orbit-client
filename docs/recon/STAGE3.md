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
`StartupSequenceTest` сверяет порядок и паузы с записями.

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
- Повторный `ShipInitializationCommand` посреди сессии (запись `-6`, 52 с) → клиент снова шлёт
  `ReadyRequest` 0 и 2 через ~0,3 с. Пока не реализовано (нужен разбор причины: возрождение?).

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
