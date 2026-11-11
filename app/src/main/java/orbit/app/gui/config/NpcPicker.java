package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

/**
 * A list to pick from with a search field over it: typing narrows the list (any part of the name), the arrows
 * move, Enter or a double click picks. With {@code custom} a name that is not in the list may be typed in.
 */
final class NpcPicker {

    private NpcPicker() {
    }

    /** What was picked and on which map's filter ({@code 0} = all maps). */
    record Pick(String item, int map) {
    }

    /** @return the chosen item, the typed text (with {@code custom}), or {@code null} when cancelled */
    static String pick(Component parent, String title, List<String> items, String current, boolean custom) {
        Pick p = pick(parent, title, items, current, custom, java.util.Map.of());
        return p == null ? null : p.item();
    }

    /**
     * The same with a map filter: {@code byMap} says which NPCs (bare names, {@link orbit.config.BotConfig#bare})
     * live on which map; an item counts by its name without a trailing «(number)».
     */
    static Pick pick(Component parent, String title, List<String> items, String current, boolean custom,
                     java.util.Map<Integer, java.util.Set<String>> byMap) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), title, JDialog.ModalityType.APPLICATION_MODAL);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", custom ? "поиск или своё имя" : "поиск");
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(16);
        String[] result = {null};
        // the map filter: «Все карты», then the maps with NPCs known, in the game's order of maps
        orbit.world.maps.MapCatalog catalog = orbit.world.maps.MapCatalog.bundled();
        java.util.List<Integer> maps = byMap.keySet().stream().filter(m -> catalog.info(m).known())
                .sorted(java.util.Comparator.comparing(m -> catalog.info(m).name())).toList();
        javax.swing.JComboBox<String> mapBox = new javax.swing.JComboBox<>();
        mapBox.addItem("Все карты");
        maps.forEach(m -> mapBox.addItem(catalog.info(m).name()));
        mapBox.setVisible(!maps.isEmpty());
        mapBox.setToolTipText("Только NPC, которых видели на этой карте (бот запоминает их сам)");
        Runnable filter = () -> {
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            int mi = mapBox.getSelectedIndex();
            java.util.Set<String> onMap = mi > 0 ? byMap.get(maps.get(mi - 1)) : null;
            model.clear();
            for (String i : items) {
                String key = orbit.config.BotConfig.bare(i.replaceFirst("\\s*\\(\\d+\\)$", ""));
                if ((q.isEmpty() || i.toLowerCase(Locale.ROOT).contains(q)) && (onMap == null || onMap.contains(key))) {
                    model.addElement(i);
                }
            }
            int at = current != null ? model.indexOf(current) : -1;
            list.setSelectedIndex(at >= 0 && q.isEmpty() ? at : model.isEmpty() ? -1 : 0);
            list.ensureIndexIsVisible(Math.max(0, list.getSelectedIndex()));
        };
        search.getDocument().addDocumentListener(DocumentChange.of(filter));
        mapBox.addActionListener(e -> filter.run());
        Runnable ok = () -> {
            String chosen = list.getSelectedValue();
            if (chosen == null && custom && !search.getText().isBlank()) {
                chosen = search.getText().strip();
            }
            if (chosen != null) {
                result[0] = chosen;
                d.dispose();
            }
        };
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    ok.run();
                }
            }
        });
        // the arrows move in the list while typing in the search field
        search.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "down");
        search.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "up");
        search.getActionMap().put("down", act(() -> list.setSelectedIndex(Math.min(model.size() - 1, list.getSelectedIndex() + 1)), list));
        search.getActionMap().put("up", act(() -> list.setSelectedIndex(Math.max(0, list.getSelectedIndex() - 1)), list));
        search.addActionListener(e -> ok.run());
        JButton choose = new JButton(custom ? "Добавить" : "Выбрать");
        choose.addActionListener(e -> ok.run());
        JButton cancel = new JButton("Отмена");
        cancel.addActionListener(e -> d.dispose());
        d.getRootPane().registerKeyboardAction(e -> d.dispose(), KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        buttons.add(cancel);
        buttons.add(choose);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.add(search, BorderLayout.CENTER);
        top.add(mapBox, BorderLayout.EAST);
        p.add(top, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(360, 360));
        p.add(scroll, BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.getRootPane().setDefaultButton(choose);
        filter.run();
        d.pack();
        d.setLocationRelativeTo(parent);
        search.requestFocusInWindow();
        d.setVisible(true);
        int mi = mapBox.getSelectedIndex();
        return result[0] == null ? null : new Pick(result[0], mi > 0 ? maps.get(mi - 1) : 0);
    }

    private static javax.swing.Action act(Runnable r, JList<String> list) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
                list.ensureIndexIsVisible(list.getSelectedIndex());
            }
        };
    }

    /**
     * Which NPCs live on which map (bare names): those the bot has met ({@link orbit.world.maps.NpcMaps}) and the
     * maps in the config's NPC list (imported from DarkBot, which learns them the same way).
     */
    static java.util.Map<Integer, java.util.Set<String>> byMap(java.util.Map<String, orbit.config.BotConfig.NpcRule> config) {
        java.util.Map<Integer, java.util.Set<String>> out = new java.util.HashMap<>();
        orbit.world.maps.NpcMaps seen = orbit.world.maps.NpcMaps.shared();
        for (int m : seen.maps()) {
            for (String n : seen.on(m)) {
                out.computeIfAbsent(m, k -> new java.util.HashSet<>()).add(orbit.config.BotConfig.bare(n));
            }
        }
        config.forEach((name, rule) -> rule.maps().forEach(m ->
                out.computeIfAbsent(m, k -> new java.util.HashSet<>()).add(orbit.config.BotConfig.bare(name))));
        return out;
    }
}
