package orbit.app.gui;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Where each block of the account view goes: beside the map, on the map, or nowhere. One choice for
 * all accounts, kept in {@code local/ui.properties}; the views repaint when it changes.
 */
public final class ViewSettings {

    /** The blocks of the account view, in the order they are stacked. */
    public enum Block {
        STATUS("Состояние"),
        SHIP("Корабль"),
        TARGET("Цель"),
        ATTACKERS("Атакующие"),
        BOOSTERS("Бустеры"),
        PET("Питомец"),
        GROUP("Группа"),
        QUESTS("Квесты"),
        STATS("Итоги сессии");

        public final String label;

        Block(String label) {
            this.label = label;
        }

        /** The column on the map: the fight and the results right, the ship and its state left. */
        boolean right() {
            return this == TARGET || this == ATTACKERS || this == BOOSTERS || this == STATS;
        }
    }

    public enum Place {
        SIDE("Сбоку"),
        MAP("На карте"),
        BOTH("Сбоку и на карте"),
        HIDDEN("Скрыть");

        public final String label;

        Place(String label) {
            this.label = label;
        }

        public boolean side() {
            return this == SIDE || this == BOTH;
        }

        public boolean map() {
            return this == MAP || this == BOTH;
        }
    }

    /** The parts of the account view around the map, each to show or hide. */
    public enum Section {
        HEADER("Шапка"),
        SIDE("Боковая панель"),
        LOG("Журнал");

        public final String label;

        Section(String label) {
            this.label = label;
        }
    }

    /** {@code local/ui.properties}; another file with {@code -Dorbit.ui.file=…} (tools that draw the window). */
    static final Path FILE = Path.of(System.getProperty("orbit.ui.file", "local/ui.properties"));
    private static final ViewSettings SHARED = load(FILE);

    private final Path file;
    private final Map<Block, Place> places = new EnumMap<>(Block.class);
    private final Map<Section, Boolean> shown = new EnumMap<>(Section.class);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ViewSettings(Path file) {
        this.file = file;
        for (Block b : Block.values()) {
            places.put(b, b == Block.TARGET || b == Block.ATTACKERS ? Place.MAP : Place.SIDE);
        }
        for (Section s : Section.values()) {
            shown.put(s, true);
        }
    }

    /** How the group's and the targets' bars write hull and shield. */
    public enum Numbers {
        EXACT("Числа (245 300 / 250 000)"),
        PERCENT("Проценты (98%)"),
        BOTH("Числа и проценты");

        public final String label;

        Numbers(String label) {
            this.label = label;
        }
    }

    private Numbers numbers = Numbers.EXACT;

    public synchronized Numbers numbers() {
        return numbers;
    }

    public void setNumbers(Numbers n) {
        synchronized (this) {
            numbers = n;
        }
        changed();
    }

    public static ViewSettings shared() {
        return SHARED;
    }

    /** Defaults, not saved (tests, snapshots). */
    static ViewSettings defaults() {
        return new ViewSettings(null);
    }

    static ViewSettings load(Path file) {
        ViewSettings v = new ViewSettings(file);
        if (file != null && Files.exists(file)) {
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(file)) {
                p.load(r);
                for (Section sec : Section.values()) {
                    String s = p.getProperty("section." + sec.name());
                    if (s != null) {
                        v.shown.put(sec, Boolean.parseBoolean(s));
                    }
                }
                try {
                    v.numbers = Numbers.valueOf(p.getProperty("numbers", Numbers.EXACT.name()));
                } catch (IllegalArgumentException ignored) {
                    // an old or broken value: numbers
                }
                for (Block b : Block.values()) {
                    String s = p.getProperty("block." + b.name());
                    if (s != null) {
                        try {
                            v.places.put(b, Place.valueOf(s));
                        } catch (IllegalArgumentException ignored) {
                            // an old or broken value: keep the default
                        }
                    }
                }
            } catch (IOException ignored) {
                // defaults
            }
        }
        return v;
    }

    public synchronized Place place(Block b) {
        return places.get(b);
    }

    public void set(Block b, Place p) {
        synchronized (this) {
            places.put(b, p);
        }
        changed();
    }

    /** Every block to one place. */
    public void setAll(Place p) {
        synchronized (this) {
            for (Block b : Block.values()) {
                places.put(b, p);
            }
        }
        changed();
    }

    /** True when the side panel is shown and at least one block stands beside the map. */
    public synchronized boolean anySide() {
        return shown.get(Section.SIDE) && (places.containsValue(Place.SIDE) || places.containsValue(Place.BOTH));
    }

    public synchronized boolean shown(Section s) {
        return shown.get(s);
    }

    public void show(Section s, boolean on) {
        synchronized (this) {
            shown.put(s, on);
        }
        changed();
    }

    /** Only the map: every part around it hidden (the blocks beside it move onto the map). */
    public void mapOnly() {
        synchronized (this) {
            for (Section s : Section.values()) {
                shown.put(s, false);
            }
        }
        changed();
    }

    /** True when every part around the map is hidden. */
    public synchronized boolean mapOnlyNow() {
        return !shown.containsValue(true);
    }

    /** Every part around the map shown again. */
    public void showAll() {
        synchronized (this) {
            for (Section s : Section.values()) {
                shown.put(s, true);
            }
        }
        changed();
    }

    /** True when the map shows this block: placed on it, or placed beside it while the side panel is hidden. */
    public synchronized boolean onMap(Block b) {
        Place p = places.get(b);
        return p.map() || (p.side() && !shown.get(Section.SIDE));
    }

    public void onChange(Runnable r) {
        listeners.add(r);
    }

    private void changed() {
        save();
        listeners.forEach(Runnable::run);
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        places.forEach((b, pl) -> p.setProperty("block." + b.name(), pl.name()));
        shown.forEach((s, on) -> p.setProperty("section." + s.name(), String.valueOf(on)));
        p.setProperty("numbers", numbers.name());
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                p.store(w, "Orbit: where each block of the account view goes");
            }
        } catch (IOException ignored) {
            // a preference only
        }
    }
}
