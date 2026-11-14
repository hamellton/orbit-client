package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;

/**
 * «Бегемот» (BEHEMOTH): Mindfire Behemoth solo on the BL maps, the settings of AstraBot's «Mindfire Behemoth» module
 * (its BEHEMOTH section: firstMap…thirdMap, changeMapSeconds, killOthers, defenceMode, collectBox, collectBoxConfig,
 * collectBoxFormation, useEmp/empPercent, useIsh/ishPercent). How the behemoth itself is fought — its row in the NPC
 * list («Убивать»): radius, A-BL, Drill, HP Link, RSB.
 */
final class BehemothSection implements Section {

    private static final String KEY = BotConfig.Module.BEHEMOTH.key();
    private static final int[] MAPS = {306, 307, 308};

    private final JCheckBox[] maps = {new JCheckBox("1-BL"), new JCheckBox("2-BL"), new JCheckBox("3-BL")};
    private final JSpinner changeMap = Form.number(5, 900, 5);
    private final JSpinner respawn = Form.number(0, 120, 1);
    private final JCheckBox defence = new JCheckBox("Отвечать NPC, которые стреляют по нам (пока бегемота нет)");
    private final JCheckBox killOthers = new JCheckBox("Бить и других NPC у точки ожидания");
    private final JCheckBox collect = new JCheckBox("Подбирать ящик бегемота");
    private final JCheckBox spawnBoxes = new JCheckBox("Пока ждём — подбирать ящики у места появления (любые, бегемот мёртв, до него больше минуты)");
    private final JComboBox<String> collectConfig = new JComboBox<>(new String[] {"1", "2"});
    private final ItemBox collectFormation = new ItemBox(ItemCatalog.FORMATIONS, true);
    private final JCheckBox emp = new JCheckBox("EMP-01 при HP корабля ниже");
    private final JSpinner empBelow = Form.percent();
    private final JCheckBox empOnlyPlayers = new JCheckBox("ЭМИ — только когда по нам стреляет игрок");
    private final JCheckBox empThenIsh = new JCheckBox("Стреляет игрок (не NPC) — ЭМИ, сразу за ним стенка ISH");
    private final JCheckBox abilityAtStart = new JCheckBox("Призматический щит (Citadel+, Spectrum) — сразу, как подлетел к бегемоту");
    private final JCheckBox pushThrough = new JCheckBox("Поезд стреляет в пути — ЭМИ и всё равно лететь к месту появления");
    private final JCheckBox ish = new JCheckBox("ISH-01 при HP корабля ниже");
    private final JSpinner ishBelow = Form.percent();
    private final JCheckBox prismatic = new JCheckBox("Призматический щит (Spectrum, Citadel+) при HP ниже");
    private final JSpinner prismaticBelow = Form.percent();
    private final JCheckBox jamx = new JCheckBox("JAM-X (Spearhead) по бегемоту ближе, чем");
    private final JSpinner jamxDistance = Form.number(100, 2_000, 50);
    private final JCheckBox petOnly = new JCheckBox("Питомец только у бегемота (в остальное время убран)");
    private final JCheckBox beacon = new JCheckBox("Боевой маяк питомца в бою, когда HP Link не нужен");
    private final JSpinner linkBelow = Form.percent();
    private final JCheckBox onTime = new JCheckBox("Прилетать ровно к появлению (пока рано — ждать на своей карте)");
    private final JSpinner reward = Form.number(0, 100_000, 500);
    private final JCheckBox useLocator = new JCheckBox("Искать бегемота пеленгатором питомца");
    private final JCheckBox followTrain = new JCheckBox("Поезд ушёл через портал — лететь за ним на ту карту (через 20 с)");
    private final JCheckBox powers = new JCheckBox("Навык, ISH, EMP — только у бегемота: навык, когда стреляют игроков не меньше");
    private final JSpinner threatPlayers = Form.number(1, 20, 1);
    private final JCheckBox prismaticAtStart = new JCheckBox("Призма — сразу, как начал бить бегемота");
    private final JSpinner empAfterIsh = Form.number(0, 10, 1);
    private final JSpinner closeMin = Form.number(300, 3_000, 100);
    private final JSpinner closeKeep = Form.number(300, 4_000, 100);
    private final JSpinner respawnSec = Form.number(0, 3_600, 5);
    private final JCheckBox waitFirst = new JCheckBox("Пока таймеров нет — ждать на первой карте, пока не придёт бегемот (до 17 мин)");
    private final JCheckBox locatorList = new JCheckBox("Нет бегемота в списке целей пеленгатора — сразу на другую карту");
    private final JCheckBox useCloak = new JCheckBox("Маскировка, пока ждём бегемота (CL04K)");
    private final ItemBox cloakFormation = new ItemBox(ItemCatalog.FORMATIONS, true);
    private final JSpinner beaconSeconds = Form.number(0, 120, 1);
    private final JSpinner closeSeconds = Form.number(0, 300, 5);
    // where to wait (the user, 2026-11-13: trains camp the spawn and BeheFarm's wait points)
    private final JCheckBox trainTimer = new JCheckBox("Поезд стоит у места появления, бегемота не видно — считать его убитым (таймер)");
    private final JCheckBox waitForTrain = new JCheckBox("Не улетать, пока поезд идёт к месту появления (до 3 мин)");
    private final JCheckBox avoidEnemies = new JCheckBox("Облетать видимых врагов заранее (2000), рядом враги — не вылетать с точки (до 3 мин)");
    private final JCheckBox waitInZone = new JCheckBox("Ждать в предпочитаемой зоне конфига, дальше всего от поездов (как AstraBot)");
    private final JCheckBox avoidTrainPaths = new JCheckBox("Летать в обход путей поездов «портал → бегемот»");
    private final JCheckBox dodgeEnemies = new JCheckBox("Пока ждём — отходить от врагов (не дальше 1200 от точки), ушли — назад");
    private final JCheckBox learnSafeSpot = new JCheckBox("Учиться, где ходят враги, и ждать там, где их меньше всего");
    private final JCheckBox awayFromPortals = new JCheckBox("Ждать в стороне от путей «портал → бегемот», где ходят поезда");
    private final JCheckBox freeSide = new JCheckBox("Ждать со свободной стороны: чужие у точки — встать на круге от них");
    private final JSpinner freeSideRadius = Form.number(800, 5_000, 100);
    private final JCheckBox avoidSpawn = new JCheckBox("Пока ждём — облетать место появления, радиус");
    private final JSpinner avoidSpawnRadius = Form.number(300, 4_000, 100);
    // a train of others by the behemoth (CARNAGE's BLCycle and behemoth modules)
    private final JCheckBox trainMode = new JCheckBox("Бить бегемота, даже когда рядом чужой поезд и бьёт нас");
    private final JSpinner trainRadius = Form.number(300, 5_000, 100);
    private final JSpinner trainLimit = Form.number(0, 50, 1);
    private final JCheckBox trainAtLocator = new JCheckBox("Считать врагов и у точки пеленгатора, ещё до того, как бегемот виден");
    private final JCheckBox leaveSkipped = new JCheckBox("Пропущенную карту покидать сразу и не возвращаться до следующего бегемота");
    private final javax.swing.JButton trainProven = new javax.swing.JButton("Поставить как в CARNAGE");
    private final JCheckBox avoidOrbit = new JCheckBox("Стоять с другой стороны бегемота от чужих (не лезть в их кольцо)");
    private final JSpinner deathsPerSpawn = Form.number(0, 20, 1);
    // players (CARNAGE's PvpHelper / DefenseMode, AstraBot's defenceMode, chaseEnemy, BLACKLIST)
    private final JCheckBox respond = new JCheckBox("Отвечать игрокам, которые стреляют по нам, ближе чем");
    private final JSpinner respondRadius = Form.number(200, 3_000, 50);
    private final JCheckBox defendGroup = new JCheckBox("Защищать свою группу: бить тех, кто бьёт её");
    private final JCheckBox hunt = new JCheckBox("Атаковать всех врагов ближе, чем");
    private final JSpinner huntRadius = Form.number(300, 5_000, 100);
    private final javax.swing.JTextField blacklist = new javax.swing.JTextField(24);
    private final JComboBox<String> focus = new JComboBox<>(new String[] {"Сначала бегемот", "Сначала враги"});
    private final ItemBox pvpAmmo = new ItemBox(ItemCatalog.LASERS, true);
    // the group (AstraBot's followLeader / leaderId)
    private final JCheckBox follow = new JCheckBox("Идти за лидером группы");
    private final javax.swing.JTextField leader = new javax.swing.JTextField(18);
    private final JSpinner followDistance = Form.number(100, 2_000, 50);
    private final JComponent view;

