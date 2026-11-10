package orbit.bot.mod;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where boss NPCs (Cubikons) were seen, when they were killed and how long they took to come back —
 * learned while the bot plays, kept in {@code local/cubikon-spawns.json} per map. Points closer than
 * {@link #SAME_SPAWN} are one spawn. Times are wall-clock milliseconds (they survive restarts).
 */
public final class SpawnMemory {

    private static final Logger log = LoggerFactory.getLogger(SpawnMemory.class);
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static final Path DEFAULT_FILE = Path.of("local", "cubikon-spawns.json");
    /** Two sightings closer than this are the same spawn. */
    public static final double SAME_SPAWN = 1_500;
    /** Respawn samples kept per spawn. */
    static final int SAMPLES = 20;
    /** A respawn longer than this is not a respawn we saw (the bot was away). */
    static final long MAX_RESPAWN_MS = 6 * 3_600_000L;
    /** Shorter than this is not a respawn but the same cube seen again. */
    static final long MIN_RESPAWN_MS = 60_000L;

    /**
     * One spawn point.
     *
     * @param seen      sightings of a new Cubikon here
     * @param lastSeen  last time a new one appeared here
     * @param lastKill  last time one died here (0 = unknown)
     * @param respawnMs observed times from a death to the next appearance
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Spawn(int x, int y, int seen, long lastSeen, long lastKill, List<Long> respawnMs) {

        public Spawn {
            respawnMs = respawnMs == null ? List.of() : List.copyOf(respawnMs);
        }

        /**
         * The shortest observed respawn, or -1 with no samples. A sample can only come out too long (the
         * bot saw the cube later than it appeared, or missed one in between), never too short, so the
         * minimum is the best guess.
         */
        public long respawnEstimate() {
            return respawnMs.stream().mapToLong(Long::longValue).filter(v -> v >= MIN_RESPAWN_MS).min().orElse(-1);
        }

        /** When the next one is expected here, or -1 if unknown. */
        public long expectedAt() {
            return expectedAt(respawnEstimate());
        }

        /** When the next one is expected here with a given respawn time (e.g. the map's), or -1. */
        public long expectedAt(long respawn) {
            return respawn < 0 || lastKill == 0 || lastKill < lastSeen ? -1 : lastKill + respawn;
        }

        double distanceTo(double px, double py) {
            return Math.hypot(x - px, y - py);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(Map<Integer, List<Spawn>> maps) {
        Data {
            maps = maps == null ? new TreeMap<>() : new TreeMap<>(maps);
        }
    }

    private final Path file;
    private final Map<Integer, List<Spawn>> maps = new TreeMap<>();

    private SpawnMemory(Path file) {
        this.file = file;
    }

    /** Kept in memory only (tests). */
    public static SpawnMemory inMemory() {
        return new SpawnMemory(null);
    }

    public static SpawnMemory open(Path file) {
        SpawnMemory m = new SpawnMemory(file);
        if (file != null && Files.exists(file)) {
            try {
                Data d = JSON.readValue(file.toFile(), Data.class);
                d.maps().forEach((k, v) -> m.maps.put(k, new ArrayList<>(v)));
            } catch (IOException e) {
                log.warn("Не прочитать {}: {} — начинаю заново", file, e.getMessage());
            }
        }
        return m;
    }

    public synchronized List<Spawn> spawns(int mapId) {
        return List.copyOf(maps.getOrDefault(mapId, List.of()));
    }

    /** A new Cubikon appeared at (x, y). */
    public synchronized void seen(int mapId, int x, int y, long nowMs) {
        List<Spawn> list = maps.computeIfAbsent(mapId, k -> new ArrayList<>());
        int i = find(list, x, y);
        if (i < 0) {
            list.add(new Spawn(x, y, 1, nowMs, 0, List.of()));
            log.info("Новый спаун куба: {}/{}", x / 100, y / 100);
        } else {
            Spawn s = list.get(i);
            List<Long> samples = new ArrayList<>(s.respawnMs());
            if (s.lastKill() > s.lastSeen() && nowMs - s.lastKill() <= MAX_RESPAWN_MS) {
                samples.add(nowMs - s.lastKill());
                while (samples.size() > SAMPLES) {
                    samples.removeFirst();
                }
                log.info("Куб вернулся на {}/{} через {} мин", s.x() / 100, s.y() / 100, (nowMs - s.lastKill()) / 60_000);
            }
            list.set(i, new Spawn(s.x(), s.y(), s.seen() + 1, nowMs, s.lastKill(), samples));
        }
        save();
    }

    /** A Cubikon died (or vanished in sight) at (x, y). */
    public synchronized void killed(int mapId, int x, int y, long nowMs) {
        List<Spawn> list = maps.computeIfAbsent(mapId, k -> new ArrayList<>());
        int i = find(list, x, y);
        if (i < 0) {
            list.add(new Spawn(x, y, 1, nowMs, nowMs, List.of()));
        } else {
            Spawn s = list.get(i);
            list.set(i, new Spawn(s.x(), s.y(), s.seen(), s.lastSeen(), nowMs, s.respawnMs()));
        }
        save();
    }

    /**
     * The respawn time of the map: the shortest seen on any of its spawns (cubes seem to come back after
     * the same time everywhere, and a long sample only means the bot was elsewhere), or -1.
     */
    public synchronized long mapRespawn(int mapId) {
        return maps.getOrDefault(mapId, List.of()).stream().mapToLong(Spawn::respawnEstimate).filter(v -> v > 0)
                .min().orElse(-1);
    }

    public synchronized void forget(int mapId) {
        maps.remove(mapId);
        save();
    }

    private static int find(List<Spawn> list, double x, double y) {
        int best = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).distanceTo(x, y) <= SAME_SPAWN
                    && (best < 0 || list.get(i).distanceTo(x, y) < list.get(best).distanceTo(x, y))) {
                best = i;
            }
        }
        return best;
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writeValue(tmp.toFile(), new Data(new TreeMap<>(maps)));
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Не сохранить {}: {}", file, e.getMessage());
        }
    }

    /** Most recently active first. */
    static Comparator<Spawn> byRecent() {
        return Comparator.comparingLong(Spawn::lastSeen).reversed();
    }
}
