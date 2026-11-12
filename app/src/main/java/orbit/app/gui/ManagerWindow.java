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
 * all without restarting the program. A started account gets its tab (map, cards, log), «Все» shows them
 * side by side. Closing the window logs every running account out at a portal first.
 */
public final class ManagerWindow {

    private static final Color BAD = new Color(0xff6b5b);
    private static final Color WAIT = new Color(0xf59e0b);
    /** The first account tab: 0 = «Аккаунты», 1 = «Все». */
    private static final int FIRST_TAB = 2;

    private final Vault vault;
    private final Function<String, ManagedAccount> factory;
    private final Map<String, ManagedAccount> managed = new LinkedHashMap<>();
    final List<MainWindow.Tab> tabs = new ArrayList<>();
    /** Made in {@link #show}: none in tests and snapshots (no screen there). */
    private JFrame frame;
    final JTabbedPane pane = new JTabbedPane();
    final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel hint = new JLabel(" ");
    /**
     * Accounts left out of «Запустить отмеченные» (unticked), kept in {@code local/manager.properties}; new accounts
     * are ticked. Another file with {@code -Dorbit.manager.file=…}.
     */
    java.nio.file.Path picksFile = java.nio.file.Path.of(System.getProperty("orbit.manager.file", "local/manager.properties"));

    ManagerWindow(Vault vault, Function<String, ManagedAccount> factory) {
        this.vault = vault;
        this.factory = factory;
        model.onPick = this::savePicks;
        pane.putClientProperty("JTabbedPane.tabType", "card");
        pane.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        pane.addTab("Аккаунты", accountsPage());
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
        SwingUtilities.invokeLater(() -> {
            Theme.setup();
            new ManagerWindow(vault, factory).show(onClosed);
        });
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
        if (model.names.isEmpty()) {
            SwingUtilities.invokeLater(this::add);
        }
        new Timer(1_000, e -> refreshTitle()).start();
    }

    // ---- the accounts page ----

    private JComponent accountsPage() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(table.getRowHeight() + 8);
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new Renderer());
        loadPicks();
        int[] widths = {28, 110, 120, 60, 200, 120, 150, 230, 70, 230};
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
                    int tab = tabOf(selected());
                    if (tab >= 0) {
                        pane.setSelectedIndex(tab);
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
        bar.add(button("Конфиги…", () -> orbit.app.gui.config.ConfigWindow.open(
                orbit.config.ConfigStore.openDefault(), AccountSettings.DEFAULT_FILE, null),
                "Конфиги и расписание"));

        hint.setForeground(Hud.DIM);
        hint.setBorder(BorderFactory.createEmptyBorder(6, 10, 8, 10));
        JScrollPane scroll = new JScrollPane(table);
        scroll.setColumnHeaderView(table.getTableHeader()); // also without a screen (snapshots)
        scroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel page = new JPanel(new BorderLayout());
        page.add(bar, BorderLayout.NORTH);
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
        int r = table.getSelectedRow();
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
        int tab = tabOf(n);
        if (tab >= 0) {
            dropTab(tab);
        }
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
        if (r >= 0) {
            table.setRowSelectionInterval(r, r);
        }
    }

    // ---- account tabs ----

    private int tabOf(String name) {
        for (int i = 0; i < tabs.size(); i++) {
            if (tabs.get(i).managed().name().equals(name)) {
                return i + FIRST_TAB;
            }
        }
        return -1;
    }

    /** The account's tab, made on its first start and kept (with its log) while the program runs. */
    private void ensureTab(ManagedAccount a) {
        if (tabOf(a.name()) >= 0) {
            return;
        }
        MainWindow.Tab tab = MainWindow.Tab.of(a);
        tabs.add(tab);
        if (pane.getTabCount() < FIRST_TAB) {
            pane.addTab("Все", new JPanel());
        }
        AccountView view = new AccountView(tab);
        view.putClientProperty("JTabbedPane.tabClosable", true);
        view.putClientProperty("JTabbedPane.tabCloseToolTipText", "Закрыть (аккаунт остановится)");
        pane.addTab(a.feed().title(), AccountStatus.dot(() -> status(a)), view);
        rebuildOverview();
    }

    /** The tab's ×: a running account is stopped first (after a question); the account stays in the list. */
    void closeTab(int index) {
        if (index < FIRST_TAB || index >= pane.getTabCount()) {
            return;
        }
        ManagedAccount a = tabs.get(index - FIRST_TAB).managed();
        if (a.phase() == ManagedAccount.Phase.RUNNING
                && !StopDialog.stop(frame, "Закрыть вкладку", List.of(a), "и закрыть вкладку")) {
            return; // in game and the user cancelled; not in game: stopped at once without a question
        }
        dropTab(index);
        refresh();
    }

    private void dropTab(int index) {
        Component view = pane.getComponentAt(index);
        tabs.remove(index - FIRST_TAB);
        pane.removeTabAt(index);
        if (view instanceof AccountView v) {
            v.dispose();
        }
        rebuildOverview();
    }

    private void rebuildOverview() {
        if (pane.getTabCount() >= FIRST_TAB) {
            MainWindow.disposeOverview(pane.getComponentAt(1));
        }
        if (tabs.isEmpty()) {
            if (pane.getTabCount() >= FIRST_TAB) {
                pane.removeTabAt(1);
            }
            return;
        }
        pane.setComponentAt(1, MainWindow.overview(tabs, pane, FIRST_TAB));
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
        for (int i = 0; i < tabs.size(); i++) {
            String title = tabs.get(i).feed().title();
            if (!title.equals(pane.getTitleAt(i + FIRST_TAB))) {
                pane.setTitleAt(i + FIRST_TAB, title);
            }
        }
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
            WorldSnapshot.Rewards r = feed.snapshot().rewards();
            double hours = feed.sessionHours();
            results = money(r.credits(), hours) + " кр · " + money(r.uridium(), hours) + " ур";
        }
        return new Object[] {model.picked(n), n, acc.username(), acc.home(), proxy, config == null ? "—" : config, schedule,
            new State(stateText(a), stateColor(a)), session, results};
    }

    private static String money(double v, double hours) {
        String total = String.format("%,.0f", v).replace(' ', ' ');
        return hours >= 1.0 / 60 && v != 0 ? total + " (" + String.format("%,.0f", v / hours).replace(' ', ' ') + "/ч)" : total;
    }

    private static String stateText(ManagedAccount a) {
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
        private static final String[] COLUMNS = {"✓", "Аккаунт", "Логин", "Сервер", "Прокси", "Конфиг", "Расписание",
            "Состояние", "Сессия", "Итоги"};
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
}
