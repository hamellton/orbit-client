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

    private final JCheckBox enabled = new JCheckBox("Включить закупку");
    private final JSpinner keepCredits = Form.number(0, 2_000_000_000, 100_000);
    private final JSpinner keepUridium = Form.number(0, 2_000_000_000, 1_000);
    private final DefaultTableModel rules = new DefaultTableModel(new Object[] {"Что", "Если меньше", "Купить"}, 0) {
        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? String.class : Integer.class;
        }
    };
    private final JTable table = new JTable(rules);
    private final java.util.Map<String, JCheckBox> boosters = new java.util.LinkedHashMap<>();
    private final JSpinner boosterMinutes = Form.number(0, 600, 10);
    private final DefaultTableModel crafts = new DefaultTableModel(
            new Object[] {"Рецепт", "Предмет (что считать)", "Если меньше", "За раз"}, 0) {
        @Override
        public Class<?> getColumnClass(int c) {
            return c <= 1 ? String.class : Integer.class;
        }
    };
    private final JTable craftTable = new JTable(crafts);
    private final JComponent view;

    static List<ItemCatalog.Item> items() {
        List<ItemCatalog.Item> all = new ArrayList<>(ItemCatalog.LASERS);
        all.addAll(ItemCatalog.ROCKETS);
        all.addAll(ItemCatalog.LAUNCHER);
        all.addAll(ItemCatalog.SPECIAL); // EMP-01 for the flag USE_EMP (ISH-01 is not sold)
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
        javax.swing.JPanel boosterBoxes = new javax.swing.JPanel(new java.awt.GridLayout(0, 2, 12, 2));
        boosterBoxes.setOpaque(false);
        for (ItemCatalog.Item i : ItemCatalog.BOOSTERS) {
            JCheckBox box = new JCheckBox(i.label());
            boosters.put(i.id(), box);
            boosterBoxes.add(box);
        }
        JComboBox<String> recipeBox = new JComboBox<>(orbit.config.CraftCatalog.RECIPES.stream()
                .map(orbit.config.CraftCatalog.Recipe::label).toArray(String[]::new));
        recipeBox.setEditable(true); // any recipe id the game has
        craftTable.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(recipeBox));
        crafts.addTableModelListener(e -> {
            // a known recipe chosen: its item filled in
            if (e.getColumn() == 0 && e.getFirstRow() >= 0 && e.getFirstRow() < crafts.getRowCount()) {
                String item = orbit.config.CraftCatalog.item(orbit.config.CraftCatalog.id(
                        String.valueOf(crafts.getValueAt(e.getFirstRow(), 0))));
                if (!item.isEmpty() && !item.equals(crafts.getValueAt(e.getFirstRow(), 1))) {
                    crafts.setValueAt(item, e.getFirstRow(), 1);
                }
            }
        });
        JButton addCraft = new JButton("Добавить");
        addCraft.addActionListener(e -> {
            orbit.config.CraftCatalog.Recipe first = orbit.config.CraftCatalog.RECIPES.getFirst();
            crafts.addRow(new Object[] {first.label(), first.item(), 5, 50});
        });
        JButton removeCraft = new JButton("Удалить");
        removeCraft.addActionListener(e -> {
            int r = craftTable.getSelectedRow();
            if (r >= 0) {
                crafts.removeRow(r);
            }
        });
        JScrollPane craftScroll = new JScrollPane(craftTable);
        craftScroll.setPreferredSize(new java.awt.Dimension(620, 160));
        view = new Form()
                .section("Боеприпасы")
                .row("", enabled)
                .row("Не тратить кредиты ниже", keepCredits)
                .row("Не тратить уридий ниже", keepUridium)
                .note("Ниже «Если меньше» докупает «Купить», не трогая запас.")
                .note("До записи одной ручной покупки — только пишет в журнал.")
                .wide(Form.inline(add, remove), false)
                .wide(scroll, true)
                .section("Бустеры")
                .note("Отмеченные — за уридий (10 000 за шт.), при включённой закупке.")
                .row("Докупать, когда меньше, мин", boosterMinutes)
                .note("0 — когда закончился.")
                .wide(boosterBoxes, false)
                .section("Крафт")
                .note("Окно «Сборка» в игре: когда «Предмет» меньше «Если меньше», бот запускает рецепт на «За раз» штук и"
                        + " забирает готовое. «Если меньше» 0 — крафтит всегда, пока рецепт свободен и хватает ресурсов."
                        + " Рецепт — из списка или его id из игры (recipe_blueprint-…). Работает и без «Включить закупку».")
                .wide(Form.inline(addCraft, removeCraft), false)
                .wide(craftScroll, true)
                .build(true);
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
        boosters.forEach((code, box) -> box.setSelected(b.boosters().contains(code)));
        Form.set(boosterMinutes, b.boosterMinutes());
        rules.setRowCount(0);
        for (BotConfig.BuyRule r : b.items()) {
            rules.addRow(new Object[] {ItemCatalog.label(r.item()), r.below(), r.amount()});
        }
        crafts.setRowCount(0);
        for (BotConfig.CraftRule r : b.crafts()) {
            crafts.addRow(new Object[] {orbit.config.CraftCatalog.label(r.recipe()), r.item() == null ? "" : r.item(),
                    r.below(), r.amount()});
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
        List<String> chosen = boosters.entrySet().stream().filter(e -> e.getValue().isSelected())
                .map(java.util.Map.Entry::getKey).toList();
        if (craftTable.isEditing()) {
            craftTable.getCellEditor().stopCellEditing();
        }
        List<BotConfig.CraftRule> craftList = new ArrayList<>();
        for (int r = 0; r < crafts.getRowCount(); r++) {
            String recipe = orbit.config.CraftCatalog.id(String.valueOf(crafts.getValueAt(r, 0)).strip());
            if (recipe.isBlank()) {
                continue;
            }
            Object item = crafts.getValueAt(r, 1);
            craftList.add(new BotConfig.CraftRule(recipe, item == null ? "" : item.toString().strip(),
                    Math.max(0, (Integer) crafts.getValueAt(r, 2)), Math.max(1, (Integer) crafts.getValueAt(r, 3))));
        }
        BotConfig.Buy b = new BotConfig.Buy(enabled.isSelected(), list, Form.intOf(keepCredits), Form.intOf(keepUridium),
                chosen, Form.intOf(boosterMinutes), craftList);
        return b.equals(c.buy()) ? c : c.withBuy(b);
    }
}
