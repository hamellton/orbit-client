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
    static final String[] MODE_NAMES = {"По кругу (споты из таблицы)", "По запомненным спаунам"};

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
    private final JSpinner boxesAbove = Form.percent();
    private final JCheckBox killWhileWaiting = new JCheckBox("Бить других NPC, пока ждём (Кристаллины)");
    private final JSpinner waitKillRange = Form.number(200, 10_000, 100);
    private final javax.swing.JComboBox<String> mode = new javax.swing.JComboBox<>(MODE_NAMES);
    private final JCheckBox learn = new JCheckBox("Запоминать, где появляются и когда умирают кубы");
    private final javax.swing.JLabel learned = new javax.swing.JLabel(" ");
    private final JTable table = new JTable(spots);
    private final JComponent view;

    CubikonSection() {
        table.setPreferredScrollableViewportSize(new java.awt.Dimension(260, 4 * table.getRowHeight()));
        javax.swing.JButton reset = new javax.swing.JButton("Споты по умолчанию (x-6)");
        reset.addActionListener(e -> setSpots(null));
        javax.swing.JButton forget = new javax.swing.JButton("Забыть запомненное");
        forget.addActionListener(e -> {
            if (javax.swing.JOptionPane.showConfirmDialog(table, "Забыть все запомненные спауны и время возврата кубов?",
                    "Кубы", javax.swing.JOptionPane.OK_CANCEL_OPTION) == javax.swing.JOptionPane.OK_OPTION) {
                orbit.bot.mod.SpawnMemory m = orbit.bot.mod.SpawnMemory.open(orbit.bot.mod.SpawnMemory.DEFAULT_FILE);
                for (int map : new int[] {18, 22, 26}) {
                    m.forget(map);
                }
                showLearned();
            }
        });
        killWhileWaiting.addActionListener(e -> waitKillRange.setEnabled(killWhileWaiting.isSelected()));
        view = new Form()
                .section("Кубиконы")
                .note("Если на карте живой Cubikon (даже если его уже бьют другие) — бот сразу летит к нему. "
                        + "Иначе дежурит на спаунах по кругу, начиная с ближайшего. Protegit не бьёт (список NPC во "
                        + "вкладке «Атака»).")
                .row("Выбор спота", mode)
                .note("«По кругу» — споты из таблицы по очереди, начиная с ближайшего. «По запомненным» — бот летит "
                        + "туда, где куб должен вот-вот вернуться (по времени, которое он сам замерил), иначе туда, где "
                        + "кубы появлялись последними; споты из таблицы тоже обходит.")
                .row("Споты", new javax.swing.JScrollPane(table))
                .row("", reset)
                .row("", learn)
                .row("Запомнено", Form.inline(learned, forget))
                .row("Подлёт к споту на", arrive)
                .row("Ждать на пустом споте, с", wait)
                .note("0 — ждать на споте, пока не появится куб.")
                .row("", collect)
                .row("Собирать, пока у куба HP больше, %", boxesAbove)
                .row("", killWhileWaiting)
                .row("Бить их не дальше", waitKillRange)
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
        Form.setFraction(boxesAbove, m.path("boxesWhileCubikonHpAbove").asDouble(0.25));
        killWhileWaiting.setSelected(m.path("killWhileWaiting").asBoolean(false));
        Form.set(waitKillRange, m.path("waitKillRange").asInt(1_500));
        waitKillRange.setEnabled(killWhileWaiting.isSelected());
        int i = java.util.Arrays.asList(MODES).indexOf(m.path("spotMode").asText("ROUND"));
        mode.setSelectedIndex(Math.max(0, i));
        learn.setSelected(m.path("learnSpawns").asBoolean(true));
        showLearned();
    }

    /** What the bot has learned so far (local/cubikon-spawns.json). */
    private void showLearned() {
        orbit.bot.mod.SpawnMemory m = orbit.bot.mod.SpawnMemory.open(orbit.bot.mod.SpawnMemory.DEFAULT_FILE);
        StringBuilder b = new StringBuilder();
        String[] names = {"1-6", "2-6", "3-6"};
        int[] ids = {18, 22, 26};
        for (int k = 0; k < ids.length; k++) {
            var list = m.spawns(ids[k]);
            if (!list.isEmpty()) {
                long timed = list.stream().filter(x -> x.respawnEstimate() > 0).count();
                b.append(b.isEmpty() ? "" : "; ").append(names[k]).append(": спаунов ").append(list.size())
                        .append(", с временем возврата ").append(timed);
            }
        }
        learned.setText(b.isEmpty() ? "пока ничего" : b.toString());
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
        m.put("boxesWhileCubikonHpAbove", Form.fraction(boxesAbove));
        m.put("killWhileWaiting", killWhileWaiting.isSelected());
        m.put("waitKillRange", Form.intOf(waitKillRange));
        m.put("spotMode", MODES[mode.getSelectedIndex()]);
        m.put("learnSpawns", learn.isSelected());
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
        m.put("boxesWhileCubikonHpAbove", 0.25);
        m.put("killWhileWaiting", false);
        m.put("waitKillRange", 1_500);
        m.put("spotMode", "ROUND");
        m.put("learnSpawns", true);
        return m;
    }
}
