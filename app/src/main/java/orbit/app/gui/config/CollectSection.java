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

    CollectSection() {
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        JButton add = new JButton("Добавить");
        add.addActionListener(e -> {
            String type = JOptionPane.showInputDialog(table, "Тип ящика, напр. BONUS_BOX:");
            if (type != null && !type.isBlank()) {
                boxes.add(type.strip());
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
        view = new Form()
                .section("Сбор")
                .row("Собирать в радиусе", radius)
                .row("", stayAway)
                .row("", ignoreContested)
                .section("Ящики")
                .note("Ожидание — сколько стоять на ящике перед сбором.")
                .wide(Form.inline(add, remove), false)
                .wide(scroll, true)
                .build(false);
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
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        return c.withCollect(new BotConfig.Collect(boxes.get(), Form.intOf(radius), stayAway.isSelected(),
                ignoreContested.isSelected()));
    }

    private static final class Boxes extends AbstractTableModel {

        private static final String[] COLUMNS = {"Ящик", "Собирать", "Ожидание, мс", "Приоритет"};
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

        void add(String type) {
            rows.add(new Object[] {type, true, 0, 0});
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
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
            return c == 0 ? String.class : c == 1 ? Boolean.class : Integer.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return true;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            rows.get(r)[c] = v;
            fireTableCellUpdated(r, c);
        }
    }
}
