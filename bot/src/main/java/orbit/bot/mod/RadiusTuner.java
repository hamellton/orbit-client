package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The distance to fight each NPC kind at, learned from the fights (the user, 2026-11-13: «чтобы цель не била
 * корабль»): the target that hit the ship a good part of a fight is fought {@link #STEP} farther next time; once the
 * lasers stopped reaching it (it lost nothing), {@link #STEP_BACK} nearer, and that is the kind's limit. Kept in
 * {@code local/npc-radius-learned.json}; used when the config's NPC list leaves the radius at 0.
 */
public final class RadiusTuner {

    private static final Logger log = LoggerFactory.getLogger(RadiusTuner.class);
    static final double STEP = 30;
    static final double STEP_BACK = 40;
    /** No laser reaches farther than this (a ship's own lasers ~ 650–700). */
    static final double MAX = 760;
    /** The target hitting the ship more than this share of the fight: farther next time. */
    static final double HIT_SHARE = 0.25;
    static final double MIN_SECONDS = 6;
    /** Short fights of a kind are summed until this much time in range (live 2026-11-13 on 2-8: StreuneR dies in
     * 5 s, hit the ship 46 % of it, never learned). */
    static final double SUM_SECONDS = 12;
    /** name → {seconds in range, seconds hit, radius}: short won fights not yet learned from. */
    private static final Map<String, double[]> SHORT = new TreeMap<>();
    private static final Path FILE = Path.of("local", "npc-radius-learned.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, double[]> KINDS = new TreeMap<>(); // name → {radius, limit}
    private static volatile boolean onDisk;
    /** Learning without the file (its own test); off in the other tests, so they do not change each other's radii. */
    static volatile boolean testing;
    private static boolean read;

    private RadiusTuner() {
    }

    /** The app keeps what is learned; tests do not. */
    public static void keepOnDisk() {
        onDisk = true;
    }

    /** The learned radius of a kind, or {@code base} when none yet. */
    static synchronized double radius(String name, double base) {
        load();
        double[] k = name == null ? null : KINDS.get(name);
        return k == null ? base : k[0];
    }

    /**
     * One fight's lesson.
     *
     * @param base     the radius the kind is fought at with nothing learned
     * @param seconds  how long it was in range
     * @param hitShare the share of that time the target shot the ship
     * @param reached  the target lost hull or shield while in range
     */
    static synchronized void learn(String name, double base, double used, double seconds, double hitShare, boolean reached) {
        if (!onDisk && !testing || name == null || seconds <= 0) {
            return;
        }
        if (seconds < MIN_SECONDS) {
            if (!reached) {
                return; // too short to blame the range
            }
            double[] sum = SHORT.get(name);
            if (sum == null || Math.abs(sum[2] - used) > 1) {
                sum = new double[] {0, 0, used};
                SHORT.put(name, sum);
            }
            sum[0] += seconds;
            sum[1] += seconds * hitShare;
            if (sum[0] < SUM_SECONDS) {
                return;
            }
            SHORT.remove(name);
            seconds = sum[0];
            hitShare = sum[1] / sum[0];
        }
        load();
        double[] k = KINDS.computeIfAbsent(name, n -> new double[] {base, MAX});
        if (Math.abs(k[0] - used) > 1) {
            return; // fought at another distance (the config's own): nothing to learn for this one
        }
        double before = k[0];
        if (!reached) {
            k[1] = Math.max(base * 0.6, used - STEP_BACK);
            k[0] = k[1];
        } else if (hitShare > HIT_SHARE && used + STEP <= k[1]) {
            k[0] = used + STEP;
        } else {
            return;
        }
        log.info("Радиус для {}: {} → {} ({})", name, Math.round(before), Math.round(k[0]),
                reached ? "били нас " + Math.round(hitShare * 100) + "% боя" : "лазеры не доставали");
        save();
    }

    private static void load() {
        if (read || !onDisk) {
            return;
        }
        read = true;
        try {
            if (Files.exists(FILE)) {
                JSON.readTree(FILE.toFile()).fields().forEachRemaining(e -> {
                    JsonNode n = e.getValue();
                    KINDS.put(e.getKey(), new double[] {n.path("radius").asDouble(), n.path("limit").asDouble(MAX)});
                });
            }
        } catch (IOException e) {
            // learned again
        }
    }

    private static void save() {
        if (!onDisk) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        KINDS.forEach((name, k) -> {
            ObjectNode n = root.putObject(name);
            n.put("radius", Math.round(k[0]));
            n.put("limit", Math.round(k[1]));
        });
        try {
            Files.createDirectories(FILE.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(FILE.toFile(), root);
        } catch (IOException e) {
            // kept for this run
        }
    }

    /** For tests. */
    static synchronized void forget() {
        KINDS.clear();
        SHORT.clear();
        testing = false;
    }
}