    BehemothSection() {
        collect.addActionListener(e -> sync());
        emp.addActionListener(e -> sync());
        ish.addActionListener(e -> sync());
        prismatic.addActionListener(e -> sync());
        jamx.addActionListener(e -> sync());
        trainMode.addActionListener(e -> sync());
        trainProven.addActionListener(e -> proven());
        trainProven.setToolTipText("Сценарий CARNAGE (BeheSolo): ждать в предпочитаемой зоне вкладки «Зоны», к бегемоту —"
                + " по пеленгатору; у бегемота сразу навык; стреляет игрок — ЭМИ и сразу стенка; в пути от поезда не уходить;"
                + " погиб у бегемота — возрождение в порту и следующая карта. Плюс проверенное на VPS: больше 12 врагов —"
                + " другая карта. Зоны ставятся во вкладке «Зоны»");
        trainAtLocator.setToolTipText("Пеленгатор показал бегемота, а у его точки уже больше врагов, чем в пороге выше,"
                + " — пропустить карту, не подлетая (поезд там убьёт его за секунды)");
        leaveSkipped.setToolTipText("Карта пропущена из-за толпы или смертей — бот сразу улетает и не тянется к точке"
                + " пеленгатора на ней; снова в выборе за 3 мин до следующего бегемота");
        empThenIsh.setToolTipText("Как CARNAGE: по нам начал стрелять игрок — сразу ЭМИ (сбивает их захват), в ту же"
                + " секунду стенка ISH-01; без порогов HP. Нужна галочка «Навык, ISH, EMP — только у бегемота»");
        abilityAtStart.setToolTipText("Как CARNAGE: призматический щит включается, как только бот подлетел к бегемоту,"
                + " и снова, как откатится. Fortify не включается — он делает корабль неподвижным");
        pushThrough.setToolTipText("На картах бегемота бот не уходит от поезда, а ЭМИ сбивает их захват; дальше — к месту"
                + " появления, как задумано (CARNAGE не убегает никогда)");
        empOnlyPlayers.setToolTipText("ЭМИ снимает только захват игроков: если стреляют одни NPC, он пропадёт зря");
        useCloak.addActionListener(e -> sync());
        freeSide.addActionListener(e -> sync());
        closeSeconds.setToolTipText("Столько секунд до появления (по таймеру) — подлететь как можно ближе к месту появления,"
                + " насколько позволяют враги там (не ближе 1100 к ним), и ждать там, не уходя от поезда");
        powers.addActionListener(e -> sync());
        dodgeEnemies.setToolTipText("Враги ближе к точке ожидания, чем радиус «Обходить» (вкладка «Бегство»), — бот встаёт рядом,"
                + " вне их радиуса, не ближе к ним; ушли — возвращается. Бегемот появился — летит к нему, как раньше");
        learnSafeSpot.setToolTipText("Раз в 2 с бот отмечает, где на BL-карте видны враги, место своей гибели — с большим весом"
                + " (файл local/behemoth-heat.json, копится между запусками). Ждёт в 1500–3000 от места появления, с прямой"
                + " видимостью его, там, где врагов видели меньше всего. Пока отметок мало — как без этой галочки");
        prismaticAtStart.setToolTipText("Призматический щит Citadel+/Spectrum — в первую же секунду боя с бегемотом,"
                + " не дожидаясь, пока начнут стрелять игроки");
        empAfterIsh.setToolTipText("Стенка ISH держит ~3 с: ЭМИ не раньше стольких секунд после неё, чтобы не тратить"
                + " оба разом");
        closeMin.setToolTipText("Перед появлением не подлетать к месту появления ближе этого — там стоит поезд");
        closeKeep.setToolTipText("Перед появлением держаться от видимых врагов не ближе этого");
        respawnSec.setToolTipText("Бегемот появляется снова через столько секунд после прошлого появления (~915);"
                + " 0 — по минутам выше или как бот узнал сам");
        powers.setToolTipText("Не тратить навык, ISH-01 и EMP-01 ни на кого, кроме бегемота. У него — по очереди: навык"
                + " (призма первой), когда по нам стреляет столько игроков (или HP ниже порога призмы); потом ISH ниже своего"
                + " порога; потом EMP ниже своего, не пока держится стенка");
        followTrain.setToolTipText("Поезда знают таймеры: 3 и больше врагов пропали у одного портала за 30 с — поезд ушёл"
                + " туда, бегемот, похоже, там; бот летит следом, выждав 20 с, чтобы не прилететь им на хвост");
        waitFirst.setToolTipText("Как scoutMinutes у BLCycle (CARNAGE): пока бот не видел ни одного бегемота, он не облетает"
                + " карты, а ждёт на первой — до времени появления и 2 минут запаса; дальше — по кругу, по таймерам");
        locatorList.setToolTipText("В пеленгаторе можно выбрать только тех NPC, что есть на карте: бегемота в списке нет —"
                + " его нет на карте, лететь дальше; есть — ждать его, даже дольше времени ожидания (до 3 мин)");
        waitInZone.setToolTipText("Как AstraBot («Moving to behe zone»): ждать в зелёной зоне вкладки «Зоны» — у края карты,"
                + " далеко от места появления; к бегемоту — по пеленгатору или по времени. Зоны нет — точка ниже");
        avoidTrainPaths.setToolTipText("Поезда летят кратчайшим путём от портала к месту появления; бот облетает эти полосы"
                + " (ширина 2000), сами порталы и место появления остаются открыты");
        trainTimer.setToolTipText("3 и больше врагов 20 с в 1500 от места появления, бегемота не видно — значит, они его"
                + " убили: бот запоминает смерть, следующий — через время появления; так работает «прилетать ровно к появлению»");
        waitForTrain.setToolTipText("Ожидание вышло, а 3 и больше врагов в 4000 от места появления — бегемот, похоже, скоро"
                + " будет здесь: ждать дальше, но не больше 3 минут");
        awayFromPortals.setToolTipText("Точка на круге вокруг места появления, дальше всего от линий от каждого портала"
                + " карты к месту появления: поезда идут из портала к бегемоту и дальше в следующий портал");
        avoidSpawn.addActionListener(e -> sync());
        freeSide.setToolTipText("Видны враги у точки ожидания — бот встаёт на круге вокруг места появления там, где до"
                + " ближайшего врага дальше всего; врагов нет — обычная точка");
        freeSideRadius.setToolTipText("Радиус круга вокруг места появления, на котором выбирается свободная точка");
        avoidSpawnRadius.setToolTipText("По пути к точке ожидания не пролетать ближе этого к месту появления, где стоят"
                + " поезда; не больше, чем от точки ожидания до места появления");
        onTime.setToolTipText("Как BLCycle в CARNAGE: вылет так, чтобы прилететь к времени появления; время перелёта бот"
                + " узнаёт сам; карту, где появление не видели, считает на круг вперёд");
        reward.setToolTipText("Пришло столько уридия рядом с бегемотом — убийство засчитано (как BLCycle: 5 000); 0 — нет");
        cloakFormation.setToolTipText("Формация на время маскировки (BLCycle: Heart); не выбрана — как в «Общем»");
        beaconSeconds.setToolTipText("За столько секунд до появления — к месту появления, питомец ставит боевой маяк; 0 — нет");
        respond.addActionListener(e -> sync());
        hunt.addActionListener(e -> sync());
        follow.addActionListener(e -> sync());
        trainRadius.setToolTipText("Чужие игроки ближе этого к бегемоту считаются поездом");
        trainLimit.setToolTipText("Смотрит первые 1,5 с после того, как увидел бегемота: врагов больше — сразу другая"
                + " карта. Начал бой — уже не уходит, даже если поезд прилетел. 0 — не уходить (как BLCycle). Проверено:"
                + " до 10 врагов награда приходила, при 13–15 — смерть без награды");
        deathsPerSpawn.setToolTipText("Погиб у бегемота столько раз — эту карту пропустить до следующего бегемота; 0 — нет");
        blacklist.setToolTipText("Имена игроков через запятую — их бить всегда, когда видны (как BLACKLIST в AstraBot)");
        focus.setToolTipText("Сначала бегемот — игроков только пока его нет; сначала враги — даже когда бегемот рядом");
        pvpAmmo.setToolTipText("Патроны по игрокам; не выбраны — те, что стоят в игре");
        leader.setToolTipText("Имя или номер лидера в группе; пусто — лидер группы");
        followDistance.setToolTipText("Держаться не дальше этого от лидера");
        linkBelow.setToolTipText("HP Link (флаг в строке бегемота) — пока HP корабля ниже этого; 0 % — всегда");
        changeMap.setToolTipText("Сколько ждать на карте после прилёта (или после времени появления), потом — другая карта");
        respawn.setToolTipText("Через сколько минут бегемот появляется снова; 0 — бот узнаёт сам (до этого 15 мин)");
        view = new Form()
                .section("Карты")
                .row("Обходить", Form.inline(maps[0], maps[1], maps[2]))
                .row("Нет бегемота — другая карта через, с", changeMap)
                .row("Появляется снова через, мин", respawn)
                .row("…или точно, с (0 — по минутам)", respawnSec)
                .row("", onTime)
                .row("", waitFirst)
                .row("", followTrain)
                .row("Засчитывать убийство от, уридия", reward)
                .row("", useLocator)
                .row("", locatorList)
                .note("Пеленгатор (нужен модуль у питомца): есть бегемот на карте — летит к нему; «нет» — карта"
                        + " пустая, но ждёт своё время (0 — не ждёт). Питомец тогда выпущен всё время.")
                .note("Бот запоминает, когда бегемот появился и погиб на каждой карте, и летит туда, где он будет раньше;"
                        + " ждёт у места появления (точки — из BeheFarm, уточняются по увиденному).")
                .section("Где ждать")
                .row("", waitInZone)
                .row("", dodgeEnemies)
                .row("", learnSafeSpot)
                .row("", awayFromPortals)
                .row("", avoidTrainPaths)
                .row("", trainTimer)
                .row("", waitForTrain)
                .row("", avoidEnemies)
                .row("", freeSide)
                .row("Круг вокруг места появления", freeSideRadius)
                .row("", Form.inline(avoidSpawn, avoidSpawnRadius))
                .note("Точки ожидания по картам — в файле конфига (waitPoints); в «behe train» — точки BeheSolo из"
                        + " CARNAGE: в стороне от места появления, где стоят поезда.")
                .section("Бой")
                .note("Как бить самого бегемота (радиус, A-BL, Drill, HP Link, RSB) — строка «Mindfire Behemoth» во"
                        + " вкладке «Убивать». Чужих игроков у бегемота не боимся: урон засчитывается всем.")
                .row("", Form.inline(emp, empBelow, new javax.swing.JLabel("%")))
                .row("", empOnlyPlayers)
                .row("", empThenIsh)
                .row("", abilityAtStart)
                .row("", Form.inline(ish, ishBelow, new javax.swing.JLabel("%")))
                .row("", Form.inline(prismatic, prismaticBelow, new javax.swing.JLabel("%")))
                .row("", Form.inline(jamx, jamxDistance))
                .row("", Form.inline(powers, threatPlayers))
                .row("", prismaticAtStart)
                .row("ЭМИ не раньше, с после стенки", empAfterIsh)
                .row("", defence)
                .row("", killOthers)
                .section("Питомец")
                .row("", petOnly)
                .row("HP Link, пока HP корабля ниже, %", linkBelow)
                .row("", beacon)
                .row("Маяк урона за, с до появления (0 — нет)", beaconSeconds)
                .row("Встать как можно ближе за, с до появления (0 — нет)", closeSeconds)
                .row("Ближняя точка: не ближе к месту появления", closeMin)
                .row("Ближняя точка: до врагов не меньше", closeKeep)
                .note("Энергетическая пиявка и способность корабля — галочки USE_LEECH и USE_ABILITY в строке бегемота.")
                .section("Маскировка")
                .row("", useCloak)
                .row("Формация в маскировке", cloakFormation)
                .note("Покупка маскировки — вкладка «Покупки», предмет CL04K-XL (если купленная не появилась в игре,"
                        + " бот больше не покупает до перезапуска). Крафт пока не сделан.")
                .section("Чужой поезд у бегемота")
                .row("", trainMode)
                .row("", trainProven)
                .row("Поезд — чужие ближе к бегемоту, чем", trainRadius)
                .row("Врагов больше — другая карта (0 — нет)", trainLimit)
                .row("", trainAtLocator)
                .row("", leaveSkipped)
                .row("", pushThrough)
                .row("", avoidOrbit)
                .row("Погиб столько раз — другая карта (0 — нет)", deathsPerSpawn)
                .note("Не знаете, что ставить, — нажмите «Поставить как в CARNAGE», а точки ожидания нарисуйте во"
                        + " вкладке «Зоны» (предпочитаемая зона на каждой BL-карте). Отличия видно в проверке конфига"
                        + " перед запуском.")
                .note("Как в CARNAGE: от их атак не убегаем, бьём бегемота дальше; урон засчитывается всем, кто бил."
                        + " Против частых смертей — «Стоп после смертей от одного игрока» во вкладке «Безопасность».")
                .section("Игроки")
                .row("", Form.inline(respond, respondRadius))
                .row("", defendGroup)
                .row("", Form.inline(hunt, huntRadius))
                .row("Чёрный список", blacklist)
                .row("Кого бить первым", focus)
                .row("Патроны по игрокам", pvpAmmo)
                .note("Всё выключено — игроков бот не трогает. Свои по клану, союзники и группа не враги.")
                .section("Группа")
                .row("", follow)
                .row("Лидер", leader)
                .row("Держаться от лидера, не дальше", followDistance)
                .note("За лидером: на его карту, рядом с ним, бьёт того игрока, которого бьёт он. Бегемота бьёт сам."
                        + " Приглашения в группу — вкладка «Группа».")
                .section("Ящик")
                .row("", collect)
                .row("", spawnBoxes)
                .row("Конфигурация", collectConfig)
                .row("Формация", collectFormation)
                .build(true);
    }

