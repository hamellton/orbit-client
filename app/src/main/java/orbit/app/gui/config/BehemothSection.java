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
    private final JComboBox<String> collectConfig = new JComboBox<>(new String[] {"1", "2"});
    private final ItemBox collectFormation = new ItemBox(ItemCatalog.FORMATIONS, true);
    private final JCheckBox emp = new JCheckBox("EMP-01 при HP корабля ниже");
    private final JSpinner empBelow = Form.percent();
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
    private final JCheckBox useCloak = new JCheckBox("Маскировка, пока ждём бегемота (CL04K)");
    private final ItemBox cloakFormation = new ItemBox(ItemCatalog.FORMATIONS, true);
    private final JSpinner beaconSeconds = Form.number(0, 120, 1);
    // where to wait (the user, 2026-11-13: trains camp the spawn and BeheFarm's wait points)
    private final JCheckBox trainTimer = new JCheckBox("Поезд стоит у места появления, бегемота не видно — считать его убитым (таймер)");
    private final JCheckBox waitForTrain = new JCheckBox("Не улетать, пока поезд идёт к месту появления (до 3 мин)");
    private final JCheckBox awayFromPortals = new JCheckBox("Ждать в стороне от путей «портал → бегемот», где ходят поезда");
    private final JCheckBox freeSide = new JCheckBox("Ждать со свободной стороны: чужие у точки — встать на круге от них");
    private final JSpinner freeSideRadius = Form.number(800, 5_000, 100);
    private final JCheckBox avoidSpawn = new JCheckBox("Пока ждём — облетать место появления, радиус");
    private final JSpinner avoidSpawnRadius = Form.number(300, 4_000, 100);
    // a train of others by the behemoth (CARNAGE's BLCycle and behemoth modules)
    private final JCheckBox trainMode = new JCheckBox("Бить бегемота, даже когда рядом чужой поезд и бьёт нас");
    private final JSpinner trainRadius = Form.number(300, 5_000, 100);
    private final JSpinner trainLimit = Form.number(0, 50, 1);
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
        useCloak.addActionListener(e -> sync());
        freeSide.addActionListener(e -> sync());
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
        trainLimit.setToolTipText("Больше стольких врагов у бегемота — уйти на другую карту; 0 — не уходить (как BLCycle)");
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
                .row("", onTime)
                .row("Засчитывать убийство от, уридия", reward)
                .row("", useLocator)
                .note("Пеленгатор (нужен модуль у питомца): есть бегемот на карте — летит к нему; «нет» — карта"
                        + " пустая, но ждёт своё время (0 — не ждёт). Питомец тогда выпущен всё время.")
                .note("Бот запоминает, когда бегемот появился и погиб на каждой карте, и летит туда, где он будет раньше;"
                        + " ждёт у места появления (точки — из BeheFarm, уточняются по увиденному).")
                .section("Где ждать")
                .row("", awayFromPortals)
                .row("", trainTimer)
                .row("", waitForTrain)
                .row("", freeSide)
                .row("Круг вокруг места появления", freeSideRadius)
                .row("", Form.inline(avoidSpawn, avoidSpawnRadius))
                .note("Точки ожидания по картам — в файле конфига (waitPoints); в «behe train» — точки BeheSolo из"
                        + " CARNAGE: в стороне от места появления, где стоят поезда.")
                .section("Бой")
                .note("Как бить самого бегемота (радиус, A-BL, Drill, HP Link, RSB) — строка «Mindfire Behemoth» во"
                        + " вкладке «Убивать». Чужих игроков у бегемота не боимся: урон засчитывается всем.")
                .row("", Form.inline(emp, empBelow, new javax.swing.JLabel("%")))
                .row("", Form.inline(ish, ishBelow, new javax.swing.JLabel("%")))
                .row("", Form.inline(prismatic, prismaticBelow, new javax.swing.JLabel("%")))
                .row("", Form.inline(jamx, jamxDistance))
                .row("", defence)
                .row("", killOthers)
                .section("Питомец")
                .row("", petOnly)
                .row("HP Link, пока HP корабля ниже, %", linkBelow)
                .row("", beacon)
                .row("Маяк урона за, с до появления (0 — нет)", beaconSeconds)
                .note("Энергетическая пиявка и способность корабля — галочки USE_LEECH и USE_ABILITY в строке бегемота.")
                .section("Маскировка")
                .row("", useCloak)
                .row("Формация в маскировке", cloakFormation)
                .note("Покупка маскировки — вкладка «Покупки», предмет CL04K-XL (если купленная не появилась в игре,"
                        + " бот больше не покупает до перезапуска). Крафт пока не сделан.")
                .section("Чужой поезд у бегемота")
                .row("", trainMode)
                .row("Поезд — чужие ближе к бегемоту, чем", trainRadius)
                .row("Уйти, если врагов больше (0 — нет)", trainLimit)
                .row("", avoidOrbit)
                .row("Погиб столько раз — другая карта (0 — нет)", deathsPerSpawn)
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
                .row("Конфигурация", collectConfig)
                .row("Формация", collectFormation)
                .build(true);
    }

    private void sync() {
        collectConfig.setEnabled(collect.isSelected());
        collectFormation.setEnabled(collect.isSelected());
        empBelow.setEnabled(emp.isSelected());
        ishBelow.setEnabled(ish.isSelected());
        prismaticBelow.setEnabled(prismatic.isSelected());
        jamxDistance.setEnabled(jamx.isSelected());
        boolean t = trainMode.isSelected();
        trainRadius.setEnabled(t);
        trainLimit.setEnabled(t);
        avoidOrbit.setEnabled(t);
        deathsPerSpawn.setEnabled(t);
        respondRadius.setEnabled(respond.isSelected());
        huntRadius.setEnabled(hunt.isSelected());
        leader.setEnabled(follow.isSelected());
        cloakFormation.setEnabled(useCloak.isSelected());
        freeSideRadius.setEnabled(freeSide.isSelected() || awayFromPortals.isSelected());
        avoidSpawnRadius.setEnabled(avoidSpawn.isSelected());
        followDistance.setEnabled(follow.isSelected());
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
        collectConfig.setSelectedIndex(m.path("collectBoxConfig").asInt(2) == 1 ? 0 : 1);
        String f = m.path("collectBoxFormation").asText("Veteran");
        collectFormation.set(f.isBlank() ? null : f.startsWith("drone_formation_") ? f : formationId(f));
        emp.setSelected(m.path("useEmp").asBoolean(true));
        Form.setFraction(empBelow, m.path("empPercent").asDouble(35) / 100);
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
        Form.set(reward, m.path("rewardUridium").asInt(5_000));
        useCloak.setSelected(m.path("useCloak").asBoolean(false));
        String cf = m.path("cloakFormation").asText("");
        cloakFormation.set(cf.isBlank() ? null : cf.startsWith("drone_formation_") ? cf : formationId(cf));
        Form.set(beaconSeconds, m.path("beaconBeforeSeconds").asInt(0));
        awayFromPortals.setSelected(m.path("awayFromPortals").asBoolean(false));
        trainTimer.setSelected(m.path("trainTimer").asBoolean(false));
        waitForTrain.setSelected(m.path("waitForTrain").asBoolean(false));
        freeSide.setSelected(m.path("freeSide").asBoolean(false));
        Form.set(freeSideRadius, m.path("freeSideRadius").asInt(2_000));
        avoidSpawn.setSelected(m.path("avoidSpawn").asBoolean(false));
        Form.set(avoidSpawnRadius, m.path("avoidSpawnRadius").asInt(1_500));
        trainMode.setSelected(m.path("trainMode").asBoolean(false));
        Form.set(trainRadius, m.path("trainRadius").asInt(1_500));
        Form.set(trainLimit, m.path("trainEnemyLimit").asInt(0));
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
        m.put("collectBoxConfig", collectConfig.getSelectedIndex() + 1);
        // none chosen: "" (the formation stays as it is), not the default Veteran
        m.put("collectBoxFormation", collectFormation.get() != null ? collectFormation.get() : "");
        m.put("useEmp", emp.isSelected());
        m.put("empPercent", Math.round(Form.fraction(empBelow) * 100));
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
        m.put("rewardUridium", Form.intOf(reward));
        m.put("useCloak", useCloak.isSelected());
        m.put("cloakFormation", cloakFormation.get() != null ? cloakFormation.get() : "");
        m.put("beaconBeforeSeconds", Form.intOf(beaconSeconds));
        m.put("awayFromPortals", awayFromPortals.isSelected());
        m.put("trainTimer", trainTimer.isSelected());
        m.put("waitForTrain", waitForTrain.isSelected());
        m.put("freeSide", freeSide.isSelected());
        m.put("freeSideRadius", Form.intOf(freeSideRadius));
        m.put("avoidSpawn", avoidSpawn.isSelected());
        m.put("avoidSpawnRadius", Form.intOf(avoidSpawnRadius));
        m.put("trainMode", trainMode.isSelected());
        m.put("trainRadius", Form.intOf(trainRadius));
        m.put("trainEnemyLimit", Form.intOf(trainLimit));
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
