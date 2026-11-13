package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import orbit.config.BotConfig;

/**
 * How many of an ability item (EMP-01, ISH-01) a pilot used in the last hour and day, for the config's limits (the
 * user, 2026-11-13). Kept in {@code local/ability-use.json} by pilot id, so a restart does not start the count anew.
 */
public final class AbilityUse {

    private static final Path FILE = Path.of("local", "ability-use.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    static final long HOUR_MS = 3_600_000;
    static final long DAY_MS = 24 * HOUR_MS;
    /** pilot:item → use times (epoch ms), oldest first, the last 24 hours. */
    private static final Map<String, ArrayDeque<Long>> USES = new HashMap<>();
    private static volatile boolean onDisk;
    private static boolean read;

    private AbilityUse() {
    }

    /** The app keeps the counts; tests do not. */
    public static void keepOnDisk() {
        onDisk = true;
    }

    /** Why the item may not be used now ({@code null} when it may): the hour's or the day's limit reached. */
    static synchronized String limited(int pilot, String item, BotConfig.Spend s, long nowMs) {
        ArrayDeque<Long> u = uses(pilot, item, nowMs);
        if (s.perDay() > 0 && u.size() >= s.perDay()) {
            return "за сутки уже " + u.size() + " (лимит " + s.perDay() + ")";
        }
        if (s.perHour() > 0 && u.stream().filter(t -> nowMs - t < HOUR_MS).count() >= s.perHour()) {
            return "за час уже " + s.perHour() + " (лимит)";
        }
        return null;
    }

    /** One use now. */
    static synchronized void used(int pilot, String item, long nowMs) {
        uses(pilot, item, nowMs).addLast(nowMs);
        save();
    }

    /** Uses in the last 24 hours. */
    static synchronized int today(int pilot, String item, long nowMs) {
        return uses(pilot, item, nowMs).size();
    }

    private static ArrayDeque<Long> uses(int pilot, String item, long nowMs) {
        load();
        ArrayDeque<Long> u = USES.computeIfAbsent(pilot + ":" + item, k -> new ArrayDeque<>());
        while (!u.isEmpty() && nowMs - u.peekFirst() >= DAY_MS) {
            u.removeFirst();
        }
        return u;
    }

    private static void load() {
        if (read || !onDisk) {
            return;
        }
        read = true;
        try {
            if (Files.exists(FILE)) {
                JSON.readTree(FILE.toFile()).properties().forEach(e -> {
                    ArrayDeque<Long> u = new ArrayDeque<>();
                    for (JsonNode t : e.getValue()) {
                        u.addLast(t.asLong());
                    }
                    USES.put(e.getKey(), u);
                });
            }
        } catch (IOException e) {
            // counted anew
        }
    }

    private static void save() {
        if (!onDisk) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        USES.forEach((k, u) -> {
            ArrayNode a = root.putArray(k);
            u.forEach(a::add);
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
        USES.clear();
    }
}