    private void sync() {
        collectConfig.setEnabled(collect.isSelected());
        collectFormation.setEnabled(collect.isSelected());
        empBelow.setEnabled(emp.isSelected());
        empOnlyPlayers.setEnabled(emp.isSelected());
        ishBelow.setEnabled(ish.isSelected());
        prismaticBelow.setEnabled(prismatic.isSelected());
        jamxDistance.setEnabled(jamx.isSelected());
        boolean t = trainMode.isSelected();
        trainRadius.setEnabled(t);
        trainLimit.setEnabled(t);
        trainAtLocator.setEnabled(t);
        pushThrough.setEnabled(t);
        leaveSkipped.setEnabled(t);
        avoidOrbit.setEnabled(t);
        deathsPerSpawn.setEnabled(t);
        respondRadius.setEnabled(respond.isSelected());
        huntRadius.setEnabled(hunt.isSelected());
        leader.setEnabled(follow.isSelected());
        cloakFormation.setEnabled(useCloak.isSelected());
        threatPlayers.setEnabled(powers.isSelected());
        empAfterIsh.setEnabled(powers.isSelected());
        freeSideRadius.setEnabled(freeSide.isSelected() || awayFromPortals.isSelected());
        avoidSpawnRadius.setEnabled(avoidSpawn.isSelected());
        followDistance.setEnabled(follow.isSelected());
    }

