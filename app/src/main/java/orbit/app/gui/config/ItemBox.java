package orbit.app.gui.config;

import java.util.List;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;

/**
 * Picks an item by name. A reference that only has a DarkBot slot key stays as an extra entry
 * ("клавиша 1 (из DarkBot)") until another item is chosen.
 */
final class ItemBox extends JComboBox<Object> {

    private static final String NONE = "—";
    private final List<ItemCatalog.Item> items;
    private final boolean allowNone;

    ItemBox(List<ItemCatalog.Item> items, boolean allowNone) {
        this.items = items;
        this.allowNone = allowNone;
        set(null);
    }

    void set(BotConfig.ItemRef ref) {
        DefaultComboBoxModel<Object> m = new DefaultComboBoxModel<>();
        if (allowNone) {
            m.addElement(NONE);
        }
        Object selected = allowNone ? NONE : (items.isEmpty() ? null : items.getFirst());
        if (ref != null && ref.item() == null && ref.key() != null) {
            m.addElement(ref);
            selected = ref;
        }
        for (ItemCatalog.Item i : items) {
            m.addElement(i);
            if (ref != null && i.id().equals(ref.item())) {
                selected = i;
            }
        }
        if (ref != null && ref.item() != null && items.stream().noneMatch(i -> i.id().equals(ref.item()))) {
            ItemCatalog.Item unknown = new ItemCatalog.Item(ref.item(), ref.item());
            m.addElement(unknown);
            selected = unknown;
        }
        setModel(m);
        setSelectedItem(selected);
        setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean sel, boolean focus) {
                Object shown = value instanceof BotConfig.ItemRef r ? ItemCatalog.label(r) : value;
                return super.getListCellRendererComponent(list, shown, index, sel, focus);
            }
        });
    }

    BotConfig.ItemRef get() {
        Object s = getSelectedItem();
        if (s instanceof ItemCatalog.Item i) {
            return BotConfig.ItemRef.item(i.id());
        }
        if (s instanceof BotConfig.ItemRef r) {
            return r;
        }
        return null;
    }

    /** For pet gears and similar plain ids. */
    String id() {
        return getSelectedItem() instanceof ItemCatalog.Item i ? i.id() : null;
    }

    void setId(String id) {
        set(id == null ? null : BotConfig.ItemRef.item(id));
    }
}
