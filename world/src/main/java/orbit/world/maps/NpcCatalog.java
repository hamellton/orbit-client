package orbit.world.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The game's NPC numbers and names ({@code reference/maps/npcs.json}, from the client's {@code game.xml} and
 * {@code InGameCatalog} by {@code scripts/update-npcs.sh}): the pet's enemy locator names its targets by them.
 */
public final class NpcCatalog {

    /** One NPC kind: the game's number and its name (some names repeat: variants of one NPC). */
    public record Npc(int id, String name) {

        /** «Cubikon (13)»: the name with the number, as the config shows it. */
        public String label() {
            return name + " (" + id + ")";
        }
    }

    private static volatile NpcCatalog bundled;
    private final Map<Integer, Npc> byId;

    private NpcCatalog(Map<Integer, Npc> byId) {
        this.byId = byId;
    }

    public static NpcCatalog bundled() {
        NpcCatalog c = bundled;
        if (c == null) {
            try (InputStream in = NpcCatalog.class.getResourceAsStream("npcs.json")) {
                Map<Integer, Npc> m = new LinkedHashMap<>();
                if (in != null) {
                    for (JsonNode n : new ObjectMapper().readTree(in)) {
                        m.put(n.path("npcId").asInt(), new Npc(n.path("npcId").asInt(), n.path("name").asText()));
                    }
                }
                c = new NpcCatalog(Map.copyOf(m));
                bundled = c;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return c;
    }

    /** The name of an NPC number, or {@code null} when the game has none. */
    public String name(int id) {
        Npc n = byId.get(id);
        return n == null ? null : n.name();
    }

    /** All of them by name (then number). */
    public List<Npc> all() {
        List<Npc> out = new ArrayList<>(byId.values());
        out.sort(Comparator.comparing(Npc::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Npc::id));
        return out;
    }
}
