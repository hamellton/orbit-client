# Сборка (крафт): все рецепты и где брать ресурсы

Сгенерировано `tools/craft_doc.py` из клиента 2026-11-04 (`ItemDataProvider.as`: рецепты, цены, время) и текстов игры
(`resource_items.xml`: названия и краткие эффекты); где брать ресурсы — `reference/craft/sources-ru.json` (ведётся
руками). Обновить: `scripts/update-craft-doc.sh` (после декомпиляции нового клиента). Правки — в генераторе и в
файле источников, не здесь.

Как читать:
- **Цена** — за одну сборку. У рецептов с уровнями (улучшения предметов) — цена первого уровня и число уровней.
- **Когда**: «всегда» — рецепт в Сборке постоянно; «в событие» — только пока идёт событие или его расписание;
  «когда есть чертёж» — виден, если чертёж уже есть. «Один раз», «раз за событие», «до N раз» — сколько можно собрать.
- Клиент знает и рецепты прошлых событий: «в событие» значит, что сейчас его может и не быть в Сборке.
- Сколько чего есть у аккаунта и что сейчас доступно, игра присылает при входе (`AssemblyRecipeContentModule`).

## Разделы

- [Корабли](#корабли) — 42
- [Дизайны кораблей](#дизайны-кораблей) — 104
- [Дроны и их дизайны](#дроны-и-их-дизайны) — 41
- [Лазеры](#лазеры) — 24
- [Ракетомёты](#ракетомёты) — 5
- [Генераторы](#генераторы) — 6
- [Модули апгрейда корабля](#модули-апгрейда-корабля) — 89
- [Процессоры и экстры](#процессоры-и-экстры) — 6
- [Прочее оборудование](#прочее-оборудование) — 26
- [Лазерные боеприпасы](#лазерные-боеприпасы) — 24
- [Ракеты, мины и другие боеприпасы](#ракеты-мины-и-другие-боеприпасы) — 44
- [Улучшения предметов (уровни)](#улучшения-предметов-уровни) — 17
- [Ресурсы и руды](#ресурсы-и-руды) — 119
- [Питомец](#питомец) — 27
- [Бустеры](#бустеры) — 11
- [Прочее](#прочее) — 49
- [Ресурсы: где брать](#ресурсы-где-брать)

## Корабли

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Berserker <sub>`recipe_blueprint-chromin-berserker`</sub> | Линейный крейсер | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Centurion <sub>`recipe_blueprint-centurion`</sub> | Starfighter | Ключ к зелёному трофею ×200, Кредиты ×750 000 000, UCB-100 ×75 000, RSB-75 ×15 000, Капсула Black Light ×15 000 | 5 с | всегда, один раз |
| Centurion <sub>`recipe_blueprint-chromin-centurion`</sub> | Starfighter | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Citadel Plus <sub>`recipe_blueprint-ship-citadel-plus`</sub> | Линейный крейсер | Citadel ×1, Уридиум ×1 000 000, Диаметрион ×2 500 | 12 ч | всегда, один раз |
| Cyborg <sub>`recipe_blueprint-cyborg`</sub> | Battle Cruiser | Индоктринационная нефть ×450, Гибридный процессор ×20, Микротранзисторы ×15, Наноконденсатор ×30 | 5 с | в событие, один раз |
| Cyborg <sub>`recipe_blueprint-chromin-cyborg`</sub> | Battle Cruiser | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Diminisher <sub>`recipe_blueprint-diminisher`</sub> | Боевой крейсер | Высокочастотный кабель ×10, Микротранзисторы ×1, Наноконденсатор ×8 | 5 с | в событие, один раз |
| Diminisher <sub>`recipe_blueprint-chromin-diminisher`</sub> | Боевой крейсер | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Goliath Bastion <sub>`recipe_blueprint-bastion`</sub> | Боевой крейсер | Нанокейс ×5, Призма-слот ×4, Наноконденсатор ×4 | 5 с | в событие, один раз |
| Goliath Centaur <sub>`recipe_blueprint-g-centaur`</sub> | Боевой крейсер | Нанокейс ×8, Наноконденсатор ×2, Призма-слот ×2 | 5 с | в событие, один раз |
| Goliath Champion <sub>`recipe_blueprint-g-champion`</sub> | Боевой крейсер | Мьюкосум ×1 200, Плазмид ×260, Скрапиум ×450 | 5 с | в событие, один раз |
| Goliath Peacemaker <sub>`recipe_blueprint-g-peacemaker`</sub> | Боевой крейсер | Нанокейс ×8, Наноконденсатор ×2, Призма-слот ×2 | 5 с | в событие, один раз |
| Goliath Plus <sub>`recipe_blueprint-ship-goliath-plus`</sub> | Линейный крейсер | Goliath ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×2 500 | 12 ч | всегда, один раз |
| Goliath Sovereign <sub>`recipe_blueprint-g-sovereign`</sub> | Боевой крейсер | Нанокейс ×8, Наноконденсатор ×2, Призма-слот ×2 | 5 с | в событие, один раз |
| Goliath Surgeon <sub>`recipe_blueprint-ancient`</sub> | Боевой крейсер | Плазмид ×650, Скрапиум ×2 800, Призматиум ×60 | 5 с | в событие, один раз |
| Goliath Vanquisher <sub>`recipe_blueprint-g-vanquisher`</sub> | Боевой крейсер | Нанокейс ×8, Наноконденсатор ×2, Призма-слот ×2 | 5 с | в событие, один раз |
| Goliath-X <sub>`recipe_blueprint-goliath-x-regular`</sub> | Дизайн | Высокочастотный кабель ×7, Нанокейс ×7, Гибридный процессор ×2 | 5 с | в событие, один раз |
| Hammerclaw <sub>`recipe_blueprint-chromin-hammerclaw`</sub> | Ремонтный корабль | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Hammerclaw <sub>`recipe_blueprint-hammerclaw`</sub> | Ремонтный корабль | Индоктринационная нефть ×450, Гибридный процессор ×20, Микротранзисторы ×15, Наноконденсатор ×30 | 5 с | в событие, один раз |
| Hammerclaw Plus <sub>`recipe_blueprint-ship-hammerclaw-plus`</sub> | Ремонтный корабль | Hammerclaw ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Hecate <sub>`recipe_blueprint-chromin-hecate`</sub> | Battle Cruiser | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Hecate <sub>`recipe_blueprint-flower-token-hecate`</sub> | Battle Cruiser | Лепестковый кварц ×15 000 | 5 с | в событие, один раз |
| Hecate Plus <sub>`recipe_blueprint-ship-hecate-plus`</sub> | Линейный крейсер | Hecate ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 250 | 12 ч | всегда, один раз |
| Keres <sub>`recipe_blueprint-keres`</sub> | ЛИНЕЙНЫЙ КРЕЙСЕР | Индоктринационная нефть ×500, Гибридный процессор ×20, Микротранзисторы ×15, Наноконденсатор ×30 | 5 с | в событие, один раз |
| Leonov <sub>`recipe_blueprint-leonov`</sub> | STAR JET | Нанокейс ×2, Высокочастотный кабель ×1 | 5 с | в событие, один раз |
| Liberator Plus <sub>`recipe_blueprint-ship-liberator-plus`</sub> | Линейный крейсер | Liberator ×1, Уридиум ×1 000 000, Диаметрион ×2 000 | 12 ч | всегда, один раз |
| Mimesis <sub>`recipe_blueprint-chromin-mimesis`</sub> | Stealth Fighter | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Mimesis <sub>`recipe_blueprint-mimesis`</sub> | Stealth Fighter | Индоктринационная нефть ×450, Гибридный процессор ×20, Микротранзисторы ×15, Наноконденсатор ×30 | 5 с | в событие, один раз |
| Pusat <sub>`recipe_blueprint-pusat`</sub> | Истребитель | Плазмид ×500, Скрапиум ×2 300 | 5 с | в событие, один раз |
| Pusat Plus <sub>`recipe_blueprint-ship-pusat-plus`</sub> | Истребитель | Pusat ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Retiarus Plus <sub>`recipe_blueprint-ship-retiarus-plus`</sub> | Линейный крейсер | Retiarus ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Sentinel <sub>`recipe_blueprint-sentinel`</sub> | Боевой крейсер | Нанокейс ×6, Призма-слот ×3, Гибридный процессор ×4 | 5 с | в событие, один раз |
| Solace <sub>`recipe_blueprint-solace`</sub> | Боевой крейсер | Высокочастотный кабель ×10, Микротранзисторы ×1, Наноконденсатор ×8 | 5 с | в событие, один раз |
| Solace Plus <sub>`recipe_blueprint-ship-solace-plus`</sub> | Линейный крейсер | Solace ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 250 | 12 ч | всегда, один раз |
| Solaris Plus <sub>`recipe_blueprint-ship-solaris-plus`</sub> | Линейный крейсер | Solaris ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Spearhead Plus <sub>`recipe_blueprint-ship-spearhead-plus`</sub> | Корабль-разведчик | Spearhead ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Spectrum <sub>`recipe_blueprint-spectrum`</sub> | Боевой крейсер | Высокочастотный кабель ×10, Микротранзисторы ×1, Наноконденсатор ×8 | 5 с | в событие, один раз |
| Spectrum Plus <sub>`recipe_blueprint-ship-spectrum-plus`</sub> | Быстрый выживальщик | Spectrum ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Tartarus Plus <sub>`recipe_blueprint-ship-tartarus-plus`</sub> | Тяжелый крейсер | Tartarus ×1, Уридиум ×1 000 000, Индоктринационный ускоритель ×500, Диаметрион ×3 000 | 12 ч | всегда, один раз |
| Vengeance Lightning <sub>`recipe_blueprint-v-lightning`</sub> | Истребитель | Наноконденсатор ×2, Гибридный процессор ×4 | 5 с | в событие, один раз |
| Venom <sub>`recipe_blueprint-venom`</sub> | Боевой крейсер | Высокочастотный кабель ×10, Микротранзисторы ×1, Наноконденсатор ×8 | 5 с | в событие, один раз |
| Zephyr <sub>`recipe_blueprint-chromin-zephyr`</sub> | Звездный истребитель | Хромин ×80 000 | 5 с | в событие, раз за событие |

## Дизайны кораблей

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Psyche Pusat Plus <sub>`recipe_blueprint-pusat-plus-psyche`</sub> | Дизайн корабля | Осколок Charopos ×200, Осколок Styxus ×1 400 | 5 с | в событие, один раз |
| Дизайн Amor Berserker <sub>`recipe_blueprint-berserker-amor`</sub> | Дизайн корабля | Осколок Charopos ×1 400, Осколок Styxus ×200 | 5 с | в событие, один раз |
| Дизайн Amor Goliath Plus <sub>`recipe_blueprint-goliath-plus-amor`</sub> | Дизайн корабля | Осколок Charopos ×1 600, Осколок Styxus ×1 600 | 5 с | в событие, один раз |
| Дизайн Amor Solaris <sub>`recipe_blueprint-solaris-amor`</sub> | Дизайн корабля | Осколок Styxus ×200, Осколок Charopos ×1 400 | 5 с | в событие, один раз |
| Дизайн Arctic Nostromo Ambassador <sub>`recipe_blueprint-n-ambassador-frost`</sub> | Дизайн | Высокочастотный кабель ×12, Наноконденсатор ×6, Микротранзисторы ×3 | 5 с | в событие, один раз |
| Дизайн Arctic Pusat <sub>`recipe_blueprint-pusat-frost`</sub> | Дизайн | Высокочастотный кабель ×12, Наноконденсатор ×6, Микротранзисторы ×3 | 5 с | в событие, один раз |
| Дизайн Arctic Sentinel <sub>`recipe_blueprint-sentinel-frost`</sub> | Дизайн | Высокочастотный кабель ×12, Наноконденсатор ×6, Микротранзисторы ×3 | 5 с | в событие, один раз |
| Дизайн Arctic Spectrum <sub>`recipe_blueprint-spectrum-frost`</sub> | Дизайн | Высокочастотный кабель ×12, Наноконденсатор ×6, Микротранзисторы ×3 | 5 с | в событие, один раз |
| Дизайн Arctic Vengeance Lightning <sub>`recipe_blueprint-v-lightning-frost`</sub> | Дизайн | Высокочастотный кабель ×12, Наноконденсатор ×6, Микротранзисторы ×3 | 5 с | в событие, один раз |
| Дизайн Arctic Venom <sub>`recipe_blueprint-venom-frost`</sub> | Дизайн | Высокочастотный кабель ×12, Наноконденсатор ×6, Микротранзисторы ×3 | 5 с | в событие, один раз |
| Дизайн Argon Tartarus <sub>`recipe_blueprint-tartarus-argon`</sub> | Дизайн | Нанокейс ×8, Наноконденсатор ×6, Гибридный процессор ×3 | 5 с | когда есть чертёж, один раз |
| Дизайн Asimov Citadel Plus <sub>`recipe_blueprint-agate-asimov-citadel-plus`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Cyborg <sub>`recipe_blueprint-agate-asimov-cyborg`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Mimesis <sub>`recipe_blueprint-agate-asimov-mimesis`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Orcus <sub>`recipe_blueprint-agate-asimov-orcus`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Pusat Plus <sub>`recipe_blueprint-agate-asimov-pusat-plus`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Sentinel <sub>`recipe_blueprint-agate-asimov-sentinel`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Solace <sub>`recipe_blueprint-agate-asimov-solace`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Solace Plus <sub>`recipe_blueprint-agate-asimov-solace-plus`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Spectrum <sub>`recipe_blueprint-agate-asimov-spectrum`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Asimov Zephyr <sub>`recipe_blueprint-agate-asimov-zephyr`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Blaze Pusat <sub>`recipe_blueprint-pusat-blaze`</sub> | Дизайн | Высокочастотный кабель ×4, Наноконденсатор ×3, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн Blaze Spectrum <sub>`recipe_blueprint-spectrum-blaze`</sub> | Дизайн | Высокочастотный кабель ×4, Наноконденсатор ×3, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн Borealis Goliath-X <sub>`recipe_blueprint-goliath-x-borealis`</sub> | Дизайн | Высокочастотный кабель ×8, Наноконденсатор ×5, Микротранзисторы ×2 | 5 с | в событие, один раз |
| Дизайн Contagion Solace <sub>`recipe_blueprint-solace-contagion`</sub> | Дизайн корабля | Индоктринационная нефть ×60, Гибридный процессор ×5, Микротранзисторы ×10, Наноконденсатор ×10 | 5 с | в событие, один раз |
| Дизайн Contagion Solace <sub>`recipe_blueprint-champion-ship-solace-contagion`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Dogma Goliath Plus <sub>`recipe_blueprint-season-goliath-plus-dogma`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн Eldritch Diminisher <sub>`recipe_blueprint-season-diminisher-eldritch`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн Harbinger Diminisher <sub>`recipe_blueprint-champion-ship-diminisher-harbinger`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Harbinger Disruptor <sub>`recipe_blueprint-champion-ship-disruptor-harbinger`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Harbinger Orcus <sub>`recipe_blueprint-champion-ship-orcus-harbinger`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Harbinger Sentinel <sub>`recipe_blueprint-champion-ship-sentinel-harbinger`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Horus Cyborg <sub>`recipe_blueprint-season-cyborg-horus`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн Lava Pusat <sub>`recipe_blueprint-pusat-lava`</sub> | Дизайн | Высокочастотный кабель ×10, Призма-слот ×5, Гибридный процессор ×2 | 5 с | в событие, один раз |
| Дизайн Lava Spectrum <sub>`recipe_blueprint-spectrum-lava`</sub> | Дизайн | Высокочастотный кабель ×10, Призма-слот ×5, Гибридный процессор ×2 | 5 с | в событие, один раз |
| Дизайн Legend A-Elite <sub>`recipe_blueprint-a-elite-legend`</sub> | Дизайн | Микротранзисторы ×2, Гибридный процессор ×7, Наноконденсатор ×7 | 5 с | когда есть чертёж, один раз |
| Дизайн Legend Pusat <sub>`recipe_blueprint-pusat-legend`</sub> | Дизайн | Скрапиум ×500 | 5 с | когда есть чертёж, один раз |
| Дизайн Legend Spectrum <sub>`recipe_blueprint-spectrum-legend`</sub> | Дизайн | Скрапиум ×500 | 5 с | когда есть чертёж, один раз |
| Дизайн Legend Tartarus <sub>`recipe_blueprint-tartarus-legend`</sub> | Дизайн | Призма-слот ×10, Микротранзисторы ×2, Гибридный процессор ×5 | 5 с | когда есть чертёж, один раз |
| Дизайн Mobius Orcus <sub>`recipe_blueprint-season-orcus-mobius`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн Ocean A-Elite <sub>`recipe_blueprint-a-elite-ocean`</sub> | Дизайн | Гибридный процессор ×3, Aurus ×3 | 5 с | в событие, один раз |
| Дизайн Ocean Goliath-X <sub>`recipe_blueprint-goliath-x-ocean`</sub> | Дизайн | Высокочастотный кабель ×4, Наноконденсатор ×3, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн Ocean Tartarus <sub>`recipe_blueprint-tartarus-ocean`</sub> | Дизайн | Нанокейс ×6, Наноконденсатор ×4, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн Phantasm Berserker <sub>`recipe_blueprint-candy-phantasm-berserker`</sub> | Дизайн корабля | Золотая кукурузная конфета ×35, Candy Corn ×50 000 | 5 с | в событие, раз за событие |
| Дизайн Phantasm Diminisher <sub>`recipe_blueprint-candy-phantasm-diminisher`</sub> | Дизайн корабля | Золотая кукурузная конфета ×35, Candy Corn ×50 000 | 5 с | в событие, раз за событие |
| Дизайн Phantasm Goliath <sub>`recipe_blueprint-goliath-phantasm`</sub> | Дизайн корабля | Золотая кукурузная конфета ×35, Candy Corn ×50 000 | 5 с | в событие, раз за событие |
| Дизайн Poison A-Elite <sub>`recipe_blueprint-a-elite-poison`</sub> | Дизайн | Гибридный процессор ×3 | 5 с | когда есть чертёж, один раз |
| Дизайн Poison Hecate <sub>`recipe_blueprint-flower-token-poison-hecate-ship-design`</sub> | Дизайн корабля | Лепестковый кварц ×10 700 | 5 с | в событие, один раз |
| Дизайн Poison Pusat <sub>`recipe_blueprint-pusat-poison`</sub> | Дизайн | Нанокейс ×4, Призма-слот ×5 | 5 с | когда есть чертёж, один раз |
| Дизайн Poison Spectrum <sub>`recipe_blueprint-spectrum-poison`</sub> | Дизайн | Нанокейс ×4, Призма-слот ×5 | 5 с | когда есть чертёж, один раз |
| Дизайн Poison Tartarus <sub>`recipe_blueprint-tartarus-poison`</sub> | Дизайн | Нанокейс ×6, Наноконденсатор ×4, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн Psyche Berserker <sub>`recipe_blueprint-berserker-psyche`</sub> | Дизайн корабля | Осколок Charopos ×200, Осколок Styxus ×1 400 | 5 с | в событие, один раз |
| Дизайн Psyche Cyborg <sub>`recipe_blueprint-cyborg-psyche`</sub> | Дизайн корабля | Осколок Charopos ×200, Осколок Styxus ×1 400 | 5 с | в событие, один раз |
| Дизайн Psyche Goliath Plus <sub>`recipe_blueprint-goliath-plus-psyche`</sub> | Дизайн корабля | Осколок Charopos ×200, Осколок Styxus ×1 400 | 5 с | в событие, один раз |
| Дизайн Psyche Orcus <sub>`recipe_blueprint-orcus-psyche`</sub> | Дизайн корабля | Осколок Charopos ×200, Осколок Styxus ×1 400 | 5 с | в событие, один раз |
| Дизайн Psyche Solaris <sub>`recipe_blueprint-solaris-psyche`</sub> | Дизайн корабля | Осколок Styxus ×1 400, Осколок Charopos ×200 | 5 с | в событие, один раз |
| Дизайн Psyche Spectrum Plus <sub>`recipe_blueprint-spectrum-plus-psyche`</sub> | Дизайн корабля | Осколок Charopos ×200, Осколок Styxus ×1 400 | 5 с | в событие, один раз |
| Дизайн Samurai Citadel Plus <sub>`recipe_blueprint-season-citadel-plus-samurai`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн Sandstorm Goliath-X <sub>`recipe_blueprint-goliath-x-sandstorm`</sub> | Дизайн | Высокочастотный кабель ×8, Наноконденсатор ×5, Микротранзисторы ×2 | 5 с | в событие, один раз |
| Дизайн Seraph Cyborg <sub>`recipe_blueprint-champion-ship-cyborg-seraph`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Seraph Diminisher <sub>`recipe_blueprint-champion-ship-diminisher-seraph`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Seraph Disruptor <sub>`recipe_blueprint-champion-ship-disruptor-seraph`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Seraph Orcus <sub>`recipe_blueprint-champion-ship-orcus-seraph`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Seraph Sentinel <sub>`recipe_blueprint-champion-ship-sentinel-seraph`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Tyrannos Centurion <sub>`recipe_blueprint-champion-ship-centurion-tyrannos`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Tyrannos Centurion <sub>`recipe_blueprint-agate-tyrannos-centurion`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Tyrannos Cyborg <sub>`recipe_blueprint-champion-ship-cyborg-tyrannos`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Tyrannos Disruptor <sub>`recipe_blueprint-agate-tyrannos-disruptor`</sub> | Дизайн корабля | Агатовый осколок ×8 000 | 5 с | в событие, один раз |
| Дизайн Tyrannos G-Champion <sub>`recipe_blueprint-agate-tyrannos-g-champion`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Tyrannos Hecate <sub>`recipe_blueprint-champion-ship-hecate-tyrannos`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Tyrannos Mimesis <sub>`recipe_blueprint-champion-ship-mimesis-tyrannos`</sub> | Дизайн корабля | Жетон чемпиона ×120 | 5 с | когда есть чертёж, один раз |
| Дизайн Tyrannos Sentinel <sub>`recipe_blueprint-agate-tyrannos-sentinel`</sub> | Дизайн корабля | Агатовый осколок ×8 000 | 5 с | в событие, один раз |
| Дизайн Tyrannos Solace <sub>`recipe_blueprint-agate-tyrannos-solace`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Tyrannos Solace Plus <sub>`recipe_blueprint-agate-tyrannos-solace-plus`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Tyrannos Spectrum <sub>`recipe_blueprint-agate-tyrannos-spectrum`</sub> | Дизайн корабля | Агатовый осколок ×15 000 | 5 с | в событие, один раз |
| Дизайн Ullrin Berserker <sub>`recipe_blueprint-chromin-berserker-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin C-Elite <sub>`recipe_blueprint-chromin-c-elite-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Cyborg <sub>`recipe_blueprint-chromin-cyborg-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, один раз |
| Дизайн Ullrin Diminisher <sub>`recipe_blueprint-chromin-diminisher-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Disruptor <sub>`recipe_blueprint-chromin-disruptor-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Goliath Plus <sub>`recipe_blueprint-chromin-goliath-plus-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Hammerclaw <sub>`recipe_blueprint-chromin-hammerclaw-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, один раз |
| Дизайн Ullrin Hecate <sub>`recipe_blueprint-chromin-hecate-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, один раз |
| Дизайн Ullrin Holo <sub>`recipe_blueprint-chromin-holo-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Orcus <sub>`recipe_blueprint-chromin-orcus-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Pusat Plus <sub>`recipe_blueprint-chromin-pusat-plus-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Sentinel <sub>`recipe_blueprint-chromin-sentinel-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Solace <sub>`recipe_blueprint-chromin-solace-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Solace Plus <sub>`recipe_blueprint-chromin-solace-plus-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Solaris <sub>`recipe_blueprint-chromin-solaris-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Solaris Plus <sub>`recipe_blueprint-chromin-solaris-plus-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Spectrum <sub>`recipe_blueprint-chromin-spectrum-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Venom <sub>`recipe_blueprint-chromin-venom-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Ullrin Zephyr <sub>`recipe_blueprint-chromin-zephyr-ullrin`</sub> | Дизайн корабля | Хромин ×15 000 | 5 с | в событие, раз за событие |
| Дизайн Viking Orcus <sub>`recipe_blueprint-season-orcus-viking`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн корабля Cerevisia Hyperion <sub>`recipe_blueprint-season-hyperion-cerevisia`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн корабля Dreadwing Goliath Plus <sub>`recipe_blueprint-season-goliath-plus-dreadwing`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | в событие, один раз |
| Дизайн корабля Enigma Solace Plus <sub>`recipe_blueprint-season-solace-plus-enigma`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн корабля Helios Goliath Plus <sub>`recipe_blueprint-season-goliath-plus-helios`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн корабля Khepri Paladin <sub>`recipe_blueprint-season-paladin-khepri`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | в событие, один раз |
| Дизайн корабля Striker Solace Plus <sub>`recipe_blueprint-season-solace-plus-striker`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | всегда, один раз |
| Дизайн корабля Thornspire Orcus <sub>`recipe_blueprint-season-orcus-thornspire`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | в событие, один раз |
| Дизайн корабля Viking Citadel Plus <sub>`recipe_blueprint-season-citadel-plus-viking`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | в событие, один раз |
| Дизайн корабля Wyvern Retiarus Plus <sub>`recipe_blueprint-season-retiarus-plus-wyvern`</sub> | Дизайн корабля | Блок данных ×10 | 5 с | в событие, один раз |

## Дроны и их дизайны

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Apis <sub>`recipe_blueprint-drone-apis`</sub> | Эпический дроид | Фрагмент чертежа («Apis») ×45 | 5 с | всегда |
| Iris <sub>`recipe_blueprint-drone-iris`</sub> | Боевой дроид | Наноконденсатор ×2, Гибридный процессор ×1 | 5 с | в событие |
| Zeus <sub>`recipe_blueprint-drone-zeus`</sub> | Эпический дроид | Фрагмент чертежа («Zeus») ×45 | 5 с | всегда |
| Дизайн дроида Blue Robo-bat <sub>`recipe_blueprint-bat-blue`</sub> | Косметический дизайн дроида | Скрапиум ×140, Призматиум ×10 | 5 с | в событие |
| Дизайн дроида Eldritch Diminisher <sub>`recipe_blueprint-champion-drone-diminisher-eldritch`</sub> | Косметический дизайн дроида | Жетон чемпиона ×40 | 5 с | когда есть чертёж |
| Дизайн дроида Erythros <sub>`recipe_blueprint-drone-erythros`</sub> | Косметический дизайн дроида | Фрагмент Infiltrator ×5 000 | 5 с | в событие, до 4 раз за событие |
| Дизайн дроида Green Robo-bat <sub>`recipe_blueprint-bat-green`</sub> | Косметический дизайн дроида | Мьюкосум ×420, Скрапиум ×140 | 5 с | в событие |
| Дизайн дроида Harbinger Centurion <sub>`recipe_blueprint-champion-drone-centurion-harbinger`</sub> | Косметический дизайн дроида | Жетон чемпиона ×40 | 5 с | когда есть чертёж |
| Дизайн дроида Infiltrator <sub>`recipe_blueprint-drone-infiltrator-1`</sub> | Косметический дизайн дроида | Фрагмент Infiltrator ×5 000 | 5 с | в событие, раз за событие |
| Дизайн дроида Infiltrator <sub>`recipe_blueprint-drone-infiltrator-2`</sub> | Косметический дизайн дроида | Фрагмент Infiltrator ×7 500 | 5 с | в событие, раз за событие |
| Дизайн дроида Intimidator <sub>`recipe_blueprint-drone-intimidator`</sub> | Косметический дизайн дроида | Фрагмент Infiltrator ×4 000 | 5 с | в событие, до 3 раз за событие |
| Дизайн дроида Red Robo-bat <sub>`recipe_blueprint-bat-red`</sub> | Косметический дизайн дроида | Плазмид ×50, Скрапиум ×140 | 5 с | в событие |
| Дизайн дроида Seraph Diminisher <sub>`recipe_blueprint-champion-drone-diminisher-seraph`</sub> | Косметический дизайн дроида | Жетон чемпиона ×40 | 5 с | когда есть чертёж |
| Дизайн дроида Seraph Sentinel <sub>`recipe_blueprint-champion-drone-sentinel-seraph`</sub> | Косметический дизайн дроида | Жетон чемпиона ×40 | 5 с | когда есть чертёж |
| Дизайн дроида Spartan <sub>`recipe_blueprint-spartan`</sub> | Статистический дизайн дроида | Дизайн дроида Havoc ×1, Дизайн дроида Hercules ×1, Схизмокристалл ×225 | 10 с | когда есть чертёж |
| Дизайн дроида Xanthos <sub>`recipe_blueprint-drone-xanthos`</sub> | Косметический дизайн дроида | Фрагмент Infiltrator ×5 000 | 5 с | в событие, до 4 раз за событие |
| Дизайн дроида «Argon Phantom» <sub>`recipe_blueprint-phantom-argon`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×4, Призма-слот ×2, Наноконденсатор ×2 | 5 с | когда есть чертёж |
| Дизайн дроида «Blaze Enigma» <sub>`recipe_blueprint-blaze-enigma`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×5, Нанокейс ×7, Призма-слот ×1 | 5 с | в событие |
| Дизайн дроида «Blaze Pusat» <sub>`recipe_blueprint-drone-pusat-blaze`</sub> | Косметический дизайн дроида | Нанокейс ×4, Призма-слот ×1, Гибридный процессор ×1 | 5 с | в событие |
| Дизайн дроида «Blaze Spectrum» <sub>`recipe_blueprint-drone-spectrum-blaze`</sub> | Косметический дизайн дроида | Нанокейс ×4, Призма-слот ×1, Гибридный процессор ×1 | 5 с | в событие |
| Дизайн дроида «Borealis Enigma» <sub>`recipe_blueprint-borealis-enigma`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×5, Нанокейс ×7, Призма-слот ×1 | 5 с | в событие |
| Дизайн дроида «Borealis Enigma» <sub>`recipe_blueprint-enigma-green`</sub> | Косметический дизайн дроида | Скрапиум ×500, Призматиум ×90 | 5 с | в событие |
| Дизайн дроида «Borealis Goliath-X» <sub>`recipe_blueprint-drone-goliath-x-borealis`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×8, Призма-слот ×2, Гибридный процессор ×1 | 5 с | в событие |
| Дизайн дроида «Borealis Phantom» <sub>`recipe_blueprint-borealis-phantom`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×5, Нанокейс ×7, Призма-слот ×1 | 5 с | в событие |
| Дизайн дроида «Borealis Phantom» <sub>`recipe_blueprint-phantom-green`</sub> | Косметический дизайн дроида | Скрапиум ×500, Призматиум ×90 | 5 с | в событие |
| Дизайн дроида «Goliath-X» <sub>`recipe_blueprint-drone-goliath-x-regular`</sub> | Косметический дизайн дроида | Нанокейс ×4, Призма-слот ×2, Наноконденсатор ×1 | 5 с | в событие |
| Дизайн дроида «Harbinger Sentinel» <sub>`recipe_blueprint-champion-drone-sentinel-harbinger`</sub> | Косметический дизайн дроида | Жетон чемпиона ×40 | 5 с | когда есть чертёж |
| Дизайн дроида «Lava Pusat» <sub>`recipe_blueprint-drone-pusat-lava`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×4, Нанокейс ×4, Микротранзисторы ×2 | 5 с | в событие |
| Дизайн дроида «Lava Spectrum» <sub>`recipe_blueprint-drone-spectrum-lava`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×4, Нанокейс ×4, Микротранзисторы ×2 | 5 с | в событие |
| Дизайн дроида «Legend Phantom» <sub>`recipe_blueprint-phantom-legend`</sub> | Косметический дизайн дроида | Нанокейс ×3, Наноконденсатор ×3, Микротранзисторы ×2 | 5 с | когда есть чертёж |
| Дизайн дроида «Ocean A-Elite» <sub>`recipe_blueprint-drone-a-elite-ocean`</sub> | Косметический дизайн дроида | Гибридный процессор ×1, Наноконденсатор ×2, Aurus ×2 | 5 с | в событие |
| Дизайн дроида «Ocean Goliath-X» <sub>`recipe_blueprint-drone-goliath-x-ocean`</sub> | Косметический дизайн дроида | Нанокейс ×4, Призма-слот ×1, Гибридный процессор ×1 | 5 с | в событие |
| Дизайн дроида «Ocean Phantom» <sub>`recipe_blueprint-phantom-ocean`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×8, Наноконденсатор ×1, Микротранзисторы ×1 | 5 с | в событие |
| Дизайн дроида «Poison A-Elite» <sub>`recipe_blueprint-drone-a-elite-poison`</sub> | Дизайн | Гибридный процессор ×1, Наноконденсатор ×2 | 5 с | когда есть чертёж |
| Дизайн дроида «Poison Phantom» <sub>`recipe_blueprint-phantom-poison`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×8, Наноконденсатор ×1, Микротранзисторы ×1 | 5 с | в событие |
| Дизайн дроида «Poison Pusat» <sub>`recipe_blueprint-drone-pusat-poison`</sub> | Косметический дизайн дроида | Нанокейс ×4, Призма-слот ×2, Наноконденсатор ×2 | 5 с | когда есть чертёж |
| Дизайн дроида «Poison Spectrum» <sub>`recipe_blueprint-drone-spectrum-poison`</sub> | Косметический дизайн дроида | Нанокейс ×4, Призма-слот ×2, Наноконденсатор ×2 | 5 с | когда есть чертёж |
| Дизайн дроида «Sandstorm Goliath-X» <sub>`recipe_blueprint-drone-goliath-x-sandstorm`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×8, Призма-слот ×2, Гибридный процессор ×1 | 5 с | в событие |
| Дизайн дроида «Sandstorm Phantom» <sub>`recipe_blueprint-sandstorm-phantom`</sub> | Косметический дизайн дроида | Высокочастотный кабель ×5, Нанокейс ×7, Призма-слот ×1 | 5 с | в событие |
| Дизайн дроида «Seraph Centurion» <sub>`recipe_blueprint-champion-drone-centurion-seraph`</sub> | Косметический дизайн дроида | Жетон чемпиона ×40 | 5 с | когда есть чертёж |
| Формация «Копья» <sub>`recipe_blueprint-f-03-la`</sub> | ФОРМАЦИЯ «КОПЬЯ» | Нанокейс ×10, Призма-слот ×10, Гибридный процессор ×4 | 5 с | в событие |

## Лазеры

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| AA-1 <sub>`recipe_blueprint-aa-1`</sub> | Лазерная пушка для борьбы с ИИ | Реликтовый байт ×1 000 | 5 с | в событие |
| AAP-1 <sub>`recipe_blueprint-aap-1`</sub> | Лазерная пушка P.E.T. для борьбы с ИИ | Реликтовый байт ×1 000 | 5 с | в событие |
| Caucasus <sub>`recipe_blueprint-caucasus-laser`</sub> | Caucasus | Ключ к зелёному трофею ×50, UCB-100 ×7 500, RSB-75 ×1 250, Кредиты ×75 000 000, Монета Darksilver ×5 000 | 5 с | всегда |
| LF-3 <sub>`recipe_blueprint-lf-3`</sub> | ЛАЗЕРНАЯ ПУШКА | Высокочастотный кабель ×2, Нанокейс ×1 | 5 с | в событие |
| LF-4 <sub>`recipe_blueprint-agate-lf4`</sub> | ЛАЗЕРНАЯ ПУШКА | Агатовый осколок ×30 000 | 5 с | в событие, раз за событие |
| LF-4 <sub>`recipe_blueprint-tarnom-lf-4`</sub> | ЛАЗЕРНАЯ ПУШКА | Тарном ×1 000, Уридиум ×10 000, Кредиты ×40 000 000 | 5 с | в событие |
| LF-5 <sub>`recipe_blueprint-lf-5`</sub> | Лазерная пушка | LF-4 ×1, Уридиум ×30 000, Плазменная плита стабилизации ×350 | 5 с | всегда |
| LF-P01 <sub>`recipe_blueprint-lf-p01`</sub> | Лазер P.E.T. | Микротранзисторы ×2, Хромин ×2 500, Ринуск ×2 500 | 5 с | всегда |
| LF-P01 <sub>`recipe_blueprint-flower-token-lf-p01`</sub> | Лазер P.E.T. | Лепестковый кварц ×21 450 | 5 с | в событие, один раз |
| Блокировщик LF-5 <sub>`recipe_blueprint-pst-lf-5-al`</sub> | Лазерная пушка | LF-5 ×1, Плазменный стабилизатор ×1 | 5 с | всегда |
| Блокировщик LF-5 <sub>`recipe_blueprint-cnc-lf-5-al`</sub> | Лазерная пушка | LF-5 ×1, Хладагент «Крио-нано» ×10 | 5 с | всегда |
| Гиперплазмоид LF-4 <sub>`recipe_blueprint-lf-4-hp`</sub> | LF-4-HP | Кайхалон ×160, Тетратрин ×300, Байфенон ×350, LF-4 ×1, Лог-диск ×400 | 5 с | в событие |
| Гиперплазмоид LF-4 <sub>`recipe_blueprint-tarnom-lf-4-hp`</sub> | LF-4-HP | Тарном ×1 750, Уридиум ×25 000, Кредиты ×60 000 000 | 5 с | в событие |
| Лазер «Прометей» <sub>`recipe_blueprint-prometheus-laser`</sub> | Лазерная пушка | Ключ к зелёному трофею ×75, Кредиты ×200 000 000, UCB-100 ×15 000, RSB-75 ×2 500, Ринуск ×6 000 | 5 с | всегда |
| Магма-лазер LF-4 <sub>`recipe_blueprint-tarnom-lf-4-md`</sub> | LF-4-MD | Тарном ×1 750, Уридиум ×25 000, Кредиты ×60 000 000 | 5 с | в событие |
| Магма-лазер LF-4 <sub>`recipe_blueprint-lf-4-md`</sub> | LF-4-MD | Кайхалон ×160, Тетратрин ×300, Байфенон ×350, LF-4 ×1, Лог-диск ×400 | 5 с | в событие |
| Нестабильный лазер LF-4 <sub>`recipe_blueprint-u-lf-4-permanent`</sub> | Лазерная пушка | Микротранзисторы ×2, Наноконденсатор ×1, Aurus ×50, Хромин ×1 200 | 5 с | всегда |
| Нестабильный лазер LF-4 (чертёж) <sub>`recipe_blueprint-u-lf-4`</sub> |  | Проксий ×25, Дуотрин ×1, Высокочастотный кабель ×1 | 5 с | в событие |
| Парити-лазер LF-4 <sub>`recipe_blueprint-tarnom-lf-4-pd`</sub> | LF-4-PD | Тарном ×1 750, Уридиум ×25 000, Кредиты ×60 000 000 | 5 с | в событие |
| Парити-лазер LF-4 <sub>`recipe_blueprint-lf-4-pd`</sub> | LF-4-PD | Кайхалон ×160, Тетратрин ×300, Байфенон ×350, LF-4 ×1, Лог-диск ×400 | 5 с | в событие |
| Рассеивающий лазер II <sub>`recipe_blueprint-sl-02`</sub> | Лазер | Высокочастотный кабель ×2, Нанокейс ×1 | 5 с | в событие |
| Рассеивающий лазер III <sub>`recipe_blueprint-sl-03`</sub> | Лазер | Микротранзисторы ×2, Наноконденсатор ×1, Aurus ×100, Хромин ×1 200 | 5 с | в событие |
| Спектральный скальпель III <sub>`recipe_blueprint-sll-03`</sub> | Лазер | Высокочастотный кабель ×2, Нанокейс ×1 | 5 с | в событие |
| Умертвитель LF-5 <sub>`recipe_blueprint-lf-5-mf`</sub> | Лазерная пушка | Осколок эфира ×10, Уридиум ×30 000 | 5 с | всегда |

## Ракетомёты

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Hydra <sub>`recipe_blueprint-hydra`</sub> | Ракетомет | Сплав «Гидра» ×500, Ракетомёт «Hellstorm-2» ×1, Схизмокристалл ×2 400, Тетратрин ×4 800, Индоктринационная нефть ×300 | 5 с | всегда |
| Neptune <sub>`recipe_blueprint-neptune`</sub> | Ракетомет | Сплав «Нептун» ×500, Ракетомёт «Hellstorm-2» ×1, Схизмокристалл ×2 400, Байфенон ×6 400, Индоктринационная нефть ×300 | 5 с | всегда |
| Ouroboros <sub>`recipe_blueprint-ouroboros`</sub> | Ракетомёт | Сплав «Уроборос» ×500, Ракетомёт «Hellstorm-2» ×1, Схизмокристалл ×2 400, Байфенон ×6 400, Индоктринационная нефть ×300 | 5 с | всегда |
| Triskelion <sub>`recipe_blueprint-triskelion`</sub> | Ракетомет | Сплав «Трискелион» ×500, Ракетомёт «Hellstorm-2» ×1, Схизмокристалл ×2 400, Кайхалон ×3 200, Индоктринационная нефть ×300 | 5 с | всегда |
| Ракетомёт «Hellstorm-2» <sub>`recipe_blueprint-hst-2`</sub> | Ракетомёт | Мьюкосум ×180, Скрапиум ×53 | 5 с | в событие, один раз |

## Генераторы

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| SG3N-P01 <sub>`recipe_blueprint-sg3n-p01`</sub> | Генератор щита P.E.T. | Гибридный процессор ×2, Хромин ×2 500, Ринуск ×2 500 | 5 с | всегда |
| Синтезирующий щит III <sub>`recipe_blueprint-fs-03`</sub> | Генератор щита | Высокочастотный кабель ×2 | 5 с | в событие |
| Щит SG3N-B00 <sub>`recipe_blueprint-sg3n-b00`</sub> | Генератор щита | Плазмид ×2, Нанокейс ×1, Высокочастотный кабель ×1 | 5 с | всегда |
| Щит SG3N-B01 <sub>`recipe_blueprint-sg3n-b01`</sub> | ГЕНЕРАТОР ЩИТА | Призма-слот ×1, Схизмокристалл ×20 | 5 с | всегда |
| Щит SG3N-B02 <sub>`recipe_blueprint-sg3n-b02`</sub> | ГЕНЕРАТОР ЩИТА | Наноконденсатор ×1, Схизмокристалл ×50 | 5 с | всегда |
| Щит SG3N-B03 <sub>`recipe_blueprint-sg3n-b03`</sub> | Генератор щита | Микротранзисторы ×2, Гибридный процессор ×2, Хромин ×1 800, Ринуск ×5 000 | 5 с | всегда |

## Модули апгрейда корабля

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| DMG-CHT01 <sub>`recipe_blueprint-chromin-dmg-cht01`</sub> | +5% к урону от лазеров, +15% к урону от ракет для Cyborg, Hecate, Hecate Plus, Tempest | Хромин ×30 000 | 5 с | в событие, раз за событие |
| DMG-HOLO ×10 <sub>`recipe_blueprint-dmg-holo-10x`</sub> | +4% к урону ракетами после установки на корабль. Этот модуль апгрейда можно использовать на следующих кораблях: Holo, Orcus. | Уридиум ×2 000 | 5 с | в событие, один раз |
| DMG-LEO01 <sub>`recipe_blueprint-dmg-leo01`</sub> | +20% к точности лазера и +30% к урону от ракет для «Leonov». | Реликтовый байт ×1 500 | 5 с | в событие, раз за событие |
| DMG-MTCH01 <sub>`recipe_blueprint-agate-dmg-mtch01`</sub> | +7% к урону для Hammerclaw, Hammerclaw Plus, Mimesis, Tartarus, Tartarus Plus, Citadel, Citadel Plus, C-Veteran, C-Elite. | Агатовый осколок ×10 000 | 5 с | в событие, раз за событие |
| DMG-MTCH01 <sub>`recipe_blueprint-chromin-dmg-mtch01`</sub> | +7% к урону для Hammerclaw, Hammerclaw Plus, Mimesis, Tartarus, Tartarus Plus, Citadel, Citadel Plus, C-Veteran, C-Elite. | Хромин ×20 000 | 5 с | в событие, раз за событие |
| DMG-NPC01 <sub>`recipe_blueprint-candy-dmg-npc01`</sub> | +15% к урону по NPC для «Sentinel», «Solace», «Mimesis», «Berserker». | Реликтовый байт ×2 000 | 5 с | в событие, раз за событие |
| DMG-NPC01 <sub>`recipe_blueprint-agate-dmg-npc01`</sub> | +15% к урону по NPC для «Sentinel», «Solace», «Mimesis», «Berserker». | Агатовый осколок ×10 000 | 5 с | в событие, раз за событие |
| DMG-RET01 <sub>`recipe_blueprint-agate-dmg-ret01`</sub> | +10% к урону от лазера, -5% к уклону и -5% к щиту для Retiarus. | Агатовый осколок ×10 000 | 5 с | в событие, раз за событие |
| DMG-SG01 <sub>`recipe_blueprint-dmg-sg01-mm`</sub> | +5% к урону для Goliath-X, Goliath, Goliath Plus, Spearhead, Spearhead Plus, S-Elite, S-Veteran, Sentinel, Solace, Solace Plus. | Реликтовый байт ×1 500 | 5 с | в событие, раз за событие |
| DMG-VP01 <sub>`recipe_blueprint-agate-dmg-vp01`</sub> | +10% урона для «Vengeance», «V-Lightning», «V-Adept», «V-Avenger», «V-Corsair», «V-Revenge», «Pusat» | Агатовый осколок ×20 000 | 5 с | в событие, раз за событие |
| DMG-X1 <sub>`recipe_blueprint-dmg-x1`</sub> | Нестабильный модуль | Осколок Black Light ×4 500, Ринуск ×1 500, Капсула Black Light ×1 000 | 5 с | всегда |
| DMG-X2 <sub>`recipe_blueprint-dmg-x2`</sub> | Нестабильный модуль | Осколок Black Light ×14 500, Нейроплата Mindfire ×200, Уридиум ×12 500 | 5 с | всегда |
| DMG-XA01 <sub>`recipe_blueprint-dmg-xa01-atlas`</sub> | Нестабильный модуль | Осколок Атласа ×450 | 5 с | в событие, раз за событие |
| DMG-XH01 <sub>`recipe_blueprint-dmg-xh01`</sub> | Нестабильный модуль | Золотая кукурузная конфета ×40, Candy Corn ×90 000 | 5 с | в событие, раз за событие |
| DMG-XSP01 <sub>`recipe_blueprint-season-dmg-xsp01`</sub> | Нестабильный модуль | Блок данных ×5 | 5 с | в событие, раз за событие |
| DMG-XT10 <sub>`recipe_blueprint-dmg-xt10-empyrian`</sub> | Нестабильный модуль | Люминиум ×7 000 | 5 с | в событие, раз за событие |
| DMG-XT10 <sub>`recipe_blueprint-dmg-xt10`</sub> | Нестабильный модуль | Осколок Black Light ×25 000, Нейроплата Mindfire ×1 000 | 5 с | в событие |
| DMG-XU01 <sub>`recipe_blueprint-dmg-xu01-empyrian`</sub> | Нестабильный модуль | Люминиум ×3 540 | 5 с | в событие, раз за событие |
| DMG-XV01 <sub>`recipe_blueprint-dmg-xv01`</sub> | Нестабильный модуль | Осколок Charopos ×600, Осколок Styxus ×600 | 5 с | в событие, раз за событие |
| DMG-XV02 <sub>`recipe_blueprint-dmg-xv02-valentine`</sub> | Нестабильный модуль | Осколок Charopos ×800, Осколок Styxus ×800 | 5 с | в событие, раз за событие |
| DMG-ZEP01 <sub>`recipe_blueprint-chromin-dmg-zep01`</sub> | +8% к урону для «Zephyr» | Хромин ×30 000 | 5 с | в событие, раз за событие |
| DMG-ZPVE <sub>`recipe_blueprint-dmg-zpve-worldboss`</sub> | Нестабильный модуль | Фрагмент Infiltrator ×2 500 | 5 с | в событие, раз за событие |
| DMG-ZPVE <sub>`recipe_blueprint-dmg-zpve`</sub> | Нестабильный модуль | Реликтовый байт ×4 500 | 5 с | в событие, раз за событие |
| HP-A01 <sub>`recipe_blueprint-candy-hp-a01`</sub> | +5% ПР для «Aegis», «A-Elite», «A-Veteran». | Candy Corn ×5 000 | 5 с | в событие |
| HP-BES01 <sub>`recipe_blueprint-hp-bes01`</sub> | +20% к ПР для «G-Bastion», «G-Enforcer», «G-Saturn». | Реликтовый байт ×1 500 | 5 с | в событие, раз за событие |
| HP-CHT01 <sub>`recipe_blueprint-chromin-hp-cht01`</sub> | +12% к ПР для Cyborg, Hecate, Hecate Plus, Tempest | Хромин ×20 000 | 5 с | в событие, раз за событие |
| HP-HAM01 <sub>`recipe_blueprint-hp-ham01`</sub> | +15% к ПР для «Hammerclaw» и «Aegis» | Реликтовый байт ×1 500 | 5 с | в событие, раз за событие |
| HP-HOLO ×10 <sub>`recipe_blueprint-hp-holo-10x`</sub> | +5% к урону и -2% к показателям щита после установки на корабль. Этот модуль апгрейда можно использовать на следующих кораблях: Holo. | Уридиум ×2 000 | 5 с | в событие, один раз |
| HP-MTCH01 <sub>`recipe_blueprint-agate-hp-mtch01`</sub> | +8% к ПР для Hammerclaw, Hammerclaw Plus, Mimesis, Tartarus, Tartarus Plus, Citadel, Citadel Plus, C-Veteran, C-Elite. | Агатовый осколок ×8 000 | 5 с | в событие, раз за событие |
| HP-NPC01 <sub>`recipe_blueprint-agate-hp-npc01`</sub> | +13% ПР для Sentinel, Solace, Solace Plus, Mimesis, Berserker. | Агатовый осколок ×8 000 | 5 с | в событие, раз за событие |
| HP-S01 <sub>`recipe_blueprint-agate-hp-s01`</sub> | +15% ПР для «Spearhead», «S-Elite», «S-Veteran» | Агатовый осколок ×6 000 | 5 с | в событие, раз за событие |
| HP-SOL01 <sub>`recipe_blueprint-agate-hp-sol01`</sub> | +15% ПР для «Solace» | Агатовый осколок ×5 000 | 5 с | в событие, раз за событие |
| HP-T01 <sub>`recipe_blueprint-hp-t01`</sub> | +25% к ПР для Tartarus и Tartarus Plus. | Реликтовый байт ×2 000 | 5 с | в событие, раз за событие |
| HP-X1 <sub>`recipe_blueprint-hp-x1`</sub> | Нестабильный модуль | Осколок Black Light ×4 500, Ринуск ×1 500, Капсула Black Light ×1 000 | 5 с | всегда |
| HP-X2 <sub>`recipe_blueprint-hp-x2`</sub> | Нестабильный модуль | Осколок Black Light ×14 500, Нейроплата Mindfire ×200, Уридиум ×12 500 | 5 с | всегда |
| HP-XA01 <sub>`recipe_blueprint-hp-xa01-atlas`</sub> | Нестабильный модуль | Осколок Атласа ×450 | 5 с | в событие, раз за событие |
| HP-XH01 <sub>`recipe_blueprint-hp-xh01`</sub> | Нестабильный модуль | Золотая кукурузная конфета ×40, Candy Corn ×90 000 | 5 с | в событие, раз за событие |
| HP-XSP01 <sub>`recipe_blueprint-season-hp-xsp01`</sub> | Нестабильный модуль | Блок данных ×5 | 5 с | в событие, раз за событие |
| HP-XT10 <sub>`recipe_blueprint-hp-xt10-empyrian-limited`</sub> | Нестабильный модуль | Люминиум ×7 000 | 5 с | в событие, до 2 раз за событие |
| HP-XT10 <sub>`recipe_blueprint-hp-xt10`</sub> | Нестабильный модуль | Осколок Black Light ×25 000, Нейроплата Mindfire ×1 000 | 5 с | в событие |
| HP-XT10 <sub>`recipe_blueprint-hp-xt10-empyrian`</sub> | Нестабильный модуль | Люминиум ×7 000 | 5 с | в событие, раз за событие |
| HP-XU01 <sub>`recipe_blueprint-hp-xu01-empyrian`</sub> | Нестабильный модуль | Люминиум ×3 540 | 5 с | в событие, раз за событие |
| HP-XV01 <sub>`recipe_blueprint-hp-xv01`</sub> | Нестабильный модуль | Осколок Charopos ×600, Осколок Styxus ×600 | 5 с | в событие, раз за событие |
| HP-XV02 <sub>`recipe_blueprint-hp-xv02-valentine`</sub> | Нестабильный модуль | Осколок Charopos ×800, Осколок Styxus ×800 | 5 с | в событие, раз за событие |
| HP-ZPVE <sub>`recipe_blueprint-hp-zpve`</sub> | Нестабильный модуль | Реликтовый байт ×4 500 | 5 с | в событие, раз за событие |
| HP-ZPVE <sub>`recipe_blueprint-hp-zpve-worldboss`</sub> | Нестабильный модуль | Фрагмент Infiltrator ×2 500 | 5 с | в событие, до 2 раз за событие |
| SHD-BACH01 <sub>`recipe_blueprint-agate-shd-bach01`</sub> | +15% к щиту для «G-Champion», «G-Bastion», «Hammerclaw», «Aegis», «A-Elite», «A-Veteran». | Агатовый осколок ×8 000 | 5 с | в событие, раз за событие |
| SHD-DIM01 <sub>`recipe_blueprint-shd-dim01`</sub> | +25% к щиту для «Diminisher» | Реликтовый байт ×1 500 | 5 с | в событие, раз за событие |
| SHD-HOLO ×10 <sub>`recipe_blueprint-shd-holo-10x`</sub> | +8% к показателям щита после установки на корабль. Этот модуль апгрейда можно использовать на следующих кораблях: Holo. | Уридиум ×2 000 | 5 с | в событие, один раз |
| SHD-SK01 <sub>`recipe_blueprint-shd-sk01`</sub> | +12% к показателям щита, -5% к урону для кораблей «Keres», «Spectrum», «Spearhead». | Индоктринационная нефть ×30, Гибридный процессор ×5, Микротранзисторы ×10, Наноконденсатор ×10 | 5 с | в событие |
| SHD-SL01 <sub>`recipe_blueprint-agate-shd-sl01`</sub> | +10% к щиту, -5% к ПР для «Sentinel» и «Solace». | Агатовый осколок ×5 000 | 5 с | в событие, раз за событие |
| SHD-X1 <sub>`recipe_blueprint-shd-x1`</sub> | Нестабильный модуль | Осколок Black Light ×4 500, Ринуск ×1 500, Капсула Black Light ×1 000 | 5 с | всегда |
| SHD-X2 <sub>`recipe_blueprint-shd-x2`</sub> | Нестабильный модуль | Осколок Black Light ×14 500, Нейроплата Mindfire ×200, Уридиум ×12 500 | 5 с | всегда |
| SHD-XA01 <sub>`recipe_blueprint-shd-xa01-atlas`</sub> | Нестабильный модуль | Осколок Атласа ×450 | 5 с | в событие, раз за событие |
| SHD-XH01 <sub>`recipe_blueprint-shd-xh01`</sub> | Нестабильный модуль | Золотая кукурузная конфета ×40, Candy Corn ×90 000 | 5 с | в событие, раз за событие |
| SHD-XSP01 <sub>`recipe_blueprint-season-shd-xsp01`</sub> | Нестабильный модуль | Блок данных ×5 | 5 с | в событие, раз за событие |
| SHD-XT10 <sub>`recipe_blueprint-shd-xt10-empyrian`</sub> | Нестабильный модуль | Люминиум ×7 000 | 5 с | в событие, раз за событие |
| SHD-XT10 <sub>`recipe_blueprint-shd-xt10`</sub> | Нестабильный модуль | Осколок Black Light ×25 000, Нейроплата Mindfire ×1 000 | 5 с | в событие |
| SHD-XT10 <sub>`recipe_blueprint-shd-xt10-empyrian-limited`</sub> | Нестабильный модуль | Люминиум ×7 000 | 5 с | в событие, до 2 раз за событие |
| SHD-XU01 <sub>`recipe_blueprint-shd-xu01-empyrian`</sub> | Нестабильный модуль | Люминиум ×3 540 | 5 с | в событие, раз за событие |
| SHD-XV01 <sub>`recipe_blueprint-shd-xv01`</sub> | Нестабильный модуль | Осколок Charopos ×600, Осколок Styxus ×600 | 5 с | в событие, раз за событие |
| SHD-XV02 <sub>`recipe_blueprint-shd-xv02`</sub> | Нестабильный модуль | Осколок Charopos ×800, Осколок Styxus ×800 | 5 с | в событие, раз за событие |
| SHD-ZPVE <sub>`recipe_blueprint-shd-zpve-worldboss`</sub> | Нестабильный модуль | Фрагмент Infiltrator ×2 500 | 5 с | в событие, раз за событие |
| SHD-ZPVE <sub>`recipe_blueprint-shd-zpve`</sub> | Нестабильный модуль | Реликтовый байт ×4 500 | 5 с | в событие, раз за событие |
| SPC-C01 <sub>`recipe_blueprint-spc-c01-mm`</sub> | +5% к точности для Citadel, Citadel Plus, C-Veteran, C-Elite. | Реликтовый байт ×1 500 | 5 с | в событие, раз за событие |
| SPC-CHT01 <sub>`recipe_blueprint-chromin-spc-cht01`</sub> | +10% к уклону, +3% к скорости для Cyborg, Hecate, Hecate Plus, Tempest | Хромин ×30 000 | 5 с | в событие, раз за событие |
| SPC-DIM01 <sub>`recipe_blueprint-agate-spc-dim01`</sub> | +10% к поглощению урона щитом, +5% к уклонению и +5% к урону от лазера для «Diminisher». | Агатовый осколок ×8 000 | 5 с | в событие, раз за событие |
| SPC-HOLO ×10 <sub>`recipe_blueprint-spc-holo-10x`</sub> | +19% к уклону после установки на корабль. Этот модуль апгрейда можно использовать на следующих кораблях: Holo. | Уридиум ×2 000 | 5 с | в событие, один раз |
| SPC-MTCH01 <sub>`recipe_blueprint-agate-spc-mtch01`</sub> | +8% к скорости и +5% к уклону для Hammerclaw, Hammerclaw Plus, Mimesis, Tartarus, Tartarus Plus, Citadel, Citadel Plus, C-Veteran, C-Elite. | Агатовый осколок ×8 000 | 5 с | в событие, раз за событие |
| SPC-NPC01 <sub>`recipe_blueprint-agate-spc-npc01`</sub> | +7% скорость, +7% точность лазеров для Sentinel, Solace, Solace Plus, Mimesis, Berserker. | Агатовый осколок ×8 000 | 5 с | в событие, раз за событие |
| SPC-ROC01 <sub>`recipe_blueprint-spc-roc01`</sub> | +4% к урону и точности ракет для Solaris, Disruptor, Zephyr, Spectrum, Tartarus и Tartarus Plus. | Осколок Styxus ×700, Осколок Charopos ×700 | 5 с | в событие |
| SPC-ROC01 <sub>`recipe_blueprint-agate-spc-roc01`</sub> | +4% к урону и точности ракет для Solaris, Disruptor, Zephyr, Spectrum, Tartarus и Tartarus Plus. | Агатовый осколок ×5 000 | 5 с | в событие, раз за событие |
| SPC-SL01 <sub>`recipe_blueprint-agate-spc-sl01`</sub> | +5% к точности, -5% к щиту для «Sentinel» и «Solace». | Агатовый осколок ×15 000 | 5 с | в событие, раз за событие |
| SPC-SL01 <sub>`recipe_blueprint-candy-spc-sl01`</sub> | +5% к точности, -5% к щиту для «Sentinel» и «Solace». | Candy Corn ×10 000 | 5 с | в событие |
| SPC-SPD01 <sub>`recipe_blueprint-agate-spc-spd01`</sub> | +5% к скорости Goliath, Goliath-X, Goliath Plus, Hecate, Sentinel, Solace. | Агатовый осколок ×6 000 | 5 с | в событие, раз за событие |
| SPC-SPD02 <sub>`recipe_blueprint-spc-spd02`</sub> | +3% к скорости для кораблей «Venom», «Disruptor», «Diminisher», «Keres», «Mimesis». | Индоктринационная нефть ×20, Гибридный процессор ×5, Микротранзисторы ×5, Наноконденсатор ×5 | 5 с | в событие |
| SPC-SPE01 <sub>`recipe_blueprint-chromin-spc-spe01`</sub> | +10% к прочности щита и +20% к урону от ракет для «Spectrum» | Хромин ×30 000 | 5 с | в событие, раз за событие |
| SPC-X1 <sub>`recipe_blueprint-spc-x1`</sub> | Нестабильный модуль | Осколок Black Light ×4 500, Ринуск ×1 500, Капсула Black Light ×1 000 | 5 с | всегда |
| SPC-X2 <sub>`recipe_blueprint-spc-x2`</sub> | Нестабильный модуль | Осколок Black Light ×14 500, Нейроплата Mindfire ×200, Уридиум ×12 500 | 5 с | всегда |
| SPC-XA01 <sub>`recipe_blueprint-spc-xa01-atlas`</sub> | Нестабильный модуль | Осколок Атласа ×450 | 5 с | в событие, раз за событие |
| SPC-XH01 <sub>`recipe_blueprint-spc-xh01`</sub> | Нестабильный модуль | Золотая кукурузная конфета ×40, Candy Corn ×90 000 | 5 с | в событие, раз за событие |
| SPC-XSP01 <sub>`recipe_blueprint-season-spc-xsp01`</sub> | Нестабильный модуль | Блок данных ×5 | 5 с | в событие, раз за событие |
| SPC-XT10 <sub>`recipe_blueprint-spc-xt10`</sub> | Нестабильный модуль | Осколок Black Light ×25 000, Нейроплата Mindfire ×1 000 | 5 с | в событие |
| SPC-XT10 <sub>`recipe_blueprint-spc-xt10-empyrian`</sub> | Нестабильный модуль | Люминиум ×7 000 | 5 с | в событие, раз за событие |
| SPC-XU01 <sub>`recipe_blueprint-spc-xu01-empyrian`</sub> | Нестабильный модуль | Люминиум ×3 540 | 5 с | в событие, раз за событие |
| SPC-XV01 <sub>`recipe_blueprint-spc-xv01`</sub> | Нестабильный модуль | Осколок Charopos ×600, Осколок Styxus ×600 | 5 с | в событие, раз за событие |
| SPC-XV02 <sub>`recipe_blueprint-spc-xv02`</sub> | Нестабильный модуль | Осколок Charopos ×800, Осколок Styxus ×800 | 5 с | в событие, раз за событие |
| SPC-ZPVE <sub>`recipe_blueprint-spc-zpve`</sub> | Нестабильный модуль | Реликтовый байт ×4 500 | 5 с | в событие, раз за событие |
| SPC-ZPVE <sub>`recipe_blueprint-spc-zpve-worldboss`</sub> | Нестабильный модуль | Фрагмент Infiltrator ×2 500 | 5 с | в событие, до 2 раз за событие |

## Процессоры и экстры

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Гиперпроцессор «Телепорт-1» <sub>`recipe_blueprint-ajp-01`</sub> | ГИПЕРПРОЦЕССОР «ТЕЛЕПОРТ» | Мьюкосум ×150, Плазмид ×40, Скрапиум ×441, Призматиум ×4 | 5 с | в событие |
| Камуфляж типа А <sub>`recipe_blueprint-cl04k-xs`</sub> | для защитной маскировки | Плазмид ×2, Скрапиум ×5 | 5 с | в событие |
| Камуфляжный процессор XL <sub>`recipe_blueprint-flower-token-clo4k-xl-cpu`</sub> | МАСКИРОВКА XL | Лепестковый кварц ×1 600 | 5 с | в событие, один раз |
| Камуфляжный процессор XL <sub>`recipe_blueprint-cloakcpu-blacklight`</sub> | МАСКИРОВКА XL | Уридиум ×5 000, Ринуск ×1 500, Капсула Black Light ×180 | 5 с | всегда |
| Расширитель отсеков 4 <sub>`recipe_blueprint-sle-04`</sub> | РАСШИРИТЕЛЬ ОТСЕКОВ | Мьюкосум ×740, Плазмид ×33, Скрапиум ×588, Призматиум ×75 | 5 с | в событие |
| Робот-ремонтник 4 <sub>`recipe_blueprint-rep-4`</sub> | Робот-ремонтник | Мьюкосум ×160, Скрапиум ×375 | 5 с | в событие |

## Прочее оборудование

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| IgniDrive Lite <sub>`recipe_blueprint-drive-igd-l01`</sub> | СИНГУЛЯРНЫЙ ДВИГАТЕЛЬ | Фрагмент сингулярного двигателя ×200 | 5 с | всегда |
| IgniDrive Prime <sub>`recipe_blueprint-drive-igd-p`</sub> | СИНГУЛЯРНЫЙ ДВИГАТЕЛЬ | Осколок эфира ×10, Уридиум ×30 000 | 5 с | всегда |
| Indra - Cyborg <sub>`recipe_blueprint-indra-cyborg`</sub> | Рельсотрон | Импульсное ядро ×1 600 | 5 с | в событие |
| Indra - Goliath Plus <sub>`recipe_blueprint-indra-goliath-plus`</sub> | Рельсотрон | Импульсное ядро ×2 000 | 5 с | в событие |
| Indra - Paladin <sub>`recipe_blueprint-indra-paladin`</sub> | Рельсотрон | Импульсное ядро ×1 200 | 5 с | в событие |
| Indra - Solaris Plus <sub>`recipe_blueprint-indra-solaris-plus`</sub> | Рельсотрон | Импульсное ядро ×1 600 | 5 с | в событие |
| Indra - Spectrum Plus <sub>`recipe_blueprint-indra-spectrum-plus`</sub> | Рельсотрон | Импульсное ядро ×1 200 | 5 с | в событие |
| Indra - Venom <sub>`recipe_blueprint-indra-venom`</sub> | Рельсотрон | Импульсное ядро ×1 600 | 5 с | в событие |
| LEG-Helios <sub>`recipe_blueprint-leg-helios`</sub> | ‌Пучковая пушка‌ | Осколок эфира ×10, Уридиум ×30 000 | 5 с | всегда |
| LEG-Nidhogg <sub>`recipe_blueprint-leg-nidhogg`</sub> | ‌Пучковая пушка‌ | Осколок эфира ×10, Уридиум ×30 000 | 5 с | всегда |
| Анти-ино-протокол <sub>`recipe_blueprint-ai-al1`</sub> | Анти-ино-протокол | Золотая кукурузная конфета ×25, Candy Corn ×50 000 | 5 с | в событие |
| Гравитронная хронолинза <sub>`recipe_blueprint-drive-gl-chron`</sub> | УНИЧТОЖИТЕЛЬ ГРАВИТАЦИИ | Фрагмент гравитронной хронолинзы ×200 | 5 с | всегда |
| Механизм P.E.T. «Боевой маячок» <sub>`recipe_blueprint-g-bc1`</sub> | Механизм P.E.T. «Боевой маячок» | Золотая кукурузная конфета ×35, Candy Corn ×45 000 | 5 с | в событие, раз за событие |
| Механизм P.E.T. «Маячок ПР» <sub>`recipe_blueprint-g-bh1`</sub> | Механизм P.E.T. «Маячок ПР» | Золотая кукурузная конфета ×35, Candy Corn ×45 000 | 5 с | в событие, раз за событие |
| Механизм P.E.T. «Мега-мина» <sub>`recipe_blueprint-agate-g-mm1`</sub> | Механизм P.E.T. «Мега-мина» | Агатовый осколок ×50 000 | 5 с | в событие, один раз |
| Механизм P.E.T. «Наводчик» <sub>`recipe_blueprint-opitite-grt1`</sub> | Механизм P.E.T. «Наводчик» | Опитит ×12 000, LF-4 ×10, Уридиум ×75 000 | 5 с | в событие, один раз |
| Механизм P.E.T. «Связь ПР» <sub>`recipe_blueprint-agate-g-hpl1`</sub> | Механизм P.E.T. «Связь ПР» | Агатовый осколок ×10 000 | 5 с | в событие, раз за событие |
| Механизмы «Жертвенный огонь» <sub>`recipe_blueprint-g-fs1`</sub> | Боевые механизмы P.E.T. | Золотая кукурузная конфета ×15, Candy Corn ×50 000 | 5 с | в событие |
| Протокол добычи <sub>`recipe_blueprint-ai-s1`</sub> | Протокол добычи | Золотая кукурузная конфета ×15, Candy Corn ×35 000 | 5 с | в событие |
| Протокол лазера <sub>`recipe_blueprint-ai-lm1`</sub> | Протокол лазера | Золотая кукурузная конфета ×25, Candy Corn ×50 000 | 5 с | в событие |
| Протокол прицела <sub>`recipe_blueprint-ai-aim1`</sub> | Протокол прицела | Золотая кукурузная конфета ×15, Candy Corn ×35 000 | 5 с | в событие |
| Протокол радара <sub>`recipe_blueprint-ai-r1`</sub> | Протокол радара | Золотая кукурузная конфета ×15, Candy Corn ×35 000 | 5 с | в событие |
| Протокол уклона <sub>`recipe_blueprint-ai-e1`</sub> | Протокол уклона | Золотая кукурузная конфета ×15, Candy Corn ×50 000 | 5 с | в событие |
| Протокол щита <sub>`recipe_blueprint-ai-sm1`</sub> | Протокол щита | Золотая кукурузная конфета ×15, Candy Corn ×50 000 | 5 с | в событие |
| Эконом-протокол <sub>`recipe_blueprint-ai-eco1`</sub> | Эконом-протокол | Золотая кукурузная конфета ×25, Candy Corn ×45 000 | 5 с | в событие, раз за событие |
| Эконом-протокол ×2 <sub>`recipe_blueprint-ai-eco1-2`</sub> | Эконом-протокол | Золотая кукурузная конфета ×50, Candy Corn ×90 000 | 5 с | в событие, раз за событие |

## Лазерные боеприпасы

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| A-BL ×25 000 <sub>`recipe_blueprint-a-bl-laser-ammo`</sub> | Лазерная батарея | UCB-100 ×10 000, Уридиум ×10 000, Кредиты ×10 000 000, Ринуск ×1 500 | 5 с | всегда |
| Cosmic Class C ×10 000 <sub>`recipe_blueprint-gop-cc-c-10k`</sub> | Турнирные боеприпасы | UCB-100 ×25 000, RSB-75 ×5 000 | 5 с | в событие, до 5 раз за событие |
| Cosmic Class C ×12 000 <sub>`recipe_blueprint-cc-c-12k`</sub> | Турнирные боеприпасы | UCB-100 ×25 000, RSB-75 ×5 000 | 5 с | в событие |
| Cosmic Class C ×15 000 <sub>`recipe_blueprint-opitite-ccc-15k`</sub> | Турнирные боеприпасы | Опитит ×2 500, Уридиум ×20 000, Кредиты ×25 000 000 | 5 с | в событие |
| Cosmic Class C ×3 000 <sub>`recipe_blueprint-group-c-atlas`</sub> | Турнирные боеприпасы | Осколок Атласа ×300 | 5 с | в событие, раз за событие |
| Cosmic Class D ×15 000 <sub>`recipe_blueprint-opitite-ccd-15k`</sub> | Турнирные боеприпасы | Опитит ×2 000, Уридиум ×17 500, Кредиты ×20 000 000 | 5 с | в событие |
| Cosmic Class G ×7 500 <sub>`recipe_blueprint-tarnom-ccg-7500`</sub> | Турнирные боеприпасы | Тарном ×300, Уридиум ×15 000, Кредиты ×5 000 000 | 5 с | в событие |
| EMAA-20 ×10 000 <sub>`recipe_blueprint-emaa-20-10k-once`</sub> | EMAA-20 | Реликтовый байт ×3 000 | 5 с | в событие, раз за событие |
| EMAA-20 ×10 000 <sub>`recipe_blueprint-emaa-20-10k-repeat`</sub> | EMAA-20 | Реликтовый байт ×1 500, Уридиум ×15 000 | 5 с | в событие |
| EMAA-20 ×3 500 <sub>`recipe_blueprint-emaa-20-3500-laser`</sub> | EMAA-20 | AA-1 ×1 | 5 с | в событие |
| EMAA-20 ×3 500 <sub>`recipe_blueprint-emaa-20-3500-laser-pet`</sub> | EMAA-20 | AAP-1 ×1 | 5 с | в событие |
| JOB-100 ×10 000 <sub>`recipe_blueprint-job-100-10k-valentine`</sub> | ХЭЛЛОУ-БАТАРЕЯ | Осколок Charopos ×150, Осколок Styxus ×150 | 5 с | в событие, один раз |
| MCB-50 ×1 000 <sub>`recipe_blueprint-mcb-50`</sub> | ВЫСОКОМОЩНАЯ БАТАРЕЯ | Мьюкосум ×8, Плазмид ×2, Скрапиум ×12 | 5 с | в событие |
| RCB-140 ×26 000 <sub>`recipe_blueprint-chromin-rcb140`</sub> | Ультрахроминовая батарея | Хромин ×80 000 | 5 с | в событие, раз за событие |
| RSB-75 <sub>`recipe_blueprint-rsb-75-1-worldboss`</sub> | УЛЬТРАСКОРОСТНАЯ БАТАРЕЯ | Фрагмент Infiltrator ×8 | 5 с | в событие |
| RSB-75 ×15 000 <sub>`recipe_blueprint-rsb75-blacklight`</sub> | УЛЬТРАСКОРОСТНАЯ БАТАРЕЯ | Уридиум ×4 000, Ринуск ×2 500, Капсула Black Light ×400 | 5 с | всегда |
| RSB-75 ×40 000 <sub>`recipe_blueprint-rsb75-40k`</sub> | УЛЬТРАСКОРОСТНАЯ БАТАРЕЯ | Проксий ×130, Квадротрин ×1 | 5 с | в событие |
| RSB-75 ×5 000 <sub>`recipe_blueprint-rsb-75`</sub> | УЛЬТРАСКОРОСТНАЯ БАТАРЕЯ | Схизмокристалл ×10, Нанокейс ×1, Высокочастотный кабель ×1 | 5 с | в событие |
| SAB-50 <sub>`recipe_blueprint-sab-50-1-worldboss`</sub> | БАТАРЕЯ-АНТИЩИТ | Фрагмент Infiltrator ×2 | 5 с | в событие |
| SAB-50 ×1 000 <sub>`recipe_blueprint-sab50-1k`</sub> | БАТАРЕЯ-АНТИЩИТ | Мьюкосум ×6, Плазмид ×2, Скрапиум ×1 | 5 с | в событие |
| SAB-50 ×50 <sub>`recipe_blueprint-sab-50-50-worldboss`</sub> | БАТАРЕЯ-АНТИЩИТ | Фрагмент Infiltrator ×800 | 5 с | в событие |
| UCB-100 ×200 <sub>`recipe_blueprint-ucb-100`</sub> | РАДИОАКТИВНЫЕ ЭЛИТНЫЕ БОЕПРИПАСЫ | Плазмид ×10, Скрапиум ×10, Призматиум ×2 | 5 с | в событие |
| UCB-100 ×5 000 <sub>`recipe_blueprint-ucb-100-5k-valentine`</sub> | РАДИОАКТИВНЫЕ ЭЛИТНЫЕ БОЕПРИПАСЫ | Осколок Charopos ×150, Осколок Styxus ×150 | 5 с | в событие, раз за событие |
| VB-142 ×10 000 <sub>`recipe_blueprint-vb-142-10k`</sub> | VB-142 | Осколок Styxus ×1 400, Осколок Charopos ×1 400 | 5 с | в событие, раз за событие |

## Ракеты, мины и другие боеприпасы

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| ACM-1 ×10 <sub>`recipe_blueprint-flower-token-acm-1`</sub> | МИНА | Лепестковый кварц ×150 | 5 с | в событие |
| FWX-COM <sub>`recipe_blueprint-fwx-com-multiple-valentine`</sub> | ФЕЙЕРВЕРК | Осколок Charopos ×25, Осколок Styxus ×25 | 5 с | в событие |
| FWX-COM <sub>`recipe_blueprint-fwx-com-empyrian`</sub> | ФЕЙЕРВЕРК | Люминиум ×500 | 5 с | в событие |
| FWX-L <sub>`recipe_blueprint-fwx-l-halloween`</sub> | ФЕЙЕРВЕРК | Candy Corn ×35 000, Скрапиум ×15 | 5 с | в событие |
| FWX-L <sub>`recipe_blueprint-fwx-l-valentine`</sub> | ФЕЙЕРВЕРК | Осколок Charopos ×250, Осколок Styxus ×250 | 5 с | в событие |
| FWX-M <sub>`recipe_blueprint-fwx-m-valentine`</sub> | ФЕЙЕРВЕРК | Осколок Charopos ×100, Осколок Styxus ×100 | 5 с | в событие |
| FWX-M <sub>`recipe_blueprint-fwx-m-halloween`</sub> | ФЕЙЕРВЕРК | Candy Corn ×15 000, Скрапиум ×10 | 5 с | в событие |
| FWX-S <sub>`recipe_blueprint-fwx-s-valentine`</sub> | ФЕЙЕРВЕРК | Осколок Charopos ×50, Осколок Styxus ×50 | 5 с | в событие |
| FWX-S <sub>`recipe_blueprint-fwx-s-halloween`</sub> | ФЕЙЕРВЕРК | Candy Corn ×5 000, Скрапиум ×5 | 5 с | в событие |
| Hellstorm ×10 <sub>`recipe_blueprint-hstrm-01-empyrian`</sub> | HELLSTORM | Люминиум ×600 | 5 с | в событие |
| Immunizer CPU <sub>`recipe_blueprint-immunizer-cpu`</sub> | Процессор | Гибридный сплав ×30 | 5 с | в событие |
| K-300M ×10 <sub>`recipe_blueprint-flower-token-k-300m`</sub> | РАКЕТА БОЛЬШОЙ ДАЛЬНОСТИ | Лепестковый кварц ×250 | 5 с | в событие |
| PLD-8 ×10 <sub>`recipe_blueprint-flower-token-pld-8`</sub> | ПЛАЗМЕННЫЙ ДИСКОННЕКТОР | Лепестковый кварц ×100 | 5 с | в событие |
| SR-5 ×10 <sub>`recipe_blueprint-flower-token-sr-5`</sub> | УКРЕПЛЯЮЩАЯ ЩИТ РАКЕТА | Лепестковый кварц ×700 | 5 с | в событие |
| SR-5 ×30 <sub>`recipe_blueprint-aa1-sr5-75`</sub> | УКРЕПЛЯЮЩАЯ ЩИТ РАКЕТА | AA-1 ×1 | 5 с | в событие |
| SR-5 ×30 <sub>`recipe_blueprint-aap1-sr5-75`</sub> | УКРЕПЛЯЮЩАЯ ЩИТ РАКЕТА | AAP-1 ×1 | 5 с | в событие |
| WIZ-X ×1 000 <sub>`recipe_blueprint-wiz-x-empyrian-l`</sub> | ТОРПЕДА-ГОЛОПРОЕКТОР | Люминиум ×1 500 | 5 с | в событие, раз за событие |
| WIZ-X ×200 <sub>`recipe_blueprint-wiz-x-empyrian-s`</sub> | ТОРПЕДА-ГОЛОПРОЕКТОР | Люминиум ×300 | 5 с | в событие, раз за событие |
| WIZ-X ×250 <sub>`recipe_blueprint-wiz-x-empyrian-limited`</sub> | ТОРПЕДА-ГОЛОПРОЕКТОР | Люминиум ×225 | 5 с | в событие, до 10 раз за событие |
| WIZ-X ×5 <sub>`recipe_blueprint-flower-token-wiz-x`</sub> | ТОРПЕДА-ГОЛОПРОЕКТОР | Лепестковый кварц ×150 | 5 с | в событие |
| WIZ-X ×500 <sub>`recipe_blueprint-wiz-x-empyrian-m`</sub> | ТОРПЕДА-ГОЛОПРОЕКТОР | Люминиум ×750 | 5 с | в событие, раз за событие |
| Астральный процессор <sub>`recipe_blueprint-astral-cpu`</sub> | Процессор | Полихромиум ×1 000, Мьюкосум ×20 | 5 с | всегда |
| Замедляющая мина ×10 <sub>`recipe_blueprint-flower-token-sl-m01`</sub> | МИНА | Лепестковый кварц ×50 | 5 с | в событие |
| Замедляющая ракета ×10 <sub>`recipe_blueprint-flower-token-dcr-250`</sub> | ЗАМЕДЛЯЮЩАЯ РАКЕТА | Лепестковый кварц ×550 | 5 с | в событие |
| Легендарный процессор пришельцев хаоса <sub>`recipe_blueprint-chaos-alien-cpu-legendary`</sub> | Процессор пришельцев | Фрагмент хаоса ×1 500 | 5 с | в событие |
| Мина с безусловным уроном <sub>`recipe_blueprint-ddm-01`</sub> | МИНА | Скрапиум ×10, Плазмид ×1, Призматиум ×5 | 5 с | в событие |
| Мина с безусловным уроном ×10 <sub>`recipe_blueprint-flower-token-dd-m1`</sub> | МИНА | Лепестковый кварц ×200 | 5 с | в событие |
| Обычный процессор пришельцев хаоса <sub>`recipe_blueprint-chaos-alien-cpu-common`</sub> | Процессор пришельцев | Фрагмент хаоса ×120 | 5 с | в событие |
| Подледниковый процессор <sub>`recipe_blueprint-subglacial-cpu`</sub> | Портал | Ледниковый фрагмент ×4, Уридиум ×1 000 | 5 с | в событие |
| Противокамуфляжная мина ×10 <sub>`recipe_blueprint-flower-token-emp-m01`</sub> | МИНА | Лепестковый кварц ×200 | 5 с | в событие |
| Противощитная мина ×10 <sub>`recipe_blueprint-flower-token-sab-m01`</sub> | Мина | Лепестковый кварц ×200 | 5 с | в событие |
| Процессор «Передатчик Saturn» <sub>`recipe_blueprint-ss-cpu`</sub> | ST-CPU | Наноконденсатор ×2, Микротранзисторы ×1, Гибридный процессор ×2 | 5 с | в событие |
| Процессор вечности <sub>`recipe_blueprint-eternal-cpu`</sub> | Процессор | Фрагмент вечности ×500, Ринуск ×500, Кредиты ×150 000 | 5 с | всегда |
| Процессор пришельцев Chaos Cubikon <sub>`recipe_blueprint-chaos-alien-cpu-cubikon`</sub> | Процессор пришельцев | Фрагмент хаоса ×1 000 | 5 с | в событие, раз за событие |
| Ракета SAR-02 ×1 000 <sub>`recipe_blueprint-sar-02`</sub> | HELLSTORM | Плазмид ×85, Скрапиум ×50, Призматиум ×50 | 5 с | в событие |
| Ракета «Uber» ×1 000 <sub>`recipe_blueprint-ubr-100`</sub> | РАКЕТА «UBER» | Плазмид ×215, Скрапиум ×55, Призматиум ×2 | 5 с | в событие |
| Ракета «Uber» ×50 <sub>`recipe_blueprint-ubr-100-50-worldboss`</sub> | РАКЕТА «UBER» | Фрагмент Infiltrator ×625 | 5 с | в событие |
| Ракета «Uber» ×500 <sub>`recipe_blueprint-aa1-ubr100-1000`</sub> | РАКЕТА «UBER» | AA-1 ×1 | 5 с | в событие |
| Ракета «Uber» ×500 <sub>`recipe_blueprint-aap1-ubr100-1000`</sub> | РАКЕТА «UBER» | AAP-1 ×1 | 5 с | в событие |
| Ракета «Волна холода» ×10 <sub>`recipe_blueprint-flower-token-r-ic3`</sub> | РАКЕТА БОЛЬШОЙ ДАЛЬНОСТИ | Лепестковый кварц ×300 | 5 с | в событие |
| Редкий процессор пришельцев хаоса <sub>`recipe_blueprint-chaos-alien-cpu-rare`</sub> | Процессор пришельцев | Фрагмент хаоса ×750 | 5 с | в событие |
| ЭМ-импульс <sub>`recipe_blueprint-emp-01`</sub> | ЭМ-импульс | Мьюкосум ×5, Призматиум ×12 | 5 с | в событие |
| ЭМ-импульс ×10 <sub>`recipe_blueprint-flower-token-emp-01`</sub> | ЭМ-импульс | Лепестковый кварц ×550 | 5 с | в событие |
| ЭМ-импульс ×500 <sub>`recipe_blueprint-chromin-emp01`</sub> | ЭМ-импульс | Хромин ×80 000 | 5 с | в событие, раз за событие |

## Улучшения предметов (уровни)

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Улучшение: Apis, Iris, Zeus <sub>`upgrade_upgrade-class2-drone`</sub> |  | ур. 1: Регулятор аксионного луча ×4, Уридиум ×632; … ур. 15: Регулятор аксионного луча ×56, Уридиум ×9 751, Индоктринационная нефть ×24 (уровней 15) | 1 мин 20 с | всегда |
| Улучшение: C-Helios, UC-Helios, R-Helios, EP-Helios … <sub>`upgrade_upgrade-class9`</sub> |  | ур. 1: Регулятор аксионного луча ×4, Уридиум ×632; … ур. 19: Регулятор аксионного луча ×300, Уридиум ×21 601, Индоктринационная нефть ×240, Диаметрион ×288 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Flax <sub>`upgrade_upgrade-class0-drone`</sub> |  | ур. 1: Регулятор аксионного луча ×1, Кредиты ×24 341; … ур. 15: Регулятор аксионного луча ×19, Кредиты ×375 405 (уровней 15) | 20 с | всегда |
| Улучшение: Hydra <sub>`upgrade_upgrade-class2-hydra`</sub> |  | ур. 1: Сплав «Гидра» ×10, Уридиум ×632; … ур. 19: Сплав «Гидра» ×100, Уридиум ×21 601, Индоктринационная нефть ×96, Индоктринационный ускоритель ×240 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: IgniDrive Lite, IgniDrive Apex, IgniDrive Prime, Гравитронная хронолинза … <sub>`upgrade_upgrade-class10`</sub> |  | ур. 1: Регулятор аксионного луча ×4, Уридиум ×632; … ур. 19: Регулятор аксионного луча ×300, Уридиум ×21 601, Диаметрион ×504, Индоктринационная нефть ×180 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: LF-5 <sub>`upgrade_upgrade-class6`</sub> |  | ур. 1: Плазменная плита стабилизации ×560, Уридиум ×632; … ур. 19: Плазменная плита стабилизации ×42 000, Уридиум ×21 601, Регулятор аксионного луча ×300 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Neptune <sub>`upgrade_upgrade-class2-neptune`</sub> |  | ур. 1: Сплав «Нептун» ×10, Уридиум ×632; … ур. 19: Сплав «Нептун» ×100, Уридиум ×21 601, Индоктринационная нефть ×96, Индоктринационный ускоритель ×240 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Ouroboros <sub>`upgrade_upgrade-class2-ouroboros`</sub> |  | ур. 1: Сплав «Уроборос» ×10, Уридиум ×632; … ур. 19: Сплав «Уроборос» ×100, Уридиум ×21 601, Индоктринационная нефть ×96, Индоктринационный ускоритель ×240 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Triskelion <sub>`upgrade_upgrade-class2-triskelion`</sub> |  | ур. 1: Сплав «Трискелион» ×10, Уридиум ×632; … ур. 19: Сплав «Трискелион» ×100, Уридиум ×21 601, Индоктринационная нефть ×96, Индоктринационный ускоритель ×240 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Блокировщик LF-5 <sub>`upgrade_upgrade-class7`</sub> |  | ур. 1: Плазменная плита стабилизации ×560, Уридиум ×632; … ур. 19: Плазменная плита стабилизации ×42 000, Уридиум ×21 601, Регулятор аксионного луча ×300, Диаметрион ×110 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Лазер Asmodeus, Лазер Odysseus <sub>`upgrade_upgrade-class5`</sub> |  | ур. 1: Регулятор аксионного луча ×4, Уридиум ×632; … ур. 19: Регулятор аксионного луча ×300, Уридиум ×21 601, Индоктринационная нефть ×96, Диаметрион ×400 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Модуль: бустер урона, Модуль: бустер чести, Лазерный модуль БРД, Лазерный модуль ОРД … <sub>`upgrade_upgrade-class3-cbs`</sub> |  | ур. 1: Уридиум ×1 264, Регулятор аксионного луча ×4; … ур. 15: Уридиум ×19 502, Регулятор аксионного луча ×56, Индоктринационная нефть ×24 (уровней 15) | 1 мин 20 с | всегда |
| Улучшение: Модуль: дефлектор, Модуль: обшивки <sub>`upgrade_upgrade-class4-cbs`</sub> |  | ур. 1: Уридиум ×2 529, Регулятор аксионного луча ×4; … ур. 15: Уридиум ×39 003, Регулятор аксионного луча ×56, Индоктринационная нефть ×24 (уровней 15) | 1 мин 20 с | всегда |
| Улучшение: Синтезирующий щит I, Синтезирующий щит II, Щит SG3N-A01, Щит SG3N-A02 … <sub>`upgrade_upgrade-class0`</sub> |  | ур. 1: Регулятор аксионного луча ×1, Кредиты ×24 341; … ур. 15: Регулятор аксионного луча ×19, Кредиты ×375 405 (уровней 15) | 20 с | всегда |
| Улучшение: Синтезирующий щит III, Синтезирующий щит IV, Щит SG3N-B02, Щит SG3N-B03 … <sub>`upgrade_upgrade-class2`</sub> |  | ур. 1: Регулятор аксионного луча ×4, Уридиум ×632; … ур. 19: Регулятор аксионного луча ×300, Уридиум ×21 601, Индоктринационная нефть ×96, Индоктринационный ускоритель ×240 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Умертвитель LF-5 <sub>`upgrade_upgrade-class8`</sub> |  | ур. 1: Нейтронные осколки ×11, Уридиум ×632; … ур. 19: Нейтронные осколки ×840, Уридиум ×21 601, Регулятор аксионного луча ×300, Диаметрион ×110 (уровней 19) | 1 мин 20 с | всегда |
| Улучшение: Щит SG3N-B00, Щит SG3N-B01, LF-2, LF-3 … <sub>`upgrade_upgrade-class1`</sub> |  | ур. 1: Регулятор аксионного луча ×4, Уридиум ×316; … ур. 15: Регулятор аксионного луча ×56, Уридиум ×4 875 (уровней 15) | 40 с | всегда |

## Ресурсы и руды

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| BK-100, 20 шт. (чертёж) <sub>`recipe_blueprint-green-booty-key-20`</sub> |  | Проксий ×15 | 5 с | в событие |
| Candy Corn ×250 <sub>`recipe_blueprint-flower-token-candy-corn`</sub> | Кукурузная конфета | Лепестковый кварц ×250 | 5 с | в событие |
| Высокочастотный кабель <sub>`recipe_blueprint-flower-token-h-hfc`</sub> | Аппаратура | Лепестковый кварц ×350 | 5 с | в событие, раз за событие |
| Высокочастотный кабель <sub>`recipe_blueprint-high-frequency-cable`</sub> | Аппаратура | Мьюкосум ×50, Плазмид ×10 | 30 мин | в событие |
| Гибридный процессор <sub>`recipe_blueprint-flower-token-h-hp`</sub> | Аппаратура | Лепестковый кварц ×2 850 | 5 с | в событие, раз за событие |
| Гибридный процессор <sub>`recipe_blueprint-hybrid-processor-empyrian-limited`</sub> | Аппаратура | Люминиум ×1 500 | 5 с | в событие, до 2 раз за событие |
| Гибридный процессор <sub>`recipe_blueprint-hybrid-processor`</sub> | Аппаратура | Мьюкосум ×240, Скрапиум ×330 | 10 ч | в событие |
| Гибридный сплав ×30 <sub>`recipe_blueprint-flower-token-hybrid-alloy`</sub> | Гибридный сплав | Лепестковый кварц ×200 | 5 с | в событие |
| Главкупон ×100 <sub>`recipe_blueprint-chromin-quickcoupon`</sub> | Главкупон | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Главный ключ к астральному трофею <sub>`recipe_blueprint-booty-key-astral-prime`</sub> | Ключ к трофею | Осколок Атласа ×500, Ключ к астральному трофею ×1 | 5 с | всегда |
| Дешифровщик Black Light <sub>`recipe_blueprint-blacklight-decoder`</sub> | КЛЮЧ К ТРОФЕЮ | Ключ к зелёному трофею ×20, Кредиты ×100 000 000, Уридиум ×6 500, Осколок Black Light ×2 500, UCB-100 ×10 000 | 5 с | всегда |
| Диаметрион ×10 <sub>`recipe_blueprint-diametrion-empyrian-m`</sub> | Диаметрион | Люминиум ×1 500 | 5 с | в событие, раз за событие |
| Диаметрион ×15 <sub>`recipe_blueprint-diametrion-empyrian-l`</sub> | Диаметрион | Люминиум ×2 250 | 5 с | в событие, раз за событие |
| Диаметрион ×5 <sub>`recipe_blueprint-diametrion-empyrian-s`</sub> | Диаметрион | Люминиум ×750 | 5 с | в событие, раз за событие |
| Доп. энергия ×100 <sub>`recipe_blueprint-extra-energy-atlas-m`</sub> | Доп. энергия | Осколок Атласа ×750 | 5 с | в событие, раз за событие |
| Доп. энергия ×15 <sub>`recipe_blueprint-extra-energy-empyrian-limited`</sub> | Доп. энергия | Люминиум ×290 | 5 с | в событие, до 10 раз за событие |
| Доп. энергия ×20 <sub>`recipe_blueprint-extra-energy-worldboss`</sub> | Доп. энергия | Фрагмент Infiltrator ×180 | 5 с | в событие, до 10 раз за событие |
| Доп. энергия ×20 <sub>`recipe_blueprint-extra-energy-empyrian-s`</sub> | Доп. энергия | Люминиум ×600 | 5 с | в событие, раз за событие |
| Доп. энергия ×30 <sub>`recipe_blueprint-extra-energy-empyrian-m`</sub> | Доп. энергия | Люминиум ×900 | 5 с | в событие, раз за событие |
| Доп. энергия ×30 <sub>`recipe_blueprint-extra-energy-valentine`</sub> | Доп. энергия | Осколок Charopos ×150, Осколок Styxus ×150 | 5 с | в событие, раз за событие |
| Доп. энергия ×50 <sub>`recipe_blueprint-extra-energy-atlas-s`</sub> | Доп. энергия | Осколок Атласа ×375 | 5 с | в событие, раз за событие |
| Доп. энергия ×60 <sub>`recipe_blueprint-extra-energy-empyrian-l`</sub> | Доп. энергия | Люминиум ×1 800 | 5 с | в событие, раз за событие |
| Доп. энергия, 500 ед. (чертёж) <sub>`recipe_blueprint-extra-energy-500`</sub> |  | Проксий ×30, Триттотрин ×1 | 5 с | в событие |
| Изохронат <sub>`recipe_blueprint-upgrade-resource-isochronate`</sub> | Накапливается | UCB-100 ×100 | 5 с | всегда |
| Индоктринационная нефть ×15 <sub>`recipe_blueprint-flower-token-indoctrine-oil`</sub> | Ресурс | Лепестковый кварц ×8 100 | 5 с | в событие, раз за событие |
| Индоктринационная нефть ×50 <sub>`recipe_blueprint-indoctrineoil-halloween`</sub> | Ресурс | Золотая кукурузная конфета ×25, Candy Corn ×95 000, Скрапиум ×20 | 5 с | в событие, раз за событие |
| Индоктринационная нефть ×75 <sub>`recipe_blueprint-indoctrine-oil-worldboss`</sub> | Ресурс | Фрагмент Infiltrator ×4 000 | 5 с | в событие, до 3 раз за событие |
| Индоктринационная нефть ×75 <sub>`recipe_blueprint-indoctrine-oil-atlas`</sub> | Ресурс | Осколок Атласа ×2 100 | 5 с | в событие, раз за событие |
| Индоктринационный ускоритель ×125 <sub>`recipe_blueprint-indoctrine-accelerator-worldboss`</sub> | Ресурс | Фрагмент Infiltrator ×4 000 | 5 с | в событие, до 3 раз за событие |
| Индоктринационный ускоритель ×50 <sub>`recipe_blueprint-flower-token-iac`</sub> | Ресурс | Лепестковый кварц ×11 500 | 5 с | в событие, раз за событие |
| Индоктринационный ускоритель ×75 <sub>`recipe_blueprint-indoctrine-accelerator-halloween`</sub> | Ресурс | Золотая кукурузная конфета ×25, Candy Corn ×95 000, Скрапиум ×20 | 5 с | в событие, раз за событие |
| Капсула <sub>`recipe_blueprint-lottery-capsule`</sub> | Лотерея «Hitac» | Мьюкосум ×40, Плазмид ×5, Скрапиум ×20 | 30 мин | в событие |
| Капсула Black Light ×500 <sub>`recipe_blueprint-chromin-blacklight-trace`</sub> | Ресурс | Хромин ×30 000 | 5 с | в событие, раз за событие |
| Ключ боевого пропуска ×2 <sub>`recipe_blueprint-flower-token-battle-pass-key`</sub> | Ключ боевого пропуска | Лепестковый кварц ×5 000 | 5 с | в событие, один раз |
| Ключ к апокалипсическому трофею <sub>`recipe_blueprint-apocalypse-booty-key`</sub> | КЛЮЧ К ТРОФЕЮ | Скрапиум ×20, Aurus ×10, Схизмокристалл ×5 | 5 с | в событие |
| Ключ к астральному трофею <sub>`recipe_blueprint-booty-key-astral`</sub> | Ключ к трофею | Астралит ×500 | 5 с | всегда |
| Ключ к зелёному трофею <sub>`recipe_blueprint-booty-key-worldboss`</sub> | КЛЮЧ К ТРОФЕЮ | Фрагмент Infiltrator ×450 | 5 с | в событие |
| Ключ к зелёному трофею ×30 <sub>`recipe_blueprint-flower-token-booty-key`</sub> | КЛЮЧ К ТРОФЕЮ | Лепестковый кварц ×6 450 | 5 с | в событие, раз за событие |
| Ключ к красному трофею <sub>`recipe_blueprint-red-booty-key`</sub> | КЛЮЧ К ТРОФЕЮ | Скрапиум ×10, Aurus ×5, Схизмокристалл ×3 | 5 с | в событие |
| Ключ к серебряным трофеям <sub>`recipe_blueprint-booty-key-silver`</sub> | КЛЮЧ К ТРОФЕЮ | Монета Darksilver ×25 | 5 с | всегда |
| Ключ к синему трофею <sub>`recipe_blueprint-blue-booty-key`</sub> | КЛЮЧ К ТРОФЕЮ | Скрапиум ×20, Aurus ×10, Схизмокристалл ×5 | 5 с | в событие |
| Ключ Морозного лабиринта ×5 <sub>`recipe_blueprint-flower-token-frozen-labyrinth-key`</sub> |  | Лепестковый кварц ×1 000 | 5 с | в событие, один раз |
| Купон на телепортацию ×5 <sub>`recipe_blueprint-flower-token-jump-vouchers`</sub> | Купон на телепортацию | Лепестковый кварц ×300 | 5 с | в событие |
| Ледниковый фрагмент <sub>`recipe_blueprint-glacial-fragment`</sub> | Аппаратура | Мьюкосум ×20, Плазмид ×4, Скрапиум ×24 | 1 ч | в событие |
| Лог-диск ×30 <sub>`recipe_blueprint-flower-token-log-disks`</sub> | ЛОГ-ДИСК | Лепестковый кварц ×1 950 | 5 с | в событие, раз за событие |
| Микротранзисторы <sub>`recipe_blueprint-micro-transitors`</sub> | Аппаратура | Мьюкосум ×40, Плазмид ×30, Скрапиум ×25, Aurus ×5 | 10 ч | в событие |
| Микротранзисторы <sub>`recipe_blueprint-micro-trans-empyrian-limited`</sub> | Аппаратура | Люминиум ×1 500 | 5 с | в событие, до 2 раз за событие |
| Микротранзисторы <sub>`recipe_blueprint-flower-token-h-mt`</sub> | Аппаратура | Лепестковый кварц ×2 850 | 5 с | в событие, раз за событие |
| Монета Darksilver ×25 <sub>`recipe_blueprint-darksilver-coin-atlas`</sub> | Darksilver | Осколок Атласа ×100 | 5 с | в событие, раз за событие |
| Мьюкосум ×10 <sub>`recipe_blueprint-flower-token-mucosum`</sub> | Ресурс | Лепестковый кварц ×50 | 5 с | в событие |
| Нанокейс <sub>`recipe_blueprint-nano-case`</sub> | Аппаратура | Плазмид ×10, Скрапиум ×25 | 30 мин | в событие |
| Нанокейс <sub>`recipe_blueprint-flower-token-h-nca`</sub> | Аппаратура | Лепестковый кварц ×350 | 5 с | в событие, раз за событие |
| Наноконденсатор <sub>`recipe_blueprint-flower-token-h-nco`</sub> | Аппаратура | Лепестковый кварц ×2 850 | 5 с | в событие, раз за событие |
| Наноконденсатор <sub>`recipe_blueprint-nano-condenser`</sub> | Аппаратура | Скрапиум ×50, Призматиум ×10 | 4 ч | в событие |
| Нейроплата Mindfire ×100 <sub>`recipe_blueprint-flower-token-mindfire-cerebrum`</sub> | Ресурс | Лепестковый кварц ×4 300 | 5 с | в событие, раз за событие |
| Нейроплата Mindfire ×35 <sub>`recipe_blueprint-chromin-mindfire-cerebrum`</sub> | Ресурс | Хромин ×30 000 | 5 с | в событие, раз за событие |
| Нестабильный осколок ×10 <sub>`recipe_blueprint-agate-unstable-shard-limited`</sub> | Нестабильный осколок | Агатовый осколок ×2 000 | 5 с | в событие, до 5 раз за событие |
| Обсидиановый микрочип <sub>`recipe_blueprint-black-booty-key`</sub> | Ключ к трофею | Скрапиум ×20, Aurus ×10, Схизмокристалл ×5 | 5 с | в событие |
| Осколок Black Light ×2 000 <sub>`recipe_blueprint-flower-token-black-light-shard`</sub> | Ресурс | Лепестковый кварц ×4 850 | 5 с | в событие, раз за событие |
| Осмиум ×6 000 <sub>`recipe_blueprint-chromin-osmium`</sub> | Осмиум | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Палладий ×150 <sub>`recipe_blueprint-palladium-empyrian-s`</sub> | Руда | Люминиум ×300 | 5 с | в событие, раз за событие |
| Палладий ×200 <sub>`recipe_blueprint-palladium-empyrian-limited`</sub> | Руда | Люминиум ×300 | 5 с | в событие, до 8 раз за событие |
| Палладий ×300 <sub>`recipe_blueprint-palladium-empyrian-m`</sub> | Руда | Люминиум ×600 | 5 с | в событие, раз за событие |
| Палладий ×600 <sub>`recipe_blueprint-palladium-empyrian-l`</sub> | Руда | Люминиум ×1 200 | 5 с | в событие, раз за событие |
| Плазмид ×10 <sub>`recipe_blueprint-flower-token-boltrum`</sub> | Ресурс | Лепестковый кварц ×300 | 5 с | в событие |
| Полихромиум ×1 000 <sub>`recipe_blueprint-chromin-polychromium`</sub> | Полихромиум | Хромин ×1 888 | 5 с | в событие, раз за событие |
| Полихромиум ×1 000 <sub>`recipe_blueprint-polychromium-empyrian-m`</sub> | Полихромиум | Люминиум ×600 | 5 с | в событие, раз за событие |
| Полихромиум ×2 500 <sub>`recipe_blueprint-polychromium-empyrian-l`</sub> | Полихромиум | Люминиум ×1 500 | 5 с | в событие, раз за событие |
| Полихромиум ×200 <sub>`recipe_blueprint-polychromium-s-valentine`</sub> | Полихромиум | Осколок Charopos ×100, Осколок Styxus ×100 | 5 с | в событие, раз за событие |
| Полихромиум ×3 000 <sub>`recipe_blueprint-polychromium-empyrian`</sub> | Полихромиум | Люминиум ×390 | 5 с | в событие, раз за событие |
| Полихромиум ×300 <sub>`recipe_blueprint-polychromium-m-valentine`</sub> | Полихромиум | Осколок Charopos ×150, Осколок Styxus ×150 | 5 с | в событие, раз за событие |
| Полихромиум ×500 <sub>`recipe_blueprint-polychromium-l-valentine`</sub> | Полихромиум | Осколок Charopos ×250, Осколок Styxus ×250 | 5 с | в событие, раз за событие |
| Полихромиум ×500 <sub>`recipe_blueprint-polychromium-empyrian-s`</sub> | Полихромиум | Люминиум ×300 | 5 с | в событие, раз за событие |
| Полихромиум ×820 <sub>`recipe_blueprint-polychromium-empyrian-limited`</sub> | Полихромиум | Люминиум ×400 | 5 с | в событие, до 5 раз за событие |
| Призма-слот <sub>`recipe_blueprint-flower-token-h-ps`</sub> | Аппаратура | Лепестковый кварц ×1 450 | 5 с | в событие, раз за событие |
| Призма-слот <sub>`recipe_blueprint-prismatic-socket`</sub> | Аппаратура | Мьюкосум ×80, Призматиум ×10 | 4 ч | в событие |
| Призматиум ×10 <sub>`recipe_blueprint-flower-token-prismatium`</sub> | Ресурс | Лепестковый кварц ×1 700 | 5 с | в событие |
| Призматиум ×10 <sub>`recipe_blueprint-prismatium-empyrian-m`</sub> | Ресурс | Люминиум ×1 000 | 5 с | в событие, раз за событие |
| Призматиум ×20 <sub>`recipe_blueprint-prismatium-empyrian-l`</sub> | Ресурс | Люминиум ×2 000 | 5 с | в событие, раз за событие |
| Призматиум ×5 <sub>`recipe_blueprint-prismatium-empyrian-limited`</sub> | Ресурс | Люминиум ×300 | 5 с | в событие, до 10 раз за событие |
| Призматиум ×5 <sub>`recipe_blueprint-prismatium-empyrian-s`</sub> | Ресурс | Люминиум ×500 | 5 с | в событие, раз за событие |
| Призматиум ×5 <sub>`recipe_blueprint-prismatium-empyrian`</sub> | Ресурс | Люминиум ×900 | 5 с | в событие, раз за событие |
| Разрешение <sub>`recipe_blueprint-permit-worldboss`</sub> | Разрешение | Фрагмент Infiltrator ×2 000 | 5 с | в событие, до 8 раз за событие |
| Разрешение Plus <sub>`recipe_blueprint-permit-plus`</sub> | Разрешение Plus | Астралит ×1 450, Мьюкосум ×20 | 5 с | всегда |
| Разрешение Plus <sub>`recipe_blueprint-permitplus-worldboss`</sub> | Разрешение Plus | Фрагмент Infiltrator ×6 000 | 5 с | в событие, до 5 раз за событие |
| Разрешение Plus ×5 <sub>`recipe_blueprint-chromin-permit-plus`</sub> | Разрешение Plus | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Разрешение ×15 <sub>`recipe_blueprint-chromin-permit`</sub> | Разрешение | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Регулятор аксионного луча ×100 <sub>`recipe_blueprint-agate-abr-limited`</sub> | Накапливается | Агатовый осколок ×5 000 | 5 с | в событие, до 3 раз за событие |
| Регулятор аксионного луча ×150 <sub>`recipe_blueprint-flower-token-abr`</sub> | Накапливается | Лепестковый кварц ×6 450 | 5 с | в событие, раз за событие |
| Регулятор аксионного луча ×250 <sub>`recipe_blueprint-abr-worldboss`</sub> | Накапливается | Фрагмент Infiltrator ×2 000 | 5 с | в событие, до 3 раз за событие |
| Регулятор аксионного луча ×250 <sub>`recipe_blueprint-abr-atlas`</sub> | Накапливается | Осколок Атласа ×1 500 | 5 с | в событие, раз за событие |
| Ринуск ×800 <sub>`recipe_blueprint-chromin-rinusk`</sub> | Ресурс | Хромин ×30 000 | 5 с | в событие, раз за событие |
| Сепром ×10 000 <sub>`recipe_blueprint-seprom-halloween`</sub> | Руда | Золотая кукурузная конфета ×10, Candy Corn ×65 000, Скрапиум ×20 | 5 с | в событие, раз за событие |
| Сепром ×100 <sub>`recipe_blueprint-seprom-empyrian-s`</sub> | Руда | Люминиум ×300 | 5 с | в событие, раз за событие |
| Сепром ×110 <sub>`recipe_blueprint-seprom-empyrian-limited`</sub> | Руда | Люминиум ×200 | 5 с | в событие, до 15 раз за событие |
| Сепром ×25 000 <sub>`recipe_blueprint-chromin-seprom`</sub> | Руда | Хромин ×80 000 | 5 с | в событие, раз за событие |
| Сепром ×300 <sub>`recipe_blueprint-seprom-empyrian-m`</sub> | Руда | Люминиум ×900 | 5 с | в событие, раз за событие |
| Сепром ×5 000 <sub>`recipe_blueprint-seprom-blacklight`</sub> | Руда | Уридиум ×10 000, Ринуск ×1 250, Капсула Black Light ×250 | 5 с | всегда |
| Сепром ×5 000 <sub>`recipe_blueprint-seprom`</sub> | Руда | Схизмокристалл ×10, Скрапиум ×50 | 5 с | в событие |
| Сепром ×600 <sub>`recipe_blueprint-seprom-empyrian-l`</sub> | Руда | Люминиум ×1 800 | 5 с | в событие, раз за событие |
| Скрапиум ×10 <sub>`recipe_blueprint-flower-token-scrapium`</sub> | Ресурс | Лепестковый кварц ×100 | 5 с | в событие |
| Скрапиум ×10 <sub>`recipe_blueprint-scrapium-atlas`</sub> | Ресурс | Осколок Атласа ×35 | 5 с | всегда |
| Скрапиум ×100 <sub>`recipe_blueprint-scrapium-empyrian-m`</sub> | Ресурс | Люминиум ×1 275 | 5 с | в событие, раз за событие |
| Скрапиум ×50 <sub>`recipe_blueprint-scrapium-empyrian-s`</sub> | Ресурс | Люминиум ×423 | 5 с | в событие, раз за событие |
| Схизмокристалл ×5 <sub>`recipe_blueprint-schism-empyrian`</sub> | Ресурс | Люминиум ×1 650 | 5 с | в событие, раз за событие |
| Фрагмент вечности ×250 <sub>`recipe_blueprint-flower-token-eternal-fragment`</sub> | Ресурс | Лепестковый кварц ×5 450 | 5 с | в событие, раз за событие |
| Фрагмент чертежа («Apis») <sub>`recipe_blueprint-drone-apis-part`</sub> | DB-01 A | Уридиум ×24 000 | 5 с | всегда |
| Фрагмент чертежа («Zeus») <sub>`recipe_blueprint-drone-zeus-part`</sub> | DB-02 Z | Уридиум ×33 000 | 5 с | всегда |
| Чип эвокации ×100 <sub>`recipe_blueprint-agate-evocation-chip-limited`</sub> | Чип эвокации | Агатовый осколок ×2 000 | 5 с | в событие, до 5 раз за событие |
| Шифр Black Light <sub>`recipe_blueprint-blacklight-cipher`</sub> | КЛЮЧ К ТРОФЕЮ | Нейроплата Mindfire ×100, Ключ к зелёному трофею ×20, Кредиты ×75 000 000, UCB-100 ×10 000, RSB-75 ×1 500, Ринуск ×2 400, Капсула Black Light ×1 000 | 5 с | всегда |
| Экспедиционный модуль галактических врат <sub>`recipe_blueprint-agate-ggeu-limited`</sub> | ЭМГВ | Агатовый осколок ×2 000 | 5 с | в событие, до 5 раз за событие |
| Экспедиционный модуль галактических врат <sub>`recipe_blueprint-ggeu`</sub> | ЭМГВ | Солидус ×500, Уридиум ×6 000 | 5 с | всегда |
| Экспедиционный модуль галактических врат <sub>`recipe_blueprint-agate-ggeu`</sub> | ЭМГВ | Агатовый осколок ×2 000 | 5 с | в событие |
| Экспедиционный модуль галактических врат ×2 <sub>`recipe_blueprint-ggeu-atlas-s`</sub> | ЭМГВ | Осколок Атласа ×120 | 5 с | в событие, раз за событие |
| Экспедиционный модуль галактических врат ×4 <sub>`recipe_blueprint-ggeu-atlas-m`</sub> | ЭМГВ | Осколок Атласа ×240 | 5 с | в событие, раз за событие |
| Эмпирианский фрагмент <sub>`recipe_blueprint-booty-key-empyrian-limited`</sub> | Ключ к трофею | Люминиум ×2 700 | 5 с | в событие, до 3 раз за событие |
| Эмпирианский фрагмент ×2 <sub>`recipe_blueprint-booty-key-empyrian`</sub> | Ключ к трофею | Люминиум ×5 400 | 5 с | в событие, раз за событие |
| Ядро спасения ×120 <sub>`recipe_blueprint-salvage-core-empyrian`</sub> | Ядро спасения | Люминиум ×3 600 | 5 с | в событие, раз за событие |
| Ядро спасения ×15 <sub>`recipe_blueprint-salvage-core-empyrian-limited`</sub> | Ядро спасения | Люминиум ×250 | 5 с | в событие, до 12 раз за событие |

## Питомец

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| P.E.T. 10 <sub>`recipe_blueprint-pet10`</sub> | P.E.T. 10 | Нанокейс ×3, Высокочастотный кабель ×2 | 5 с | в событие, один раз |
| Дизайн P.E.T. Borealis Berserker <sub>`recipe_blueprint-flower-token-borealis-berserker-pet-design`</sub> | Косметический дизайн P.E.T. | Лепестковый кварц ×7 150 | 5 с | в событие, один раз |
| Дизайн P.E.T. Eldritch Diminisher <sub>`recipe_blueprint-champion-pet-diminisher-eldritch`</sub> | Косметический дизайн P.E.T. | Жетон чемпиона ×80 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. Harbinger Centurion <sub>`recipe_blueprint-champion-pet-centurion-harbinger`</sub> | Косметический дизайн P.E.T. | Жетон чемпиона ×80 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. Harbinger Sentinel <sub>`recipe_blueprint-champion-pet-sentinel-harbinger`</sub> | Косметический дизайн P.E.T. | Жетон чемпиона ×80 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. Harbinger Spectrum <sub>`recipe_blueprint-champion-pet-spectrum-harbinger`</sub> | Косметический дизайн P.E.T. | Жетон чемпиона ×80 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. Mini Phantasm Curcubitor <sub>`recipe_blueprint-pet-phantasm`</sub> | Косметический дизайн P.E.T. | Золотая кукурузная конфета ×30, Candy Corn ×45 000 | 5 с | в событие, раз за событие |
| Дизайн P.E.T. Seraph Centurion <sub>`recipe_blueprint-champion-pet-centurion-seraph`</sub> | Косметический дизайн P.E.T. | Жетон чемпиона ×80 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. Seraph Spectrum <sub>`recipe_blueprint-champion-pet-spectrum-seraph`</sub> | Косметический дизайн P.E.T. | Жетон чемпиона ×80 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. «Argon Chimera» <sub>`recipe_blueprint-chimera-argon`</sub> | Косметический дизайн P.E.T. | Высокочастотный кабель ×3, Наноконденсатор ×3, Микротранзисторы ×2 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. «Blaze Mirage» <sub>`recipe_blueprint-blaze-mirage`</sub> | Косметический дизайн для P.E.T. | Высокочастотный кабель ×4, Призма-слот ×4 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Blaze Pusat» <sub>`recipe_blueprint-pet-pusat-blaze`</sub> | Косметический дизайн P.E.T. | Нанокейс ×4, Наноконденсатор ×2, Микротранзисторы ×1 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Blaze Spectrum» <sub>`recipe_blueprint-pet-spectrum-blaze`</sub> | Косметический дизайн P.E.T. | Нанокейс ×4, Наноконденсатор ×2, Микротранзисторы ×1 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Borealis Chimera» <sub>`recipe_blueprint-chimera-green`</sub> | Косметический дизайн для P.E.T. | Мьюкосум ×440, Скрапиум ×1 300, Призматиум ×60 | 5 с | в событие |
| Дизайн P.E.T. «Borealis Chimera» <sub>`recipe_blueprint-borealis-chimera`</sub> | Косметический дизайн для P.E.T. | Нанокейс ×4, Наноконденсатор ×4 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Borealis Goliath-X» <sub>`recipe_blueprint-pet-goliath-x-borealis`</sub> | Косметический дизайн P.E.T. | Нанокейс ×6, Наноконденсатор ×4, Микротранзисторы ×1 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Borealis Mirage» <sub>`recipe_blueprint-mirage-green`</sub> | Косметический дизайн для P.E.T. | Мьюкосум ×440, Скрапиум ×1 300, Призматиум ×60 | 5 с | в событие |
| Дизайн P.E.T. «Borealis Mirage» <sub>`recipe_blueprint-borealis-mirage`</sub> | Косметический дизайн для P.E.T. | Высокочастотный кабель ×4, Призма-слот ×4 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Goliath-X» <sub>`recipe_blueprint-pet-goliath-x-regular`</sub> | Косметический дизайн P.E.T. | Высокочастотный кабель ×4, Нанокейс ×4, Наноконденсатор ×3 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Legend Chimera» <sub>`recipe_blueprint-chimera-legend`</sub> | Косметический дизайн P.E.T. | Высокочастотный кабель ×10, Наноконденсатор ×8, Гибридный процессор ×2 | 5 с | когда есть чертёж, один раз |
| Дизайн P.E.T. «Ocean Chimera» <sub>`recipe_blueprint-chimera-ocean`</sub> | Косметический дизайн P.E.T. | Наноконденсатор ×3, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Ocean Goliath-X» <sub>`recipe_blueprint-pet-goliath-x-ocean`</sub> | Косметический дизайн P.E.T. | Нанокейс ×4, Наноконденсатор ×2, Микротранзисторы ×1 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Poison Chimera» <sub>`recipe_blueprint-chimera-poison`</sub> | Косметический дизайн P.E.T. | Наноконденсатор ×3, Гибридный процессор ×1 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Sandstorm Chimera» <sub>`recipe_blueprint-sandstorm-chimera`</sub> | Косметический дизайн для P.E.T. | Нанокейс ×4, Наноконденсатор ×4 | 5 с | в событие, один раз |
| Дизайн P.E.T. «Sandstorm Goliath-X» <sub>`recipe_blueprint-pet-goliath-x-sandstorm`</sub> | Косметический дизайн P.E.T. | Нанокейс ×6, Наноконденсатор ×4, Микротранзисторы ×1 | 5 с | в событие, один раз |
| Дизайн «Ocean A-Elite» для P.E.T. <sub>`recipe_blueprint-pet-a-elite-ocean`</sub> | Косметический дизайн для P.E.T. | Гибридный процессор ×2, Нанокейс ×3, Aurus ×3 | 5 с | в событие, один раз |
| Дизайн «Poison A-Elite» для P.E.T. <sub>`recipe_blueprint-pet-a-elite-poison`</sub> | Дизайн | Гибридный процессор ×2, Нанокейс ×3 | 5 с | когда есть чертёж, один раз |

## Бустеры

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| Бустер опыта <sub>`recipe_blueprint-1hbooster-xp`</sub> | Бустер опыта | Мьюкосум ×30, Плазмид ×1, Скрапиум ×25 | 5 с | в событие |
| Бустер опыта/щита ×2 <sub>`recipe_blueprint-ephon100-blacklight`</sub> |  | Уридиум ×150 000, Нейроплата Mindfire ×1 500, Ринуск ×10 000, Капсула Black Light ×10 000 | 5 с | когда есть чертёж |
| Бустер охлаждения <sub>`recipe_blueprint-1hbooster-cd`</sub> | Бустер охлаждения | Мьюкосум ×30, Плазмид ×1, Скрапиум ×25 | 5 с | в событие |
| Бустер очков прогресса акции (количество) ×2 <sub>`recipe_blueprint-2hbooster-rm-b00`</sub> | Бустер | UCB-100 ×15 000, Уридиум ×1 500 | 5 с | в событие |
| Бустер прочности <sub>`recipe_blueprint-1hbooster-hp`</sub> | Бустер прочности | Мьюкосум ×30, Плазмид ×1, Скрапиум ×25 | 5 с | в событие |
| Бустер ресурсов <sub>`recipe_blueprint-1hbooster-res`</sub> | Бустер ресурсов | Мьюкосум ×30, Плазмид ×1, Скрапиум ×25 | 5 с | в событие |
| Бустер урона <sub>`recipe_blueprint-1hbooster-dmg`</sub> | Бустер урона | Мьюкосум ×30, Плазмид ×1, Скрапиум ×25 | 5 с | в событие |
| Бустер урона DLB <sub>`recipe_blueprint-tarnom-dmg-dlb`</sub> | Бустер | Тарном ×750, Уридиум ×2 500, Кредиты ×1 500 000 | 5 с | в событие |
| Бустер щита <sub>`recipe_blueprint-1hbooster-shd`</sub> | Бустер щита | Мьюкосум ×30, Плазмид ×1, Скрапиум ×25 | 5 с | в событие |
| Бустер щита DLB ×5 <sub>`recipe_blueprint-opitite-shd-dlb-5`</sub> | Booster | Опитит ×2 000, Уридиум ×8 500, Кредиты ×10 000 000 | 5 с | в событие |
| Бустер-распределитель урона ×5 <sub>`recipe_blueprint-opitite-dmg-dlb2-5`</sub> | Бустер | Опитит ×2 000, Уридиум ×8 500, Кредиты ×10 000 000 | 5 с | в событие |

## Прочее

| Получаешь | Что даёт | Цена | Время | Когда |
|---|---|---|---|---|
| N.O.V.A. <sub>`recipe_blueprint-captain-generic`</sub> | N.O.V.A. | Чип N.E.U. ×500 | 5 с | в событие |
| N.O.V.A. <sub>`recipe_blueprint-captain-generic-worldboss`</sub> | N.O.V.A. | Фрагмент Infiltrator ×6 000 | 5 с | в событие, до 2 раз за событие |
| N.O.V.A. - дивизион «Нео» <sub>`recipe_blueprint-captain-gen1-neo`</sub> | N.O.V.A. | Чип N.E.U. ×500 | 5 с | в событие |
| N.O.V.A. - дивизион «Эос» <sub>`recipe_blueprint-captain-gen2-eos-worldboss`</sub> | N.O.V.A. | Фрагмент Infiltrator ×6 000 | 5 с | в событие, раз за событие |
| Боевой робот-ремонтник <sub>`recipe_blueprint-tech-battlerepairbot-uridium`</sub> |  | Уридиум ×2 800 | 5 с | всегда |
| Боевой робот-ремонтник <sub>`recipe_blueprint-tech-battlerepairbot`</sub> |  | Кредиты ×500 000, Сепром ×250, Лог-диск ×2 | 5 с | всегда |
| Боевой робот-ремонтник <sub>`recipe_blueprint-flower-token-battle-repair-bot`</sub> |  | Лепестковый кварц ×400 | 5 с | в событие |
| Боевой робот-ремонтник 2 <sub>`recipe_blueprint-brb2-worldboss`</sub> | Боевой робот-ремонтник 2 | Фрагмент Infiltrator ×500 | 5 с | в событие |
| Боевой робот-ремонтник 2 <sub>`recipe_blueprint-agate-brb-2`</sub> | Боевой робот-ремонтник 2 | Агатовый осколок ×2 500 | 5 с | в событие |
| Боевой робот-ремонтник 2 <sub>`recipe_blueprint-flower-token-brb-2`</sub> | Боевой робот-ремонтник 2 | Лепестковый кварц ×3 950 | 5 с | в событие |
| Взрыв NPC <sub>`recipe_blueprint-flower-token-npc-nuke`</sub> | Взрыв NPC | Лепестковый кварц ×3 950 | 5 с | в событие |
| Взрыв NPC <sub>`recipe_blueprint-npc-nuke-empyrian`</sub> | Взрыв NPC | Люминиум ×3 000 | 5 с | в событие |
| Взрыв NPC <sub>`recipe_blueprint-npc-nuke-valentine`</sub> | Взрыв NPC | Осколок Charopos ×250, Осколок Styxus ×250 | 5 с | в событие, раз за событие |
| Лазерный модуль БРД <sub>`recipe_blueprint-module-laserhr`</sub> | ЛАЗЕРНЫЙ МОДУЛЬ БРД | Плазмид ×10, Скрапиум ×30, Призматиум ×15 | 5 с | в событие |
| Лазерный модуль ОРД <sub>`recipe_blueprint-module-laserlr`</sub> | ЛАЗЕРНЫЙ МОДУЛЬ ОРД | Мьюкосум ×180, Скрапиум ×240 | 5 с | в событие |
| Лазерный модуль СРД <sub>`recipe_blueprint-module-lasermr`</sub> | ЛАЗЕРНЫЙ МОДУЛЬ СРД | Скрапиум ×100, Призматиум ×10 | 5 с | в событие |
| Модуль: бустер опыта <sub>`recipe_blueprint-module-xp`</sub> | МОДУЛЬ: БУСТЕР ОПЫТА | Мьюкосум ×300, Плазмид ×40 | 5 с | в событие |
| Модуль: бустер урона <sub>`recipe_blueprint-module-damage`</sub> | МОДУЛЬ: БУСТЕР УРОНА | Плазмид ×60, Скрапиум ×70 | 5 с | в событие |
| Модуль: бустер чести <sub>`recipe_blueprint-module-honor`</sub> | МОДУЛЬ: БУСТЕР ЧЕСТИ | Плазмид ×40, Призматиум ×8 | 5 с | в событие |
| Модуль: дефлектор <sub>`recipe_blueprint-module-deflector`</sub> | МОДУЛЬ: ДЕФЛЕКТОР | Плазмид ×50, Скрапиум ×150, Призматиум ×15 | 5 с | в событие |
| Модуль: обшивки <sub>`recipe_blueprint-module-hull`</sub> | МОДУЛЬ: ОБШИВКИ | Плазмид ×90, Скрапиум ×160, Призматиум ×10 | 5 с | в событие |
| Морозящее поле <sub>`recipe_blueprint-flower-token-frost-field`</sub> | Морозящее поле | Лепестковый кварц ×1 050 | 5 с | в событие |
| Пламенный след <sub>`recipe_blueprint-flower-token-burning-trail`</sub> | Пламенный след | Лепестковый кварц ×3 950 | 5 с | в событие |
| Пламенный след <sub>`recipe_blueprint-opitite-bt`</sub> | Пламенный след | Опитит ×5 000, LF-4 ×2, Уридиум ×15 000 | 5 с | в событие |
| Противоминный передатчик <sub>`recipe_blueprint-tech-amt-1`</sub> | Противоминный передатчик | UCB-100 ×20 000, RSB-75 ×5 000 | 5 с | в событие |
| Разрывной сгусток <sub>`recipe_blueprint-flower-token-explosive-charging-blob`</sub> | Разрывной сгусток | Лепестковый кварц ×4 650 | 5 с | в событие |
| Разрывной сгусток <sub>`recipe_blueprint-opitite-ecb`</sub> | Разрывной сгусток | Опитит ×5 000, Щит SG3N-B02 ×5, Уридиум ×15 000 | 5 с | в событие |
| Ракетный модуль ОМ <sub>`recipe_blueprint-module-rocketla`</sub> | РАКЕТНЫЙ МОДУЛЬ ОМ | Плазмид ×40, Призматиум ×5 | 5 с | в событие |
| Ракетный модуль СМ <sub>`recipe_blueprint-module-rocketma`</sub> | РАКЕТНЫЙ МОДУЛЬ СМ | Плазмид ×10, Призматиум ×15 | 5 с | в событие |
| Ракетный прицел <sub>`recipe_blueprint-precision-targeter-worldboss`</sub> |  | Фрагмент Infiltrator ×200 | 5 с | в событие |
| Ракетный прицел <sub>`recipe_blueprint-tech-precisiontargeter`</sub> |  | Кредиты ×20 000 | 5 с | всегда |
| Ракетный прицел <sub>`recipe_blueprint-flower-token-precision-targeter`</sub> |  | Лепестковый кварц ×200 | 5 с | в событие |
| Ракетный прицел <sub>`recipe_blueprint-tech-precisiontargeter-uridium`</sub> |  | Уридиум ×250 | 5 с | всегда |
| Ремонтный модуль <sub>`recipe_blueprint-module-repair`</sub> | РЕМОНТНЫЙ МОДУЛЬ | Мьюкосум ×120, Призматиум ×15 | 5 с | в событие |
| Ускорение <sub>`recipe_blueprint-agate-speed-burst`</sub> | Ускорение | Агатовый осколок ×3 500 | 5 с | в событие |
| Ускорение <sub>`recipe_blueprint-speed-burst-valentine`</sub> | Ускорение | Осколок Charopos ×75, Осколок Styxus ×75 | 5 с | в событие, раз за событие |
| Цепная реакция <sub>`recipe_blueprint-flower-token-chain-impulse`</sub> |  | Лепестковый кварц ×250 | 5 с | в событие |
| Цепная реакция <sub>`recipe_blueprint-tech-chainpulse-uridium`</sub> |  | Уридиум ×1 800 | 5 с | всегда |
| Цепная реакция <sub>`recipe_blueprint-tech-chainpulse`</sub> |  | Кредиты ×500 000, Сепром ×200, Лог-диск ×1 | 5 с | всегда |
| Щитовосстановитель <sub>`recipe_blueprint-tech-backupshield`</sub> |  | Кредиты ×500 000, Сепром ×250, Лог-диск ×2 | 5 с | всегда |
| Щитовосстановитель <sub>`recipe_blueprint-flower-token-backup-shield`</sub> |  | Лепестковый кварц ×400 | 5 с | в событие |
| Щитовосстановитель <sub>`recipe_blueprint-tech-backupshield-uridium`</sub> |  | Уридиум ×2 800 | 5 с | всегда |
| Щитовосстановитель 2 <sub>`recipe_blueprint-bs2-worldboss`</sub> | Щитовосстановитель 2 | Фрагмент Infiltrator ×1 000 | 5 с | в событие |
| Щитовосстановитель 2 <sub>`recipe_blueprint-agate-bs-2`</sub> | Щитовосстановитель 2 | Агатовый осколок ×2 500 | 5 с | в событие |
| Щитовосстановитель 2 <sub>`recipe_blueprint-flower-token-bs-2`</sub> | Щитовосстановитель 2 | Лепестковый кварц ×3 950 | 5 с | в событие |
| Энерготрансфер <sub>`recipe_blueprint-tech-energyleech-uridium`</sub> |  | Уридиум ×6 500 | 5 с | всегда |
| Энерготрансфер <sub>`recipe_blueprint-tech-energyleech`</sub> |  | Кредиты ×1 000 000, Сепром ×500, Лог-диск ×5 | 5 с | всегда |
| Энерготрансфер <sub>`recipe_blueprint-energy-leech-worldboss`</sub> |  | Фрагмент Infiltrator ×1 000 | 5 с | в событие |
| Энерготрансфер <sub>`recipe_blueprint-flower-token-energy-leech`</sub> |  | Лепестковый кварц ×500 | 5 с | в событие |

## Ресурсы: где брать

Каждый ресурс, который идёт в рецепты: где его взять, в скольких рецептах он нужен и собирается ли он сам.
«Не подтверждено» — источник не найден в надёжных местах, указана лучшая зацепка.

| Ресурс | Где брать | Нужен в рецептах | Собирается |
|---|---|---|---|
| Aurus <sub>`resource_collectable_aurus`</sub> | Пираты Interceptor, Saboteur, Barracuda, Annihilator, Battleray (у нас на 5-1…5-3, Battleray ещё на 4-4, 4-5). <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 10 | — |
| Candy Corn <sub>`resource_collectable_candy-corn`</sub> | **Trial of Terrors (Хэллоуин).** Хэллоуин, Trial of Terrors: Curcubitor на x-2…x-8 (2–162 за убийство), Boss Curcubitor (62–4 482, ящик пятёрке лучших по урону). <sub>[источник](https://darkorbitwiki.com/events/trial-of-terrors/)</sub> | 28 | Кварцевый чертеж 250 кукурузных конфет |
| Агатовый осколок <sub>`resource_collectable_agate-splinter`</sub> | **Agatus Breach (повторяется).** Событие Agatus Breach: со Spinel на x-3…x-8, после Spinel приходит Agatus — его ящик тройке лучших по урону. Осколки остаются до следующего раза. <sub>[источник](https://darkorbitwiki.com/events/agatus-breach/)</sub> | 44 | — |
| Астралит <sub>`resource_collectable_astralite`</sub> | Только Врата Астрального вознесения (пространственные разломы); 500 — астральный ключ. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 2 | — |
| Байфенон <sub>`resource_collectable_bifenon`</sub> | Пираты Interceptor, Barracuda, Saboteur, Annihilator, Battleray, Кубикон, Uber Kristallin/Kristallon; календарь входа, сборщики, события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 5 | — |
| Блок данных <sub>`resource_collectable_spvoucher`</sub> | **Сезонный пропуск.** За уровни сезонного пропуска (в основном золотая ветка), не сгорает после сезона; на эксклюзивные рецепты пропуска. <sub>[источник](https://board-en.darkorbit.com/threads/faq-season-pass.130346/)</sub> | 19 | — |
| Высокочастотный кабель <sub>`resource_high-frequency-cable`</sub> | Зелёные и золотые трофейные ящики (Green/Gold Booty), магазин; собирается в Сборке. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 43 | Чертеж «Кварц H-HFC», Чертёж высокочастотного кабеля |
| Гибридный процессор <sub>`resource_hybrid-processor`</sub> | Зелёные и золотые трофейные ящики, магазин; собирается в Сборке. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 39 | Чертеж «Кварц H-HP», Чертёж гибридного процессора, Эмпирианский чертёж гибридного процессора |
| Гибридный сплав <sub>`resource_collectable_hybrid-alloy`</sub> | Blighted Gygerthrall (у нас видели на 1-7, 2-7, 3-7); на процессор «Иммунизатор» для Карантинной зоны. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | Кварцевый чертеж гибридного сплава (30) |
| Диаметрион <sub>`resource_collectable_diametrion`</sub> | Сборщики диспетчера, галактические врата, Vanguard Market (Vortex of Greed). <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 12 | Эмпирианский чертеж диаметриона |
| Дуотрин <sub>`resource_collectable_duothrin`</sub> | **Permafrost Fissure.** Собирается в Сборке на первой ступени рецептов Permafrost Fissure, идёт на вторую (по FAQ 2017). (не точно) <sub>[источник](https://board-en.darkorbit.com/threads/permafrost-fissure-faq.117725/)</sub> | 1 | BK-100, 20 шт. (чертёж) |
| Жетон чемпиона <sub>`resource_collectable_champion-token`</sub> | Ежемесячный рейтинг: 1-е место 100, 2–5 — 80, 6–10 — 60, 11–25 — 40, 26–50 — 20. На дизайны кораблей, питомцев, дронов. <sub>[источник](https://board-en.darkorbit.com/threads/monthly-ranking-faq.125867/)</sub> | 26 | — |
| Золотая кукурузная конфета <sub>`resource_collectable_golden-candy-corn`</sub> | **Trial of Terrors (Хэллоуин).** Хэллоуин, Trial of Terrors: Frightful Curcubitor (босс последней карты врат), задания события (по 25). <sub>[источник](https://darkorbitwiki.com/events/trial-of-terrors/)</sub> | 23 | — |
| Импульсное ядро <sub>`resource_collectable_pulse-core`</sub> | **Fiesta Gate (летний фестиваль).** За Fiesta Gate летнего события (вход с x-1 за Galaxy Coupon, 5 сложностей), за уровни активности события, в платных пакетах. <sub>[источник](https://board-en.darkorbit.com/threads/faq-fiesta-gate.130034/)</sub> | 6 | — |
| Индоктринационная нефть <sub>`resource_collectable_indoctrineoil`</sub> | Карантинная зона и Gygerim Overlord; календарь входа, события, красные и синие трофеи, сезонные квесты. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 11 | Кварцевый чертеж «Индоктринационная нефть» (15 ед.), Чертеж «Индоктринационная нефть», Чертеж «Индоктринационная нефть»  |
| Индоктринационный ускоритель <sub>`resource_collectable_indoctrine-accelerator`</sub> | Врата вечности Black Light (ЕБГ, по тексту игры), календарь входа, события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 10 | Кварцевый чертеж «ИУС» (50 шт.), Чертеж индоктринационного ускорителя |
| Кайхалон <sub>`resource_collectable_kyhalon`</sub> | Еженедельные миссии, календарь входа, галактические врата, сборщики, события, красные и синие трофеи. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 4 | — |
| Капсула Black Light <sub>`resource_collectable_blacklight-trace`</sub> | Attend IX (5–15 за убийство), Invoke XVI и Mindfire Behemoth на BL-картах; календарь входа. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 10 | Хромин-чертеж капсулы Black Light (500 шт.) |
| Квадротрин <sub>`resource_collectable_quadrothrin`</sub> | **Permafrost Fissure.** Собирается в Сборке на третьей ступени рецептов Permafrost Fissure (триттотрин + проксий + боеприпасы). <sub>[источник](https://darkorbit.fandom.com/wiki/Permafrost_Fissure)</sub> | 1 | Доп. энергия, 500 ед. (чертёж) |
| Ключ к астральному трофею <sub>`resource_booty-key-astral`</sub> | Собирается в Сборке из 500 астралита (Врата Астрального вознесения). Открывает астральные трофеи на x-2…x-8 и 4-x. <sub>[источник](https://darkorbitwiki.com/boxes/astral-box/)</sub> | 1 | Чертеж ключа к астральному трофею |
| Ключ к зелёному трофею <sub>`resource_booty-key`</sub> | Магазин (1 500 уридия), ежедневные миссии, награды врат (Hades 4, Epsilon/Kappa/Lambda 5, Kronos 10, Zeta 25). Открывает зелёные и золотые трофеи на x-2…x-8, 4-x, 5-x. <sub>[источник](https://darkorbitwiki.com/boxes/green-box/)</sub> | 5 | BK-100, 20 шт. (чертёж), Кварцевый чертеж «Ключ к трофею» (30 шт.), Чертеж ключа к зеленому трофею |
| Ледниковый фрагмент <sub>`resource_collectable_glacial-fragment`</sub> | **Permafrost Fissure.** Permafrost Fissure: собирается в Сборке (20 мьюкосума, 4 плазмида, 24 скрапиума, 1 ч) или покупается за 5 000 уридия, за миссию «Собрать 200 Proxium»; 4 фрагмента — подледниковый процессор для входа. <sub>[источник](https://board-en.darkorbit.com/threads/permafrost-fissure-faq.117725/)</sub> | 1 | Ледниковый фрагмент (чертёж) |
| Лепестковый кварц <sub>`resource_collectable_flower-token`</sub> | **Цветочные трофеи.** Цветочные трофеи (акция для клиента-приложения 2020–2021) и календарь входа; сейчас на картах нет, в 2025–2026 не подтверждено. (не точно) <sub>[источник](https://board-en.darkorbit.com/threads/exclusive-halloween-calendar-and-flower-boxes-for-beta-client-users.126567/)</sub> | 51 | — |
| Лог-диск <sub>`resource_logfile`</sub> | За прохождение врат (по журналу бота 10 за γ, около 100 за Zeta), сборщики, ежедневные задания, магазин, события. | 7 | Кварцевый чертеж «Лог-диск» (30 шт.) |
| Люминиум <sub>`resource_collectable_luminium`</sub> | **Empyrian March.** Событие Empyrian March: со всех NPC (больше с сильных), ящики люминиума во вратах α, β, γ, на x-2…x-8, 4-1…4-5 и BL-картах; сборщики не приносят. <sub>[источник](https://darkorbitwiki.com/events/empyrian-march/)</sub> | 51 | — |
| Микротранзисторы <sub>`resource_micro-transistors`</sub> | Зелёные и золотые трофейные ящики, магазин; собирается в Сборке. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 37 | Чертеж «Кварц H-MT», Чертёж микротранзисторов, Эмпирианский чертёж микротранзисторов |
| Монета Darksilver <sub>`resource_collectable_darksilver-coin`</sub> | Uber-пираты (Uber Interceptor, Saboteur, Barracuda, Annihilator; у нас на 5-2, 5-3) и «рыбы» Spookfish, Lanternfish, Barb, Pike, Stinger; календарь входа. 25 монет — серебряный ключ. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 2 | Чертеж Атласа «Монета Darksilver» |
| Мьюкосум <sub>`resource_collectable_mucosum`</sub> | Груз со всех Lordakia и Lordakium (обычные, Boss, Uber) и с Sibelonit; также Astral Box, ресурсные сборщики, события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 28 | Чертеж «Кварц-мьюкосум» (10 шт.) |
| Нанокейс <sub>`resource_nano-case`</sub> | Зелёные и золотые трофейные ящики, магазин; собирается в Сборке. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 43 | Чертеж «Кварц H-NCA», Чертёж нанокейса |
| Наноконденсатор <sub>`resource_nano-condenser`</sub> | Зелёные и золотые трофейные ящики, магазин; собирается в Сборке. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 58 | Чертеж «Кварц H-NCO», Чертёж наноконденсатора |
| Нейроплата Mindfire <sub>`resource_collectable_mindfire-cerebrum`</sub> | Mindfire Behemoth на BL-картах (восьмёрке лучших по урону), календарь входа; на шифр Black Light (Unstable Cache). <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 10 | Кварцевый чертеж «Нейроплата Mindfire» (100 шт.), Хромин-чертеж нейроплаты Mindfire (35 шт.) |
| Нейтронные осколки <sub>`resource_collectable_neutron-shards`</sub> | Связан с лазером LF-5 (по тексту игры); вероятно, Vortex of Greed, как другие детали LF-5 — не подтверждено. (не точно) | 1 | — |
| Опитит <sub>`resource_collectable_opitite`</sub> | **Эксперименты Plutus.** Из наградных ящиков NPC Plutus в событии «Эксперименты Plutus», вместе с тарномом; только на рецепты этого события. <sub>[источник](https://darkorbit.fandom.com/wiki/Tarnom_and_Opitite_Ore)</sub> | 7 | — |
| Осколок Black Light <sub>`resource_collectable_blacklight-shard`</sub> | Врата вечности Black Light (ЕБГ), календарь входа; на нестабильные модули. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 13 | Кварцевый чертеж «Осколок Black Light» (2000 шт.) |
| Осколок Charopos <sub>`resource_collectable_charopos-shard`</sub> | **Immortal Union (февраль).** Февральское событие Immortal Union: Lanatum на x-2…x-8, за их убийства — боссы Charopos и Styxus; осколки с Charopos (ящик пятёрке лучших), иногда с Lanatum. <sub>[источник](https://darkorbitwiki.com/events/immortal-union/)</sub> | 32 | — |
| Осколок Styxus <sub>`resource_collectable_styxus-shard`</sub> | **Immortal Union (февраль).** Февральское событие Immortal Union: Lanatum на картах, после числа убийств — боссы Styxus и Charopos; осколок со Styxus, иногда с Lanatum. <sub>[источник](https://darkorbitwiki.com/events/immortal-union/)</sub> | 32 | — |
| Осколок Атласа <sub>`resource_collectable_atlas-shard`</sub> | Награда во Вратах Астрального вознесения. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 14 | — |
| Осколок эфира <sub>`resource_collectable_ether-shard`</sub> | Награды сезонного пропуска, акции магазина. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 4 | — |
| Плазменная плита стабилизации <sub>`resource_collectable_plasmatic-equalization-plate`</sub> | **Vortex of Greed.** Магазин события Vortex of Greed за Stellar Coins (100 за штуку), награды сезонного пропуска; для крафта и улучшения LF-5. <sub>[источник](https://darkorbitwiki.com/galaxy-gates/vortex-of-greed/)</sub> | 3 | — |
| Плазменный стабилизатор <sub>`resource_collectable_plasmatic-stabilizer`</sub> | **Vortex of Greed.** Vanguard Market события Vortex of Greed за 5 500 Stellar Coins, не больше одного; только на элитные лазеры. <sub>[источник](https://darkorbitwiki.com/galaxy-gates/vortex-of-greed/)</sub> | 1 | — |
| Плазмид <sub>`resource_collectable_boltrum`</sub> | Плазмид: Devolarium и Sibelon (и их Boss/Uber). <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 33 | Чертеж «Кварц-плазмид» (10 шт.) |
| Полихромиум <sub>`resource_collectable_polychromium`</sub> | Сборщики диспетчера и награды событий; с каких NPC падает — не подтверждено. (не точно) <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | Хромин-чертеж полихромиума, Чертеж полихромиума (200 ед.), Чертеж полихромиума (300 ед.), Чертеж полихромиума (500 ед.), |
| Призма-слот <sub>`resource_prismatic-socket`</sub> | Зелёные и золотые трофейные ящики, магазин; собирается в Сборке. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 28 | Чертеж «Кварц H-PS», Чертёж призма-слота |
| Призматиум <sub>`resource_collectable_prismatium`</sub> | Груз со всех Kristallin и Kristallon (обычные, Boss, Uber); также события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 23 | Чертеж «Кварц-призматиум» (10 шт.), Эмпирианский чертеж призматиума, Эмпирианский чертеж призматиума - L, Эмпирианский ч |
| Проксий <sub>`resource_collectable_proxium`</sub> | **Permafrost Fissure.** Из особых ящиков UFO и Ufonit во вратах Permafrost Fissure; основа цепочки дуотрин → триттотрин → квадротрин. <sub>[источник](https://board-en.darkorbit.com/threads/permafrost-fissure-faq.117725/)</sub> | 4 | — |
| Регулятор аксионного луча <sub>`resource_collectable_axion-beam-regulator`</sub> | Карантинная зона, галактические врата, календарь входа, магазин Vortex of Greed и Vanguard Market, события, красные и синие трофеи. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 10 | Кварцевый чертеж «РАЛ» (150 шт.), Чертеж «РАЛ», Чертеж Атласа «РАЛ», Чертеж РАЛ (агатовые осколки) |
| Реликтовый байт <sub>`resource_collectable_byte-remnant`</sub> | **Mimesis Mutiny.** Мимесисы на x-3, x-4, 4-1…4-4 (Hexor 5, Raging 10, Cloning 25, Terror 35, Reflector 50), их ящики, награды эскорта. Удаляются через сутки после конца события. | 16 | — |
| Ринуск <sub>`resource_collectable_rinusk`</sub> | Пришельцы Black Light на BL-картах: Impulse II 15–25, Attend IX 80–90, также Invoke и Mindfire Behemoth; бывает в календаре входа. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 15 | Хромин-чертеж ринуска (800 ед.) |
| Сепром <sub>`resource_ore_seprom`</sub> | Скайлэб (промериум + ксено-модуль); рецепт «Сепром (BL)» в Сборке — 5 000 за 10 000 уридия, 1 250 ринуска и 250 капсул Black Light; падает с некоторых NPC во вратах, в наградах. | 4 | Сепром (чертёж), Хромин-чертеж сепрома (25 000 ед.), Чертеж «Сепром» (10000 ед.), Чертёж «Сепром» (5 000 ед.), Эмпирианс |
| Скрапиум <sub>`resource_collectable_scrapium`</sub> | Груз со всех Saimon и Mordon (обычные, Boss, Uber); также Astral Box, сборщики, ящики, события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 52 | Чертеж «Кварц-скрапиум» (10 шт.), Чертеж Атласа «Скрапиум», Эмпирианский чертеж скрапиума |
| Солидус <sub>`resource_collectable_solidus`</sub> | Только сборщики диспетчера. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | — |
| Сплав «Гидра» <sub>`resource_collectable_hydra-alloy`</sub> | Сборщики диспетчера, серебряные трофеи на 5-4 (2–15), календарь входа. <sub>[источник](https://darkorbitwiki.com/boxes/silver-box/)</sub> | 2 | — |
| Сплав «Нептун» <sub>`resource_collectable_neptune-alloy`</sub> | Сборщики диспетчера, серебряные трофейные ящики (Silver Box), календарь входа. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 2 | — |
| Сплав «Трискелион» <sub>`resource_collectable_triskelion-alloy`</sub> | Сборщики диспетчера, серебряные трофейные ящики, календарь входа. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 2 | — |
| Сплав «Уроборос» <sub>`resource_collectable_ouroboros-alloy`</sub> | Сборщики диспетчера и награды событий. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 2 | — |
| Схизмокристалл <sub>`resource_collectable_schism`</sub> | За полное прохождение врат: по тексту игры Delta, Kuiper и Kronos, по журналу бота 12 за γ; также календарь входа и события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 13 | Эмпирианский чертеж схизмокристаллов |
| Тарном <sub>`resource_collectable_tarnom`</sub> | **Эксперименты Plutus.** Из наградных ящиков NPC Plutus в событии «Эксперименты Plutus» (в 2023 и за бонусные волны); на рецепты события. <sub>[источник](https://board-en.darkorbit.com/threads/plutus-experiments.123675/)</sub> | 6 | — |
| Тетратрин <sub>`resource_collectable_tetrathrin`</sub> | С Uber Interceptor, Uber Barracuda, Uber Saboteur, Uber Annihilator; также ежедневные задания, календарь, трофейные ящики, сборщики, события. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 4 | — |
| Триттотрин <sub>`resource_collectable_trittothrin`</sub> | **Permafrost Fissure.** Собирается в Сборке на второй ступени рецептов Permafrost Fissure (дуотрин + проксий + Extra Energy). <sub>[источник](https://darkorbit.fandom.com/wiki/Permafrost_Fissure)</sub> | 1 | Нестабильный лазер LF-4 (чертёж) |
| Фрагмент Infiltrator <sub>`resource_collectable_infiltrator-fragment`</sub> | **Treacherous Domain.** Событие Treacherous Domain: бои с боссом на карте события (вход с x-1 и x-8) — ежедневные награды, ступени урона, рейтинг топ-100 (100–500). <sub>[источник](https://darkorbitwiki.com/events/treacherous-domain/)</sub> | 26 | — |
| Фрагмент вечности <sub>`resource_collectable_eternal-fragment`</sub> | Invoke XVI и Mindfire Behemoth на BL-картах, календарь входа; процессор ЕБГ — 500 фрагментов, 500 ринуска, 150 000 кредитов. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | Кварцевый чертеж «Фрагмент вечности» (250 шт.) |
| Фрагмент гравитронной хронолинзы <sub>`resource_beacon-crystal`</sub> | **Voyagers Ascent.** Фрагмент гравитронной хронолинзы: врата события Voyagers Ascent и его VIP-пропуск, акции магазина. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | — |
| Фрагмент сингулярного двигателя <sub>`resource_ignishard-l01`</sub> | **Voyagers Ascent.** По вики (Singularity Drive Fragment) — врата Voyagers Ascent, их VIP-пропуск, платные пакеты; сами врата не подтверждены. (не точно) <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | — |
| Фрагмент хаоса <sub>`resource_collectable_chaos-fragment`</sub> | **Protegit Pandemonium.** Событие Protegit Pandemonium: Chaos Protegit на x-2…x-8 (1–30 за убийство); в прологе Chaos Omen — сборщики. Удаляются после события. <sub>[источник](https://darkorbitwiki.com/events/protegit-pandemonium/)</sub> | 4 | — |
| Фрагмент чертежа («Apis») <sub>`resource_blueprint_apis-part`</sub> | Зелёные и золотые пиратские трофеи на x-2…x-8, 4-x, 5-x (зелёный ключ); часть можно купить в Сборке за 24 000 уридия; на дроида 45 частей. <sub>[источник](https://darkorbitwiki.com/drones/)</sub> | 1 | Чертеж дроида «Apis» |
| Фрагмент чертежа («Zeus») <sub>`resource_blueprint_zeus-part`</sub> | Золотые пиратские трофеи на x-2…x-8, 4-x, 5-x (зелёный ключ); часть можно купить в Сборке за 33 000 уридия; на дроида 45 частей. <sub>[источник](https://darkorbitwiki.com/drones/)</sub> | 1 | Чертеж дроида «Zeus» |
| Хладагент «Крио-нано» <sub>`resource_collectable_cryo-nano-coolant`</sub> | Магазин врат Vortex of Greed. <sub>[источник](https://darkorbitwiki.com/resources/)</sub> | 1 | — |
| Хромин <sub>`resource_collectable_chromin`</sub> | **Chromin Rush (периодически).** Хроминовая лихорадка: NPC во вратах Delta, Epsilon, Kappa, Zeta, Kuiper, Kronos роняют ящики с хромином; лимит 160 000, остаток после события — в честь. Бот фармит в ζ (ZetaChromin). <sub>[источник](https://darkorbitwiki.com/events/chromin-rush/)</sub> | 49 | — |
| Чип N.E.U. <sub>`resource_collectable_neu`</sub> | **Выходные N.O.V.A..** Падает во вратах Delta, Epsilon, Lambda и Kuiper только в выходные N.O.V.A. (сборщик на врата их не приносит); 500 чипов — случайный N.O.V.A. «Нео» в Сборке. <sub>[источник](https://darkorbitwiki.com/guides/n-o-v-a/)</sub> | 2 | — |
| Кредиты <sub>`currency_credits`</sub> | Награды за NPC, продажа руды, задания. | 24 | — |
| Уридиум <sub>`currency_uridium`</sub> | Награды за NPC (пришельцы, пираты, BL), врата, события, покупка. | 70 | — |

### Предметы в цене, которые сами покупаются или собираются

Корабли, оборудование и боеприпасы, которые тратятся в рецептах (улучшения, сборка более сильного).

| Предмет | Собирается рецептом | Нужен в рецептах |
|---|---|---|
| AA-1 <sub>`equipment_weapon_laser_aa-1`</sub> | Чертеж AA-1 | 3 |
| AAP-1 <sub>`equipment_weapon_laser_aap-1`</sub> | Чертеж AAP-1 | 3 |
| Citadel <sub>`ship_citadel`</sub> | магазин, награды | 1 |
| Goliath <sub>`ship_goliath`</sub> | магазин, награды | 1 |
| Hammerclaw <sub>`ship_hammerclaw`</sub> | Хромин-чертеж «Hammerclaw», Чертёж «Hammerclaw» | 1 |
| Hecate <sub>`ship_hecate`</sub> | Кварцевый чертеж Hecate, Хромин-чертеж «Hecate» | 1 |
| LF-4 <sub>`equipment_weapon_laser_lf-4`</sub> | Чертеж LF-4, Чертеж LF-4 (агатовые осколки) | 6 |
| LF-5 <sub>`equipment_weapon_laser_lf-5`</sub> | Чертеж LF-5 | 2 |
| Liberator <sub>`ship_liberator`</sub> | магазин, награды | 1 |
| Pusat <sub>`ship_pusat`</sub> | Чертёж «Pusat» для «Vengeance» | 1 |
| Retiarus <sub>`ship_retiarus`</sub> | магазин, награды | 1 |
| RSB-75 <sub>`ammunition_laser_rsb-75`</sub> | RSB-75 x5000 Blueprint, RSB-75, 40 000 шт. (чертёж), Чертеж RSB-75 (1 шт.), Чертёж RSB-75 (15 000 шт.) | 7 |
| Solace <sub>`ship_solace`</sub> | Чертёж «Solace» | 1 |
| Solaris <sub>`ship_solaris`</sub> | магазин, награды | 1 |
| Spearhead <sub>`ship_spearhead`</sub> | магазин, награды | 1 |
| Spectrum <sub>`ship_spectrum`</sub> | Чертёж «Spectrum» | 1 |
| Tartarus <sub>`ship_tartarus`</sub> | магазин, награды | 1 |
| UCB-100 <sub>`ammunition_laser_ucb-100`</sub> | Чертеж UCB-100 (5000 ед.), Чертёж для 200 UCB-100 | 11 |
| Дизайн дроида Havoc <sub>`drone_designs_havoc`</sub> | магазин, награды | 1 |
| Дизайн дроида Hercules <sub>`drone_designs_hercules`</sub> | магазин, награды | 1 |
| Ракетомёт «Hellstorm-2» <sub>`equipment_weapon_rocketlauncher_hst-2`</sub> | Чертёж HST-2 | 4 |
| Щит SG3N-B02 <sub>`equipment_generator_shield_sg3n-b02`</sub> | Чертеж щита SG3N-B02 | 1 |
