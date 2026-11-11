package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import orbit.config.BotConfig;
import orbit.config.ConfigCheck;
import orbit.config.ConfigStore;
import orbit.config.DarkBotImport;
import orbit.world.maps.MapCatalog;

/**
 * The config window (like AstraBot's settings): the list of configs on the left, the chosen config
 * in tabs by module on the right, the pre-start check at the bottom; a second page for accounts and
 * their timetables.
 */
public final class ConfigWindow {

    private final ConfigStore store;
    private final MapCatalog maps;
    private final JFrame frame = new JFrame("Orbit — конфиги");
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final List<Section> sections = new ArrayList<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel title = new JLabel(" ");
    private final JLabel check = new JLabel(" ");
    private final AccountsPanel accounts;
    private String current;
    private BotConfig loaded;
    /** The loaded config as the form gives it back: changes are measured against this. */
    private BotConfig baseline;

    private ConfigWindow(ConfigStore store, Path accountsFile, MapCatalog maps) {
        this.store = store;
        this.maps = maps;
        sections.add(new GeneralSection(maps));
        sections.add(new KillSection(maps));
        sections.add(new CollectSection());
        sections.add(new PetSection());
        sections.add(new SafetySection());
        sections.add(new ZonesSection(maps));
        sections.add(new CubikonSection());
        sections.add(new GateSection());
        sections.add(new BuySection());
        sections.add(new GroupSection());
        sections.forEach(s -> tabs.addTab(s.title(), s.view()));
        accounts = new AccountsPanel(accountsFile, this::configNames);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && list.getSelectedValue() != null && !list.getSelectedValue().equals(current)) {
                open(list.getSelectedValue());
            }
        });
        JPanel buttons = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        buttons.add(button("Новый", this::create));
        buttons.add(button("Копия", this::copy));
        buttons.add(button("Переименовать", this::rename));
        buttons.add(button("Удалить", this::delete));
        buttons.add(button("Импорт DarkBot…", this::importDarkBot));
        JPanel left = new JPanel(new BorderLayout(0, 8));
        left.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 0));
        left.add(new JLabel("Конфиги"), BorderLayout.NORTH);
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(buttons, BorderLayout.SOUTH);

        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, title.getFont().getSize() + 3f));
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));
        bottom.add(check, BorderLayout.CENTER);
        JButton saveButton = button("Сохранить", this::save);
        // the main action in the accent colour
        saveButton.setBackground(new java.awt.Color(0x0e4a66));
        saveButton.setForeground(orbit.app.gui.Theme.TEXT);
        bottom.add(Form.inline(button("Проверить", this::runCheck), button("Отменить", this::revert),
                saveButton), BorderLayout.EAST);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        right.add(title, BorderLayout.NORTH);
        right.add(tabs, BorderLayout.CENTER);
        right.add(bottom, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(220);
        JTabbedPane pages = new JTabbedPane();
        pages.setBackground(orbit.app.gui.Theme.SURFACE);
        pages.setOpaque(true);
        pages.addTab("Конфиги", split);
        pages.addTab("Аккаунты", accounts.view());
        pages.addChangeListener(e -> accounts.refreshConfigs());

        frame.setContentPane(pages);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                if (confirmLeave() && confirmAccounts()) {
                    frame.dispose();
                }
            }
        });
        frame.setPreferredSize(new Dimension(1100, 760));
        frame.pack();
        frame.setLocationByPlatform(true);
        reloadList(null);
    }

    /** Opens the window on the Swing thread. */
    public static void open(ConfigStore store, Path accountsFile, Runnable onClose) {
        open(store, accountsFile, onClose, null, null);
    }

    /**
     * The window opened on one config (e.g. the one an account runs now), titled for that account.
     *
     * @param select config to show first ({@code null} = the first)
     * @param forWhom e.g. {@code int1 · FREEMASON}, shown in the title
     */
    public static void open(ConfigStore store, Path accountsFile, Runnable onClose, String select, String forWhom) {
        SwingUtilities.invokeLater(() -> {
            orbit.app.gui.Theme.setup();
            ConfigWindow w = new ConfigWindow(store, accountsFile, MapCatalog.bundled());
            if (forWhom != null) {
                w.frame.setTitle("Orbit — конфиг аккаунта " + forWhom);
            }
            if (select != null && w.names.contains(select)) {
                w.current = null;
                w.reloadList(select);
            }
            if (onClose != null) {
                w.frame.addWindowListener(new java.awt.event.WindowAdapter() {
                    @Override
                    public void windowClosed(java.awt.event.WindowEvent e) {
                        onClose.run();
                    }
                });
            }
            w.frame.setVisible(true);
        });
    }

    /** The window's content drawn into an image without showing it (to check the look). */
    public static java.awt.image.BufferedImage snapshot(ConfigStore store, Path accountsFile, String select,
                                                        int width, int height) {
        orbit.app.gui.Theme.setup();
        ConfigWindow w = new ConfigWindow(store, accountsFile, MapCatalog.bundled());
        if (select != null && w.names.contains(select)) {
            w.current = null;
            w.reloadList(select);
        }
        java.awt.Container c = w.frame.getContentPane();
        c.setSize(width, height);
        for (int pass = 0; pass < 2; pass++) {
            layoutAll(c);
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        try {
            c.paint(g);
        } finally {
            g.dispose();
        }
        w.frame.dispose();
        return img;
    }

    private static void layoutAll(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component child : k.getComponents()) {
                layoutAll(child);
            }
        }
    }

    private static JButton button(String text, Runnable action) {
        JButton b = new JButton(text);
        b.addActionListener(e -> action.run());
        return b;
    }

    private List<String> configNames() {
        try {
            return store.list();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void reloadList(String select) {
        names.clear();
        configNames().forEach(names::addElement);
        String target = select != null ? select : (names.isEmpty() ? null : names.firstElement());
        if (target == null) {
            current = null;
            loaded = null;
            title.setText("Конфигов нет — создайте или импортируйте");
            tabs.setEnabled(false);
            return;
        }
        current = null;
        list.setSelectedValue(target, true);
        if (current == null) {
            open(target); // already selected: no selection event
        }
    }

    private void open(String name) {
        if (!confirmLeave()) {
            list.setSelectedValue(current, true);
            return;
        }
        try {
            loaded = store.load(name);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Конфиг", JOptionPane.ERROR_MESSAGE);
            list.setSelectedValue(current, true);
            return;
        }
        current = name;
        tabs.setEnabled(true);
        sections.forEach(s -> s.load(loaded));
        baseline = collect(loaded);
        title.setText(name);
        runCheck();
    }

    /** All tabs applied to {@code base}. */
    private BotConfig collect(BotConfig base) {
        BotConfig c = base;
        for (Section s : sections) {
            c = s.apply(c);
        }
        return c;
    }

    private boolean dirty() {
        if (current == null) {
            return false;
        }
        try {
            return !collect(loaded).equals(baseline);
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** True when it is fine to leave the current config (saved, discarded or unchanged). */
    private boolean confirmLeave() {
        if (!dirty()) {
            return true;
        }
        int a = JOptionPane.showConfirmDialog(frame, "Сохранить «" + current + "»?", "Конфиг",
                JOptionPane.YES_NO_CANCEL_OPTION);
        if (a == JOptionPane.YES_OPTION) {
            return save();
        }
        return a == JOptionPane.NO_OPTION;
    }

    private boolean confirmAccounts() {
        if (!accounts.dirty()) {
            return true;
        }
        int a = JOptionPane.showConfirmDialog(frame, "Сохранить аккаунты?", "Аккаунты",
                JOptionPane.YES_NO_CANCEL_OPTION);
        if (a == JOptionPane.YES_OPTION) {
            return accounts.save();
        }
        return a == JOptionPane.NO_OPTION;
    }

    private boolean save() {
        if (current == null) {
            return false;
        }
        try {
            BotConfig c = collect(loaded);
            store.save(current, c);
            loaded = c;
            sections.forEach(s -> s.load(loaded));
            baseline = collect(loaded);
            runCheck();
            return true;
        } catch (IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Не сохранено", JOptionPane.WARNING_MESSAGE);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Не сохранено", JOptionPane.ERROR_MESSAGE);
        }
        return false;
    }

    private void revert() {
        if (current != null) {
            sections.forEach(s -> s.load(loaded));
            runCheck();
        }
    }

    private void runCheck() {
        if (current == null) {
            check.setText(" ");
            return;
        }
        List<ConfigCheck.Problem> problems;
        try {
            problems = ConfigCheck.check(collect(loaded), maps);
        } catch (IllegalArgumentException e) {
            check.setForeground(new Color(230, 110, 100));
            check.setText(e.getMessage());
            return;
        }
        if (problems.isEmpty()) {
            check.setForeground(new Color(110, 200, 120));
            check.setText("Проверка: замечаний нет");
            return;
        }
        StringBuilder b = new StringBuilder("<html>");
        for (ConfigCheck.Problem p : problems) {
            String color = p.level() == ConfigCheck.Level.ERROR ? "#e66e64" : "#e6c264";
            b.append("<span style='color:").append(color).append("'>").append(escape(p.toString())).append("</span><br>");
        }
        check.setForeground(Color.LIGHT_GRAY);
        check.setText(b.append("</html>").toString());
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String askName(String question, String initial) {
        while (true) {
            String n = (String) JOptionPane.showInputDialog(frame, question, "Конфиг", JOptionPane.PLAIN_MESSAGE, null,
                    null, initial);
            if (n == null) {
                return null;
            }
            n = n.strip();
            try {
                ConfigStore.checkName(n);
                if (store.exists(n)) {
                    JOptionPane.showMessageDialog(frame, "Конфиг «" + n + "» уже есть");
                    continue;
                }
                return n;
            } catch (IllegalArgumentException e) {
                JOptionPane.showMessageDialog(frame, e.getMessage());
            }
        }
    }

    private void create() {
        if (!confirmLeave()) {
            return;
        }
        String n = askName("Имя нового конфига:", "");
        if (n == null) {
            return;
        }
        try {
            store.save(n, BotConfig.empty());
            current = null;
            reloadList(n);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void copy() {
        if (current == null || !confirmLeave()) {
            return;
        }
        String n = askName("Имя копии «" + current + "»:", current + " копия");
        if (n == null) {
            return;
        }
        try {
            store.copy(current, n);
            current = null;
            reloadList(n);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void rename() {
        if (current == null || !confirmLeave()) {
            return;
        }
        String n = askName("Новое имя «" + current + "»:", current);
        if (n == null) {
            return;
        }
        try {
            store.rename(current, n);
            current = null;
            reloadList(n);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void delete() {
        if (current == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(frame, "Удалить конфиг «" + current + "»?", "Конфиг",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            store.delete(current);
            current = null;
            reloadList(null);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    /** Imports a DarkBot folder (all its profiles) or one profile file. */
    private void importDarkBot() {
        if (!confirmLeave()) {
            return;
        }
        JFileChooser ch = new JFileChooser();
        ch.setFileSelectionMode(JFileChooser.FILES_AND_DIRECTORIES);
        ch.setDialogTitle("Папка DarkBot или файл профиля (.json)");
        if (ch.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = ch.getSelectedFile().toPath();
        StringBuilder report = new StringBuilder();
        String last = null;
        try {
            List<Path> files = java.nio.file.Files.isDirectory(source) ? DarkBotImport.profiles(source) : List.of(source);
            if (files.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Профилей нет: выберите папку DarkBot или configs");
                return;
            }
            Boolean overwrite = null;
            for (Path f : files) {
                String name = DarkBotImport.nameOf(f);
                if (store.exists(name)) {
                    if (overwrite == null) {
                        overwrite = JOptionPane.showConfirmDialog(frame, "Конфиги уже есть (например «" + name
                                + "»). Заменить?", "Импорт", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION;
                    }
                    if (!overwrite) {
                        report.append(name).append(": уже есть, пропущен\n");
                        continue;
                    }
                }
                DarkBotImport.Result r = DarkBotImport.read(f);
                store.save(name, r.config());
                last = name;
                report.append(name).append(":\n");
                r.report().forEach(l -> report.append("   ").append(l).append('\n'));
            }
        } catch (IOException | RuntimeException e) {
            report.append("Ошибка: ").append(e.getMessage());
        }
        current = null;
        reloadList(last);
        javax.swing.JTextArea text = new javax.swing.JTextArea(report.toString(), 20, 80);
        text.setEditable(false);
        JOptionPane.showMessageDialog(frame, new JScrollPane(text), "Импорт из DarkBot", JOptionPane.INFORMATION_MESSAGE);
    }
}
