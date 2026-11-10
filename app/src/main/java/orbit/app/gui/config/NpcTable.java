package orbit.app.gui.config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.table.AbstractTableModel;
import orbit.config.BotConfig;
import orbit.world.maps.MapCatalog;

/** The NPC list of a config as an editable table (like AstraBot's NPC list). */
final class NpcTable extends AbstractTableModel {

    static final String[] COLUMNS = {"NPC", "Бить", "Приоритет", "Радиус", "SAB", "Патроны", "Карты", "Флаги"};
    static final String DEFAULT_AMMO = "общие";

    private static final class Row {
        String name;
        boolean kill;
        int priority;
        int radius;
        Set<Integer> maps;
        Set<String> flags;
        String ammo;
        boolean sab;
    }

    private final MapCatalog catalog;
    private final List<Row> rows = new ArrayList<>();

    NpcTable(MapCatalog catalog) {
        this.catalog = catalog;
    }

    void load(Map<String, BotConfig.NpcRule> npcs) {
        rows.clear();
        npcs.forEach((name, r) -> {
            Row row = new Row();
            row.name = name;
            row.kill = r.kill();
            row.priority = r.priority();
            row.radius = (int) Math.round(r.radius());
            row.maps = new LinkedHashSet<>(r.maps());
            row.flags = new LinkedHashSet<>(r.flags());
            row.ammo = r.ammo();
            row.sab = r.sab();
            rows.add(row);
        });
        fireTableDataChanged();
    }

    Map<String, BotConfig.NpcRule> get() {
        Map<String, BotConfig.NpcRule> out = new LinkedHashMap<>();
        for (Row r : rows) {
            String name = r.name == null ? "" : r.name.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (out.containsKey(name)) {
                throw new IllegalArgumentException("NPC «" + name + "» в списке дважды");
            }
            out.put(name, new BotConfig.NpcRule(r.radius, r.priority, r.kill, r.flags, r.maps, r.ammo, r.sab));
        }
        return out;
    }

    /** A new row (or the existing one with that name); returns its index. */
    int add(String name, int mapId) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).name.equals(name)) {
                return i;
            }
        }
        Row r = new Row();
        r.name = name;
        r.kill = true;
        r.radius = 550;
        r.maps = mapId == 0 ? new LinkedHashSet<>() : new LinkedHashSet<>(Set.of(mapId));
        r.flags = new LinkedHashSet<>();
        rows.add(r);
        fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        return rows.size() - 1;
    }

    void remove(int[] modelRows) {
        Arrays.stream(modelRows).boxed().sorted((a, b) -> b - a).forEach(i -> rows.remove((int) i));
        fireTableDataChanged();
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
    public String getColumnName(int c) {
        return COLUMNS[c];
    }

    @Override
    public Class<?> getColumnClass(int c) {
        return switch (c) {
            case 1, 4 -> Boolean.class;
            case 2, 3 -> Integer.class;
            default -> String.class;
        };
    }

    @Override
    public boolean isCellEditable(int r, int c) {
        return true;
    }

    @Override
    public Object getValueAt(int r, int c) {
        Row row = rows.get(r);
        return switch (c) {
            case 0 -> row.name;
            case 1 -> row.kill;
            case 2 -> row.priority;
            case 3 -> row.radius;
            case 4 -> row.sab;
            case 5 -> row.ammo == null ? DEFAULT_AMMO : orbit.config.ItemCatalog.label(row.ammo);
            case 6 -> mapsText(row.maps);
            default -> String.join(", ", row.flags);
        };
    }

    @Override
    public void setValueAt(Object v, int r, int c) {
        Row row = rows.get(r);
        switch (c) {
            case 0 -> row.name = String.valueOf(v);
            case 1 -> row.kill = (Boolean) v;
            case 2 -> row.priority = (Integer) v;
            case 3 -> row.radius = Math.max(0, (Integer) v);
            case 4 -> row.sab = (Boolean) v;
            case 5 -> row.ammo = ammoId(String.valueOf(v), row.ammo);
            case 6 -> row.maps = parseMaps(String.valueOf(v));
            default -> row.flags = Arrays.stream(String.valueOf(v).split("[,; ]+")).map(String::strip)
                    .filter(s -> !s.isEmpty()).map(String::toUpperCase).collect(Collectors.toCollection(LinkedHashSet::new));
        }
        fireTableCellUpdated(r, c);
    }

    /** The ammunition cell editor's choice back to an item id ({@code null} = the config's default). */
    static String ammoId(String label, String current) {
        if (DEFAULT_AMMO.equals(label)) {
            return null;
        }
        for (orbit.config.ItemCatalog.Item i : orbit.config.ItemCatalog.LASERS) {
            if (i.label().equals(label)) {
                return i.id();
            }
        }
        return current;
    }

    /** Choices of the ammunition column. */
    static String[] ammoChoices() {
        java.util.List<String> c = new java.util.ArrayList<>();
        c.add(DEFAULT_AMMO);
        orbit.config.ItemCatalog.LASERS.forEach(i -> c.add(i.label()));
        return c.toArray(String[]::new);
    }

    String mapsText(Set<Integer> maps) {
        if (maps.isEmpty()) {
            return "все";
        }
        return maps.stream().map(id -> catalog.info(id).known() ? catalog.info(id).name() : String.valueOf(id))
                .collect(Collectors.joining(", "));
    }

    /** "3-6, 2-6" or ids; "все" or empty = everywhere. Unknown names are refused. */
    Set<Integer> parseMaps(String text) {
        Set<Integer> out = new LinkedHashSet<>();
        String t = text.strip();
        if (t.isEmpty() || t.equalsIgnoreCase("все")) {
            return out;
        }
        for (String part : t.split("[,;]+")) {
            String p = part.strip();
            if (p.isEmpty()) {
                continue;
            }
            MapCatalog.MapInfo m = catalog.byName(p);
            if (m != null) {
                out.add(m.id());
            } else if (p.matches("-?\\d+")) {
                out.add(Integer.parseInt(p));
            } else {
                javax.swing.JOptionPane.showMessageDialog(null, "Карта «" + p + "» неизвестна", "Список NPC",
                        javax.swing.JOptionPane.WARNING_MESSAGE);
            }
        }
        return out;
    }
}
