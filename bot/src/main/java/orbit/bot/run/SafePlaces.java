package orbit.bot.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/**
 * Where the ship is safe to repair (low HP), to wait or to log out (the user, 2026-11-12): a base on the map — the
 * own company's or a shared one (5-2's), never another company's — and the
 * portals the ship can really jump through: the server shows them on the map (when it sent the map's gates) and none
 * refused a jump this run ({@link Stats#closedPortals}). A portal with no way back (5-2 → 5-3) is a place to stand,
 * not to jump; a base or a portal with a way back comes first, so on 5-2 the ship runs to the shared base.
 */
public final class SafePlaces {

    /**
     * A base's buildings: the HQ, the refinery, the repair dock, home or outpost ({@code AssetTypeModule}); the
     * invisible ore trade (52) is not a place.
     */
    static final Set<Integer> BASE_PARTS = Set.of(46, 47, 50, 51, 53, 54);
    /** Close enough to a base's HQ: inside its no-fight zone (radius 900, live on 1-1 and 3-4). */
    static final double AT_BASE = 450;
    /** A server's gate this close to a known portal is that portal. */
    static final double SAME_GATE = 400;

    private SafePlaces() {
    }

    /** One safe place: a base, or a portal ({@link #portal} set) — {@link #jumpable} when a jump there helps. */
    public record Place(int x, int y, String name, MapCatalog.Portal portal, boolean jumpable) {

        public boolean base() {
            return portal == null;
        }

        /** How close counts as there. */
        public double reach() {
            return base() ? AT_BASE : Safety.AT_PORTAL;
        }

        public double distance(WorldSnapshot.Hero h) {
            return Math.hypot(x - h.x(), y - h.y());
        }
    }

    /**
     * The safe bases on the map now: the own company's and the shared ones (company 0 — the pirate maps' station,
     * 5-2), never another company's.
     */
    static List<Place> bases(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        List<Place> out = new ArrayList<>();
        for (WorldSnapshot.Asset a : w.assets().values()) {
            if (BASE_PARTS.contains(a.type()) && (a.factionId() == 0 || a.factionId() == h.factionId())) {
                out.add(new Place(a.x(), a.y(), a.factionId() == 0 ? "общую базу" : "свою базу", null, false));
            }
        }
        return out;
    }

    static String key(int map, MapCatalog.Portal p) {
        return map + ":" + p.x() + ":" + p.y();
    }

    /**
     * The map's portals the ship may use: its company's or anyone's, shown by the server (if it sent any gates)
     * and not refused this run.
     */
    static List<MapCatalog.Portal> usablePortals(WorldSnapshot w, MapCatalog maps, Set<String> closed) {
        WorldSnapshot.Hero h = w.hero();
        List<MapCatalog.Portal> out = new ArrayList<>();
        for (MapCatalog.Portal p : maps.portals(h.mapId())) {
            if (p.faction() != 0 && p.faction() != h.factionId() || closed.contains(key(h.mapId(), p))) {
                continue;
            }
            boolean shown = w.portals().isEmpty() || w.portals().values().stream()
                    .anyMatch(g -> Math.hypot(g.x() - p.x(), g.y() - p.y()) <= SAME_GATE);
            if (shown) {
                out.add(p);
            }
        }
        return out;
    }

    /**
     * The nearest safe place: a base or a portal with a way back first; else a one-way portal to stand at; else
     * any portal of the map (nothing better known); {@code null} on a map with neither (a gate, an instance).
     *
     * @param oneWayJumps the user lets the bot jump through one-way portals too
     */
    public static Place nearest(WorldSnapshot w, MapCatalog maps, Set<String> closed, boolean oneWayJumps) {
        WorldSnapshot.Hero h = w.hero();
        List<Place> good = new ArrayList<>(bases(w));
        List<Place> standOnly = new ArrayList<>();
        for (MapCatalog.Portal p : usablePortals(w, maps, closed)) {
            boolean back = !Safety.oneWay(maps, p, h);
            Place place = new Place(p.x(), p.y(), "портал на " + p.toName(), p, back || oneWayJumps);
            (back || oneWayJumps ? good : standOnly).add(place);
        }
        Comparator<Place> near = Comparator.comparingDouble(pl -> pl.distance(h));
        if (!good.isEmpty()) {
            return good.stream().min(near).orElseThrow();
        }
        if (!standOnly.isEmpty()) {
            return standOnly.stream().min(near).orElseThrow();
        }
        return maps.portals(h.mapId()).stream()
                .filter(p -> p.faction() == 0 || p.faction() == h.factionId())
                .map(p -> new Place(p.x(), p.y(), "портал на " + p.toName(), p, false))
                .min(near).orElse(null);
    }
}
