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
        java.util.List<ItemCatalog.Item> l = new java.util.ArrayList<>();
        l.add(new ItemCatalog.Item(BotConfig.Loot.NO_ROCKETS, "Без ракет"));
        l.addAll(ItemCatalog.LAUNCHER);
        LAUNCHER_CHOICES = java.util.List.copyOf(l);
    }

    static final java.util.List<ItemCatalog.Item> LAUNCHER_CHOICES;
    private final ItemBox launcher = new ItemBox(LAUNCHER_CHOICES, true);
    private final JCheckBox fireLauncher = new JCheckBox("Залп сам (без CPU ракетомёта)");

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
        // narrow columns for ticks and numbers; the names and the flags take the rest
        int[] widths = {200, 44, 78, 62, 44, 80, 80, 80, 90, 190};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        // on a wide screen the short columns stop growing: the spare width goes to names, maps and flags
        int[] max = {0, 56, 110, 100, 56, 150, 150, 150};
        for (int i = 1; i < max.length; i++) {
            table.getColumnModel().getColumn(i).setMaxWidth(max[i]);
        }
        table.getColumnModel().getColumn(6).setCellEditor(new javax.swing.DefaultCellEditor(
                new javax.swing.JComboBox<>(NpcTable.rocketChoices())));
        table.getColumnModel().getColumn(7).setCellEditor(new javax.swing.DefaultCellEditor(
                new javax.swing.JComboBox<>(NpcTable.launcherChoices())));
        table.getColumnModel().getColumn(9).setCellEditor(new FlagsEditor()); // ticks from a list, not typing
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
            // every NPC of the game by name (the bot knows «Protegit» also as «-=[ Protegit ]=-»), or one typed in
            java.util.List<String> names = orbit.world.maps.NpcCatalog.bundled().all().stream()
                    .map(orbit.world.maps.NpcCatalog.Npc::name).distinct().toList();
            String name = NpcPicker.pick(table, "Добавить NPC", names, null, true);
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
        // the list takes all the height the settings leave; it scrolls by itself, the tab does not
        javax.swing.JLabel title = new javax.swing.JLabel("Список NPC");
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD));
        javax.swing.JLabel help = new javax.swing.JLabel("ⓘ");
        help.setForeground(java.awt.Color.GRAY);
        help.setToolTipText("<html>Приоритет: меньше — раньше (−100 первым). Карты: «все» — везде.<br>"
                + "Патроны «общие» — как выбрано выше.<br>Флаги — клик по ячейке, галочки из списка.</html>");
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.add(Form.bar(title, help), BorderLayout.WEST);
        bar.add(Form.bar(search, add, remove), BorderLayout.EAST);
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setBorder(javax.swing.BorderFactory.createEmptyBorder(4, 12, 10, 12));
        list.add(bar, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader()); // also without a screen (snapshots)
        scroll.setPreferredSize(new java.awt.Dimension(600, 160));
        scroll.setMinimumSize(new java.awt.Dimension(200, 200)); // about six rows at least
        list.add(scroll, BorderLayout.CENTER);

        useSab.addActionListener(e -> sync());
        useRsb.addActionListener(e -> sync());
        fireRockets.setToolTipText("С ракетным CPU бот только держит тип; тип для NPC — в списке");
        fireLauncher.setToolTipText("С CPU ракетомёта (RLLB-X) он стреляет сам; бот держит тип ракет");
        circle.setToolTipText("Часть выстрелов мимо; не кружит с NO_CIRCLE и на споте куба");
        groupNpcs.setToolTipText("Сначала добивать NPC рядом, а не лететь к дальним");
        // the settings in two top-aligned columns: ammunition | rockets and targets; one under the other when narrow
        JComponent ammoColumn = new Form()
                .section("Патроны")
                .row("Основные", ammo)
                .row("Запасные", Form.inline(fallback1, fallback2))
                .row("", Form.inline(useSab, sabBelow, new javax.swing.JLabel("%")))
                .row("Патроны SAB", sab)
                .row("Щит NPC от", sabNpcShield)
                .row("", useRsb)
                .row("Патроны RSB", rsb)
                .build(false);
        JComponent otherColumn = new Form()
                .section("Ракеты")
                .row("Тип", rocket)
                .row("", fireRockets)
                .row("Ракетомёт", launcher)
                .row("", fireLauncher)
                .section("Цели")
                .row("", circle)
                .row("", groupNpcs)
                .row("Не видеть дальше", ignoreFarther)
                .build(false);
        JPanel settings = new JPanel(new Columns());
        settings.add(ammoColumn);
        settings.add(otherColumn);
        JPanel page = new JPanel(new BorderLayout());
        page.add(settings, BorderLayout.NORTH);
        page.add(list, BorderLayout.CENTER);
        view = Fit.of(page); // a small window scrolls the tab; the list never shrinks below a few rows
    }

    /** Two components side by side, top-aligned, when they fit the width; otherwise one under the other. */
    static final class Columns implements java.awt.LayoutManager {
        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Component a = parent.getComponent(0);
            java.awt.Component b = parent.getComponent(1);
            java.awt.Dimension pa = a.getPreferredSize();
            java.awt.Dimension pb = b.getPreferredSize();
            if (pa.width + pb.width <= parent.getWidth()) {
                a.setBounds(0, 0, pa.width, pa.height);
                b.setBounds(pa.width, 0, parent.getWidth() - pa.width, pb.height);
            } else {
                a.setBounds(0, 0, parent.getWidth(), pa.height);
                b.setBounds(0, pa.height, parent.getWidth(), pb.height);
            }
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Dimension pa = parent.getComponent(0).getPreferredSize();
            java.awt.Dimension pb = parent.getComponent(1).getPreferredSize();
            int width = parent.getWidth();
            boolean side = width == 0 || pa.width + pb.width <= width;
            return side ? new java.awt.Dimension(pa.width + pb.width, Math.max(pa.height, pb.height))
                    : new java.awt.Dimension(Math.max(pa.width, pb.width), pa.height + pb.height);
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
            // as narrow as the wider column; as high as the columns stand now (side by side on a wide tab — counting
            // them one under the other there kept the tab «too small», so the list never got the spare height)
            java.awt.Dimension ma = parent.getComponent(0).getMinimumSize();
            java.awt.Dimension mb = parent.getComponent(1).getMinimumSize();
            return new java.awt.Dimension(Math.max(ma.width, mb.width), preferredLayoutSize(parent).height);
        }

        @Override
        public void addLayoutComponent(String name, java.awt.Component comp) {
        }

        @Override
        public void removeLayoutComponent(java.awt.Component comp) {
        }
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
        launcher.set(l.launcher());
        fireLauncher.setSelected(l.fireLauncher());
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
                fireRockets.isSelected(), circle.isSelected(), fallbacks(), launcher.get(), fireLauncher.isSelected());
        Map<String, BotConfig.NpcRule> list = new TreeMap<>(npcs.get());
        return c.withLoot(l).withNpcs(list);
    }
}
