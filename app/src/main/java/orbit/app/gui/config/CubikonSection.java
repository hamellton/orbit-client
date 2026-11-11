package orbit.app.gui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import orbit.config.BotConfig;

/**
 * «Кубы» (CUBIKON): the spawn camp — wait on the spawn spots in turn, fly to any live Cubikon at once.
 * Spots are in minimap units (x/100, y/100), like the coordinates in the game.
 */
final class CubikonSection implements Section {

    static final int[][] DEFAULT_SPOTS = {{75, 39}, {134, 39}, {134, 79}, {75, 79}};
    static final String[] MODES = {"ROUND", "LEARNED"};
    static final String[] WAIT_KILL = {"OFF", "ATTACKERS", "ALL"};
    static final String[] WAIT_KILL_NAMES = {"Никого", "Только атакующих", "Всех из списка NPC"};
    static final String[] MODE_NAMES = {"По кругу", "По запомненным"};
    /** The x-6 maps: 1-6, 2-6, 3-6. */
    static final int[] X6 = {18, 22, 26};
    static final String[] X6_NAMES = {"1-6", "2-6", "3-6"};
    static final String[] MAP_MODES = {"SOONEST", "ROUND"};
    static final String[] MAP_MODE_NAMES = {"Где куб раньше", "По кругу"};
    static final String[] TRAVEL = {"PORTALS", "JUMP_CPU"};
    static final String[] TRAVEL_NAMES = {"Порталами", "CPU: купоны, потом уридий"};

    private final JCheckBox[] mapBoxes = {new JCheckBox("1-6"), new JCheckBox("2-6"), new JCheckBox("3-6")};
    private final javax.swing.JComboBox<String> mapMode = new javax.swing.JComboBox<>(MAP_MODE_NAMES);
    private final javax.swing.JComboBox<String> travel = new javax.swing.JComboBox<>(TRAVEL_NAMES);
    private final JSpinner jumpUridium = Form.number(0, 1_000_000, 500);
    private final DefaultTableModel windows = new DefaultTableModel(new Object[] {"С", "По", "Карты"}, 0);
    private final JTable windowTable = new JTable(windows);

