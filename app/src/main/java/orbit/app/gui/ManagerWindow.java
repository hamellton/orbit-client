package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import orbit.app.WorldFeed;
import orbit.auth.proxy.ProxyRoute;
import orbit.config.AccountSettings;
import orbit.secrets.Account;
import orbit.secrets.ProxySpec;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;
import orbit.world.WorldSnapshot;

/**
 * The account manager (like AstraBot's account list): every account of the vault with its server, proxy,
 * config, timetable, state and session results; add, edit, remove, proxy check; start and stop each or
 * all without restarting the program. A started account gets its tab (map, cards, log), the All tab shows them
 * side by side. Closing the window logs every running account out at a portal first.
 */
public final class ManagerWindow {

    private static final Color BAD = new Color(0xff6b5b);
    private static final Color WAIT = new Color(0xf59e0b);
    /** Columns of the account table. */
    static final int C_PICK = 0, C_NAME = 1, C_GROUP = 2, C_LOGIN = 3, C_SERVER = 4, C_PROXY = 5, C_CONFIG = 6, C_MODULE = 7,
            C_GOAL = 8, C_SCHEDULE = 9, C_STATE = 10, C_SESSION = 11, C_RESULTS = 12;

    private final Vault vault;
    private final Function<String, ManagedAccount> factory;
    private final Map<String, ManagedAccount> managed = new LinkedHashMap<>();
    /** Made in {@link #show}: none in tests and snapshots (no screen there). */
    private JFrame frame;
    final JTabbedPane pane = new JTabbedPane();
    final Model model = new Model();
    private final JTable table = new JTable(model);
    private final javax.swing.table.TableRowSorter<Model> sorter = new javax.swing.table.TableRowSorter<>(model);
    private final JLabel hint = new JLabel(" ");
    /** Groups, pinned accounts and start sets (docs/PLAN.md «Менеджер: группы»). */
    final ManagerGroups groups = ManagerGroups.openDefault();
    /** The accounts' tabs by groups (after «Аккаунты»), the same as in the bot's window. */
    final GroupedTabs grouped;
    private final javax.swing.JTextField search = new javax.swing.JTextField(16);
    private final javax.swing.JComboBox<String> groupFilter = new javax.swing.JComboBox<>();
    private final javax.swing.JComboBox<String> stateFilter = new javax.swing.JComboBox<>(
            new String[] {"Все", "Запущенные", "Остановленные", "С проблемами"});
    private final javax.swing.JComboBox<String> serverFilter = new javax.swing.JComboBox<>();
    private final javax.swing.JComboBox<String> moduleFilter = new javax.swing.JComboBox<>();
    static final String ANY = "Все";
    static final String NO_GROUP = "Без группы";
    /** The config → module names, read again every half minute (the list refreshes each second). */
    private final Map<String, String> moduleOf = new java.util.HashMap<>();
    private long modulesAt;
    /**
     * Accounts left out of the start-ticked button (unticked), kept in {@code local/manager.properties}; new accounts
     * are ticked. Another file with {@code -Dorbit.manager.file=…}.
     */
    java.nio.file.Path picksFile = java.nio.file.Path.of(System.getProperty("orbit.manager.file", "local/manager.properties"));

    ManagerWindow(Vault vault, Function<String, ManagedAccount> factory) {
        this.vault = vault;
        this.factory = factory;
        orbit.app.gui.config.AccountNames.source(() -> new ArrayList<>(vault.accounts().keySet()));
        model.onPick = this::savePicks;
        pane.putClientProperty("JTabbedPane.tabType", "card");
        pane.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        pane.addTab("Аккаунты", accountsPage());
        grouped = new GroupedTabs(pane, 1, groups, true);
        grouped.groupActions = (g, start) -> {
            if (start) {
                startGroupNamed(g);
            } else {
                stopGroupNamed(g);
            }
        };
        pane.putClientProperty("JTabbedPane.tabCloseCallback",
                (java.util.function.BiConsumer<JTabbedPane, Integer>) (p, index) -> closeTab(index));
    }

    /** The window's content drawn into an image without showing it (to check the look). */
    public static java.awt.image.BufferedImage snapshot(Vault vault, Function<String, ManagedAccount> factory,
                                                        List<String> start, int select, int width, int height) {
        Theme.setup();
        ManagerWindow w = new ManagerWindow(vault, factory);
        w.refresh();
        start.forEach(n -> w.startOne(n, 0, false));
        w.pane.setSelectedIndex(Math.min(select, w.pane.getTabCount() - 1));
        return w.paint(width, height);
    }

    java.awt.image.BufferedImage paint(int width, int height) {
        pane.setSize(width, height);
        for (int pass = 0; pass < 2; pass++) {
            layout(pane);
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            pane.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) {
            for (Component child : k.getComponents()) {
                layout(child);
            }
        }
    }

    /**
     * Opens the window on the Swing thread.
     *
     * @param factory makes the account runner of a vault name (once per name)
     * @param onClosed runs when the window is gone and every account has stopped
     */
    public static void open(Vault vault, Function<String, ManagedAccount> factory, Runnable onClosed) {
        open(vault, factory, onClosed, List.of());
    }

    /** @param startNow accounts to start as soon as the window is up, a few seconds apart (unknown names are told) */
    public static void open(Vault vault, Function<String, ManagedAccount> factory, Runnable onClosed,
                            List<String> startNow) {
        SwingUtilities.invokeLater(() -> {
            Theme.setup();
            ManagerWindow w = new ManagerWindow(vault, factory);
            w.show(onClosed);
            w.startNow(startNow);
        });
    }