    /** The train settings tested live (docs/BLACKLIGHT.md): one button, nothing to remember. */
    private void proven() {
        trainMode.setSelected(true);
        Form.set(trainRadius, orbit.config.BehemothProven.TRAIN_RADIUS);
        Form.set(trainLimit, orbit.config.BehemothProven.TRAIN_LIMIT);
        trainAtLocator.setSelected(true);
        leaveSkipped.setSelected(true);
        avoidOrbit.setSelected(true);
        Form.set(deathsPerSpawn, orbit.config.BehemothProven.DEATHS_PER_SPAWN);
        empOnlyPlayers.setSelected(true);
        // CARNAGE: the wait is the zone, nothing clever round it
        waitInZone.setSelected(true);
        learnSafeSpot.setSelected(false);
        awayFromPortals.setSelected(false);
        avoidTrainPaths.setSelected(false);
        freeSide.setSelected(false);
        avoidSpawn.setSelected(false);
        Form.set(closeSeconds, 0);
        Form.set(beaconSeconds, 0);
        useLocator.setSelected(true);
        powers.setSelected(true);
        emp.setSelected(true);
        ish.setSelected(true);
        empThenIsh.setSelected(true);
        abilityAtStart.setSelected(true);
        pushThrough.setSelected(true);
        sync();
    }

