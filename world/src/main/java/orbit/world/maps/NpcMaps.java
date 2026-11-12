package orbit.world.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Which NPCs live on which map, by the names the game shows ({@code -=[ Protegit ]=-}). Neither the client nor
 * DarkBot has this: DarkBot learns it while flying, and so does the bot — the bundled start
 * ({@code reference/maps/npc-maps.json}, from the recorded sessions) plus every NPC seen since
 * ({@code local/npc-maps.json}, shared by all accounts).
 */
public final class NpcMaps {

    static final Path LEARNED = Path.of("local", "npc-maps.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static NpcMaps shared;

    private final Map<Integer, Set<String>> known = new TreeMap<>();
    private final Path file;

    NpcMaps(Path file) {
        this.file = file;
    }

    /** The bundled start and what was learned on this computer. */
    public static synchronized NpcMaps shared() {
        if (shared == null) {
            NpcMaps m = new NpcMaps(LEARNED);
            try (InputStream in = NpcMaps.class.getResourceAsStream("npc-maps.json")) {
                if (in != null) {
                    m.read(JSON.readTree(in));
                }
            } catch (IOException e) {
                // the start is only a help: without it the bot learns from scratch
            }
            try {
                if (Files.exists(LEARNED)) {
                    m.read(JSON.readTree(LEARNED.toFile()));
                }
            } catch (IOException e) {
                // a broken file is rewritten with what is learned next
            }
            shared = m;
        }
        return shared;
    }

    private void read(JsonNode root) {
        root.fields().forEachRemaining(e -> {
            try {
                int map = Integer.parseInt(e.getKey());
                for (JsonNode n : e.getValue()) {
                    known.computeIfAbsent(map, k -> new TreeSet<>()).add(n.asText());
                }
            } catch (NumberFormatException ignored) {
                // not a map id
            }
        });
    }

    /** An NPC seen on a map; kept when new (true then). */
    public synchronized boolean learn(int map, String name) {
        if (map <= 0 || name == null || name.isBlank()) {
            return false;
        }
        if (!known.computeIfAbsent(map, k -> new TreeSet<>()).add(name)) {
            return false;
        }
        save();
        return true;
    }

    /** The NPCs known on a map (as the game names them). */
    public synchronized Set<String> on(int map) {
        return Set.copyOf(known.getOrDefault(map, Set.of()));
    }

    /** The maps with any NPC known. */
    public synchronized Set<Integer> maps() {
        return Set.copyOf(known.keySet());
    }

    private void save() {
        if (file == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            known.forEach((map, names) -> {
                ArrayNode a = root.putArray(String.valueOf(map));
                names.forEach(a::add);
            });
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(root));
        } catch (IOException e) {
            // learning goes on in memory
        }
    }

    /** For tests: nothing on disk, nothing bundled. */
    public static NpcMaps inMemory() {
        return new NpcMaps(null);
    }
}
