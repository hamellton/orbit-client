package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
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
    /** The folders and their configs (the user, 2026-11-16: «фасовка конфигов по папкам»). */
    private final javax.swing.tree.DefaultMutableTreeNode root = new javax.swing.tree.DefaultMutableTreeNode();
    private final javax.swing.tree.DefaultTreeModel model = new javax.swing.tree.DefaultTreeModel(root);
    private final javax.swing.JTree list = new javax.swing.JTree(model);
    /** The names shown, to select one by name. */
    private final java.util.Set<String> names = new java.util.HashSet<>();
    private JButton saveButton;

    /** A tree node: a folder ({@code name == null}) or a config in it. */
    private record Node(String folder, String name) {
        @Override
        public String toString() {
            if (name != null) {
                return name;
            }
            return folder.isEmpty() ? "Без папки" : ConfigStore.DEFAULT_FOLDER.equals(folder) ? "🔒 " + folder : folder;
        }
    }
    private final List<Section> sections = new ArrayList<>();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JLabel title = new JLabel(" ");
    /** The check result; cut in a narrow window, whole in its tooltip. */
    private final JLabel check = new JLabel(" ") {
        @Override
        public String getToolTipText() {
            return getText().isBlank() ? null : getText();
        }
    };
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
        sections.add(new BehemothSection());
        sections.add(new PalladiumSection());
        sections.add(new QuestsSection());
        sections.add(new GateSection());
        sections.add(new EbgSection());
        sections.add(new LowSection());
        sections.add(new MimesisSection());
        sections.add(new EnrichSection());
        sections.add(new BuySection());
        sections.add(new GroupSection());
        sections.forEach(s -> tabs.addTab(s.title(), s.view()));
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT); // one row of tabs: a small window keeps its height
        tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        accounts = new AccountsPanel(accountsFile, this::configNames);

        list.setRootVisible(false);
        list.setShowsRootHandles(true);
        list.getSelectionModel().setSelectionMode(javax.swing.tree.TreeSelectionModel.SINGLE_TREE_SELECTION);
        list.addTreeSelectionListener(e -> {
            Node n = selectedNode();
            if (n != null && n.name() != null && !n.name().equals(current)) {
                open(n.name());
            }
        });
        JPanel buttons = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        buttons.add(button("Новый", this::create));
        buttons.add(button("Копия", this::copy));
        buttons.add(button("В папку…", this::moveToFolder));
        buttons.add(button("Новая папка", this::createFolder));
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
        check.setMinimumSize(new Dimension(0, 0)); // a narrow window cuts it with «…»; the tooltip has it all
        javax.swing.ToolTipManager.sharedInstance().registerComponent(check);
        bottom.add(check, BorderLayout.CENTER);
        saveButton = button("Сохранить", this::save);
        // the main action in the accent colour
        saveButton.setBackground(new java.awt.Color(0x0e4a66));
        saveButton.setForeground(orbit.app.gui.Theme.TEXT);
        bottom.add(Form.bar(button("Проверить", this::runCheck), button("Отменить", this::revert),
                saveButton), BorderLayout.EAST);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        right.add(title, BorderLayout.NORTH);
        right.add(tabs, BorderLayout.CENTER);
        right.add(bottom, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        // the config list takes about a fifth of the width (140–240 px) until the user drags the divider
        left.setMinimumSize(new Dimension(110, 0));
        right.setMinimumSize(new Dimension(280, 0));
        split.setOneTouchExpandable(true);
        split.setContinuousLayout(true);
        split.setDividerLocation(220);
        boolean[] auto = {true};
        boolean[] adjusting = {false};
        Runnable fitList = () -> {
            if (auto[0] && split.getWidth() > 0) {
                adjusting[0] = true;
                split.setDividerLocation(Math.max(140, Math.min(240, split.getWidth() / 5)));
                adjusting[0] = false;
            }
        };
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fitList.run();
            }
        });
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (!adjusting[0] && split.getWidth() > 0 && split.isShowing()) {
                auto[0] = false; // dragged by the user: keep it
            }
        });
        JTabbedPane pages = new JTabbedPane();
        pages.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
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
        return snapshot(store, accountsFile, select, 0, 0, width, height);
    }

    /**
     * The same on a chosen page and tab, to check every tab at every window size.
     *
     * @param page 0 = «Конфиги», 1 = «Аккаунты»
     * @param tab  the section tab on «Конфиги» (0 = «Общее»)
     */
    public static java.awt.image.BufferedImage snapshot(ConfigStore store, Path accountsFile, String select, int page,
                                                        int tab, int width, int height) {
        orbit.app.gui.Theme.setup();
        ConfigWindow w = new ConfigWindow(store, accountsFile, MapCatalog.bundled());
        if (select != null && w.names.contains(select)) {
            w.current = null;
            w.reloadList(select);
        }
        java.awt.Container c = w.frame.getContentPane();
        if (c instanceof JTabbedPane pages) {
            pages.setSelectedIndex(Math.min(page, pages.getTabCount() - 1));
        }
        w.tabs.setSelectedIndex(Math.min(tab, w.tabs.getTabCount() - 1));
        w.frame.setSize(width, height); // a relayout inside sizes the content to the (never shown) frame
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
        root.removeAllChildren();
        String first = null;
        try {
            for (var e : store.folders().entrySet()) {
                javax.swing.tree.DefaultMutableTreeNode f = new javax.swing.tree.DefaultMutableTreeNode(new Node(e.getKey(), null));
                for (String n : e.getValue()) {
                    f.add(new javax.swing.tree.DefaultMutableTreeNode(new Node(e.getKey(), n)));
                    names.add(n);
                    if (first == null && !ConfigStore.DEFAULT_FOLDER.equals(e.getKey())) {
                        first = n;
                    }
                }
                root.add(f);
            }
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(frame, ex.getMessage(), "Конфиги", JOptionPane.ERROR_MESSAGE);
        }
        model.reload();
        for (int i = 0; i < list.getRowCount(); i++) {
            list.expandRow(i);
        }
        if (first == null && !names.isEmpty()) {
            first = names.iterator().next();
        }
        String target = select != null && names.contains(select) ? select : first;
        if (target == null) {
            current = null;
            loaded = null;
            title.setText("Конфигов нет — создайте или импортируйте");
            tabs.setEnabled(false);
            return;
        }
        current = null;
        selectConfig(target);
        if (current == null) {
            open(target); // already selected: no selection event
        }
    }

    private void open(String name) {
        if (!confirmLeave()) {
            selectConfig(current);
            return;
        }
        try {
            loaded = store.load(name);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage(), "Конфиг", JOptionPane.ERROR_MESSAGE);
            selectConfig(current);
            return;
        }
        current = name;
        tabs.setEnabled(true);
        boolean readOnly = store.isDefault(name);
        setFormEnabled(true); // first: a section's load sets its own dependent fields off again
        sections.forEach(s -> s.load(loaded));
        baseline = collect(loaded);
        String folder = store.folderOf(name);
        title.setText(readOnly ? "🔒 " + folder + " / " + name + " — конфиг программы, только просмотр («Копия» — чтобы менять)"
                : (folder.isEmpty() ? "" : folder + " / ") + name);
        if (readOnly) {
            setFormEnabled(false);
        }
        runCheck();
    }

    /** The default configs are shown, never edited: the form's fields off, «Сохранить» off. */
    private void setFormEnabled(boolean on) {
        for (Section sec : sections) {
            enableAll(sec.view(), on);
        }
        if (saveButton != null) {
            saveButton.setEnabled(on);
        }
    }

    private static void enableAll(java.awt.Component c, boolean on) {
        if (!(c instanceof JScrollPane) && !(c instanceof javax.swing.JViewport) && !(c instanceof JPanel)
                && !(c instanceof JTabbedPane)) {
            c.setEnabled(on);
        }
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component child : k.getComponents()) {
                enableAll(child, on);
            }
        }
    }

    private Node selectedNode() {
        Object o = list.getLastSelectedPathComponent();
        return o instanceof javax.swing.tree.DefaultMutableTreeNode t && t.getUserObject() instanceof Node n ? n : null;
    }

    /** The selected folder: the config's own, or the folder node's ({@code null} none selected). */
    private String selectedFolder() {
        Node n = selectedNode();
        return n == null ? null : n.folder();
    }

    private void selectConfig(String name) {
        if (name == null) {
            return;
        }
        java.util.Enumeration<javax.swing.tree.TreeNode> e = root.depthFirstEnumeration();
        while (e.hasMoreElements()) {
            if (e.nextElement() instanceof javax.swing.tree.DefaultMutableTreeNode t && t.getUserObject() instanceof Node n
                    && name.equals(n.name())) {
                javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(t.getPath());
                list.setSelectionPath(path);
                list.scrollPathToVisible(path);
                return;
            }
        }
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

    /** The folder a new config or a copy goes to: the selected one unless it is the defaults' («» = no folder). */
    private String targetFolder() {
        String f = selectedFolder();
        return f == null || ConfigStore.DEFAULT_FOLDER.equals(f) ? "" : f;
    }

    /** The user's folders to pick from (no folder first, the defaults' never). */
    private List<String> userFolders() {
        List<String> out = new ArrayList<>();
        out.add("");
        try {
            for (String f : store.folders().keySet()) {
                if (!f.isEmpty() && !ConfigStore.DEFAULT_FOLDER.equals(f)) {
                    out.add(f);
                }
            }
        } catch (IOException ignored) {
            // just the top level
        }
        return out;
    }

    /** A folder chosen from the user's ones, or a new one typed in; {@code null} cancelled. */
    private String askFolder(String question, String initial) {
        List<String> folders = userFolders();
        String none = "— без папки";
        List<String> shown = new ArrayList<>();
        folders.forEach(f -> shown.add(f.isEmpty() ? none : f));
        javax.swing.JComboBox<String> box = new javax.swing.JComboBox<>(shown.toArray(new String[0]));
        box.setEditable(true);
        box.setSelectedItem(initial == null || initial.isEmpty() ? none : initial);
        int ok = JOptionPane.showConfirmDialog(frame, new Object[] {question, box}, "Папка", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || box.getSelectedItem() == null) {
            return null;
        }
        String f = box.getSelectedItem().toString().strip();
        return f.equals(none) ? "" : f;
    }

    private void create() {
        if (!confirmLeave()) {
            return;
        }
        String folder = targetFolder();
        String n = askName("Имя нового конфига" + (folder.isEmpty() ? "" : " в «" + folder + "»") + ":", "");
        if (n == null) {
            return;
        }
        try {
            store.save(folder, n, BotConfig.empty());
            current = null;
            reloadList(n);
        } catch (IOException | IllegalArgumentException e) {
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
        String from = store.folderOf(current);
        String folder = askFolder("В какую папку положить «" + n + "»:", ConfigStore.DEFAULT_FOLDER.equals(from) ? ""
                : from);
        if (folder == null) {
            return;
        }
        try {
            store.copy(current, n, folder);
            current = null;
            reloadList(n);
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void moveToFolder() {
        if (current == null || !confirmLeave()) {
            return;
        }
        if (store.isDefault(current)) {
            JOptionPane.showMessageDialog(frame, "«" + current + "» — конфиг программы: его не переносят, сделайте копию");
            return;
        }
        String folder = askFolder("Перенести «" + current + "» в папку:", store.folderOf(current));
        if (folder == null) {
            return;
        }
        try {
            String n = current;
            store.move(n, folder);
            current = null;
            reloadList(n);
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void createFolder() {
        String f = (String) JOptionPane.showInputDialog(frame, "Имя новой папки:", "Папка", JOptionPane.PLAIN_MESSAGE,
                null, null, "");
        if (f == null || f.isBlank()) {
            return;
        }
        try {
            store.createFolder(f.strip());
            String keep = current;
            current = null;
            reloadList(keep);
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void rename() {
        Node sel = selectedNode();
        if (sel != null && sel.name() == null) {
            renameFolder(sel.folder());
            return;
        }
        if (current == null || !confirmLeave()) {
            return;
        }
        if (store.isDefault(current)) {
            JOptionPane.showMessageDialog(frame, "«" + current + "» — конфиг программы: его не переименовывают, сделайте копию");
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

    private void renameFolder(String folder) {
        if (folder.isEmpty() || ConfigStore.DEFAULT_FOLDER.equals(folder)) {
            return;
        }
        String to = (String) JOptionPane.showInputDialog(frame, "Новое имя папки «" + folder + "»:", "Папка",
                JOptionPane.PLAIN_MESSAGE, null, null, folder);
        if (to == null || to.isBlank() || to.strip().equals(folder)) {
            return;
        }
        try {
            store.renameFolder(folder, to.strip());
            String keep = current;
            current = null;
            reloadList(keep);
        } catch (IOException | IllegalArgumentException e) {
            JOptionPane.showMessageDialog(frame, e.getMessage());
        }
    }

    private void delete() {
        Node sel = selectedNode();
        if (sel != null && sel.name() == null) {
            if (sel.folder().isEmpty() || ConfigStore.DEFAULT_FOLDER.equals(sel.folder())) {
                return;
            }
            try {
                store.deleteFolder(sel.folder());
                String keep = current;
                current = null;
                reloadList(keep);
            } catch (IOException | IllegalArgumentException e) {
                JOptionPane.showMessageDialog(frame, e.getMessage());
            }
            return;
        }
        if (current == null) {
            return;
        }
        if (store.isDefault(current)) {
            JOptionPane.showMessageDialog(frame, "«" + current + "» — конфиг программы: его не удаляют");
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
