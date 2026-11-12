package orbit.world.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The maps where a quest giver (a map asset of type 34) was seen: in the user's recordings 2-5, 2-8, 3-1, 3-4, 3-5
 * (x-1, x-4, x-5, x-8, as the user says), and every map the bot sees one on since ({@code local/giver-maps.json}) —
 * 5-2 too, if it has one.
 */
public final class GiverMaps {

    static final Path LEARNED = Path.of("local", "giver-maps.json");
    private static GiverMaps shared;

    private final Set<Integer> maps = new TreeSet<>();
    private final Path file;

    GiverMaps(Path file) {
        this.file = file;
    }

    public static synchronized GiverMaps shared() {
        if (shared == null) {
            GiverMaps g = new GiverMaps(LEARNED);
            try {
                if (Files.exists(LEARNED)) {
                    new ObjectMapper().readTree(LEARNED.toFile()).forEach(n -> g.maps.add(n.asInt()));
                }
            } catch (IOException e) {
                // rewritten with what is seen next
            }
            shared = g;
        }
        return shared;
    }

    public static GiverMaps inMemory() {
        return new GiverMaps(null);
    }

    public synchronized boolean learn(int map) {
        if (map <= 0 || !maps.add(map)) {
            return false;
        }
        if (file != null) {
            try {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, new ObjectMapper().writeValueAsString(maps));
            } catch (IOException e) {
                // kept in memory
            }
        }
        return true;
    }

    public synchronized Set<Integer> all() {
        return Set.copyOf(maps);
    }
}
