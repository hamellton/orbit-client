package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where on a map the enemies have been seen and the ship was killed: a grid of {@link #CELL} cells per map, kept in a
 * file between runs. The behemoth module waits where it is lowest (the user, 2026-11-14: «найдём идеальную точку, чтобы
 * дождаться бегемота и чтобы поезда не били» — the trains' rounds differ, so the place is learned, not guessed).
 */
public final class EnemyHeat {

    private static final Logger log = LoggerFactory.getLogger(EnemyHeat.class);

    static final int CELL = 500;
    static final int COLS = 44;
    static final int ROWS = 28;

    private final Path file;
    private final Map<Integer, double[]> maps = new HashMap<>();
    private long savedAt;

    private EnemyHeat(Path file) {
        this.file = file;
    }

    public static EnemyHeat inMemory() {
        return new EnemyHeat(null);
    }

    public static EnemyHeat open(Path file) {
        EnemyHeat h = new EnemyHeat(file);
        if (file != null && Files.exists(file)) {
            try {
                JsonNode n = orbit.config.Json.MAPPER.readTree(file.toFile());
                n.properties().forEach(e -> {
                    double[] g = new double[COLS * ROWS];
                    for (int i = 0; i < g.length && i < e.getValue().size(); i++) {
                        g[i] = e.getValue().get(i).asDouble();
                    }
                    h.maps.put(Integer.parseInt(e.getKey()), g);
                });
            } catch (java.io.IOException | RuntimeException e) {
                log.warn("Не прочитал {}: {}", file.getFileName(), e.toString());
            }
        }
        return h;
    }

    public synchronized void add(int map, double x, double y, double weight) {
        int cx = (int) (x / CELL);
        int cy = (int) (y / CELL);
        if (cx < 0 || cy < 0 || cx >= COLS || cy >= ROWS) {
            return;
        }
        maps.computeIfAbsent(map, k -> new double[COLS * ROWS])[cy * COLS + cx] += weight;
    }

    /** The heat within {@code radius} of the point, the nearer the more. */
    public synchronized double around(int map, double x, double y, double radius) {
        double[] g = maps.get(map);
        if (g == null) {
            return 0;
        }
        double sum = 0;
        int r = (int) Math.ceil(radius / CELL);
        int cx = (int) (x / CELL);
        int cy = (int) (y / CELL);
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                int ix = cx + dx;
                int iy = cy + dy;
                if (ix < 0 || iy < 0 || ix >= COLS || iy >= ROWS) {
                    continue;
                }
                double px = (ix + 0.5) * CELL;
                double py = (iy + 0.5) * CELL;
                double d = Math.hypot(px - x, py - y);
                if (d <= radius) {
                    sum += g[iy * COLS + ix] * (1 - d / radius); // the nearer the more it weighs
                }
            }
        }
        return sum;
    }

    /** All the heat of the map: how much has been learned there. */
    public synchronized double total(int map) {
        double[] g = maps.get(map);
        if (g == null) {
            return 0;
        }
        double sum = 0;
        for (double v : g) {
            sum += v;
        }
        return sum;
    }

    /** Written at most once a minute. */
    public synchronized void saveSometimes(long nowNanos) {
        if (file == null || nowNanos - savedAt < 60_000_000_000L) {
            return;
        }
        savedAt = nowNanos;
        try {
            ObjectNode n = orbit.config.Json.MAPPER.createObjectNode();
            maps.forEach((m, g) -> {
                var a = n.putArray(String.valueOf(m));
                for (double v : g) {
                    a.add(Math.round(v * 10) / 10.0);
                }
            });
            Path tmp = file.resolveSibling(file.getFileName() + ".new");
            orbit.config.Json.MAPPER.writeValue(tmp.toFile(), n);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Не записал {}: {}", file.getFileName(), e.toString());
        }
    }
}
