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
        ATTACKERS("Кто атакует"),
        BOOSTERS("Бустеры"),
        PET("Питомец"),
        GROUP("Группа"),
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
        HIDDEN("Скрыть");

        public final String label;

        Place(String label) {
            this.label = label;
        }
    }

    static final Path FILE = Path.of("local", "ui.properties");
    private static final ViewSettings SHARED = load(FILE);

    private final Path file;
    private final Map<Block, Place> places = new EnumMap<>(Block.class);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private ViewSettings(Path file) {
        this.file = file;
        for (Block b : Block.values()) {
            places.put(b, b == Block.TARGET || b == Block.ATTACKERS ? Place.MAP : Place.SIDE);
        }
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

    /** True when at least one block stands beside the map. */
    public synchronized boolean anySide() {
        return places.containsValue(Place.SIDE);
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