    /**
     * The accounts given at the start, logins spread out as the buttons do (15–45 s apart, the user's wish 2026-11-27: 5 s
     * apart is no person); the ones without a saved site session (a password login) last — a few password logins in a row
     * from one address bring the site's captcha.
     */
    private void startNow(List<String> names) {
        java.util.Random random = new java.util.Random();
        List<String> order = new ArrayList<>(names);
        order.sort(java.util.Comparator.comparing(n -> vault.accounts().containsKey(n) && vault.get(n).dosid() == null));
        long delay = 0;
        for (String n : order) {
            if (!model.names.contains(n)) {
                org.slf4j.LoggerFactory.getLogger(ManagerWindow.class).warn("Запуск при старте: аккаунта «{}» нет", n);
                continue;
            }
            String why = startOne(n, delay, false);
            org.slf4j.LoggerFactory.getLogger(ManagerWindow.class).info("Запуск при старте: «{}» через {} с — {}", n,
                    delay / 1000, why == null ? "запущен" : why);
            if (why == null) {
                delay += 15_000 + random.nextInt(30_000);
            }
        }
    }

    private void show(Runnable onClosed) {
        frame = new JFrame("Orbit");
        frame.setContentPane(pane);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                close(onClosed);
            }
        });
        new Timer(1_000, e -> refresh()).start();
        MinimapBackgrounds.onLoaded(() -> SwingUtilities.invokeLater(frame::repaint));
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        frame.setMinimumSize(new Dimension(640, 360));
        frame.setSize(Math.min(1440, (int) (screen.width * 0.85)), Math.min(920, (int) (screen.height * 0.85)));
        frame.setLocationRelativeTo(null);
        refresh();
        frame.setVisible(true);
        fitAtQuietMoments();
        if (model.names.isEmpty()) {
            SwingUtilities.invokeLater(this::add);
        }
        new Timer(1_000, e -> refreshTitle()).start();
    }

    // ---- the accounts page ----

    private JComponent accountsPage() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 8);
        table.setRowSorter(sorter);
        sorter.setSortable(C_PICK, false);
        sorter.setRowFilter(new javax.swing.RowFilter<Model, Integer>() {
            @Override
            public boolean include(Entry<? extends Model, ? extends Integer> e) {
                return shows(e.getIdentifier());
            }
        });
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new Renderer());
        loadPicks();
        int[] widths = {28, 110, 110, 110, 55, 180, 130, 90, 230, 140, 200, 65, 200};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(0).setMaxWidth(36);
        table.getTableHeader().setToolTipText("Отмеченные запускает «Запустить отмеченные»; щелчок по ✓ — отметить все или снять");
        table.getTableHeader().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (table.columnAtPoint(e.getPoint()) == 0) {
                    model.pickAll(!model.allPicked());
                }
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && selected() != null) {
                    if (grouped.views.containsKey(selected())) {
                        openAccount(selected());
                    } else {
                        edit();
                    }
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> updateHint());

        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
        bar.setBackground(Theme.SURFACE);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        bar.add(button("Добавить…", this::add, "Новый аккаунт"));
        bar.add(button("Изменить…", this::edit, "Настройки выбранного аккаунта"));
        bar.add(button("Удалить", this::remove, "Удалить аккаунт с логином и паролем"));
        bar.add(gap());
        bar.add(button("Проверить прокси", this::checkProxy, "Связь с сайтом и внешний IP, без входа"));
        bar.add(gap());
        JButton start = button("Запустить", this::start, "Войти и работать по конфигу");
        start.setBackground(new Color(0x0e4a66));
        bar.add(start);
        bar.add(button("Остановить", this::stop, "Выйти у портала или закрыть сразу"));
        bar.add(button("Запустить отмеченные", this::startAll, "Аккаунты с галочкой, входы с паузой 15–45 с"));
        bar.add(button("Остановить все", this::stopAll, "Выйти у портала или закрыть сразу"));
        bar.add(gap());
        bar.add(button("Группа…", this::assignGroup, "Группа выбранного аккаунта и отдельная вкладка"));
        bar.add(button("Запустить группу", this::startGroup, "Аккаунты группы, выбранной в фильтре «Группа»"));
        bar.add(button("Остановить группу", this::stopGroup, "Аккаунты группы, выбранной в фильтре «Группа»"));
        JButton sets = new JButton("Наборы ▾");
        sets.setToolTipText("Сохранённые наборы аккаунтов с конфигом — запуск одним щелчком");
        sets.addActionListener(e -> presetsMenu().show(sets, 0, sets.getHeight()));
        bar.add(sets);
        bar.add(gap());
        bar.add(button("Конфиги…", () -> orbit.app.gui.config.ConfigWindow.open(
                orbit.config.ConfigStore.openDefault(), AccountSettings.DEFAULT_FILE, null),
                "Конфиги и расписание"));
        bar.add(button("Группа LoW…", () -> LowGroupDialog.show(frame, new ArrayList<>(vault.accounts().keySet()),
                selected()), "Лидер LoW и аккаунты-помощники, которых он запускает сам"));

        hint.setForeground(Hud.DIM);
        hint.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader()); // also without a screen (snapshots)
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel filters = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 4));
        filters.setBackground(Theme.SURFACE);
        filters.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        search.putClientProperty("JTextField.placeholderText", "поиск: имя, сервер, конфиг, цель");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                sorter.sort();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                sorter.sort();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                sorter.sort();
            }
        });
        filters.add(search);
        filters.add(filterGroup("Группа", groupFilter));
        filters.add(filterGroup("Показать", stateFilter));
        filters.add(filterGroup("Сервер", serverFilter));
        filters.add(filterGroup("Модуль", moduleFilter));
        for (var c : List.of(groupFilter, stateFilter, serverFilter, moduleFilter)) {
            c.addActionListener(e -> sorter.sort());
        }
        JPanel top = new JPanel(new BorderLayout());
        top.add(bar, BorderLayout.NORTH);
        top.add(filters, BorderLayout.SOUTH);
        JPanel page = new JPanel(new BorderLayout());
        page.add(top, BorderLayout.NORTH);
        page.add(scroll, BorderLayout.CENTER);
        page.add(hint, BorderLayout.SOUTH);
        return page;
    }

    private static JButton button(String text, Runnable action, String tip) {
        JButton b = new JButton(text);
        b.setToolTipText(tip);
        b.addActionListener(e -> action.run());
        return b;
    }

    private static JComponent gap() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(8, 1));
        return p;
    }

    private ManagedAccount account(String name) {
        return managed.computeIfAbsent(name, factory);
    }

    private String selected() {
        int v = table.getSelectedRow();
        if (v < 0) {
            return null;
        }
        int r = table.convertRowIndexToModel(v);
        return r < 0 || r >= model.names.size() ? null : model.names.get(r);
    }

    private String needSelected() {
        String n = selected();
        if (n == null) {
            JOptionPane.showMessageDialog(frame, "Выберите аккаунт", "Аккаунты", JOptionPane.INFORMATION_MESSAGE);
        }
        return n;
    }

    private void add() {
        String n = AccountDialog.show(frame, vault, null, false);
        if (n != null) {
            refresh();
            select(n);
            warnIfShared(n);
        }
    }

    private void edit() {
        String n = needSelected();
        if (n != null && AccountDialog.show(frame, vault, n, account(n).phase() != ManagedAccount.Phase.STOPPED) != null) {
            refresh();
            warnIfShared(n);
        }
    }

    private void remove() {
        String n = needSelected();
        if (n == null) {
            return;
        }
        if (account(n).phase() != ManagedAccount.Phase.STOPPED) {
            JOptionPane.showMessageDialog(frame, "Сначала остановите «" + n + "»", "Аккаунты", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (JOptionPane.showConfirmDialog(frame, "<html>Удалить «" + n + "»?<br>Логин, пароль, прокси и расписание удалятся.</html>", "Удалить", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            vault.remove(n);
            vault.save();
            var file = AccountSettings.DEFAULT_FILE;
            AccountSettings.load(file).with(n, null).save(file);
            orbit.app.IpHistory.shared().remove(n);
        } catch (VaultException | java.io.IOException e) {
            JOptionPane.showMessageDialog(frame, "Не удалось: " + e.getMessage(), "Аккаунты", JOptionPane.ERROR_MESSAGE);
        }
        dropAccount(n);
        groups.forget(n);
        managed.remove(n);
        refresh();
    }

    private void checkProxy() {
        String n = needSelected();
        if (n == null) {
            return;
        }
        Account a = vault.get(n);
        ProxySpec spec;
        try {
            spec = a.proxySpec();
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, "Неверный прокси: " + e.getMessage(), n, JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (spec.direct()) {
            JOptionPane.showMessageDialog(frame, "Без прокси — напрямую", n,
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        hint.setText("Проверяю прокси…");
        new SwingWorker<String, Void>() {
            private boolean good;

            @Override
            protected String doInBackground() {
                try (ProxyRoute route = ProxyRoute.of(spec)) {
                    ProxyRoute.Check c = route.check();
                    String text = c.describe();
                    String before = orbit.app.IpHistory.shared().record(n, c.ip(), c.country());
                    if (before != null) {
                        text += ". Страна сменилась: в прошлый раз была " + before + ".";
                    }
                    good = true;
                    return text;
                } catch (java.io.IOException | RuntimeException e) {
                    return spec.describe() + ": " + e.getMessage();
                }
            }

            @Override
            protected void done() {
                updateHint();
                String text;
                try {
                    text = get();
                } catch (Exception e) {
                    text = String.valueOf(e.getMessage());
                }
                JOptionPane.showMessageDialog(frame, Theme.wrapped(text, 420), "Прокси «" + n + "»",
                        good ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
            }
        }.execute();
    }

    private void start() {
        String n = needSelected();
        if (n != null) {
            startOne(n, 0, true);
        }
    }

    /** @return {@code null} when it started, otherwise why not */
    String startOne(String n, long delayMs, boolean tell) {
        ManagedAccount a = account(n);
        if (a.heldBy() != null) {
            String why = "Занят: помощник LoW для «" + a.heldBy() + "». Он остановится вместе с лидером.";
            if (tell) {
                JOptionPane.showMessageDialog(frame, Theme.wrapped(why, 420), "«" + n + "» уже в работе",
                        JOptionPane.INFORMATION_MESSAGE);
            }
            return why;
        }
        if (a.phase() != ManagedAccount.Phase.STOPPED) {
            return "Уже запущен";
        }
        String why = a.start(delayMs);
        if (why != null) {
            if (tell) {
                JOptionPane.showMessageDialog(frame, Theme.wrapped(why, 420), "«" + n + "» не запущен",
                        JOptionPane.WARNING_MESSAGE);
            }
            return why;
        }
        ensureTab(a);
        grouped.showGroup(groups.group(n));
        refresh();
        return null;
    }

    private void stop() {
        String n = needSelected();
        if (n == null) {
            return;
        }
        ManagedAccount a = account(n);
        if (a.phase() == ManagedAccount.Phase.STOPPING) {
            if (JOptionPane.showConfirmDialog(frame, "«" + n + "» выходит у портала. Закрыть сразу?",
                    "Остановить", JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION) {
                a.stopNow();
            }
        } else {
            StopDialog.stop(frame, "Остановить «" + n + "»", List.of(a), "");
        }
        refresh();
    }

    void startAll() {
        List<String> picked = model.names.stream().filter(model::picked).toList();
        if (picked.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Ни один аккаунт не отмечен — поставьте галочки в первом столбце.",
                    "Запустить отмеченные", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        startEach(picked);
    }

    /** Starts the stopped ones of {@code names}, logins spread out; says which could not start. */
    void startEach(List<String> names) {
        java.util.Random random = new java.util.Random();
        long delay = 0;
        List<String> failed = new ArrayList<>();
        int started = 0;
        for (String n : names) {
            ManagedAccount a = account(n);
            if (a.phase() != ManagedAccount.Phase.STOPPED) {
                continue;
            }
            String why = startOne(n, delay, false);
            if (why == null) {
                started++;
                delay += 15_000 + random.nextInt(30_000); // logins spread out: the site sees no burst
            } else {
                failed.add(n + ": " + why);
            }
        }
        if (!failed.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Запущено: " + started + ". Не запущены:\n" + String.join("\n", failed),
                    "Запустить отмеченные", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void loadPicks() {
        java.util.Properties p = new java.util.Properties();
        try (var in = java.nio.file.Files.newBufferedReader(picksFile, java.nio.charset.StandardCharsets.UTF_8)) {
            p.load(in);
        } catch (java.nio.file.NoSuchFileException e) {
            return;
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(ManagerWindow.class).warn("Не прочитать {}: {}", picksFile, e.getMessage());
            return;
        }
        model.unpicked.clear();
        for (String n : p.getProperty("unticked", "").split("\\n")) {
            if (!n.isBlank()) {
                model.unpicked.add(n);
            }
        }
    }

    private void savePicks() {
        java.util.Properties p = new java.util.Properties();
        p.setProperty("unticked", String.join("\n", model.unpicked));
        try {
            if (picksFile.getParent() != null) {
                java.nio.file.Files.createDirectories(picksFile.getParent());
            }
            try (var out = java.nio.file.Files.newBufferedWriter(picksFile, java.nio.charset.StandardCharsets.UTF_8)) {
                p.store(out, "Orbit: accounts without a tick (not started by «Запустить отмеченные»)");
            }
        } catch (java.io.IOException e) {
            org.slf4j.LoggerFactory.getLogger(ManagerWindow.class).warn("Не сохранить {}: {}", picksFile, e.getMessage());
        }
    }

    private void stopAll() {
        StopDialog.stop(frame, "Остановить все", List.copyOf(managed.values()), "");
        refresh();
    }

    private void select(String n) {
        int r = model.names.indexOf(n);
        int v = r < 0 ? -1 : table.convertRowIndexToView(r);
        if (v >= 0) {
            table.setRowSelectionInterval(v, v);
        }
    }

    // ---- account tabs ----

    /** The started account's view, made on its first start and kept (with its log) while the program runs. */
    private void ensureTab(ManagedAccount a) {
        grouped.add(MainWindow.Tab.of(a));
    }

    /** The account's own tab, opened (from its card or a double click) and selected. */
    void openAccount(String name) {
        grouped.openAccount(name);
    }

    /**
     * The tab's ×: a grouped account's tab is only hidden (the account goes on, seen on its group's tab); an account without a
     * group is stopped as before (after a question when in game) and its tab goes.
     */
    void closeTab(int index) {
        String g = grouped.groupAt(index);
        if (g != null) {
            closeGroupTab(g);
            return;
        }
        String name = grouped.nameAt(index);
        if (name == null || grouped.hide(name)) {
            return; // the overview tabs stay; a grouped one is hidden
        }
        ManagedAccount a = account(name);
        if (a.phase() == ManagedAccount.Phase.RUNNING
                && !StopDialog.stop(frame, "Закрыть вкладку", List.of(a), "и закрыть вкладку")) {
            return; // in game and cancelled; not in game: stopped at once without a question
        }
        dropAccount(name);
        refresh();
    }

    /**
     * A group tab's ×: its accounts in game are stopped as «Остановить группу» asks (exit at a portal, close at once or
     * cancel), then the tab goes; with none in game it goes at once. It comes back when one of the group's accounts starts.
     */
    private void closeGroupTab(String g) {
        List<ManagedAccount> running = inGroup(g).stream().map(this::account)
                .filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).toList();
        if (!running.isEmpty() && !StopDialog.stop(frame, "Закрыть группу «" + g + "»", running, "и закрыть вкладку группы")) {
            return;
        }
        grouped.closeGroup(g);
        refresh();
    }

    /** A removed or closed account: its view and tab gone. */
    private void dropAccount(String name) {
        grouped.remove(name);
    }

    void rebuildTabs() {
        grouped.rebuild();
    }

    private static AccountStatus status(ManagedAccount a) {
        return a.phase() == ManagedAccount.Phase.STOPPED ? AccountStatus.OFFLINE : AccountStatus.of(a.feed());
    }

    // ---- refresh ----

    void refresh() {
        String keep = selected();
        List<String> names = new ArrayList<>(vault.accounts().keySet());
        AccountSettings settings;
        try {
            settings = AccountSettings.load(AccountSettings.DEFAULT_FILE);
        } catch (java.io.IOException e) {
            settings = new AccountSettings(null);
        }
        links = links(names);
        List<Object[]> rows = new ArrayList<>();
        for (String n : names) {
            rows.add(row(n, settings));
        }
        for (String n : names) {
            alertIfNew(account(n));
            if (account(n).heldBy() != null) {
                ensureTab(account(n)); // a LoW helper started by its leader gets its tab too
            }
        }
        boolean sameNames = names.equals(model.names);
        model.names = names;
        model.rows = rows;
        if (sameNames) {
            model.fireTableRowsUpdated(0, Math.max(0, rows.size() - 1));
        } else {
            model.fireTableDataChanged();
            if (keep != null) {
                select(keep);
            }
        }
        grouped.refresh();
        refreshFilters();
        pane.repaint();
        updateHint();
    }

    private void refreshTitle() {
        long playing = managed.values().stream().filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).count();
        int all = model.names.size();
        frame.setTitle(playing == 0 ? "Orbit — аккаунтов: " + all : "Orbit — запущено " + playing + " из " + all);
    }

    private void updateHint() {
        hint.setText(model.names.isEmpty() ? "Нет аккаунтов — «Добавить…»"
                : "Двойной щелчок — вкладка или настройки аккаунта");
    }

    /** What links each account to others by its address, as of the last {@link #refresh}. */
    private Map<String, orbit.app.ProxySharing.Links> links = Map.of();

    private Map<String, orbit.app.ProxySharing.Links> links(List<String> names) {
        Map<String, ProxySpec> proxies = new LinkedHashMap<>();
        for (String n : names) {
            try {
                proxies.put(n, vault.get(n).proxySpec());
            } catch (IllegalArgumentException e) {
                proxies.put(n, null);
            }
        }
        Map<String, String> ips = new java.util.HashMap<>();
        orbit.app.IpHistory.shared().all().forEach((k, v) -> ips.put(k, v.ip()));
        return orbit.app.ProxySharing.of(proxies, ips);
    }

    /** After an account was saved: its proxy is another account's too — the game links such accounts. */
    private void warnIfShared(String n) {
        var l = links(new ArrayList<>(vault.accounts().keySet())).get(n);
        if (l == null || !l.any()) {
            return;
        }
        boolean direct;
        try {
            direct = vault.get(n).proxySpec().direct();
        } catch (IllegalArgumentException e) {
            return;
        }
        String text = !l.sameProxy().isEmpty()
                ? (direct ? "«" + n + "» без прокси, как и " + names(l.sameProxy()) + ": у них один IP — этого компьютера."
                        : "Этот прокси уже стоит у " + names(l.sameProxy()) + ": у аккаунтов будет один IP.")
                : "Через этот прокси в прошлый раз был тот же IP, что у " + names(l.sameIp()) + ".";
        JOptionPane.showMessageDialog(frame, Theme.wrapped(text + " Игра может связать такие аккаунты.", 420),
                "«" + n + "»: общий адрес", JOptionPane.WARNING_MESSAGE);
    }

    private static String names(List<String> names) {
        return String.join(", ", names.stream().map(x -> "«" + x + "»").toList());
    }

    /** Alerts already shown, by account: each one once. */
    private final Map<String, Long> alertsShown = new java.util.HashMap<>();

    /** A sign of trouble stopped the account: a dialog that does not block the window, and a beep. */
    private void alertIfNew(ManagedAccount a) {
        long at = a.feed().alertAt();
        if (at == 0 || alertsShown.getOrDefault(a.name(), 0L) == at) {
            return;
        }
        alertsShown.put(a.name(), at);
        if (java.awt.GraphicsEnvironment.isHeadless() || !frame.isDisplayable()) {
            return;
        }
        Toolkit.getDefaultToolkit().beep();
        boolean stopped = a.feed().alertStopped();
        String text = stopped ? "«" + a.name() + "» остановлен: " + a.feed().alertText()
                + ". Повторов входа не будет. Проверьте аккаунт, прежде чем запускать снова."
                : "«" + a.name() + "»: " + a.feed().alertText() + ". Бот работает дальше.";
        JOptionPane pane = new JOptionPane(Theme.wrapped(text, 420), JOptionPane.WARNING_MESSAGE);
        javax.swing.JDialog d = pane.createDialog(frame, (stopped ? "Признак беды" : "Внимание") + " — «" + a.name() + "»");
        d.setModal(false);
        d.setVisible(true);
    }

    private Object[] row(String n, AccountSettings settings) {
        Account acc = vault.get(n);
        ManagedAccount a = account(n);
        AccountSettings.Entry entry = settings.account(n);
        String proxy;
        try {
            ProxySpec spec = acc.proxySpec();
            proxy = orbit.app.ProxySharing.describe(spec, orbit.app.IpHistory.shared().get(n), links.get(n));
        } catch (IllegalArgumentException e) {
            proxy = "ошибка: " + e.getMessage();
        }
        String config = a.config();
        String schedule = "—";
        if (entry.schedule().enabled() && !entry.schedule().entries().isEmpty()) {
            var d = entry.decide(java.time.LocalDateTime.now(), n.hashCode());
            String until = d.until() == null ? "" : " до " + d.until().toLocalTime().withNano(0);
            schedule = d.online() ? d.config() + until : "Перерыв" + until;
        }
        WorldFeed feed = a.feed();
        String session = "";
        String results = "";
        if (feed.sessionStartNanos() != 0) { // after a stop: the session's length and results stay
            session = Hud.clock(java.time.Duration.ofNanos(feed.sessionNanos()));
            WorldSnapshot.Rewards r = feed.sessionRewards();
            double hours = feed.sessionHours();
            results = money(r.credits(), hours) + " кр · " + money(r.uridium(), hours) + " ур";
        }
        String group = groups.group(n);
        String goal = orbit.app.LevelGoals.of(n);
        if (goal == null && a.phase() == ManagedAccount.Phase.RUNNING && feed.runner() != null) {
            goal = feed.runner().status();
        }
        return new Object[] {model.picked(n), n, group == null ? "" : group, acc.username(), acc.home(), proxy,
            config == null ? "—" : config, module(config), goal == null ? "" : goal, schedule,
            new State(stateText(a), stateColor(a)), session, results};
    }

    /** The config's module as the config window names it; read again every half minute. */
    private String module(String config) {
        if (config == null) {
            return "—";
        }
        if (System.currentTimeMillis() - modulesAt > 30_000) {
            moduleOf.clear();
            modulesAt = System.currentTimeMillis();
        }
        return moduleOf.computeIfAbsent(config, c -> {
            try {
                return orbit.app.gui.config.ConfigWindow.moduleName(orbit.config.ConfigStore.openDefault().load(c).module());
            } catch (java.io.IOException | RuntimeException e) {
                return "—";
            }
        });
    }

    // ---- filters ----

    private static JComponent filterGroup(String label, javax.swing.JComboBox<String> box) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        p.setOpaque(false);
        JLabel l = new JLabel(label);
        l.setForeground(Hud.DIM);
        p.add(l);
        p.add(box);
        return p;
    }

    /** The filters' choices from the list (the chosen one kept). */
    private void refreshFilters() {
        java.util.TreeSet<String> gs = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        java.util.TreeSet<String> servers = new java.util.TreeSet<>();
        java.util.TreeSet<String> modules = new java.util.TreeSet<>();
        for (Object[] r : model.rows) {
            if (!String.valueOf(r[C_GROUP]).isEmpty()) {
                gs.add(String.valueOf(r[C_GROUP]));
            }
            servers.add(String.valueOf(r[C_SERVER]));
            modules.add(String.valueOf(r[C_MODULE]));
        }
        List<String> g = new ArrayList<>(List.of(ANY));
        g.addAll(gs);
        g.add(NO_GROUP);
        fill(groupFilter, g);
        List<String> sv = new ArrayList<>(List.of(ANY));
        sv.addAll(servers);
        fill(serverFilter, sv);
        List<String> md = new ArrayList<>(List.of(ANY));
        md.addAll(modules);
        fill(moduleFilter, md);
    }

    private void fill(javax.swing.JComboBox<String> box, List<String> items) {
        List<String> now = new ArrayList<>();
        for (int i = 0; i < box.getItemCount(); i++) {
            now.add(box.getItemAt(i));
        }
        if (now.equals(items)) {
            return;
        }
        Object keep = box.getSelectedItem();
        var listeners = box.getActionListeners();
        for (var l : listeners) {
            box.removeActionListener(l);
        }
        box.removeAllItems();
        items.forEach(box::addItem);
        box.setSelectedItem(keep != null && items.contains(keep) ? keep : ANY);
        for (var l : listeners) {
            box.addActionListener(l);
        }
    }

    /** The row passes the search and the filters. */
    boolean shows(int row) {
        if (row >= model.rows.size()) {
            return true;
        }
        Object[] r = model.rows.get(row);
        String q = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
        if (!q.isEmpty()) {
            boolean hit = false;
            for (int c : new int[] {C_NAME, C_GROUP, C_LOGIN, C_SERVER, C_CONFIG, C_MODULE, C_GOAL, C_STATE}) {
                if (String.valueOf(r[c]).toLowerCase(java.util.Locale.ROOT).contains(q)) {
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                return false;
            }
        }
        String g = (String) groupFilter.getSelectedItem();
        if (g != null && !ANY.equals(g) && !(NO_GROUP.equals(g) ? String.valueOf(r[C_GROUP]).isEmpty() : g.equals(r[C_GROUP]))) {
            return false;
        }
        String sv = (String) serverFilter.getSelectedItem();
        if (sv != null && !ANY.equals(sv) && !sv.equals(String.valueOf(r[C_SERVER]))) {
            return false;
        }
        String md = (String) moduleFilter.getSelectedItem();
        if (md != null && !ANY.equals(md) && !md.equals(String.valueOf(r[C_MODULE]))) {
            return false;
        }
        String st = (String) stateFilter.getSelectedItem();
        ManagedAccount a = row < model.names.size() ? account(model.names.get(row)) : null;
        if (st == null || ANY.equals(st) || a == null) {
            return true;
        }
        return switch (st) {
            case "Запущенные" -> a.phase() != ManagedAccount.Phase.STOPPED;
            case "Остановленные" -> a.phase() == ManagedAccount.Phase.STOPPED;
            case "С проблемами" -> a.problem() != null || a.feed().alertAt() != 0 || stateColor(a).equals(BAD);
            default -> true;
        };
    }

    // ---- groups and start sets ----

    /** The selected account's group (an existing one or a new name; empty — none) and its own tab. */
    private void assignGroup() {
        String n = needSelected();
        if (n == null) {
            return;
        }
        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>();
        box.setEditable(true);
        box.addItem("");
        groups.groups().forEach(box::addItem);
        box.setSelectedItem(groups.group(n) == null ? "" : groups.group(n));
        javax.swing.JCheckBox pin = new javax.swing.JCheckBox("Отдельная вкладка (кроме вкладки группы)", groups.pinned.contains(n));
        JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Группа «" + n + "» (новое имя — новая группа, пусто — без группы):"));
        p.add(box);
        p.add(pin);
        if (JOptionPane.showConfirmDialog(frame, p, "Группа", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION) {
            return;
        }
        Object g = box.getEditor().getItem();
        groups.setGroup(n, g == null ? null : g.toString());
        groups.setPinned(n, pin.isSelected());
        refresh(); // the tabs follow by the groups' change (GroupedTabs hears it)
    }

    /** The group chosen in the «Группа» filter, or {@code null} (with a word why). */
    private List<String> chosenGroup(String what) {
        String g = (String) groupFilter.getSelectedItem();
        if (g == null || ANY.equals(g)) {
            JOptionPane.showMessageDialog(frame, "Выберите группу в фильтре «Группа».", what, JOptionPane.INFORMATION_MESSAGE);
            return null;
        }
        return model.names.stream().filter(n -> NO_GROUP.equals(g) ? groups.group(n) == null : g.equals(groups.group(n)))
                .toList();
    }

    /** The accounts of a group by its name. */
    private List<String> inGroup(String g) {
        return model.names.stream().filter(n -> g.equals(groups.group(n))).toList();
    }

    /** The group tab's «Запустить группу»: its stopped accounts, logins spread out. */
    void startGroupNamed(String g) {
        startEach(inGroup(g));
    }

    /** The group tab's «Остановить группу»: its running accounts, as «Остановить» asks. */
    void stopGroupNamed(String g) {
        List<ManagedAccount> running = inGroup(g).stream().map(this::account)
                .filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).toList();
        if (!running.isEmpty()) {
            StopDialog.stop(frame, "Остановить группу «" + g + "»", running, "");
        }
        refresh();
    }

    private void startGroup() {
        List<String> in = chosenGroup("Запустить группу");
        if (in != null) {
            startEach(in);
        }
    }

    private void stopGroup() {
        List<String> in = chosenGroup("Остановить группу");
        if (in == null) {
            return;
        }
        List<ManagedAccount> running = in.stream().map(this::account)
                .filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).toList();
        if (!running.isEmpty()) {
            StopDialog.stop(frame, "Остановить группу «" + groupFilter.getSelectedItem() + "»", running, "");
        }
        refresh();
    }

    /** The start sets: each starts its accounts (on its config, if it has one); save the ticked ones as a set; remove. */
    private javax.swing.JPopupMenu presetsMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        for (ManagerGroups.Preset p : groups.presets) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem("Запустить «" + p.name() + "» — " + p.accounts().size()
                    + " акк." + (p.config() == null ? "" : ", конфиг «" + p.config() + "»"));
            item.setToolTipText(String.join(", ", p.accounts()));
            item.addActionListener(e -> startPreset(p));
            menu.add(item);
        }
        if (!groups.presets.isEmpty()) {
            menu.addSeparator();
        }
        javax.swing.JMenuItem save = new javax.swing.JMenuItem("Сохранить отмеченные как набор…");
        save.addActionListener(e -> savePreset());
        menu.add(save);
        if (!groups.presets.isEmpty()) {
            javax.swing.JMenuItem del = new javax.swing.JMenuItem("Удалить набор…");
            del.addActionListener(e -> {
                Object[] names = groups.presets.stream().map(ManagerGroups.Preset::name).toArray();
                Object pick = JOptionPane.showInputDialog(frame, "Какой набор удалить?", "Наборы", JOptionPane.PLAIN_MESSAGE,
                        null, names, names[0]);
                if (pick != null) {
                    groups.removePreset(pick.toString());
                }
            });
            menu.add(del);
        }
        return menu;
    }

    private void savePreset() {
        List<String> picked = model.names.stream().filter(model::picked).toList();
        if (picked.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Отметьте аккаунты галочками.", "Набор", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        javax.swing.JTextField name = new javax.swing.JTextField(20);
        javax.swing.JComboBox<String> config = new javax.swing.JComboBox<>();
        config.addItem("(свой конфиг у каждого)");
        try {
            orbit.config.ConfigStore.openDefault().list().forEach(config::addItem);
        } catch (java.io.IOException | RuntimeException e) {
            // no configs listed: own ones
        }
        JPanel p = new JPanel(new java.awt.GridLayout(0, 1, 4, 4));
        p.add(new JLabel("Набор из " + picked.size() + " отмеченных: имя"));
        p.add(name);
        p.add(new JLabel("Конфиг при запуске набора"));
        p.add(config);
        if (JOptionPane.showConfirmDialog(frame, p, "Сохранить набор", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                != JOptionPane.OK_OPTION || name.getText().isBlank()) {
            return;
        }
        String cfg = config.getSelectedIndex() <= 0 ? null : (String) config.getSelectedItem();
        groups.putPreset(new ManagerGroups.Preset(name.getText().strip(), picked, cfg));
    }

    /** A start set: its config given to each account (saved as the account's), then the stopped ones started. */
    private void startPreset(ManagerGroups.Preset p) {
        if (p.config() != null) {
            try {
                var file = AccountSettings.DEFAULT_FILE;
                var all = AccountSettings.load(file);
                for (String n : p.accounts()) {
                    if (model.names.contains(n)) {
                        all = all.with(n, all.account(n).withConfig(p.config()));
                        if (account(n).phase() != ManagedAccount.Phase.STOPPED) {
                            account(n).switchConfig(p.config());
                        }
                    }
                }
                all.save(file);
            } catch (java.io.IOException | RuntimeException e) {
                JOptionPane.showMessageDialog(frame, "Конфиг набора не задан: " + e.getMessage(), "Набор", JOptionPane.WARNING_MESSAGE);
            }
        }
        startEach(p.accounts().stream().filter(model.names::contains).toList());
        refresh();
    }

    private static String money(double v, double hours) {
        String total = String.format("%,.0f", v).replace(' ', ' ');
        return hours >= 1.0 / 60 && v != 0 ? total + " (" + String.format("%,.0f", v / hours).replace(' ', ' ') + "/ч)" : total;
    }

    private static String stateText(ManagedAccount a) {
        String held = a.heldBy();
        String text = stateOnly(a);
        return held == null ? text : "LoW для «" + held + "»: " + text;
    }

    private static String stateOnly(ManagedAccount a) {
        return switch (a.phase()) {
            case STOPPED -> a.problem() != null ? "Остановлен — " + a.problem() : "Остановлен";
            case STOPPING -> a.feed().state();
            case RUNNING -> {
                AccountStatus s = AccountStatus.of(a.feed());
                yield s == AccountStatus.OFFLINE ? a.feed().state() : s.label;
            }
        };
    }

    private static Color stateColor(ManagedAccount a) {
        return switch (a.phase()) {
            case STOPPED -> a.problem() != null ? BAD : AccountStatus.OFFLINE.color;
            case STOPPING -> WAIT;
            case RUNNING -> {
                AccountStatus s = AccountStatus.of(a.feed());
                yield s == AccountStatus.OFFLINE ? WAIT : s.color;
            }
        };
    }

    // ---- closing ----

    private void close(Runnable onClosed) {
        List<ManagedAccount> running = managed.values().stream()
                .filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).toList();
        if (running.isEmpty()) {
            frame.dispose();
            onClosed.run();
            return;
        }
        if (!StopDialog.stop(frame, "Закрыть бота", running, "и закрыть бота")) {
            return;
        }
        JDialog wait = new JDialog(frame, "Закрытие", false);
        JLabel text = new JLabel("Остановка…");
        text.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        wait.setContentPane(text);
        wait.pack();
        wait.setLocationRelativeTo(frame);
        wait.setVisible(true);
        long giveUp = System.currentTimeMillis() + 150_000; // a logout waits two minutes at most
        new Timer(500, e -> {
            long left = running.stream().filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).count();
            text.setText("Остановка… осталось " + left);
            wait.pack();
            if (left == 0 || System.currentTimeMillis() > giveUp) {
                ((Timer) e.getSource()).stop();
                wait.dispose();
                frame.dispose();
                onClosed.run();
            }
        }).start();
    }

    // ---- table ----

    /** The state cell: text in the status colour. */
    record State(String text, Color color) {
        @Override
        public String toString() {
            return text;
        }
    }

    static final class Model extends AbstractTableModel {
        private static final String[] COLUMNS = {"✓", "Аккаунт", "Группа", "Логин", "Сервер", "Прокси", "Конфиг", "Модуль",
            "Цель / сейчас", "Расписание", "Состояние", "Сессия", "Итоги"};
        List<String> names = List.of();
        List<Object[]> rows = List.of();
        /** Names without a tick; everything else is ticked (so new accounts are). */
        final java.util.Set<String> unpicked = new java.util.TreeSet<>();
        Runnable onPick = () -> { };

        boolean picked(String name) {
            return !unpicked.contains(name);
        }

        boolean allPicked() {
            return names.stream().allMatch(this::picked);
        }

        void pick(String name, boolean on) {
            if (on ? unpicked.remove(name) : unpicked.add(name)) {
                int r = names.indexOf(name);
                if (r >= 0 && r < rows.size()) {
                    rows.get(r)[0] = on;
                    fireTableCellUpdated(r, 0);
                }
                onPick.run();
            }
        }

        void pickAll(boolean on) {
            names.forEach(n -> pick(n, on));
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Boolean.class : Object.class;
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return column == 0;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 0 && row < names.size()) {
                pick(names.get(row), Boolean.TRUE.equals(value));
            }
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
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            return row < rows.size() ? rows.get(row)[column] : "";
        }
    }

    private static final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            Component c = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (value instanceof State s && !selected) {
                c.setForeground(s.color());
            } else if (!selected) {
                c.setForeground(table.getForeground());
            }
            setToolTipText(value == null || value.toString().isEmpty() ? null : value.toString());
            return c;
        }
    }

    /**
     * Only the map in the manager: the window takes the map's shape at quiet moments — the mode turned on, an account's
     * tab opened, the start — and never while its edge is dragged (the map's top was cut in a small window, and
     * the window visibly changing while resizing was not wanted). Same fit as the bot's window.
     */
    private void fitAtQuietMoments() {
        Timer later = new Timer(150, e -> MainWindow.fitOnce(frame, pane));
        later.setRepeats(false);
        pane.addChangeListener(e -> later.restart());
        boolean[] mapOnly = {ViewSettings.shared().mapOnlyNow()};
        ViewSettings.shared().onChange(() -> SwingUtilities.invokeLater(() -> {
            boolean now = ViewSettings.shared().mapOnlyNow();
            if (now && !mapOnly[0]) {
                later.restart(); // turned on: once, after the parts around the map are gone
            }
            mapOnly[0] = now;
        }));
        later.restart();
    }
}
