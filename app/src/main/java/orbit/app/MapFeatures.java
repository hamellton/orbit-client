package orbit.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import orbit.world.WorldSnapshot;

/**
 * What the bot saw on each map that the config window has no other way to know: the obstacles (no-access zones,
 * radiation, mine fields, asteroids…) and the stations (bases, refineries, clan battle stations), kept in
 * {@code local/map-features.json} for the «Зоны» tab (the user, 2026-11-12: no obstacles or bases to draw the zones
 * by). Learned whenever the server shows them; never forgotten (a map does not change much).
 */
public final class MapFeatures {

    public static final Path FILE = Path.of("local", "map-features.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static MapFeatures shared;

    /** {@code POITypeModule}: zones that are in the way or hurt (not the triggers, buffs and outlines). */
    static final Set<Integer> OBSTACLES = Set.of(3, 5, 6, 8, 9, 10, 18);
    /** {@code POITypeModule} 16: the no-fight ring round a base. */
    public static final int BASE_ZONE = 16;
    /** {@code AssetTypeModule}: battle station, HQ, hangar, refinery, repair dock (home or outpost). */
    static final Set<Integer> STATIONS = Set.of(36, 46, 47, 48, 49, 50, 51, 52, 53, 54);

    /** A zone: {@code POITypeModule} type, {@code MapAddPOICommand} shape, its coordinates, inside out or not. */
    public record Obstacle(int type, int shape, List<Integer> coords, boolean inverted) {
    }

    /** A station: {@code AssetTypeModule} type, its name, place, company (0 shared) and clan tag. */
    public record Station(int type, String name, int x, int y, int faction, String clanTag) {
    }

    private final Path file;
    private final Map<Integer, Map<String, Obstacle>> obstacles = new TreeMap<>();
    private final Map<Integer, Map<String, Station>> stations = new TreeMap<>();

    MapFeatures(Path file) {
        this.file = file;
        try {
            if (file != null && Files.exists(file)) {
                JSON.readTree(file.toFile()).fields().forEachRemaining(e -> {
                    int map;
                    try {
                        map = Integer.parseInt(e.getKey());
                    } catch (NumberFormatException ex) {
                        return;
                    }
                    for (JsonNode o : e.getValue().path("obstacles")) {
                        List<Integer> c = new ArrayList<>();
                        o.path("coords").forEach(n -> c.add(n.asInt()));
                        Obstacle ob = new Obstacle(o.path("type").asInt(), o.path("shape").asInt(), List.copyOf(c),
                                o.path("inverted").asBoolean());
                        obstacles.computeIfAbsent(map, k -> new LinkedHashMap<>()).put(key(ob), ob);
                    }
                    for (JsonNode s : e.getValue().path("stations")) {
                        Station st = new Station(s.path("type").asInt(), s.path("name").asText(""), s.path("x").asInt(),
                                s.path("y").asInt(), s.path("faction").asInt(), s.path("clan").asText(""));
                        stations.computeIfAbsent(map, k -> new LinkedHashMap<>()).put(key(st), st);
                    }
                });
            }
        } catch (IOException e) {
            // a broken file is written anew with what is seen next
        }
    }

    public static synchronized MapFeatures shared() {
        if (shared == null) {
            shared = new MapFeatures(FILE);
        }
        return shared;
    }

    /** For tests: nothing on disk. */
    static MapFeatures inMemory() {
        return new MapFeatures(null);
    }

    private static String key(Obstacle o) {
        return o.type() + ":" + o.shape() + ":" + o.coords();
    }

    private static String key(Station s) {
        // a battle station is rebuilt by other clans on the same spot: the place is the station
        return s.type() + ":" + s.x() / 100 + ":" + s.y() / 100;
    }

    /** Takes what the snapshot shows of its map; true when something new was learned (then saved). */
    public synchronized boolean note(WorldSnapshot w) {
        int map = w.hero().mapId();
        if (map <= 0) {
            return false;
        }
        boolean changed = false;
        Map<String, Obstacle> os = obstacles.computeIfAbsent(map, k -> new LinkedHashMap<>());
        for (WorldSnapshot.Zone z : w.zones().values()) {
            if (OBSTACLES.contains(z.type()) || z.type() == BASE_ZONE) {
                Obstacle o = new Obstacle(z.type(), z.shape(), List.copyOf(z.coords()), z.inverted());
                changed |= os.putIfAbsent(key(o), o) == null;
            }
        }
        Map<String, Station> ss = stations.computeIfAbsent(map, k -> new LinkedHashMap<>());
        for (WorldSnapshot.Asset a : w.assets().values()) {
            if (STATIONS.contains(a.type()) && !a.invisible()) {
                Station s = new Station(a.type(), a.name() == null ? "" : a.name(), a.x(), a.y(), a.factionId(),
                        a.clanTag() == null ? "" : a.clanTag());
                Station before = ss.put(key(s), s);
                changed |= !s.equals(before);
            }
        }
        if (changed) {
            save();
        }
        return changed;
    }

    public synchronized List<Obstacle> obstacles(int map) {
        return List.copyOf(obstacles.getOrDefault(map, Map.of()).values());
    }

    public synchronized List<Station> stations(int map) {
        return List.copyOf(stations.getOrDefault(map, Map.of()).values());
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        java.util.Set<Integer> maps = new java.util.TreeSet<>(obstacles.keySet());
        maps.addAll(stations.keySet());
        for (int map : maps) {
            ObjectNode m = root.putObject(String.valueOf(map));
            ArrayNode oa = m.putArray("obstacles");
            for (Obstacle o : obstacles.getOrDefault(map, Map.of()).values()) {
                ObjectNode n = oa.addObject();
                n.put("type", o.type());
                n.put("shape", o.shape());
                o.coords().forEach(n.putArray("coords")::add);
                if (o.inverted()) {
                    n.put("inverted", true);
                }
            }
            ArrayNode sa = m.putArray("stations");
            for (Station s : stations.getOrDefault(map, Map.of()).values()) {
                ObjectNode n = sa.addObject();
                n.put("type", s.type());
                n.put("name", s.name());
                n.put("x", s.x());
                n.put("y", s.y());
                n.put("faction", s.faction());
                if (!s.clanTag().isEmpty()) {
                    n.put("clan", s.clanTag());
                }
            }
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // kept in memory
        }
    }
}
