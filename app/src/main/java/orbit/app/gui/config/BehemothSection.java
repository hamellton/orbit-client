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
    private final JComponent view;

    BehemothSection() {
        collect.addActionListener(e -> sync());
        emp.addActionListener(e -> sync());
        ish.addActionListener(e -> sync());
        changeMap.setToolTipText("Сколько ждать на карте после прилёта (или после времени появления), потом — другая карта");
        respawn.setToolTipText("Через сколько минут бегемот появляется снова; 0 — бот узнаёт сам (до этого 15 мин)");
        view = new Form()
                .section("Карты")
                .row("Обходить", Form.inline(maps[0], maps[1], maps[2]))
                .row("Нет бегемота — другая карта через, с", changeMap)
                .row("Появляется снова через, мин", respawn)
                .note("Бот запоминает, когда бегемот появился и погиб на каждой карте, и летит туда, где он будет раньше;"
                        + " ждёт у места появления (точки — из BeheFarm, уточняются по увиденному).")
                .section("Бой")
                .note("Как бить самого бегемота (радиус, A-BL, Drill, HP Link, RSB) — строка «Mindfire Behemoth» во"
                        + " вкладке «Убивать». Чужих игроков у бегемота не боимся: урон засчитывается всем.")
                .row("", Form.inline(emp, empBelow, new javax.swing.JLabel("%")))
                .row("", Form.inline(ish, ishBelow, new javax.swing.JLabel("%")))
                .row("", defence)
                .row("", killOthers)
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
        return c.withModuleSettings(KEY, m);
    }
}
