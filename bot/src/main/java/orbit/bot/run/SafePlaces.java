package orbit.bot.run;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/**
 * Where the ship is safe to repair (low HP), to wait or to log out (the user, 2026-11-12): a base on the map — the
 * own company's or a shared one (5-2's), never another company's — and the portals the ship can really jump
 * through: none refused a jump this run ({@link Stats#closedPortals}). A portal with no way back (5-2 → 5-3) is a place to stand,
 * not to jump; a base or a portal with a way back comes first, so on 5-2 the ship runs to the shared base.
 */
public final class SafePlaces {

    /**
     * A base's buildings: the HQ, the refinery (also the pirate maps' station: 5-2 has only its OreTrade), the
     * repair dock, home or outpost ({@code AssetTypeModule}).
     */
    static final Set<Integer> BASE_PARTS = Set.of(46, 47, 50, 51, 52, 53, 54);
    /** Close enough to a base's HQ: inside its no-fight zone (radius 900, live on 1-1 and 3-4). */
    static final double AT_BASE = 450;

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
     * The map's portals the ship may use: its company's or anyone's, not refused this run.
     */
    static List<MapCatalog.Portal> usablePortals(WorldSnapshot w, MapCatalog maps, Set<String> closed) {
        WorldSnapshot.Hero h = w.hero();
        List<MapCatalog.Portal> out = new ArrayList<>();
        for (MapCatalog.Portal p : maps.portals(h.mapId())) {
            // the server sends a map's gates as the ship comes near (live 5-2 2026-11-12), so a gate not sent
            // yet is no sign of a missing portal: only a refused jump is
            if (p.faction() != 0 && p.faction() != h.factionId() || closed.contains(key(h.mapId(), p))
                    // another company's Blacklight lets in from level 24 only (live 2026-11-13: level 19 at 3-BL's portal)
                    || !orbit.bot.nav.Router.openKnown(p.toId(), h.factionId(), h.level())) {
                continue;
            }
            out.add(p);
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
        return nearest(w, maps, closed, oneWayJumps, 0);
    }

    /**
     * The same, away from the work map ({@code workMap}, after an escape jump): the portal back to it first, so
     * the ship repairs where it came in and goes straight back (the user, 2026-11-12: 2-BL → 2-8, not to 2-8's
     * base).
     */
    public static Place nearest(WorldSnapshot w, MapCatalog maps, Set<String> closed, boolean oneWayJumps, int workMap) {
        WorldSnapshot.Hero h = w.hero();
        if (workMap > 0 && workMap != h.mapId()) {
            Place back = usablePortals(w, maps, closed).stream().filter(p -> p.toId() == workMap)
                    .map(p -> new Place(p.x(), p.y(), "портал на " + p.toName(), p, true))
                    .min(Comparator.comparingDouble(pl -> pl.distance(h))).orElse(null);
            if (back != null) {
                return back;
            }
        }
        List<Place> good = new ArrayList<>(bases(w));
        List<Place> standOnly = new ArrayList<>();
        for (MapCatalog.Portal p : usablePortals(w, maps, closed)) {
            boolean back = !Safety.oneWay(maps, p, h);
            Place place = new Place(p.x(), p.y(), "портал на " + p.toName(), p, back || oneWayJumps);
            (back || oneWayJumps ? good : standOnly).add(place);
        }
        Comparator<Place> near = Comparator.comparingDouble(pl -> pl.distance(h));
        // away from the company's own maps (5-2) the base first, a portal only without one (the user, 2026-11-12)
        List<Place> bases = bases(w);
        if (!bases.isEmpty() && maps.info(h.mapId()).homeFaction() != h.factionId()) {
            return bases.stream().min(near).orElseThrow();
        }
        if (!good.isEmpty()) {
            return good.stream().min(near).orElseThrow();
        }
        if (!standOnly.isEmpty()) {
            return standOnly.stream().min(near).orElseThrow();
        }
        return maps.portals(h.mapId()).stream()
                .filter(p -> p.faction() == 0 || p.faction() == h.factionId())
                .filter(p -> orbit.bot.nav.Router.openKnown(p.toId(), h.factionId(), h.level()))
                .map(p -> new Place(p.x(), p.y(), "портал на " + p.toName(), p, false))
                .min(near).orElse(null);
    }

    /**
     * Every way out of the map is one-way (5-2: only to 5-3, back by the whole loop round the pirate maps; its
     * 5-4 leads nowhere else): leaving costs a long way back, so the map's work is finished first.
     */
    public static boolean hardToLeave(WorldSnapshot w, MapCatalog maps) {
        WorldSnapshot.Hero h = w.hero();
        boolean anyOut = false;
        for (MapCatalog.Portal p : maps.portals(h.mapId())) {
            if (p.faction() != 0 && p.faction() != h.factionId() || p.toId() <= 0) {
                continue;
            }
            // a dead end (its portals all lead back here) is no way out
            boolean wayOut = maps.portals(p.toId()).stream().anyMatch(q -> q.toId() > 0 && q.toId() != h.mapId());
            if (!wayOut) {
                continue;
            }
            anyOut = true;
            if (!Safety.oneWay(maps, p, h)) {
                return false;
            }
        }
        return anyOut;
    }
}
