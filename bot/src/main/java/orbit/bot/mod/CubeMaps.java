package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import orbit.bot.nav.Router;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which x-6 map the Cubikon camp works on now. The module's settings:
 * <ul>
 *   <li>{@code maps} — the maps to farm (ids 18 = 1-6, 22 = 2-6, 26 = 3-6); none = the config's working map;</li>
 *   <li>{@code mapMode} — {@code SOONEST}: where a cube comes back first, the way there counted;
 *       {@code ROUND}: one map after another, moving on when a cube dies;</li>
 *   <li>{@code mapSchedule} — windows {@code {from, to, maps}} (e.g. 08:00–12:00 on 1-6 and 2-6) that replace
 *       {@code maps} while they last; the first window covering the time wins, a window may pass midnight.</li>
 * </ul>
 * A cube alive where the ship is keeps it there.
 */
public final class CubeMaps {

    private static final Logger log = LoggerFactory.getLogger(CubeMaps.class);

    /** 1-6, 2-6, 3-6. */
    public static final List<Integer> X6 = List.of(18, 22, 26);
    /** A jump through a portal with the flight to it and the look around after: a rough minute. */
    static final long HOP_MS = 60_000;
    /** Another map must promise its cube this much earlier before the ship leaves for it. */
    static final long SWITCH_GAIN_MS = 60_000;

    public enum Mode { SOONEST, ROUND }

    /** A timetable window: these maps from {@code from} to {@code to} (wall time). */
    public record Window(LocalTime from, LocalTime to, List<Integer> maps) {

        boolean covers(LocalTime t) {
            return from.isBefore(to) ? !t.isBefore(from) && t.isBefore(to) : !t.isBefore(from) || t.isBefore(to);
        }
    }

    private final MapCatalog catalog;
    private int target;
    private int told;

    public CubeMaps(MapCatalog catalog) {
        this.catalog = catalog;
    }

    public static List<Integer> maps(JsonNode s) {
        List<Integer> out = new ArrayList<>();
        if (s != null && s.path("maps").isArray()) {
            for (JsonNode m : s.path("maps")) {
                if (X6.contains(m.asInt()) && !out.contains(m.asInt())) {
                    out.add(m.asInt());
                }
            }
        }
        return out;
    }

    public static Mode mode(JsonNode s) {
        return s != null && "ROUND".equals(s.path("mapMode").asText()) ? Mode.ROUND : Mode.SOONEST;
    }

    public static List<Window> schedule(JsonNode s) {
        List<Window> out = new ArrayList<>();
        if (s == null || !s.path("mapSchedule").isArray()) {
            return out;
        }
        for (JsonNode w : s.path("mapSchedule")) {
            try {
                List<Integer> maps = maps(w);
                if (!maps.isEmpty()) {
                    out.add(new Window(LocalTime.parse(w.path("from").asText()), LocalTime.parse(w.path("to").asText()), maps));
                }
            } catch (java.time.DateTimeException e) {
                log.warn("Расписание карт: окно «{}–{}» не понять — пропускаю", w.path("from").asText(), w.path("to").asText());
            }
        }
        return out;
    }

    /** The maps allowed now: the timetable's window, else the config's maps, else the working map. */
    public static List<Integer> candidates(JsonNode s, int workingMap, LocalTime now) {
        for (Window w : schedule(s)) {
            if (w.covers(now)) {
                return w.maps();
            }
        }
        List<Integer> maps = maps(s);
        return maps.isEmpty() ? List.of(workingMap) : maps;
    }

    /**
     * The map to work on now.
     *
     * @param cubeHere a Cubikon is alive on the hero's map: stay
     * @param fighting the ship shoots a Cubikon here: stay even when the timetable has moved on
     * @param nowMs    wall clock, for the spawn memory
     */
    public int choose(JsonNode s, int workingMap, int heroMap, double hx, double hy, int faction, boolean cubeHere,
                      boolean fighting, SpawnMemory memory, long nowMs, LocalTime time) {
        List<Integer> maps = candidates(s, workingMap, time);
        if (fighting) {
            return remember(heroMap, null); // a cube under fire is finished first, whatever the plan says now
        }
        if (cubeHere && maps.contains(heroMap)) {
            return remember(heroMap, "куб здесь");
        }
        if (maps.size() == 1) {
            return remember(maps.getFirst(), null);
        }
        if (!maps.contains(target)) {
            target = maps.contains(heroMap) ? heroMap : maps.getFirst();
        }
        if (mode(s) == Mode.ROUND) {
            return remember(target, null);
        }
        // SOONEST: where a cube can be met first — the later of «there» and «the cube is back»
        int best = target;
        long bestAt = readyAt(target, heroMap, hx, hy, faction, memory, nowMs);
        for (int m : maps) {
            long at = readyAt(m, heroMap, hx, hy, faction, memory, nowMs);
            if (at + SWITCH_GAIN_MS < bestAt) {
                best = m;
                bestAt = at;
            }
        }
        return remember(best, best == heroMap ? null : "куб там через " + Math.max(0, (bestAt - nowMs) / 1000) + " с");
    }

    /** A cube died on {@code map}: in turn order the camp moves on to the next map. */
    public void killedOn(JsonNode s, int workingMap, int map, LocalTime time) {
        if (mode(s) != Mode.ROUND) {
            return;
        }
        List<Integer> maps = candidates(s, workingMap, time);
        int i = maps.indexOf(map);
        if (maps.size() > 1 && i >= 0) {
            target = maps.get((i + 1) % maps.size());
        }
    }

    private long readyAt(int map, int heroMap, double hx, double hy, int faction, SpawnMemory memory, long nowMs) {
        long arrive = nowMs + travelMs(map, heroMap, hx, hy, faction);
        long cube = expectedCube(map, memory, nowMs);
        return Math.max(arrive, cube);
    }

    long travelMs(int map, int heroMap, double hx, double hy, int faction) {
        if (map == heroMap) {
            return 0;
        }
        int hops = Router.route(catalog, heroMap, hx, hy, map, faction).size();
        return hops == 0 ? Long.MAX_VALUE / 4 : hops * HOP_MS;
    }

    /** When the next cube is due on the map: the soonest known spawn; a map not learned yet counts as now. */
    static long expectedCube(int map, SpawnMemory memory, long nowMs) {
        List<SpawnMemory.Spawn> spawns = memory.spawns(map);
        long respawn = memory.mapRespawn(map);
        long best = Long.MAX_VALUE;
        for (SpawnMemory.Spawn sp : spawns) {
            long at = sp.expectedAt(respawn);
            if (at > 0) {
                best = Math.min(best, at);
            }
        }
        return best == Long.MAX_VALUE ? nowMs : Math.max(nowMs, best);
    }

    private int remember(int map, String why) {
        target = map;
        if (map != told) {
            if (told != 0 && why != null) {
                log.info("Кубы: дальше на {} ({})", catalog.info(map).name(), why);
            }
            told = map;
        }
        return map;
    }
}
