package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import orbit.app.WorldFeed;
import orbit.world.WorldSnapshot;

/**
 * What the account has, in a window of its own (the user, 2026-11-13: «кнопка — посмотреть ангар»): the laser
 * ammunition, the rockets and mines, the resources and keys as the game counts them now, and the ships of the hangar
 * as the site listed them at the last login. Refreshed each second while open; one window per account.
 */
final class HangarWindow {

    private static final Map<String, JFrame> OPEN = new ConcurrentHashMap<>();
    static final List<String> ROCKETS = List.of("ammunition_rocket_", "ammunition_rocketlauncher_", "ammunition_mine_",
            "ammunition_specialammo_");

    private HangarWindow() {
    }

    /** Opens (or brings forward) the account's hangar window. */
    static void open(WorldFeed feed) {
        String key = feed.account() == null ? feed.title() : feed.account();
        JFrame shown = OPEN.get(key);
        if (shown != null) {
            shown.toFront();
            return;
        }
        JFrame f = new JFrame("Ангар — " + feed.title());
        Rows ammo = new Rows("Патроны", "Количество");
        Rows rockets = new Rows("Ракеты и мины", "Количество");
        Rows resources = new Rows("Ресурс", "Количество");
        Rows ships = new Rows("Корабль", "Дизайн");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Патроны", table(ammo));
        tabs.addTab("Ракеты и мины", table(rockets));
        tabs.addTab("Ресурсы и ключи", table(resources));
        tabs.addTab("Корабли", table(ships));

        JTextField search = new JTextField(18);
        search.putClientProperty("JTextField.placeholderText", "поиск");
        JLabel note = new JLabel();
        note.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(search);
        top.add(note);

        Runnable refresh = () -> {
            WorldSnapshot w = feed.snapshot();
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            ammo.set(filter(items(w, List.of("ammunition_laser_")), q));
            rockets.set(filter(items(w, ROCKETS), q));
            resources.set(filter(resources(w), q));
            ships.set(filter(ships(feed.account()), q));
            note.setText(w.hero().id() == 0 ? "не в игре — числа с последнего входа появятся после входа" : "по данным игры, сейчас");
        };
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refresh.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refresh.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refresh.run();
            }
        });
        Timer timer = new Timer(1_000, e -> refresh.run());
        refresh.run();
        timer.start();

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        f.setContentPane(root);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
                OPEN.remove(key);
            }
        });
        f.setSize(new Dimension(520, 560));
        f.setLocationByPlatform(true);
        OPEN.put(key, f);
        f.setVisible(true);
    }

    /** The game's items whose id starts so: name → amount. */
    static Map<String, String> items(WorldSnapshot w, List<String> prefixes) {
        Map<String, String> out = new TreeMap<>();
        w.items().forEach((id, item) -> {
            if (prefixes.stream().anyMatch(id::startsWith)) {
                out.put(orbit.config.ItemCatalog.label(id), orbit.app.SessionSummary.number(item.counter()));
            }
        });
        return out;
    }

    /** The ore in the hold, the other resources and the keys: name → amount. */
    static Map<String, String> resources(WorldSnapshot w) {
        Map<String, String> out = new LinkedHashMap<>();
        new TreeMap<>(w.inventory().ores()).forEach((type, n) -> {
            if (type >= 0 && n > 0) {
                out.put(orbit.config.LootNames.ore(type), orbit.app.SessionSummary.number(n));
            }
        });
        new TreeMap<>(w.inventory().resources()).forEach((id, n) -> {
            if (n > 0) {
                out.put(orbit.app.SessionSummary.displayName(id), orbit.app.SessionSummary.number(n));
            }
        });
        new TreeMap<>(w.inventory().bootyKeys()).forEach((id, n) -> {
            if (n > 0) {
                out.put("ключ " + id, orbit.app.SessionSummary.number(n));
            }
        });
        return out;
    }

    /** The ships of the account's hangar as the site listed them at the last login: name → design. */
    static Map<String, String> ships(String account) {
        Map<String, String> out = new LinkedHashMap<>();
        if (account == null) {
            return out;
        }
        try {
            var entry = orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE).account(account);
            if (entry != null) {
                int n = 0;
                for (var s : entry.ships()) {
                    // one row per ship; a second of the same name gets its number
                    String name = s.shipName();
                    out.put(out.containsKey(name) ? name + " (" + (++n + 1) + ")" : name, s.designName());
                }
            }
        } catch (java.io.IOException e) {
            // no list yet
        }
        return out;
    }

    private static Map<String, String> filter(Map<String, String> rows, String q) {
        if (q.isEmpty()) {
            return rows;
        }
        Map<String, String> out = new LinkedHashMap<>();
        rows.forEach((k, v) -> {
            if (k.toLowerCase(Locale.ROOT).contains(q) || v.toLowerCase(Locale.ROOT).contains(q)) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static JScrollPane table(Rows model) {
        JTable t = new JTable(model);
        t.setFillsViewportHeight(true);
        t.setAutoCreateRowSorter(true);
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        t.getColumnModel().getColumn(1).setCellRenderer(right);
        t.getColumnModel().getColumn(0).setPreferredWidth(320);
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(BorderFactory.createEmptyBorder());
        return sp;
    }

    /** Two columns: a name and its value. */
    static final class Rows extends AbstractTableModel {
        private final String[] columns;
        private List<String[]> rows = List.of();

        Rows(String name, String value) {
            this.columns = new String[] {name, value};
        }

        void set(Map<String, String> data) {
            List<String[]> next = new ArrayList<>();
            data.forEach((k, v) -> next.add(new String[] {k, v}));
            boolean same = next.size() == rows.size();
            for (int i = 0; same && i < next.size(); i++) {
                same = java.util.Arrays.equals(next.get(i), rows.get(i));
            }
            if (!same) {
                rows = next;
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 2;
        }

        @Override
        public String getColumnName(int c) {
            return columns[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }
    }
}
