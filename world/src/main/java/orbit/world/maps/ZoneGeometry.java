package orbit.world.maps;

import java.util.List;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.POITypeModule;
import orbit.world.WorldSnapshot;

/**
 * Point tests against server zones ({@code MapAddPOICommand}), read the way the client's
 * {@code MapAddPOIHandler} reads them: a circle is {@code x, y, radius}, a polygon is x/y pairs and a
 * rectangle is the bounding box of its x/y pairs. An inverted zone covers everything outside its shape.
 */
public final class ZoneGeometry {

    private ZoneGeometry() {
    }

    public static boolean contains(WorldSnapshot.Zone zone, double x, double y) {
        boolean inside = insideShape(zone.shape(), zone.coords(), x, y);
        return zone.inverted() != inside;
    }

    static boolean insideShape(int shape, List<Integer> c, double x, double y) {
        if (shape == MapAddPOICommand.SHAPETYPE_CIRCLE) {
            return c.size() >= 3 && Math.hypot(x - c.get(0), y - c.get(1)) <= c.get(2);
        }
        if (shape == MapAddPOICommand.SHAPETYPE_RECTANGLE) {
            // the bounding box of all points, like the client's RectangleZone
            if (c.size() < 4) {
                return false;
            }
            double minX = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < c.size(); i += 2) {
                minX = Math.min(minX, c.get(i));
                maxX = Math.max(maxX, c.get(i));
                minY = Math.min(minY, c.get(i + 1));
                maxY = Math.max(maxY, c.get(i + 1));
            }
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
        // polygon: even-odd ray casting
        int n = c.size() / 2;
        if (n < 3) {
            return false;
        }
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = c.get(2 * i);
            double yi = c.get(2 * i + 1);
            double xj = c.get(2 * j);
            double yj = c.get(2 * j + 1);
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) {
                in = !in;
            }
        }
        return in;
    }

    /** Active zones of the given {@code POITypeModule} type containing the point. */
    public static boolean inAny(WorldSnapshot snapshot, int poiType, double x, double y) {
        return snapshot.zones().values().stream()
                .anyMatch(z -> z.active() && z.type() == poiType && contains(z, x, y));
    }

    /**
     * Radiation at a point: outside the map (not verified against the game yet: the recordings
     * never left the map) or inside a radiation zone.
     */
    public static boolean radiation(WorldSnapshot snapshot, MapCatalog.MapInfo map, double x, double y) {
        return !map.contains(x, y) || inAny(snapshot, POITypeModule.POITYPE_RADIATION, x, y);
    }

    /** No-access zones (generic, or only for other factions — the server decides who they apply to). */
    public static boolean blocked(WorldSnapshot snapshot, double x, double y) {
        return inAny(snapshot, POITypeModule.POITYPE_NO_ACCESS, x, y)
                || inAny(snapshot, POITypeModule.POITYPE_FACTION_NO_ACCESS, x, y);
    }
}
