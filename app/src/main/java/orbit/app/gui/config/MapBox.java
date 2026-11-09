package orbit.app.gui.config;

import java.util.Comparator;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import orbit.world.maps.MapCatalog;

/** Picks a map by its in-game name (1-1 … 5-3, BL maps, …). */
final class MapBox extends JComboBox<MapCatalog.MapInfo> {

    private final MapCatalog catalog;

    MapBox(MapCatalog catalog) {
        this.catalog = catalog;
        setMaximumRowCount(20);
        setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean sel, boolean focus) {
                String text = value instanceof MapCatalog.MapInfo m ? MapBox.label(m) : "";
                return super.getListCellRendererComponent(list, text, index, sel, focus);
            }
        });
        setMap(1);
    }

    static String label(MapCatalog.MapInfo m) {
        return m.known() ? m.name() + "  (" + m.id() + ")" : "карта " + m.id();
    }

    void setMap(int id) {
        DefaultComboBoxModel<MapCatalog.MapInfo> model = new DefaultComboBoxModel<>();
        catalog.all().values().stream().filter(m -> !m.name().isBlank())
                .sorted(Comparator.comparing((MapCatalog.MapInfo m) -> sortKey(m.name()))).forEach(model::addElement);
        MapCatalog.MapInfo current = catalog.info(id);
        if (!current.known()) {
            model.insertElementAt(current, 0);
        }
        setModel(model);
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i).id() == id) {
                setSelectedIndex(i);
            }
        }
    }

    int mapId() {
        MapCatalog.MapInfo m = (MapCatalog.MapInfo) getSelectedItem();
        return m == null ? 1 : m.id();
    }

    /** "1-1" … "4-4" first in order, then the rest by name. */
    private static String sortKey(String name) {
        return name.matches("\\d-\\d+") ? "0" + name.charAt(0) + String.format("%03d", Integer.parseInt(name.substring(2)))
                : "1" + name.toLowerCase();
    }
}
