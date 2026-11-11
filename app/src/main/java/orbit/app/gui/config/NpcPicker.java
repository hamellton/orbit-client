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

    /** @return the chosen item, the typed text (with {@code custom}), or {@code null} when cancelled */
    static String pick(Component parent, String title, List<String> items, String current, boolean custom) {
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), title, JDialog.ModalityType.APPLICATION_MODAL);
        JTextField search = new JTextField();
        search.putClientProperty("JTextField.placeholderText", custom ? "поиск или своё имя" : "поиск");
        DefaultListModel<String> model = new DefaultListModel<>();
        JList<String> list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(16);
        String[] result = {null};
        Runnable filter = () -> {
            String q = search.getText().strip().toLowerCase(Locale.ROOT);
            model.clear();
            for (String i : items) {
                if (q.isEmpty() || i.toLowerCase(Locale.ROOT).contains(q)) {
                    model.addElement(i);
                }
            }
            int at = current != null ? model.indexOf(current) : -1;
            list.setSelectedIndex(at >= 0 && q.isEmpty() ? at : model.isEmpty() ? -1 : 0);
            list.ensureIndexIsVisible(Math.max(0, list.getSelectedIndex()));
        };
        search.getDocument().addDocumentListener(DocumentChange.of(filter));
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
        p.add(search, BorderLayout.NORTH);
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
        return result[0];
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
}
