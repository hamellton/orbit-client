package orbit.app.gui.config;

import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import orbit.config.ItemCatalog;

/** Picks an item by name; gives its id. An id missing from the catalog is kept as its own entry. */
final class ItemBox extends JComboBox<Object> {

    private static final String NONE = "— как выбрано в игре";
    private final List<ItemCatalog.Item> items;
    private final boolean allowNone;

    ItemBox(List<ItemCatalog.Item> items, boolean allowNone) {
        this.items = items;
        this.allowNone = allowNone;
        set(null);
    }

    void set(String id) {
        DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<>();
        if (allowNone) {
            m.addElement(NONE);
        }
        Object selected = allowNone ? NONE : (items.isEmpty() ? null : items.getFirst());
        for (ItemCatalog.Item i : items) {
            m.addElement(i);
            if (i.id().equals(id)) {
                selected = i;
            }
        }
        if (id != null && items.stream().noneMatch(i -> i.id().equals(id))) {
            ItemCatalog.Item unknown = new ItemCatalog.Item(id, id);
            m.addElement(unknown);
            selected = unknown;
        }
        setModel(m);
        setSelectedItem(selected);
    }

    String get() {
        return getSelectedItem() instanceof ItemCatalog.Item i ? i.id() : null;
    }
}
