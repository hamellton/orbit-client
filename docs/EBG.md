# Вечные врата Блэклайт (EBG, Eternal Blacklight Gate)

Перенос «1 в 1 как DarkBot» (SharedPlugin `EternalBlacklightGate`) с дополнениями пользователя (2026-11-16): число
забегов, крафт CPU, выход из игры, когда всё кончилось, обогащённые лазеры перед забегом, способности на тяжёлых NPC.
Источники: SharedPlugin 0.12.10 (байткод), DarkBot `StarManager`/`EternalBlacklightProxy`, KekkaReloaded, клиент
(`EternalBlacklight*`), профили друга `DB/configs/EBG.json`, `EBG_OnSterodis.json`, AstraBot GE3 (`ebg.json`, логи 07.11 и
10.11).

## Игра
- Карты 460–465 «Eternal Blacklight» (экземпляры, 21 000 × 13 100). Вход: на 1/2/3-BL используется Eternal CPU
  (`ammunition_ggportal_eternal-blacklight-cpu`), появляется портал 54, прыжок. CPU крафтится в сборке
  (`recipe_blueprint-eternal-cpu`). Число CPU сервер шлёт сам (`EternalBlacklightCpuInfoCommand`).
- Жизнь одна: смерть — забег окончен, следующий с 1-й волны. Портал домой — тип 1.
- Волны циклами по 51: этапы по 10 (1–10 … 41–50), 51 — босс Strokelight Barrage (+ Barrage Seeker Rocket, Abide I,
  Steadfast III). После этапа — портал на ту же карту и отсчёт 15 с. Суффикс `EBG<n>` в имени NPC — волна цикла.
  Uber Kristallon — волны 23 и 47 (AstraBot гиб на 98 и 149 ≡ 47).
- Пакеты: `EternalBlacklightInfoCommand` (при входе: волна, бустеры, CPU), `…WaveInfoCommand{currentWave, furthestWave}`,
  `…CpuInfoCommand{cpuCount}`, `…SkillPointInfoCommand`, `…BoosterInfoCommand{option1..3, currentBooster}`
  (тип 0 перезарядка, 1 урон, 2 урон лазеров, 3 урон ракет, 4 точность, 5 корпус, 6 щит, 7 скорость; значение — %);
  выбор — `EternalBlacklightChooseOptionRequest{1..3}`, окно `eternal_blacklight`.
- Доход (AstraBot до 149-й волны, ~57 мин): ≈ 346 млн кредитов, 75 000 уридия.

## Как сделано у нас
- `GateCatalog`: врата `eternal` (`builderId` −4 — как `gateId` DarkBot, импорт профиля DarkBot их находит), карты
  460–465, портал 54, цикл 51, радиусы NPC из npcMap SharedPlugin (прочим 560), Barrage Seeker Rocket — приоритет −90,
  Abide I и Steadfast III — не бить (`EBG_PASSIVE`). Разбор имён понимает `EBG<n>`.
- `World`: `WorldSnapshot.Gates.eternal` — CPU, волна, очки и варианты бустеров.
- `EternalGate` (+ `GateModule`):
  - вне врат: забеги кончились → на базу и выход (`BotModule.stopRequest`) или ждать; лазеры меньше `lasersMin` →
    стоять, пока «Обогащение» не обогатит (не дольше `lasersWaitMinutes`, затем идти, если `lasersGoAnyway`); на BL
    без портала и с CPU — CPU раз в 3 с; портал — в него; CPU нет — крафт (`CraftWatch`, правило из `craft`) или
    «нет CPU» → выход/ждать; иначе лететь на BL (`map`);
  - во вратах: бой как ABG (SharedPlugin), центр и допуск по волне Uber Kristallon (двое ближе 300 — (6000, 4000), иначе
    (14000, 8000), допуск 1 500; обычно (10000, 6000) и 4 000) — для круга (`Attacker.gateStyle(centre, tolerance)`),
    дальних целей и точки ожидания; между волнами — бустеры по приоритету (меньше — важнее, при равенстве больший %);
    только порталы этапов, домой — только по тормозу «выход»;
  - тормоз `brakeOnWave` (0 — нет): «самоубийство» — огонь стоп, питомец пассивный, к NPC, ближе 1 000 — стоять до
    гибели (без ремонта); «выход» — между волнами в портал домой. Забег считается при выходе из врат.
  - способности (`ish`, `emp`, `ability` — имена NPC; `leech` с волны) — флаги USE_ISH/USE_EMP/USE_ABILITY/USE_LEECH
    правилам NPC; порог ISH — `loot.options.ishBelow`.
- Конфиг: вкладка «Вечные врата» (`EbgSection`), `modules.gate.eternal`; Default «EBG» по профилю друга; карта —
  плашка «Вечные врата · волна · CPU»; журнал — «∞ забег начат/окончен, CPU, тормоз, бустер».

## Не сделано / проверить живым тестом
- Щит ISH на NPC (SharedPlugin пропускает такие, пока есть другие) — нужен разбор `VisualModifierCommand`.
- Как именно использовать CPU (клавиша/панель), сколько живёт портал, когда сервер шлёт волну без окна, куда ведёт
  портал домой, когда даются очки бустеров, есть ли ящики и «волна с минами».
- Первый тест: GB2, «EBG GB2» (1 забег, тормоз 12 «выход», лазеры ≥ 50 000).
