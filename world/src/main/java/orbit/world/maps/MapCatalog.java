package orbit.world.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Static facts about maps: size and type from the client's {@code maps-config.xml}
 * ({@code reference/maps/maps.json}, refreshed by {@code tools/update_maps.py}) and the portal
 * graph of regular maps ({@code reference/maps/portals.json}, facts from DarkBot; the game sends
 * portal positions but never their destinations).
 *
 * <p>Immutable and thread-safe.
 */
public final class MapCatalog {

    /** Size of a map whose description is missing (the client's {@code Map.STD_WIDTH/STD_HEIGHT}). */
    public static final int STD_WIDTH = 21000;
    public static final int STD_HEIGHT = 13100;

    /**
     * @param type        {@code normal}, {@code pirate} or empty (gates, instances)
     * @param homeFaction faction whose home map this is (1 MMO, 2 EIC, 3 VRU), 0 otherwise
     * @param known       described in the client's map config
     */
    public record MapInfo(int id, String name, double scale, int width, int height, String type, int homeFaction,
                          boolean known) {

        public boolean contains(double x, double y) {
            return x >= 0 && y >= 0 && x <= width && y <= height;
        }
    }

    /**
     * A portal of a regular map.
     *
     * @param toId    destination map id, or -1 when it depends on the player ("Home Map")
     * @param faction only for this faction (pirate maps), 0 for everyone
     */
    public record Portal(int x, int y, int toId, String toName, int faction) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static volatile MapCatalog bundled;

    private final Map<Integer, MapInfo> maps;
    private final Map<Integer, List<Portal>> portals;
    private final String portalSource;

    private MapCatalog(Map<Integer, MapInfo> maps, Map<Integer, List<Portal>> portals, String portalSource) {
        this.maps = maps;
        this.portals = portals;
        this.portalSource = portalSource;
    }

    /** The catalog shipped with the bot. */
    public static MapCatalog bundled() {
        MapCatalog c = bundled;
        if (c == null) {
            try (InputStream m = resource("maps.json"); InputStream p = resource("portals.json")) {
                c = parse(m, p);
            } catch (IOException e) {
                throw new UncheckedIOException("данные карт повреждены", e);
            }
            bundled = c;
        }
        return c;
    }

    static MapCatalog parse(InputStream mapsJson, InputStream portalsJson) throws IOException {
        Map<Integer, MapInfo> maps = new LinkedHashMap<>();
        for (JsonNode m : JSON.readTree(mapsJson).get("maps")) {
            int id = m.get("id").asInt();
            maps.put(id, new MapInfo(id, m.get("name").asText(), m.get("scale").asDouble(), m.get("width").asInt(),
                    m.get("height").asInt(), m.path("type").asText(), m.path("homeFaction").asInt(), true));
        }
        JsonNode pj = JSON.readTree(portalsJson);
        Map<Integer, List<Portal>> portals = new HashMap<>();
        for (JsonNode m : pj.get("maps")) {
            List<Portal> list = new ArrayList<>();
            for (JsonNode p : m.get("portals")) {
                list.add(new Portal(p.get("x").asInt(), p.get("y").asInt(), p.get("toId").asInt(), p.get("to").asText(),
                        p.path("faction").asInt()));
            }
            portals.put(m.get("id").asInt(), List.copyOf(list));
        }
        return new MapCatalog(Collections.unmodifiableMap(maps), Map.copyOf(portals),
                pj.path("source").asText() + " @ " + pj.path("commit").asText());
    }

    private static InputStream resource(String name) throws IOException {
        InputStream in = MapCatalog.class.getResourceAsStream(name);
        if (in == null) {
            throw new IOException("нет ресурса " + name);
        }
        return in;
    }

    /** Map description; unknown ids get the standard size and are marked {@code known = false}. */
    public MapInfo info(int mapId) {
        MapInfo m = maps.get(mapId);
        return m != null ? m : new MapInfo(mapId, "?" + mapId, 1, STD_WIDTH, STD_HEIGHT, "", 0, false);
    }

    /** By client name, e.g. {@code 3-1} or {@code 4-4}; {@code null} if unknown. */
    public MapInfo byName(String name) {
        return maps.values().stream().filter(m -> m.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }

    public Map<Integer, MapInfo> all() {
        return maps;
    }

    /** Known portals of a map (empty for gates and instances). */
    public List<Portal> portals(int mapId) {
        return portals.getOrDefault(mapId, List.of());
    }

    /** The known portal nearest to a point, within {@code maxDistance}; {@code null} if none. */
    public Portal portalNear(int mapId, double x, double y, double maxDistance) {
        Portal best = null;
        double bestD = maxDistance;
        for (Portal p : portals(mapId)) {
            double d = Math.hypot(p.x() - x, p.y() - y);
            if (d <= bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    /** Where the portal graph comes from (for logs and documentation). */
    public String portalSource() {
        return portalSource;
    }
}
