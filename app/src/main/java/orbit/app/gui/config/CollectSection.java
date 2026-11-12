package orbit.app.gui.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import orbit.config.BotConfig;

/** «Сбор» (COLLECTOR): which boxes to pick up. */
final class CollectSection implements Section {

    private final JSpinner radius = Form.number(0, 10_000, 50);
    private final JCheckBox stayAway = new JCheckBox("Не собирать рядом с врагами");
    private final JCheckBox ignoreContested = new JCheckBox("Пропускать ящики, занятые другими");
    private final Boxes boxes = new Boxes();
    private final JTable table = new JTable(boxes);
    private final JComponent view;
    static final String[] CARGO = {BotConfig.Collect.CARGO_NONE, BotConfig.Collect.CARGO_STOP, BotConfig.Collect.CARGO_BASE,
            BotConfig.Collect.CARGO_PET};
    static final String[] CARGO_NAMES = {"Ничего не делать", "Перестать собирать груз", "Продать на базе",
            "Продать через питомца (торговый модуль)"};
    /** The ores the trade window buys, in the game's numbers, with their names. */
    static final int[] ORES = {0, 1, 2, 4, 5, 6, 7, 28};
    static final String[] ORE_NAMES = {"Прометий", "Эндуриум", "Тербий", "Прометид", "Дюраниум", "Промериум", "Сепром",
            "Осмий"};
    private final javax.swing.JComboBox<String> cargo = new javax.swing.JComboBox<>(CARGO_NAMES);
    private final JSpinner cargoFullAt = Form.number(0, 100_000, 50);
    private final JCheckBox[] sell = new JCheckBox[ORES.length];

    CollectSection() {
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        JButton add = new JButton("Добавить");
        add.addActionListener(e -> {
            // the known boxes not in the list yet (the user's list, 2026-11-12), or any other type typed in
            javax.swing.JComboBox<String> pick = new javax.swing.JComboBox<>();
            pick.setEditable(true);
            for (orbit.config.BoxCatalog.Box b : orbit.config.BoxCatalog.all()) {
                if (b.collectable() && !boxes.has(b.type())) {
                    pick.addItem(label(b));
                }
            }
            int ok = JOptionPane.showConfirmDialog(table, new Object[] {"Ящик из списка или свой тип (напр. BONUS_BOX):", pick},
                    "Добавить ящик", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            Object chosen = pick.getEditor().getItem();
            if (ok == JOptionPane.OK_OPTION && chosen != null && !String.valueOf(chosen).isBlank()) {
                String type = String.valueOf(chosen).strip();
                int dash = type.indexOf(" — ");
                boxes.add(dash > 0 ? type.substring(0, dash) : type);
            }
        });
        JButton remove = new JButton("Удалить");
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                boxes.remove(table.convertRowIndexToModel(r));
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new java.awt.Dimension(600, 300));
        scroll.setMinimumSize(new java.awt.Dimension(240, 140));
        for (int i = 0; i < ORES.length; i++) {
            sell[i] = new JCheckBox(ORE_NAMES[i]);
        }
        cargo.addActionListener(e -> syncCargo());
        cargo.setToolTipText("Трюм занимают руда и ресурсы (кроме ксеномита); бонусные и бути-ящики — нет");
        view = new Form()
                .section("Сбор")
                .row("Собирать в радиусе", radius)
                .row("", stayAway)
                .row("", ignoreContested)
                .section("Полный трюм")
                .row("Когда трюм полон", cargo)
                .row("Полон при свободном месте ≤", cargoFullAt)
                .row("Продавать", Form.inline(sell))
                .note("Нечего продать — бот просто перестаёт брать груз. Промериум, сепром и осмий обычно берегут для улучшений. "
                        + "Через питомца цены на 30% выше.")
                .section("Ящики")
                .note("Ожидание — сколько стоять на ящике перед сбором.")
                .wide(Form.inline(add, remove), false)
                .wide(scroll, true)
                .build(true);
    }

