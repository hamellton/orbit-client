package orbit.app.gui;

import java.awt.Component;
import java.awt.Font;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JList;

/**
 * A config to choose, by folders (a flat list hid which folder a config was in): each folder a heading that cannot
 * be chosen, its configs under it indented; the chosen one
 * shown with its folder. The items are the config names themselves (unique over the folders), so callers keep
 * {@code (String) getSelectedItem()}.
 */
public final class ConfigBox extends JComboBox<String> {

    /** A heading's item: this prefix and the folder (no config name can hold it: names are letters, digits, {@code _-. }). */
    static final String HEAD = "\u0001";

    private final Map<String, String> folderOf = new HashMap<>();
    /** Items above the folders (e.g. the no-config item), shown as they are. */
    private final java.util.Set<String> plain = new java.util.HashSet<>();

    public ConfigBox() {
        setMaximumRowCount(24);
        setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean sel,
                                                          boolean focus) {
                String v = value == null ? "" : value.toString();
                boolean head = v.startsWith(HEAD);
                String text;
                if (head) {
                    text = folderLabel(v.substring(HEAD.length()));
                } else if (index < 0) {
                    String f = folderOf.get(v);
                    text = f == null || plain.contains(v) ? v : folderLabel(f) + "  ›  " + v;
                } else {
                    text = v;
                }
                super.getListCellRendererComponent(list, text, index, sel && !head, focus && !head);
                if (head) {
                    setFont(getFont().deriveFont(Font.BOLD));
                    setForeground(Hud.DIM);
                    setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));
                } else if (index >= 0 && !plain.contains(v)) {
                    setBorder(BorderFactory.createEmptyBorder(1, 20, 1, 4));
                }
                return this;
            }
        });
    }

    /** The folder as the config window names it. */
    static String folderLabel(String folder) {
        return folder.isEmpty() ? "Без папки" : orbit.config.ConfigStore.isProgramFolder(folder)
                ? "🔒 " + folder + " (программы)" : "📁 " + folder;
    }

    /**
     * The list anew: {@code first} items as they are, then each folder of {@code folders} (heading, its configs); the
     * selection {@code current}, added at the end when it is in no folder.
     */
    public void fill(List<String> first, Map<String, List<String>> folders, String current) {
        removeAllItems();
        folderOf.clear();
        plain.clear();
        for (String f : first) {
            plain.add(f);
            addItem(f);
        }
        for (Map.Entry<String, List<String>> e : folders.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            addItem(HEAD + e.getKey());
            for (String n : e.getValue()) {
                folderOf.put(n, e.getKey());
                addItem(n);
            }
        }
        if (current != null && !plain.contains(current) && !folderOf.containsKey(current)) {
            plain.add(current);
            addItem(current);
        }
        setSelectedItem(current);
    }

    /** The store's folders, read now (none on an error). */
    public static Map<String, List<String>> folders() {
        try {
            return orbit.config.ConfigStore.openDefault().folders();
        } catch (java.io.IOException e) {
            return Map.of();
        }
    }

    /** A heading is never chosen: the config next to it the way the selection moves (the keys, a click). */
    @Override
    public void setSelectedItem(Object item) {
        if (item instanceof String s && s.startsWith(HEAD)) {
            int at = -1;
            for (int i = 0; i < getItemCount(); i++) {
                if (s.equals(getItemAt(i))) {
                    at = i;
                    break;
                }
            }
            int step = at >= 0 && getSelectedIndex() > at ? -1 : 1;
            for (int i = at + step; i >= 0 && i < getItemCount(); i += step) {
                String v = getItemAt(i);
                if (!v.startsWith(HEAD)) {
                    super.setSelectedItem(v);
                    return;
                }
            }
            return;
        }
        super.setSelectedItem(item);
    }
}
