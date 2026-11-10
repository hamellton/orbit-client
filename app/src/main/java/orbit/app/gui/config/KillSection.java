package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.RowFilter;
import javax.swing.table.TableRowSorter;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;
import orbit.world.maps.MapCatalog;

/** «Атака» (KILL): ammunition and the NPC list. */
final class KillSection implements Section {

    private final ItemBox ammo = new ItemBox(ItemCatalog.LASERS, true);
    private final JCheckBox useSab = new JCheckBox("Щитовые патроны (SAB), когда свой щит ниже");
    private final ItemBox sab = new ItemBox(ItemCatalog.LASERS, false);
    private final JSpinner sabBelow = Form.percent();
    private final JSpinner sabNpcShield = Form.number(0, 10_000_000, 1000);
    private final JCheckBox useRsb = new JCheckBox("Быстрый залп (RSB), когда готов");
    private final ItemBox rsb = new ItemBox(ItemCatalog.LASERS, false);
    private final JCheckBox groupNpcs = new JCheckBox("Бить стоящих рядом NPC подряд");
    static final java.util.List<ItemCatalog.Item> ROCKET_CHOICES;

    static {
        java.util.List<ItemCatalog.Item> r = new java.util.ArrayList<>();
        r.add(new ItemCatalog.Item(BotConfig.Loot.NO_ROCKETS, "Без ракет"));
        r.addAll(ItemCatalog.ROCKETS);
        ROCKET_CHOICES = java.util.List.copyOf(r);
    }

    private final ItemBox rocket = new ItemBox(ROCKET_CHOICES, true);
    private final JCheckBox fireRockets = new JCheckBox("Пускать ракеты самому (если на корабле нет ракетного CPU)");
    private final JSpinner ignoreFarther = Form.number(0, 50_000, 500);
    private final NpcTable npcs;
    private final JTable table;
    private final JComponent view;
    private BotConfig.Loot loaded = BotConfig.Loot.DEFAULT;

    KillSection(MapCatalog maps) {
        npcs = new NpcTable(maps);
        table = new JTable(npcs);
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(8).setPreferredWidth(200);
        table.getColumnModel().getColumn(6).setCellEditor(new javax.swing.DefaultCellEditor(
                new javax.swing.JComboBox<>(NpcTable.rocketChoices())));
        table.getColumnModel().getColumn(5).setCellEditor(new javax.swing.DefaultCellEditor(
                new javax.swing.JComboBox<>(NpcTable.ammoChoices())));
        table.getTableHeader().setToolTipText("Приоритет: меньше — раньше. Карты: «3-6, 2-6» или «все».");

        JTextField search = new JTextField(16);
        search.putClientProperty("JTextField.placeholderText", "поиск NPC");
        TableRowSorter<NpcTable> sorter = new TableRowSorter<>(npcs);
        table.setRowSorter(sorter);
        search.getDocument().addDocumentListener(DocumentChange.of(() -> {
            String t = search.getText().strip();
            sorter.setRowFilter(t.isEmpty() ? null : RowFilter.regexFilter("(?iu)" + java.util.regex.Pattern.quote(t), 0));
        }));
        JButton add = new JButton("Добавить");
        add.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(table, "Имя NPC, как в игре (например -=[ Streuner ]=-):");
            if (name != null && !name.isBlank()) {
                int row = table.convertRowIndexToView(npcs.add(name.strip(), 0));
                table.setRowSelectionInterval(row, row);
                table.scrollRectToVisible(table.getCellRect(row, 0, true));
            }
        });
        JButton remove = new JButton("Удалить");
        remove.addActionListener(e -> {
            int[] sel = table.getSelectedRows();
            for (int i = 0; i < sel.length; i++) {
                sel[i] = table.convertRowIndexToModel(sel[i]);
            }
            npcs.remove(sel);
        });
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.add(Form.inline(search, add, remove), BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new java.awt.Dimension(700, 320));
        list.add(scroll, BorderLayout.CENTER);

        useSab.addActionListener(e -> sync());
        useRsb.addActionListener(e -> sync());
        view = new Form()
                .section("Патроны")
                .row("Лазерные патроны", ammo)
                .row("", Form.inline(useSab, sabBelow, new javax.swing.JLabel("%")))
                .row("Патроны SAB", sab)
                .row("Щит NPC не меньше", sabNpcShield)
                .row("", useRsb)
                .row("Патроны RSB", rsb)
                .section("Ракеты")
                .row("Ракеты", rocket)
                .row("", fireRockets)
                .note("С ракетным CPU сервер сам пускает выбранные ракеты, пока идёт атака: бот только держит нужный "
                        + "тип. Свой тип для отдельного NPC — колонка «Ракеты» в списке.")
                .section("Цели")
                .row("", groupNpcs)
                .row("Не замечать NPC дальше", ignoreFarther)
                .section("Список NPC")
                .note("Бить — атаковать ли; приоритет — меньше значит раньше (−100 первым); радиус — на какой "
                        + "дистанции держаться; SAB — включать щитовые патроны на этого NPC (по порогам выше); "
                        + "патроны — свои патроны для этого NPC («общие» — как выше); карты — где действует строка («все» — везде); флаги: IGNORE_OWNERSHIP "
                        + "(бить, даже если чужой), IGNORE_ATTACKED, PASSIVE (не убегать от него), AGGRESSIVE_FOLLOW (преследовать), NO_CIRCLE, PET_LOCATOR, KAMIKAZE.")
                .wide(list, true)
                .build(false);
    }

    private void sync() {
        sab.setEnabled(useSab.isSelected());
        sabBelow.setEnabled(useSab.isSelected());
        sabNpcShield.setEnabled(useSab.isSelected());
        rsb.setEnabled(useRsb.isSelected());
    }

    @Override
    public String title() {
        return "Атака";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        BotConfig.Loot l = c.loot();
        loaded = l;
        ammo.set(l.ammo());
        useSab.setSelected(l.sab() != null);
        sab.set(l.sab() != null ? l.sab() : "ammunition_laser_sab-50");
        Form.setFraction(sabBelow, l.sab() != null ? l.sabBelow() : 0.5);
        Form.set(sabNpcShield, l.sabNpcShieldMin());
        useRsb.setSelected(l.rsb() != null);
        rsb.set(l.rsb() != null ? l.rsb() : "ammunition_laser_rsb-75");
        groupNpcs.setSelected(l.groupNpcs());
        rocket.set(l.rocket());
        fireRockets.setSelected(l.fireRockets());
        Form.set(ignoreFarther, l.ignoreFartherThan());
        npcs.load(c.npcs());
        sync();
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        BotConfig.Loot l = new BotConfig.Loot(ammo.get(), useSab.isSelected() ? sab.get() : null,
                useSab.isSelected() ? Form.fraction(sabBelow) : loaded.sabBelow(), Form.intOf(sabNpcShield),
                useRsb.isSelected() ? rsb.get() : null, groupNpcs.isSelected(), Form.intOf(ignoreFarther), rocket.get(),
                fireRockets.isSelected());
        Map<String, BotConfig.NpcRule> list = new TreeMap<>(npcs.get());
        return c.withLoot(l).withNpcs(list);
    }
}
