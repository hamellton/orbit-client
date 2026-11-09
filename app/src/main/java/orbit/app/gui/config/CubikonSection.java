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
    private final JTable table = new JTable(spots);
    private final JComponent view;

    CubikonSection() {
        table.setPreferredScrollableViewportSize(new java.awt.Dimension(260, 4 * table.getRowHeight()));
        javax.swing.JButton reset = new javax.swing.JButton("Споты по умолчанию (x-6)");
        reset.addActionListener(e -> setSpots(null));
        view = new Form()
                .section("Кубиконы")
                .note("Если на карте живой Cubikon (даже если его уже бьют другие) — бот сразу летит к нему. "
                        + "Иначе дежурит на спаунах по кругу, начиная с ближайшего. Protegit не бьёт (список NPC во "
                        + "вкладке «Атака»).")
                .row("Споты (по кругу)", new javax.swing.JScrollPane(table))
                .row("", reset)
                .row("Подлёт к споту на", arrive)
                .row("Ждать на пустом споте, с", wait)
                .note("0 — ждать на споте, пока не появится куб.")
                .row("", collect)
                .row("Собирать, пока у куба HP больше, %", boxesAbove)
                .row("", killWhileWaiting)
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
        return m;
    }
}
