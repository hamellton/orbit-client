package orbit.bot.nav;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import orbit.world.maps.MapCatalog;

/**
 * Shortest way between maps over the portal graph ({@link MapCatalog}). The cost is the distance
 * flown: from where the ship is to the portal, and on every next map from the arrival point (the
 * portal leading back) to the next portal, plus a fixed price per jump. Portals of another faction
 * (pirate maps) are skipped.
 */
public final class Router {

    /** A jump takes about 3.3 s (recordings); counted as this much flying. */
    static final double JUMP_COST = 1_500;

    private Router() {
    }

    /**
     * The Blacklight maps 1-BL, 2-BL, 3-BL, one per company (MMO, EIC, VRU): the own one opens at level
     * {@link #OWN_BLACKLIGHT_LEVEL}, the others' at {@link #BLACKLIGHT_LEVEL} (official «Blacklight FAQ», 2018).
     */
    static final java.util.Set<Integer> BLACKLIGHT = java.util.Set.of(306, 307, 308);
    static final int BLACKLIGHT_LEVEL = 24;
    static final int OWN_BLACKLIGHT_LEVEL = 17;

    /** May the pilot enter the map: a Blacklight one by the level, the own company's sooner. */
    static boolean open(int map, int faction, int level) {
        if (!BLACKLIGHT.contains(map)) {
            return true;
        }
        return level >= (map == 305 + faction ? OWN_BLACKLIGHT_LEVEL : BLACKLIGHT_LEVEL);
    }

    /** As {@link #route(MapCatalog, int, double, double, int, int, int)} for a pilot who may enter every map. */
    public static List<MapCatalog.Portal> route(MapCatalog catalog, int fromMap, double x, double y, int toMap, int faction) {
        return route(catalog, fromMap, x, y, toMap, faction, Integer.MAX_VALUE);
    }

    /**
     * Portals to take, one per map, from the current map to {@code toMap}; empty if there or unreachable.
     *
     * @param level the pilot's level: the Blacklight maps it does not open are left out (own from 17, others from 24)
     */
    public static List<MapCatalog.Portal> route(MapCatalog catalog, int fromMap, double x, double y, int toMap, int faction,
                                                int level) {
        if (fromMap == toMap) {
            return List.of();
        }
        record Node(int map, double x, double y, double cost) {
        }
        Map<Integer, Double> best = new HashMap<>();
        Map<Integer, MapCatalog.Portal> via = new HashMap<>();
        Map<Integer, Integer> previous = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.cost(), b.cost()));
        open.add(new Node(fromMap, x, y, 0));
        best.put(fromMap, 0.0);
        while (!open.isEmpty()) {
            Node n = open.poll();
            if (n.cost() > best.getOrDefault(n.map(), Double.MAX_VALUE)) {
                continue;
            }
            if (n.map() == toMap) {
                break;
            }
            for (MapCatalog.Portal p : catalog.portals(n.map())) {
                if (p.toId() <= 0 || (p.faction() != 0 && p.faction() != faction)
                        || !open(p.toId(), faction, level)) {
                    continue;
                }
                double cost = n.cost() + Math.hypot(p.x() - n.x(), p.y() - n.y()) + JUMP_COST;
                if (cost < best.getOrDefault(p.toId(), Double.MAX_VALUE)) {
                    best.put(p.toId(), cost);
                    via.put(p.toId(), p);
                    previous.put(p.toId(), n.map());
                    double[] arrival = arrival(catalog, p.toId(), n.map());
                    open.add(new Node(p.toId(), arrival[0], arrival[1], cost));
                }
            }
        }
        if (!via.containsKey(toMap)) {
            return List.of();
        }
        List<MapCatalog.Portal> route = new ArrayList<>();
        for (int m = toMap; m != fromMap; m = previous.get(m)) {
            route.add(0, via.get(m));
        }
        return route;
    }

    /** Where a ship appears after jumping from {@code fromMap}: on the portal leading back, or the centre. */
    static double[] arrival(MapCatalog catalog, int map, int fromMap) {
        for (MapCatalog.Portal back : catalog.portals(map)) {
            if (back.toId() == fromMap) {
                return new double[] {back.x(), back.y()};
            }
        }
        MapCatalog.MapInfo info = catalog.info(map);
        return new double[] {info.width() / 2.0, info.height() / 2.0};
    }
}
