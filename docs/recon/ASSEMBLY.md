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

## Оснащение через сайт (страница инвентаря, 2026-11-14, gb2)

Окно ангара в игре у мини-клиента — только пушки (`hangarCannons`); процессоры ставятся на сайте. Страница инвентаря —
Flash `swf_global/inventory/inventory.swf` (декомпилирован FFDec): все её вызовы — `POST flashAPI/inventory.php`,
`action=<имя>`, `params` = base64 JSON всего объекта запроса, к нему добавляется `params.hi` = ангар.

- `getHangar`, `{"params":{"hi":<ангар>}}` → `data.ret.items[]` (`I` id, `L` индекс в `data.map.lootIds`, `EQH` ангар,
  где стоит, `null` = запасной, `EQC` конфиг, `CH` "Charges: N", `properties.durability`), `data.ret.hangars{…}.config
  {"1","2"}.ship.EQ.{lasers,generators,heavy_guns,extras,ship_upgrades}` — id по конфигам. Список включает предметы,
  стоящие в других ангарах. Один процессор может стоять сразу в обоих конфигах.
- `move` (перетаскивание, `DragComponent` → `ConnectionProxy.itemMoveRequest`):
  `{"action":"move","params":{"hi":H},"from":{"configId":C,"target":"inventory","items":["id"]},
  "to":{"configId":C,"target":"ship","slotset":"extras"}}`; снять — `from` корабль со `slotset`, `to`
  `{"configId":C,"target":"inventory","index":0}`. Ответ `{"isError":0,"data":{"ret":1,"money":{…}}}`.
  Проверено: CL04K-XL снят из конфига 1 и поставлен обратно (аккаунт был не в игре).
- Питомец: в `getHangar` рядом с `ship` — `config{"1","2"}.pet.EQ.{lasers,generators,gears,protocols}` (gb2: по 7 лазеров
  в конфиге), `general.pet.lockedSlots`. Перенос — тот же `move` с `"target":"pet"` и `slotset` (`ActionIdentifiers.PET`);
  `from.items` — массив: страница переносит несколько выбранных предметов одним вызовом. Снятие лазеров питомца у нас —
  `Hangars.move(h, ids, c, false, "pet", "lasers")`. Проверено 2026-11-14 на VPS (GE1): у штаба 2-8, 24 лазера
  из двух конфигов за два вызова, сайт после этого лазеров не показывает, перевход 4 с.