    @Override
    public String title() {
        return "Бегемот";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        java.util.Set<Integer> chosen = new java.util.HashSet<>();
        m.path("maps").forEach(n -> chosen.add(n.asInt()));
        for (int i = 0; i < MAPS.length; i++) {
            maps[i].setSelected(chosen.isEmpty() || chosen.contains(MAPS[i]));
        }
        Form.set(changeMap, m.path("changeMapSeconds").asInt(30));
        Form.set(respawn, m.path("respawnMinutes").asInt(0));
        defence.setSelected(m.path("defenceMode").asBoolean(true));
        killOthers.setSelected(m.path("killOthers").asBoolean(false));
        collect.setSelected(m.path("collectBox").asBoolean(true));
        spawnBoxes.setSelected(m.path("collectSpawnBoxes").asBoolean(true));
        collectConfig.setSelectedIndex(m.path("collectBoxConfig").asInt(2) == 1 ? 0 : 1);
        String f = m.path("collectBoxFormation").asText("Veteran");
        collectFormation.set(f.isBlank() ? null : f.startsWith("drone_formation_") ? f : formationId(f));
        emp.setSelected(m.path("useEmp").asBoolean(true));
        Form.setFraction(empBelow, m.path("empPercent").asDouble(35) / 100);
        empOnlyPlayers.setSelected(m.path("empOnlyPlayers").asBoolean(true));
        empThenIsh.setSelected(m.path("empThenIsh").asBoolean(false));
        abilityAtStart.setSelected(m.path("abilityAtStart").asBoolean(false));
        pushThrough.setSelected(m.path("pushThrough").asBoolean(false));
        ish.setSelected(m.path("useIsh").asBoolean(true));
        Form.setFraction(ishBelow, m.path("ishPercent").asDouble(60) / 100);
        prismatic.setSelected(m.path("usePrismatic").asBoolean(false));
        Form.setFraction(prismaticBelow, m.path("prismaticPercent").asDouble(50) / 100);
        jamx.setSelected(m.path("useSpearheadJamx").asBoolean(false));
        Form.set(jamxDistance, m.path("jamxDistance").asInt(750));
        petOnly.setSelected(m.path("petOnlyOnBehemoth").asBoolean(false));
        beacon.setSelected(m.path("usePetCombatBeacon").asBoolean(false));
        Form.setFraction(linkBelow, m.path("hpLinkPercent").asDouble(0) / 100);
        onTime.setSelected(m.path("arriveOnTime").asBoolean(false));
        useLocator.setSelected(m.path("useLocator").asBoolean(false));
        locatorList.setSelected(m.path("locatorList").asBoolean(false));
        waitFirst.setSelected(m.path("waitFirst").asBoolean(false));
        powers.setSelected(m.path("powersForBehemoth").asBoolean(false));
        prismaticAtStart.setSelected(m.path("prismaticAtStart").asBoolean(false));
        Form.set(empAfterIsh, m.path("empAfterIshSeconds").asInt(3));
        Form.set(closeMin, m.path("closeMin").asInt(1_200));
        Form.set(closeKeep, m.path("closeKeep").asInt(1_500));
        Form.set(respawnSec, m.path("respawnSeconds").asInt(0));
        Form.set(threatPlayers, m.path("threatPlayers").asInt(3));
        followTrain.setSelected(m.path("followTrain").asBoolean(false));
        Form.set(reward, m.path("rewardUridium").asInt(5_000));
        useCloak.setSelected(m.path("useCloak").asBoolean(false));
        String cf = m.path("cloakFormation").asText("");
        cloakFormation.set(cf.isBlank() ? null : cf.startsWith("drone_formation_") ? cf : formationId(cf));
        Form.set(beaconSeconds, m.path("beaconBeforeSeconds").asInt(0));
        Form.set(closeSeconds, m.path("closeBeforeSeconds").asInt(0));
        awayFromPortals.setSelected(m.path("awayFromPortals").asBoolean(false));
        learnSafeSpot.setSelected(m.path("learnSafeSpot").asBoolean(false));
        waitInZone.setSelected(m.path("waitInZone").asBoolean(false));
        dodgeEnemies.setSelected(m.path("dodgeEnemies").asBoolean(true));
        avoidTrainPaths.setSelected(m.path("avoidTrainPaths").asBoolean(false));
        trainTimer.setSelected(m.path("trainTimer").asBoolean(false));
        waitForTrain.setSelected(m.path("waitForTrain").asBoolean(false));
        avoidEnemies.setSelected(m.path("avoidEnemies").asBoolean(false));
        freeSide.setSelected(m.path("freeSide").asBoolean(false));
        Form.set(freeSideRadius, m.path("freeSideRadius").asInt(2_000));
        avoidSpawn.setSelected(m.path("avoidSpawn").asBoolean(false));
        Form.set(avoidSpawnRadius, m.path("avoidSpawnRadius").asInt(1_500));
        trainMode.setSelected(m.path("trainMode").asBoolean(false));
        Form.set(trainRadius, m.path("trainRadius").asInt(1_500));
        Form.set(trainLimit, m.path("trainEnemyLimit").asInt(orbit.config.BehemothProven.TRAIN_LIMIT));
        trainAtLocator.setSelected(m.path("trainAtLocator").asBoolean(true));
        leaveSkipped.setSelected(m.path("leaveSkipped").asBoolean(true));
        avoidOrbit.setSelected(m.path("avoidEnemyOrbit").asBoolean(true));
        Form.set(deathsPerSpawn, m.path("deathsPerSpawn").asInt(0));
        respond.setSelected(m.path("respondPlayers").asBoolean(false));
        Form.set(respondRadius, m.path("respondRadius").asInt(500));
        defendGroup.setSelected(m.path("defendGroup").asBoolean(false));
        hunt.setSelected(m.path("huntEnemies").asBoolean(false));
        Form.set(huntRadius, m.path("huntRadius").asInt(1_500));
        java.util.List<String> names = new java.util.ArrayList<>();
        m.path("blacklist").forEach(n -> names.add(n.asText()));
        blacklist.setText(String.join(", ", names));
        focus.setSelectedIndex("ENEMY".equals(m.path("enemyFocus").asText("BEHEMOTH")) ? 1 : 0);
        String pa = m.path("pvpAmmo").asText("");
        pvpAmmo.set(pa.isBlank() ? null : pa);
        follow.setSelected(m.path("followLeader").asBoolean(false));
        leader.setText(m.path("leader").asText(""));
        Form.set(followDistance, m.path("followDistance").asInt(400));
        sync();
    }

