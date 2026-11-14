# Крафт, сборщики, врата — пакеты (запись 2026-11-14, gb2)

Запись `recordings/session-20261114-183923-2.dorec` (только локально): официальный клиент через сниффер, аккаунт gb2,
кластер 502 (порты 6101–6107). Всё ниже — игровое соединение; магазин сайта идёт по HTTPS и в запись не попадает
(покупка на сайте у нас уже есть: `orbit.auth.Shop`, `ajax/shop.php`). Окно `new_shop` в игре — другой магазин.

## Сборка (окно `assembly`)

Открыть окно: `UIButtonTriggerRequest{itemId="assembly"}`. Фильтр разделов: `AssemblySetFilterRequest{filterType=0,
assemblyItemFilters=[ammunition|weapons|extras|…]}` — только вид окна.

Крафт — два запроса:
1. `AssemblyBuildRequest{amount, targetItemId=0, recipeLootId}` — запуск, `amount` — сколько штук (2 → партия из двух).
2. `AssemblyCollectRequest{recipeLootId, targetItemId=0}` — забрать всю партию одним запросом.

Ответы сервера на RSB-75 ×2:
- сразу после запуска — `InventoryItemUpdatedCommand` по каждому списанному ресурсу (новые остатки: кредиты, уридий,
  `resource_collectable_rinusk`, `resource_collectable_blacklight-trace`…) и `AssemblyRecipeContentModule` со статусом
  `AssemblyRecipeBuildStatusModule{buildAmount=2, prevBuildCount=0, remainingTimeMillis=4831}`;
- когда готово (~5 с) — статус `AssemblyRecipeReadyToCollectStatusModule{buildAmount=2}`;
- после сбора — `InventoryItemUpdatedCommand{lootId="ammunition_laser_rsb-75", amount=<новый остаток>}` и статус
  `AssemblyRecipeAvailableStatusModule{currentBuildCount=0}`.

Рецепты из записи (`recipeLootId`):

| Что | Рецепт |
|---|---|
| процессор маскировки (BL) | `recipe_blueprint-cloakcpu-blacklight` |
| сепром | `recipe_blueprint-seprom-blacklight` |
| ЕБГ-процессор | `recipe_blueprint-eternal-cpu` |
| пушка «Прометей» | `recipe_blueprint-prometheus-laser` |
| RSB-75 | `recipe_blueprint-rsb75-blacklight` |
| A-BL | `recipe_blueprint-a-bl-laser-ammo` |

## Сборщики (окно `dispatch`)

- `DispatchGetRetrieverListRequest{statusType=1}` — список;
- `DispatchCollectRetrieverRequest{slotId}` — забрать из слота (1–5);
- `DispatchSendRetrieverRequest{dispatchId}` — отправить (в записи `dispatchId=13`, пять раз — пять слотов).

## Врата (окно `ggBuilder`)

- `GalaxyGateBuilderGetGatePartsRequest{gateId}` — части врат;
- `GalaxyGateBuilderSetupGateRequest{gateId}` — выбрать врата для сборки;
- `GalaxyGateBuilderSaveSpinAmountRequest{spinAmount}` — сколько прокруток за раз (1, 5, 10, 100);
- `GalaxyGateBuilderMultiEnergyRequest{gateId, spinAmount, useMultiplier}` — прокрутка.

## Прочее из записи

- ангар: `HangarShipMoveItemsRequest{hangarId, slotSet="cannons"|"cannon_shields", source="ship"|"inventory",
  destination=…, items=[id]}`;
- Арсенал: `ArmoryUnlockInfoRequest`, `ArmoryUnlockDrawRequest{count, type}`.