    @Override
    public String title() {
        return "Сбор";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        Form.set(radius, c.collect().radius());
        stayAway.setSelected(c.collect().stayAwayFromEnemies());
        ignoreContested.setSelected(c.collect().ignoreContested());
        boxes.load(c.collect().boxes());
        int ci = java.util.Arrays.asList(CARGO).indexOf(c.collect().cargo());
        cargo.setSelectedIndex(Math.max(0, ci));
        Form.set(cargoFullAt, c.collect().cargoFullAt());
        for (int i = 0; i < ORES.length; i++) {
            sell[i].setSelected(c.collect().sellOres().contains(ORES[i]));
        }
        syncCargo();
    }

    private void syncCargo() {
        boolean selling = cargo.getSelectedIndex() >= 2;
        for (JCheckBox b : sell) {
            b.setEnabled(selling);
        }
        cargoFullAt.setEnabled(cargo.getSelectedIndex() >= 1);
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<Integer> ores = new ArrayList<>();
        for (int i = 0; i < ORES.length; i++) {
            if (sell[i].isSelected()) {
                ores.add(ORES[i]);
            }
        }
        return c.withCollect(new BotConfig.Collect(boxes.get(), Form.intOf(radius), stayAway.isSelected(),
                ignoreContested.isSelected(), CARGO[cargo.getSelectedIndex()], Form.intOf(cargoFullAt), ores));
    }

    /** {@code PIRATE_BOOTY — пиратский бути-ящик (зелёный), 5500 мс} for the picker. */
    static String label(orbit.config.BoxCatalog.Box b) {
        return b.type() + " — " + b.name() + (b.waitMs() > 0 ? ", " + b.waitMs() + " мс" : "");
    }

    /** Rows {type, collect, wait, priority}; shown with the box's name after its type. */
    private static final class Boxes extends AbstractTableModel {

        private static final String[] COLUMNS = {"Ящик", "Название", "Собирать", "Ожидание, мс", "Приоритет"};
        private final List<Object[]> rows = new ArrayList<>();

        void load(Map<String, BotConfig.BoxRule> m) {
            rows.clear();
            m.forEach((k, v) -> rows.add(new Object[] {k, v.collect(), v.waitMs(), v.priority()}));
            fireTableDataChanged();
        }

        Map<String, BotConfig.BoxRule> get() {
            Map<String, BotConfig.BoxRule> out = new LinkedHashMap<>();
            for (Object[] r : rows) {
                String name = String.valueOf(r[0]).strip();
                if (!name.isEmpty()) {
                    out.put(name, new BotConfig.BoxRule((Boolean) r[1], Math.max(0, (Integer) r[2]), (Integer) r[3]));
                }
            }
            return out;
        }

        /** A new box: collected, with the wait DarkBot users set for it (palladium 750 ms, booty 5 500 ms). */
        void add(String type) {
            if (has(type)) {
                return;
            }
            rows.add(new Object[] {type, orbit.config.BoxCatalog.collectable(type), orbit.config.BoxCatalog.waitMs(type), 0});
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        boolean has(String type) {
            return rows.stream().anyMatch(r -> type.equals(r[0]));
        }

        void remove(int r) {
            rows.remove(r);
            fireTableRowsDeleted(r, r);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c <= 1 ? String.class : c == 2 ? Boolean.class : Integer.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c != 1;
        }

        @Override
        public Object getValueAt(int r, int c) {
            if (c == 1) {
                String type = String.valueOf(rows.get(r)[0]);
                orbit.config.BoxCatalog.Box b = orbit.config.BoxCatalog.of(type);
                return b == null ? orbit.config.LootNames.of(type) : b.collectable() ? b.name() : b.name() + " — не собирается";
            }
            return rows.get(r)[c == 0 ? 0 : c - 1];
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 1) {
                return;
            }
            rows.get(r)[c == 0 ? 0 : c - 1] = v;
            fireTableCellUpdated(r, c);
        }
    }
}