    private static String formationId(String name) {
        for (ItemCatalog.Item i : ItemCatalog.FORMATIONS) {
            if (i.label().toLowerCase().startsWith(name.toLowerCase() + " (")) {
                return i.id();
            }
        }
        return null;
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        boolean untouched = c.module() != BotConfig.Module.BEHEMOTH && m.isEmpty();
        if (untouched) {
            return c; // a config of another module: do not add the section
        }
        ArrayNode list = m.putArray("maps");
        for (int i = 0; i < MAPS.length; i++) {
            if (maps[i].isSelected()) {
                list.add(MAPS[i]);
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Бегемот: выберите хотя бы одну карту");
        }
        m.put("changeMapSeconds", Form.intOf(changeMap));
        m.put("respawnMinutes", Form.intOf(respawn));
        m.put("defenceMode", defence.isSelected());
        m.put("killOthers", killOthers.isSelected());
        m.put("collectBox", collect.isSelected());
        m.put("collectSpawnBoxes", spawnBoxes.isSelected());
        m.put("collectBoxConfig", collectConfig.getSelectedIndex() + 1);
        // none chosen: "" (the formation stays as it is), not the default Veteran
        m.put("collectBoxFormation", collectFormation.get() != null ? collectFormation.get() : "");
        m.put("useEmp", emp.isSelected());
        m.put("empPercent", Math.round(Form.fraction(empBelow) * 100));
        m.put("empOnlyPlayers", empOnlyPlayers.isSelected());
        m.put("empThenIsh", empThenIsh.isSelected());
        m.put("abilityAtStart", abilityAtStart.isSelected());
        m.put("pushThrough", pushThrough.isSelected());
        m.put("useIsh", ish.isSelected());
        m.put("ishPercent", Math.round(Form.fraction(ishBelow) * 100));
        m.put("usePrismatic", prismatic.isSelected());
        m.put("prismaticPercent", Math.round(Form.fraction(prismaticBelow) * 100));
        m.put("useSpearheadJamx", jamx.isSelected());
        m.put("jamxDistance", Form.intOf(jamxDistance));
        m.put("petOnlyOnBehemoth", petOnly.isSelected());
        m.put("usePetCombatBeacon", beacon.isSelected());
        m.put("hpLinkPercent", Math.round(Form.fraction(linkBelow) * 100));
        m.put("arriveOnTime", onTime.isSelected());
        m.put("useLocator", useLocator.isSelected());
        m.put("locatorList", locatorList.isSelected());
        m.put("waitFirst", waitFirst.isSelected());
        m.put("powersForBehemoth", powers.isSelected());
        m.put("prismaticAtStart", prismaticAtStart.isSelected());
        m.put("empAfterIshSeconds", Form.intOf(empAfterIsh));
        m.put("closeMin", Form.intOf(closeMin));
        m.put("closeKeep", Form.intOf(closeKeep));
        m.put("respawnSeconds", Form.intOf(respawnSec));
        m.put("threatPlayers", Form.intOf(threatPlayers));
        m.put("followTrain", followTrain.isSelected());
        m.put("rewardUridium", Form.intOf(reward));
        m.put("useCloak", useCloak.isSelected());
        m.put("cloakFormation", cloakFormation.get() != null ? cloakFormation.get() : "");
        m.put("beaconBeforeSeconds", Form.intOf(beaconSeconds));
        m.put("closeBeforeSeconds", Form.intOf(closeSeconds));
        m.put("awayFromPortals", awayFromPortals.isSelected());
        m.put("learnSafeSpot", learnSafeSpot.isSelected());
        m.put("waitInZone", waitInZone.isSelected());
        m.put("dodgeEnemies", dodgeEnemies.isSelected());
        m.put("avoidTrainPaths", avoidTrainPaths.isSelected());
        m.put("trainTimer", trainTimer.isSelected());
        m.put("waitForTrain", waitForTrain.isSelected());
        m.put("avoidEnemies", avoidEnemies.isSelected());
        m.put("freeSide", freeSide.isSelected());
        m.put("freeSideRadius", Form.intOf(freeSideRadius));
        m.put("avoidSpawn", avoidSpawn.isSelected());
        m.put("avoidSpawnRadius", Form.intOf(avoidSpawnRadius));
        m.put("trainMode", trainMode.isSelected());
        m.put("trainRadius", Form.intOf(trainRadius));
        m.put("trainEnemyLimit", Form.intOf(trainLimit));
        m.put("trainAtLocator", trainAtLocator.isSelected());
        m.put("leaveSkipped", leaveSkipped.isSelected());
        m.put("avoidEnemyOrbit", avoidOrbit.isSelected());
        m.put("deathsPerSpawn", Form.intOf(deathsPerSpawn));
        m.put("respondPlayers", respond.isSelected());
        m.put("respondRadius", Form.intOf(respondRadius));
        m.put("defendGroup", defendGroup.isSelected());
        m.put("huntEnemies", hunt.isSelected());
        m.put("huntRadius", Form.intOf(huntRadius));
        ArrayNode bl = m.putArray("blacklist");
        for (String n : blacklist.getText().split(",")) {
            if (!n.isBlank()) {
                bl.add(n.strip());
            }
        }
        m.put("enemyFocus", focus.getSelectedIndex() == 1 ? "ENEMY" : "BEHEMOTH");
        m.put("pvpAmmo", pvpAmmo.get() != null ? pvpAmmo.get() : "");
        m.put("followLeader", follow.isSelected());
        m.put("leader", leader.getText().strip());
        m.put("followDistance", Form.intOf(followDistance));
        return c.withModuleSettings(KEY, m);
    }
}
