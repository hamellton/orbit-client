package orbit.app.gui.config;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;
import orbit.world.maps.MapCatalog;

/** «Общее»: module, working map, ship configuration and formation per situation, enemies, roaming. */
final class GeneralSection implements Section {

    static final String[] MODULE_NAMES = {"Убийство и сбор", "Убийство NPC", "Сбор ящиков", "Кубиконы", "Врата (GG)",
            "Палладий", "Квесты", "Бегемот", "Не поддерживается"};

    private final JComboBox<String> module = new JComboBox<>(MODULE_NAMES);
    private final MapBox map;
    private final ModeRow offensive = new ModeRow();
    private final ModeRow roam = new ModeRow();
    private final ModeRow run = new ModeRow();
    private final JCheckBox runFromEnemies = new JCheckBox("Убегать от врагов, которые атакуют или приближаются");
    private final JCheckBox runInSight = new JCheckBox("Убегать, как только враг виден");
    private final JSpinner remember = Form.number(0, 3600, 30);
    private final JCheckBox sequential = new JCheckBox("По порядку, а не случайно");
    private final JCheckBox onlyPreferred = new JCheckBox("Бить только в предпочтительной зоне");
    private final JCheckBox runTravel = new JCheckBox("Перелёты — в конфиге бегства");
    private final JCheckBox runChase = new JCheckBox("Догонять цель в конфиге бегства");
    private final JCheckBox avoidMines = new JCheckBox("Обходить мины");
    private final JCheckBox avoidBases = new JCheckBox("Облетать вражеские базы на");
    private final JSpinner baseRadius = Form.number(100, 3000, 50);
    private final JTextArea description = new JTextArea(5, 50);
    private final JSpinner work = Form.number(0, 24 * 60, 10);
    private final JSpinner rest = Form.number(0, 24 * 60, 5);
    private final JSpinner jitter = Form.number(0, 120, 1);
    private final JComponent view;

    GeneralSection(MapCatalog maps) {
        map = new MapBox(maps);
        description.setLineWrap(true);
        avoidBases.addActionListener(e -> baseRadius.setEnabled(avoidBases.isSelected()));
        description.setWrapStyleWord(true);
        view = new Form()
                .section("Модуль")
                .row("Режим", module)
                .row("Рабочая карта", map)
                .section("Конфиг корабля и формация")
                .row("Атака", offensive.view())
                .row("Полёт и сбор", roam.view())
                .row("Бегство", run.view())
                .row("", runTravel)
                .row("", runChase)
                .row("", avoidMines)
                .note("Обходит все мины, ящики у мин не берёт.")
                .row("", Form.inline(avoidBases, baseRadius))
                .note("Чужие клановые станции, штабы и турели чужих фракций.")
                .note("Догоняет, если цель дальше радиуса на 300+; AGGRESSIVE_FOLLOW — всегда.")
                .section("Облёт карты")
                .row("", sequential)
                .row("", onlyPreferred)
                .section("Сессии")
                .row("Работать, мин", work)
                .row("Перерыв, мин", rest)
                .row("Разброс ±, мин", jitter)
                .note("0 — без ограничения. Сессии не выходят за окна расписания.")
                .section("Описание")
                .wide(new JScrollPane(description), false)
                .build(true);
    }

    @Override
    public String title() {
        return "Общее";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        module.setSelectedIndex(c.module().ordinal());
        map.setMap(c.general().workingMap());
        offensive.set(c.general().offensive());
        roam.set(c.general().roam());
        run.set(c.general().run());
        runFromEnemies.setSelected(c.general().runFromEnemies());
        runInSight.setSelected(c.general().runFromEnemiesInSight());
        Form.set(remember, c.general().rememberEnemiesSec());
        sequential.setSelected(c.general().roamSequential());
        onlyPreferred.setSelected(c.general().onlyKillPreferred());
        runTravel.setSelected(c.general().runModeTravel());
        runChase.setSelected(c.general().runModeChase());
        avoidMines.setSelected(c.general().avoidMines());
        avoidBases.setSelected(c.general().baseRadius() > 0);
        Form.set(baseRadius, c.general().baseRadius() > 0 ? c.general().baseRadius() : BotConfig.General.DEFAULT_BASE_RADIUS);
        baseRadius.setEnabled(avoidBases.isSelected());
        Form.set(work, c.session().workMinutes());
        Form.set(rest, c.session().breakMinutes());
        Form.set(jitter, c.session().jitterMinutes());
        description.setText(c.description());
        description.setCaretPosition(0);
    }

    @Override
    public BotConfig apply(BotConfig c) {
        BotConfig.General g = new BotConfig.General(map.mapId(), offensive.get(), roam.get(), run.get(),
                c.general().runFromEnemies(), c.general().runFromEnemiesInSight(), c.general().rememberEnemiesSec(),
                sequential.isSelected(),
                onlyPreferred.isSelected(), runTravel.isSelected(), runChase.isSelected(), avoidMines.isSelected(),
                avoidBases.isSelected() ? Form.intOf(baseRadius) : 0);
        BotConfig.Session session = new BotConfig.Session(Form.intOf(work), Form.intOf(rest), Form.intOf(jitter));
        BotConfig out = c.withModule(BotConfig.Module.values()[module.getSelectedIndex()]).withGeneral(g)
                .withDescription(description.getText().strip());
        return session.equals(c.session()) ? out : out.withSession(session);
    }

    /** Configuration 1/2 and a formation. */
    static final class ModeRow {

        private final JComboBox<String> config = new JComboBox<>(new String[] {"Конфиг 1", "Конфиг 2"});
        private final ItemBox formation = new ItemBox(ItemCatalog.FORMATIONS, true);

        JComponent view() {
            return Form.inline(config, formation);
        }

        void set(BotConfig.ShipMode m) {
            config.setSelectedIndex(m.config() == 2 ? 1 : 0);
            formation.set(m.formation());
        }

        BotConfig.ShipMode get() {
            return new BotConfig.ShipMode(config.getSelectedIndex() + 1, formation.get());
        }
    }
}
