package orbit.app.gui.config;

import java.util.ArrayList;
import java.util.List;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;

/** «Закупка»: which ammunition and rockets to buy when they run low, and what money to keep. */
final class BuySection implements Section {

    private final JCheckBox enabled = new JCheckBox("Докупать боеприпасы, когда заканчиваются");
    private final JSpinner keepCredits = Form.number(0, 2_000_000_000, 100_000);
    private final JSpinner keepUridium = Form.number(0, 2_000_000_000, 1_000);
    private final DefaultTableModel rules = new DefaultTableModel(new Object[] {"Что", "Если меньше", "Купить"}, 0) {
        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? String.class : Integer.class;
        }
    };
    private final JTable table = new JTable(rules);
    private final JComponent view;

    static List<ItemCatalog.Item> items() {
        List<ItemCatalog.Item> all = new ArrayList<>(ItemCatalog.LASERS);
        all.addAll(ItemCatalog.ROCKETS);
        all.addAll(ItemCatalog.LAUNCHER);
        return all;
    }

    BuySection() {
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(
                new JComboBox<>(items().stream().map(ItemCatalog.Item::label).toArray(String[]::new))));
        JButton add = new JButton("Добавить");
        add.addActionListener(e -> rules.addRow(new Object[] {ItemCatalog.LASERS.getFirst().label(), 10_000, 50_000}));
        JButton remove = new JButton("Удалить");
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                rules.removeRow(r);
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new java.awt.Dimension(520, 220));
        view = new Form()
                .section("Закупка боеприпасов")
                .row("", enabled)
                .row("Не тратить кредиты ниже", keepCredits)
                .row("Не тратить уридиум ниже", keepUridium)
                .note("Когда патронов или ракет становится меньше порога, бот докупает указанное количество, если "
                        + "хватает денег сверх неприкосновенного запаса. Сама покупка подключается после записи одной "
                        + "ручной покупки в магазине; до этого бот только пишет в журнал, что пора докупить.")
                .wide(Form.inline(add, remove), false)
                .wide(scroll, true)
                .build(false);
    }

    @Override
    public String title() {
        return "Закупка";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        BotConfig.Buy b = c.buy();
        enabled.setSelected(b.enabled());
        Form.set(keepCredits, (int) Math.min(Integer.MAX_VALUE, b.keepCredits()));
        Form.set(keepUridium, (int) Math.min(Integer.MAX_VALUE, b.keepUridium()));
        rules.setRowCount(0);
        for (BotConfig.BuyRule r : b.items()) {
            rules.addRow(new Object[] {ItemCatalog.label(r.item()), r.below(), r.amount()});
        }
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        List<BotConfig.BuyRule> list = new ArrayList<>();
        for (int r = 0; r < rules.getRowCount(); r++) {
            String label = String.valueOf(rules.getValueAt(r, 0));
            String id = items().stream().filter(i -> i.label().equals(label)).map(ItemCatalog.Item::id).findFirst()
                    .orElse(label);
            int below = Math.max(0, (Integer) rules.getValueAt(r, 1));
            int amount = Math.max(0, (Integer) rules.getValueAt(r, 2));
            if (amount > 0) {
                list.add(new BotConfig.BuyRule(id, below, amount));
            }
        }
        BotConfig.Buy b = new BotConfig.Buy(enabled.isSelected(), list, Form.intOf(keepCredits), Form.intOf(keepUridium));
        return b.equals(c.buy()) ? c : c.withBuy(b);
    }
}
