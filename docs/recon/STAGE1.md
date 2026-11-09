# Этап 1. Сниффер: итоги

Дата: 2026-11-09. Сервер GE1, клиент `DarkOrbit Client.app` 2.8.0, сборка игры 71960444.

## Результат живой сессии

| Показатель | Значение |
|---|---|
| Игровых соединений | 6 (вход + 5 переходов между серверами) |
| Пакетов | 12 018 |
| Разных типов | 184 |
| Неизвестных id | **0** |
| Ошибок разбора полей | **0** (каждый пакет разобран до последнего байта) |
| Ошибок распаковки LZ4 / CRC | 0 |

Значения полей проверены по смыслу: координаты в пределах карты, `MoveRequest` героя продолжает
предыдущую точку, `AttackLaserRequest.targetId` совпадает с id NPC из `ShipCreateCommand`,
имена NPC и типы ящиков читаются. Значит, циклические сдвиги и симметрия `read`/`write`
восстановлены верно (открытый вопрос 4 из отчёта этапа 0 закрыт).

## Что подтвердилось и что нового

- **Перехват.** Flash (PepperFlash) **игнорирует прокси браузера** и подключается к игровому серверу
  напрямую. Рабочая схема: клиент запускается с
  `--host-resolver-rules="MAP *.prod.darkorbit.com 127.0.0.1"`, сниффер слушает порты 6001–6007 на
  `127.0.0.1` и пересылает на настоящий IP. Трафик сайта идёт через SOCKS5 (для наблюдения).
  Правка swf и hosts-файла не нужна.
- **GE1 = кластер 22**: `gameserver-22-0N-public.prod.darkorbit.com`, IP `170.33.12.50`,
  `LoginRequest.instanceId = 22`.
- **Вход** точно как в отчёте этапа 0: байт `0x00` → `VersionRequest` → `VersionCommand{equal=true}` →
  `LoginRequest{version="do-lz4-block-v3", isMiniClient=true}` → сразу сжатый поток
  (`EnergyUpdateCommand`, `UserSettingsCommand`, `UserKeyBindingsUpdate`, `WindowItemCommand`…,
  `UILayoutCommand`, `PlayerUpdateCommand`, `LegacyModule`…). Ответ на вход ~0,35–0,5 с.
- **Смена карты = переподключение.** Сервер шлёт `ReloginCommand`, клиент закрывает сокет и заново
  входит на другой порт кластера (6003 ↔ 6004 ↔ 6005). Боту нужно повторять то же самое.
- **KeepAlive**: клиент шлёт каждые ~15 с (иногда раньше — 6,7 с), сервер отвечает `StayinAlive`
  (30 из 30). Поле `MouseClick` во всех записях `false`.
- **Вход на сайт защищён hCaptcha** (`api.hcaptcha.com`) — важно для этапа 3: вход по
  логину/паролю без участия человека может не пройти, нужен вход по готовому `dosid`.
- Капча на карте (`CaptchaTriggerCommand`) и `LogBoxInformationRequest` в сессии **не встретились**.

## Самые частые пакеты

Клиент → сервер: `MoveRequest` 439, `SpaceSearchRequest` 64, `LegacyModule` 63, `KeepAlive` 30,
`UIWindowUpdateRequest` 22, `ReadyRequest` 14, `UIButtonTriggerRequest` 14, `JumpRequest` 5,
`AttackLaserRequest` 1, `InstantRepairRequest` 1, `PetRequest` 2.

Сервер → клиент: `MoveCommand` 2805, `MenuItemStatusCommand` 1523, `LegacyModule` 1488,
`MenuItemTimerCommand` 883, `QuestConditionUpdateCommand` 659, `RemoveCollectableCommand` 316,
`AddOreCommand` 228, `AttackLaserRunCommand` 215, `ShipCreateCommand` 151, `AddBoxCommand` 96.

## Как пользоваться

```sh
scripts/sniff-session.sh                       # сниффер + клиент, лог в logs/sniffer.log
sniffer/build/install/sniffer/bin/session-report --decode recordings
sniffer/build/install/sniffer/bin/session-report --show MoveRequest,AddBoxCommand recordings/<файл>.dorec
```

Записи — `recordings/*.dorec` (вне git: в них id аккаунта и ники других игроков; `sessionID`
замаскирован). Формат описан в `SessionRecorder`.

## Чего ещё нет в записях (снять позже, по мере надобности)

Капча на карте, смерть и возрождение, ремонт на базе, ракеты/смена патронов, способности,
бустеры, группа. Сниффер их разберёт так же — это вопрос только сценария игры.