    private final DefaultTableModel spots = new DefaultTableModel(new Object[] {"Спаун", "X", "Y"}, 0) {
        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? String.class : Integer.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c > 0;
        }
    };
    private final JSpinner arrive = Form.number(150, 1_200, 10);
    private final JSpinner wait = Form.number(0, 300, 5);
    private final JCheckBox collect = new JCheckBox("Собирать ящики");
    private final JCheckBox duringFight = new JCheckBox("Собирать в бою, не уходя от куба");
    private final JSpinner boxesAbove = Form.percent();
    private final javax.swing.JComboBox<String> waitKill = new javax.swing.JComboBox<>(WAIT_KILL_NAMES);
    private final JSpinner waitKillRange = Form.number(200, 10_000, 100);
    private final javax.swing.JComboBox<String> chase = new javax.swing.JComboBox<>(
            new String[] {"Стоять на споте", "Преследовать"});
    private final javax.swing.JComboBox<String> mode = new javax.swing.JComboBox<>(MODE_NAMES);
    private final JCheckBox learn = new JCheckBox("Запоминать спауны и смерти кубов");
    private final javax.swing.JLabel learned = new javax.swing.JLabel(" ");
    private final JTable table = new JTable(spots);
    private final JComponent view;

    CubikonSection() {
        table.setPreferredScrollableViewportSize(new java.awt.Dimension(260, 4 * table.getRowHeight()));
        javax.swing.JButton reset = new javax.swing.JButton("По умолчанию (x-6)");
        reset.addActionListener(e -> setSpots(null));
        javax.swing.JButton forget = new javax.swing.JButton("Забыть");
        forget.addActionListener(e -> {
            if (javax.swing.JOptionPane.showConfirmDialog(table, "Забыть спауны и время возврата кубов?",
                    "Кубы", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                orbit.bot.mod.SpawnMemory m = orbit.bot.mod.SpawnMemory.open(orbit.bot.mod.SpawnMemory.DEFAULT_FILE);
                for (int map : new int[] {18, 22, 26}) {
                    m.forget(map);
                }
                showLearned();
            }
        });
        waitKill.addActionListener(e -> {
            waitKillRange.setEnabled(waitKill.getSelectedIndex() > 0);
            chase.setEnabled(waitKill.getSelectedIndex() > 0);
        });
        windowTable.setPreferredScrollableViewportSize(new java.awt.Dimension(300, 3 * windowTable.getRowHeight()));
        windowTable.setToolTipText("Время ЧЧ:ММ; карты — «1-6, 3-6». Окно через полночь: 22:00–02:00");
        javax.swing.JButton addWindow = new javax.swing.JButton("Добавить");
        addWindow.addActionListener(e -> windows.addRow(new Object[] {"08:00", "12:00", "1-6, 2-6, 3-6"}));
        javax.swing.JButton removeWindow = new javax.swing.JButton("Удалить");
        removeWindow.addActionListener(e -> {
            int r = windowTable.getSelectedRow();
            if (r >= 0) {
                windows.removeRow(r);
            }
        });
        travel.addActionListener(e -> jumpUridium.setEnabled(travel.getSelectedIndex() == 1));
        view = new Form()
                .section("Карты")
                .row("Где фармить", Form.inline(mapBoxes))
                .row("Выбор карты", mapMode)
                .note("Ни одной — рабочая карта из «Общее». Живой куб держит на карте.")
                .row("По времени", new javax.swing.JScrollPane(windowTable))
                .row("", Form.inline(addWindow, removeWindow))
                .note("Окно расписания заменяет отмеченные карты, пока длится.")
                .row("Перелёт", travel)
                .row("Уридия на прыжки в день", jumpUridium)
                .note("Прыжок через CPU ещё не готов — пока порталами.")
                .section("Кубиконы")
                .note("Живой куб — летит сразу, даже если его бьют; иначе ждёт на спотах.")
                .row("Выбор спота", mode)
                .note("«По запомненным» — где куб скоро вернётся или был последним.")
                .row("Споты", new javax.swing.JScrollPane(table))
                .row("", reset)
                .row("", learn)
                .row("Запомнено", Form.inline(learned, forget))
                .row("Подлёт к споту на", arrive)
                .row("Ждать на пустом споте, с", wait)
                .note("0 — пока не появится куб.")
                .row("", collect)
                .row("", duringFight)
                .row("Собирать при HP куба выше, %", boxesAbove)
                .row("Пока ждём куб, бить", waitKill)
                .row("Как бить", chase)
                .row("…не дальше от спота", waitKillRange)
                .note("Кого бить — список NPC («Атака»). Дальше радиуса — назад на спот.")
                .build(true);
    }

    private void setSpots(JsonNode list) {
        spots.setRowCount(0);
        if (list == null || !list.isArray() || list.isEmpty()) {
            for (int[] s : DEFAULT_SPOTS) {
                spots.addRow(new Object[] {String.valueOf(spots.getRowCount() + 1), s[0], s[1]});
            }
            return;
        }
        for (JsonNode s : list) {
            spots.addRow(new Object[] {String.valueOf(spots.getRowCount() + 1), s.get(0).asInt() / 100, s.get(1).asInt() / 100});
        }
    }

    @Override
    public String title() {
        return "Кубы";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(BotConfig.Module.CUBIKON.key());
        setSpots(m.get("spots"));
        Form.set(arrive, m.path("arriveDistance").asInt(450));
        Form.set(wait, m.path("waitSeconds").asInt(90));
        collect.setSelected(m.path("collectBoxes").asBoolean(true));
        duringFight.setSelected(m.path("boxesDuringFight").asBoolean(true));
        Form.setFraction(boxesAbove, m.path("boxesWhileCubikonHpAbove").asDouble(0.25));
        String wk = m.has("waitKill") ? m.get("waitKill").asText("OFF")
                : (m.path("killWhileWaiting").asBoolean(false) ? "ALL" : "OFF");
        waitKill.setSelectedIndex(Math.max(0, java.util.Arrays.asList(WAIT_KILL).indexOf(wk)));
        Form.set(waitKillRange, m.path("waitKillRange").asInt(1_500));
        waitKillRange.setEnabled(waitKill.getSelectedIndex() > 0);
        chase.setSelectedIndex(m.path("chaseFromSpot").asBoolean(false) ? 1 : 0);
        chase.setEnabled(waitKill.getSelectedIndex() > 0);
        int i = java.util.Arrays.asList(MODES).indexOf(m.path("spotMode").asText("ROUND"));
        mode.setSelectedIndex(Math.max(0, i));
        learn.setSelected(m.path("learnSpawns").asBoolean(true));
        java.util.List<Integer> maps = orbit.bot.mod.CubeMaps.maps(m);
        for (int k = 0; k < X6.length; k++) {
            mapBoxes[k].setSelected(maps.contains(X6[k]));
        }
        mapMode.setSelectedIndex(Math.max(0, java.util.Arrays.asList(MAP_MODES).indexOf(m.path("mapMode").asText("SOONEST"))));
        windows.setRowCount(0);
        for (JsonNode w : m.path("mapSchedule")) {
            windows.addRow(new Object[] {w.path("from").asText(), w.path("to").asText(), mapNames(orbit.bot.mod.CubeMaps.maps(w))});
        }
        travel.setSelectedIndex(Math.max(0, java.util.Arrays.asList(TRAVEL).indexOf(m.path("travel").asText("PORTALS"))));
        Form.set(jumpUridium, m.path("jumpUridiumPerDay").asInt(5_000));
        jumpUridium.setEnabled(travel.getSelectedIndex() == 1);
        showLearned();
    }

    static String mapNames(java.util.List<Integer> ids) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int k = 0; k < X6.length; k++) {
            if (ids.contains(X6[k])) {
                out.add(X6_NAMES[k]);
            }
        }
        return String.join(", ", out);
    }

    /** «1-6, 3-6» → map ids; refuses what is not an x-6 map. */
    static java.util.List<Integer> mapIds(String text) {
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (String part : text.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            int k = java.util.Arrays.asList(X6_NAMES).indexOf(part.strip());
            if (k < 0) {
                throw new IllegalArgumentException("карта «" + part.strip() + "» — нужно 1-6, 2-6 или 3-6");
            }
            if (!out.contains(X6[k])) {
                out.add(X6[k]);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("в окне расписания не указаны карты");
        }
        return out;
    }

    private static String time(Object v) {
        String t = String.valueOf(v).strip();
        if (t.matches("\\d:\\d\\d")) {
            t = "0" + t;
        }
        try {
            java.time.LocalTime.parse(t);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("время «" + v + "» — нужно ЧЧ:ММ");
        }
        return t;
    }

    /** What the bot has learned so far (local/cubikon-spawns.json). */
    private void showLearned() {
        orbit.bot.mod.SpawnMemory m = orbit.bot.mod.SpawnMemory.peek(orbit.bot.mod.SpawnMemory.DEFAULT_FILE);
        StringBuilder b = new StringBuilder();
        String[] names = {"1-6", "2-6", "3-6"};
        int[] ids = {18, 22, 26};
        for (int k = 0; k < ids.length; k++) {
            var list = m.spawns(ids[k]);
            if (!list.isEmpty()) {
                long timed = list.stream().filter(x -> x.respawnEstimate() > 0).count();
                b.append(b.isEmpty() ? "" : "; ").append(names[k]).append(": спаунов ").append(list.size())
                        .append(", со временем ").append(timed);
            }
        }
        learned.setText(b.isEmpty() ? "ничего" : b.toString());
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        ObjectNode m = c.moduleSettings(BotConfig.Module.CUBIKON.key());
        boolean untouched = c.module() != BotConfig.Module.CUBIKON && m.isEmpty();
        ArrayNode list = m.putArray("spots");
        for (int r = 0; r < spots.getRowCount(); r++) {
            list.addArray().add((Integer) spots.getValueAt(r, 1) * 100).add((Integer) spots.getValueAt(r, 2) * 100);
        }
        m.put("arriveDistance", Form.intOf(arrive));
        m.put("waitSeconds", Form.intOf(wait));
        m.put("collectBoxes", collect.isSelected());
        m.put("boxesDuringFight", duringFight.isSelected());
        m.put("boxesWhileCubikonHpAbove", Form.fraction(boxesAbove));
        m.remove("killWhileWaiting");
        m.put("waitKill", WAIT_KILL[waitKill.getSelectedIndex()]);
        m.put("waitKillRange", Form.intOf(waitKillRange));
        m.put("chaseFromSpot", chase.getSelectedIndex() == 1);
        m.put("spotMode", MODES[mode.getSelectedIndex()]);
        m.put("learnSpawns", learn.isSelected());
        if (windowTable.isEditing()) {
            windowTable.getCellEditor().stopCellEditing();
        }
        ArrayNode maps = m.putArray("maps");
        for (int k = 0; k < X6.length; k++) {
            if (mapBoxes[k].isSelected()) {
                maps.add(X6[k]);
            }
        }
        m.put("mapMode", MAP_MODES[mapMode.getSelectedIndex()]);
        ArrayNode sched = m.putArray("mapSchedule");
        for (int r = 0; r < windows.getRowCount(); r++) {
            ObjectNode w = sched.addObject();
            w.put("from", time(windows.getValueAt(r, 0)));
            w.put("to", time(windows.getValueAt(r, 1)));
            ArrayNode wm = w.putArray("maps");
            mapIds(String.valueOf(windows.getValueAt(r, 2))).forEach(wm::add);
        }
        m.put("travel", TRAVEL[travel.getSelectedIndex()]);
        m.put("jumpUridiumPerDay", Form.intOf(jumpUridium));
        if (untouched && m.equals(defaults())) {
            return c; // nothing set for a config of another module: do not add the section
        }
        return c.withModuleSettings(BotConfig.Module.CUBIKON.key(), m);
    }

    private static ObjectNode defaults() {
        ObjectNode m = orbit.config.Json.MAPPER.createObjectNode();
        ArrayNode list = m.putArray("spots");
        for (int[] s : DEFAULT_SPOTS) {
            list.addArray().add(s[0] * 100).add(s[1] * 100);
        }
        m.put("arriveDistance", 450);
        m.put("waitSeconds", 90);
        m.put("collectBoxes", true);
        m.put("boxesDuringFight", true);
        m.put("boxesWhileCubikonHpAbove", 0.25);
        m.put("waitKill", "OFF");
        m.put("waitKillRange", 1_500);
        m.put("chaseFromSpot", false);
        m.put("spotMode", "ROUND");
        m.put("learnSpawns", true);
        m.putArray("maps");
        m.put("mapMode", "SOONEST");
        m.putArray("mapSchedule");
        m.put("travel", "PORTALS");
        m.put("jumpUridiumPerDay", 5_000);
        return m;
    }
}
