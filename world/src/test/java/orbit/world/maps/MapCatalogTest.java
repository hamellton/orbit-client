package orbit.world.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

class MapCatalogTest {

    private final MapCatalog catalog = MapCatalog.bundled();

    @Test
    void sizesFollowTheClientScaleFactor() {
        assertEquals(21000, catalog.info(9).width());
        assertEquals(13100, catalog.info(9).height());
        assertEquals("3-1", catalog.info(9).name());
        assertEquals(3, catalog.info(9).homeFaction());
        assertEquals(42000, catalog.info(16).width(), "4-4 has scaleFactor 2");
        assertEquals(26200, catalog.info(16).height());
        assertEquals("pirate", catalog.info(91).type());
        assertEquals(10500, catalog.info(401).width(), "experiment zones have scaleFactor 0.5");

        MapCatalog.MapInfo unknown = catalog.info(99_999);
        assertFalse(unknown.known());
        assertEquals(MapCatalog.STD_WIDTH, unknown.width());
        assertEquals(16, catalog.byName("4-4").id());
        assertNull(catalog.byName("nowhere"));
    }

    @Test
    void portalGraphIsConsistentAndConnected() {
        List<String> problems = new ArrayList<>();
        for (int mapId : List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
                25, 26, 27, 28, 29, 91, 92, 93, 94, 306, 307, 308)) {
            MapCatalog.MapInfo m = catalog.info(mapId);
            if (!m.known()) {
                problems.add(mapId + " missing in maps.json");
            }
            if (catalog.portals(mapId).isEmpty()) {
                problems.add(mapId + " has no portals");
            }
            for (MapCatalog.Portal p : catalog.portals(mapId)) {
                if (!m.contains(p.x(), p.y())) {
                    problems.add(m.name() + ": portal to " + p.toName() + " outside the map at " + p.x() + "," + p.y());
                }
                if (p.toId() != -1 && !catalog.info(p.toId()).known()) {
                    problems.add(m.name() + ": portal to unknown map " + p.toName());
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));

        // every regular map can be reached from every home map
        for (int home : List.of(1, 5, 9)) {
            Set<Integer> reached = reachable(home);
            for (int target : List.of(16, 29, 93, 306, 307, 308, 20, 24, 28)) {
                assertTrue(reached.contains(target), catalog.info(home).name() + " cannot reach " + catalog.info(target).name());
            }
        }
    }

    private Set<Integer> reachable(int from) {
        Set<Integer> seen = new HashSet<>(List.of(from));
        ArrayDeque<Integer> queue = new ArrayDeque<>(List.of(from));
        while (!queue.isEmpty()) {
            for (MapCatalog.Portal p : catalog.portals(queue.poll())) {
                if (p.toId() > 0 && seen.add(p.toId())) {
                    queue.add(p.toId());
                }
            }
        }
        return seen;
    }

    @Test
    void portalNearFindsTheClosestWithinRange() {
        MapCatalog.Portal p = catalog.portalNear(9, 2100, 1900, 500);
        assertNotNull(p);
        assertEquals(10, p.toId(), "3-1 top left leads to 3-2");
        assertNull(catalog.portalNear(9, 5000, 5000, 500));
    }

    @Test
    void zoneShapesAsTheClientReadsThem() {
        WorldSnapshot.Zone circle = zone(MapAddPOICommand.SHAPETYPE_CIRCLE, false, 1000, 1000, 100);
        assertTrue(ZoneGeometry.contains(circle, 1050, 1050));
        assertFalse(ZoneGeometry.contains(circle, 1100, 1100));

        WorldSnapshot.Zone rect = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, false, 0, 0, 100, 50);
        assertTrue(ZoneGeometry.contains(rect, 50, 25));
        assertFalse(ZoneGeometry.contains(rect, 150, 25));
        WorldSnapshot.Zone rect4 = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, false, 0, 0, 100, 0, 100, 50, 0, 50);
        assertTrue(ZoneGeometry.contains(rect4, 99, 49), "four corners: bounding box");

        WorldSnapshot.Zone triangle = zone(MapAddPOICommand.SHAPETYPE_POLYGON, false, 0, 0, 100, 0, 0, 100);
        assertTrue(ZoneGeometry.contains(triangle, 10, 10));
        assertFalse(ZoneGeometry.contains(triangle, 80, 80));

        WorldSnapshot.Zone border = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, true, 0, 0, 21000, 13100);
        assertFalse(ZoneGeometry.contains(border, 10000, 6000), "inverted: inside the map is outside the zone");
        assertTrue(ZoneGeometry.contains(border, -100, 6000));
    }

    private static WorldSnapshot.Zone zone(int shape, boolean inverted, Integer... coords) {
        return new WorldSnapshot.Zone("z", 0, shape, List.of(coords), inverted, true);
    }
}
