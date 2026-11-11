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
    private final JCheckBox useSab = new JCheckBox("SAB при своём щите ниже");
    private final ItemBox sab = new ItemBox(ItemCatalog.LASERS, false);
    private final JSpinner sabBelow = Form.percent();
    private final JSpinner sabNpcShield = Form.number(0, 10_000_000, 1000);
    private final JCheckBox useRsb = new JCheckBox("RSB, когда готов");
    private final ItemBox rsb = new ItemBox(ItemCatalog.LASERS, false);
    private final JCheckBox groupNpcs = new JCheckBox("Бить соседних NPC подряд");
    static final java.util.List<ItemCatalog.Item> ROCKET_CHOICES;

    static {
        java.util.List<ItemCatalog.Item> r = new java.util.ArrayList<>();
        r.add(new ItemCatalog.Item(BotConfig.Loot.NO_ROCKETS, "Без ракет"));
        r.addAll(ItemCatalog.ROCKETS);
        ROCKET_CHOICES = java.util.List.copyOf(r);
    }

    private final ItemBox rocket = new ItemBox(ROCKET_CHOICES, true);
    private final ItemBox fallback1 = new ItemBox(ItemCatalog.LASERS, true);
    private final ItemBox fallback2 = new ItemBox(ItemCatalog.LASERS, true);
    private final JCheckBox circle = new JCheckBox("Кружить вокруг цели");
    private final JCheckBox fireRockets = new JCheckBox("Пускать ракеты сам (без ракетного CPU)");
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
            String name = JOptionPane.showInputDialog(table, "Имя NPC, напр. -=[ Streuner ]=-:");
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
                .row("Основные", ammo)
                .row("Запасные", Form.inline(fallback1, fallback2))
                .row("", Form.inline(useSab, sabBelow, new javax.swing.JLabel("%")))
                .row("Патроны SAB", sab)
                .row("Щит NPC не меньше", sabNpcShield)
                .row("", useRsb)
                .row("Патроны RSB", rsb)
                .section("Ракеты")
                .row("Ракеты", rocket)
                .row("", fireRockets)
                .note("С ракетным CPU бот лишь держит тип. Тип для NPC — в списке.")
                .section("Цели")
                .row("", circle)
                .note("Часть выстрелов мимо. Не кружит с NO_CIRCLE и на споте куба.")
                .row("", groupNpcs)
                .row("Не замечать NPC дальше", ignoreFarther)
                .section("Список NPC")
                .note("Приоритет: меньше — раньше (−100 первым). «общие» — патроны выше. Карты: «все» — везде.")
                .note("Флаги: IGNORE_OWNERSHIP (бить чужих), IGNORE_ATTACKED, PASSIVE (не убегать), "
                        + "AGGRESSIVE_FOLLOW (догонять), NO_CIRCLE, PET_LOCATOR, KAMIKAZE.")
                .wide(list, true)
                .build(false);
    }

    private java.util.List<String> fallbacks() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (ItemBox b : new ItemBox[] {fallback1, fallback2}) {
            if (b.get() != null && !out.contains(b.get())) {
                out.add(b.get());
            }
        }
        return out;
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
        fallback1.set(l.fallback().size() > 0 ? l.fallback().get(0) : null);
        fallback2.set(l.fallback().size() > 1 ? l.fallback().get(1) : null);
        circle.setSelected(l.circle());
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
                fireRockets.isSelected(), circle.isSelected(), fallbacks());
        Map<String, BotConfig.NpcRule> list = new TreeMap<>(npcs.get());
        return c.withLoot(l).withNpcs(list);
    }
}
