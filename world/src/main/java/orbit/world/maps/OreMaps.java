package orbit.world.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which ores lie on which map ({@code OreTypeModule} numbers: 0 prometium … 8 palladium), learned while flying like
 * {@link NpcMaps}: every ore the server shows ({@code AddOreCommand}) is kept with its map in {@code local/ore-maps.json}.
 * No bundled start: a quest «collect ore» needs a map where the bot has seen that ore (or the ore in sight).
 */
public final class OreMaps {

    static final Path LEARNED = Path.of("local", "ore-maps.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static OreMaps shared;

    private final Map<Integer, Set<Integer>> known = new TreeMap<>();
    private final Path file;

    OreMaps(Path file) {
        this.file = file;
    }

    public static synchronized OreMaps shared() {
        if (shared == null) {
            OreMaps m = new OreMaps(LEARNED);
            try {
                if (Files.exists(LEARNED)) {
                    JsonNode root = JSON.readTree(LEARNED.toFile());
                    root.fields().forEachRemaining(e -> {
                        try {
                            int map = Integer.parseInt(e.getKey());
                            e.getValue().forEach(n -> m.known.computeIfAbsent(map, k -> new TreeSet<>()).add(n.asInt()));
                        } catch (NumberFormatException ignored) {
                            // not a map id
                        }
                    });
                }
            } catch (IOException e) {
                // a broken file is rewritten with what is learned next
            }
            shared = m;
        }
        return shared;
    }

    /** For tests: nothing on disk. */
    public static OreMaps inMemory() {
        return new OreMaps(null);
    }

    /** An ore seen on a map; true when new. */
    public synchronized boolean learn(int map, int ore) {
        if (map <= 0 || ore < 0) {
            return false;
        }
        if (!known.computeIfAbsent(map, k -> new TreeSet<>()).add(ore)) {
            return false;
        }
        save();
        return true;
    }

    /** The maps where the ore was seen. */
    public synchronized Set<Integer> mapsWith(int ore) {
        Set<Integer> out = new TreeSet<>();
        known.forEach((map, ores) -> {
            if (ores.contains(ore)) {
                out.add(map);
            }
        });
        return out;
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            known.forEach((map, ores) -> {
                ArrayNode a = root.putArray(String.valueOf(map));
                ores.forEach(a::add);
            });
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            // learning goes on in memory
        }
    }
}
