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
            "Палладий", "Квесты", "Не поддерживается"};

    private final JComboBox<String> module = new JComboBox<>(MODULE_NAMES);
    private final MapBox map;
    private final ModeRow offensive = new ModeRow();
    private final ModeRow roam = new ModeRow();
    private final ModeRow run = new ModeRow();
    private final JCheckBox runFromEnemies = new JCheckBox("Убегать от врагов, которые атакуют или приближаются");
    private final JCheckBox runInSight = new JCheckBox("Убегать, как только враг виден");
    private final JSpinner remember = Form.number(0, 3600, 30);
    private final JCheckBox sequential = new JCheckBox("Облетать карту по порядку, а не случайно");
    private final JCheckBox onlyPreferred = new JCheckBox("Бить только в предпочтительной зоне");
    private final JTextArea description = new JTextArea(5, 50);
    private final JComponent view;

    GeneralSection(MapCatalog maps) {
        map = new MapBox(maps);
        description.setLineWrap(true);
        description.setWrapStyleWord(true);
        view = new Form()
                .section("Модуль")
                .row("Что делает бот", module)
                .row("Рабочая карта", map)
                .section("Конфигурация корабля и формация дронов")
                .row("Атака", offensive.view())
                .row("Полёт и сбор", roam.view())
                .row("Бегство", run.view())
                .section("Враги")
                .row("", runFromEnemies)
                .row("", runInSight)
                .row("Помнить врага, с", remember)
                .section("Облёт карты")
                .row("", sequential)
                .row("", onlyPreferred)
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
        description.setText(c.description());
        description.setCaretPosition(0);
    }

    @Override
    public BotConfig apply(BotConfig c) {
        BotConfig.General g = new BotConfig.General(map.mapId(), offensive.get(), roam.get(), run.get(),
                runFromEnemies.isSelected(), runInSight.isSelected(), Form.intOf(remember), sequential.isSelected(),
                onlyPreferred.isSelected());
        return c.withModule(BotConfig.Module.values()[module.getSelectedIndex()]).withGeneral(g)
                .withDescription(description.getText().strip());
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
