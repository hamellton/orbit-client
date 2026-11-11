package orbit.bot.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.POITypeModule;
import orbit.world.WorldSnapshot;
import orbit.world.maps.ZoneGeometry;
import org.junit.jupiter.api.Test;

class PathFinderTest {

    private static WorldSnapshot.Zone zone(int shape, boolean inverted, Integer... coords) {
        return new WorldSnapshot.Zone("z", POITypeModule.POITYPE_NO_ACCESS, shape, List.of(coords), inverted, true);
    }

    private static PathFinder finder(WorldSnapshot.Zone... zones) {
        List<PathFinder.Obstacle> list = new ArrayList<>();
        for (WorldSnapshot.Zone z : zones) {
            list.add(PathFinder.polygon(z));
        }
        return new PathFinder(list, 21000, 13100);
    }

    /** Every waypoint and every straight flight between them stays out of the zones (with the client's 20). */
    private static void assertAvoids(List<double[]> path, double sx, double sy, WorldSnapshot.Zone... zones) {
        double px = sx;
        double py = sy;
        for (double[] p : path) {
            for (int k = 0; k <= 100; k++) {
                double x = px + (p[0] - px) * k / 100;
                double y = py + (p[1] - py) * k / 100;
                for (WorldSnapshot.Zone z : zones) {
                    assertFalse(insideWithOffset(z, x, y), "through the zone at " + (int) x + "," + (int) y);
                }
            }
            px = p[0];
            py = p[1];
        }
    }

    private static boolean insideWithOffset(WorldSnapshot.Zone z, double x, double y) {
        for (int i = 0; i < 8; i++) {
            double a = 2 * Math.PI * i / 8;
            if (ZoneGeometry.contains(z, x + 20 * Math.cos(a), y + 20 * Math.sin(a))) {
                return true;
            }
        }
        return ZoneGeometry.contains(z, x, y);
    }

    @Test
    void goesAroundACircle() {
        WorldSnapshot.Zone c = zone(MapAddPOICommand.SHAPETYPE_CIRCLE, false, 10000, 6000, 1500);
        PathFinder f = finder(c);
        List<double[]> path = f.path(5000, 6000, 15000, 6000);
        assertTrue(path.size() >= 2, "a detour");
        assertAvoids(path, 5000, 6000, c);
        double around = Math.PI * 1500 + 2 * 3500; // half the circle plus the straight parts
        assertTrue(PathFinder.length(5000, 6000, path) < around * 1.15, "close to the shortest way");
        assertEquals(15000, path.getLast()[0], 1e-9);
    }

    @Test
    void usesTheGapInAWall() {
        WorldSnapshot.Zone top = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, false, 9800, 0, 10200, 5800);
        WorldSnapshot.Zone bottom = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, false, 9800, 7300, 10200, 13100);
        PathFinder f = finder(top, bottom);
        List<double[]> path = f.path(5000, 2000, 15000, 2000);
        assertFalse(path.isEmpty());
        assertAvoids(path, 5000, 2000, top, bottom);
        assertTrue(path.stream().anyMatch(p -> p[1] > 5800 && p[1] < 7300), "through the gap");
    }

    @Test
    void goalInsideAZoneBecomesTheNearestFreePoint() {
        WorldSnapshot.Zone c = zone(MapAddPOICommand.SHAPETYPE_CIRCLE, false, 10000, 6000, 1000);
        PathFinder f = finder(c);
        List<double[]> path = f.path(5000, 6000, 10000, 6000);
        double[] end = path.getLast();
        assertTrue(f.free(end[0], end[1]));
        assertTrue(Math.hypot(end[0] - 10000, end[1] - 6000) < 1000 + PathFinder.MARGIN + 60, "just outside");
    }

    @Test
    void staysInsideAnInvertedZone() {
        // the allowed area is the rectangle; outside it is no access
        WorldSnapshot.Zone arena = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, true, 2000, 2000, 12000, 9000);
        WorldSnapshot.Zone pillar = zone(MapAddPOICommand.SHAPETYPE_CIRCLE, false, 7000, 5500, 1200);
        PathFinder f = finder(arena, pillar);
        List<double[]> path = f.path(3000, 5500, 11000, 5500);
        assertFalse(path.isEmpty());
        assertAvoids(path, 3000, 5500, arena, pillar);
    }

    @Test
    void noWayOutGivesAnEmptyPath() {
        WorldSnapshot.Zone ring = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, true, 4000, 4000, 6000, 6000);
        WorldSnapshot.Zone wall = zone(MapAddPOICommand.SHAPETYPE_RECTANGLE, false, 4900, 3000, 5100, 7000);
        PathFinder f = finder(ring, wall);
        assertTrue(f.path(4500, 5000, 5500, 5000).isEmpty());
    }

    private static orbit.world.World withMine(int heroX, int heroY, int mineX, int mineY) {
        orbit.world.World w = new orbit.world.World();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = heroX;
        si.y = heroY;
        w.apply(si, 1);
        orbit.protocol.gen.AddMineCommand m = new orbit.protocol.gen.AddMineCommand();
        m.hash = "m1";
        m.typeId = 1;
        m.x = mineX;
        m.y = mineY;
        w.apply(m, 1);
        return w;
    }

    @Test
    void fliesRoundAMineOnlyWhenAsked() {
        orbit.world.World w = withMine(5000, 6000, 7000, 6000);
        WorldSnapshot s = w.snapshot(1);
        assertEquals(1, s.mines().size());
        orbit.world.maps.MapCatalog maps = orbit.world.maps.MapCatalog.bundled();

        List<double[]> direct = PathFinder.of(s, maps, false, false).path(5000, 6000, 9000, 6000);
        assertEquals(1, direct.size(), "mines ignored: straight on");

        List<double[]> path = PathFinder.of(s, maps, false, true).path(5000, 6000, 9000, 6000);
        assertTrue(path.size() >= 2, "a detour");
        double px = 5000;
        double py = 6000;
        for (double[] p : path) {
            for (int k = 0; k <= 100; k++) {
                double x = px + (p[0] - px) * k / 100;
                double y = py + (p[1] - py) * k / 100;
                assertTrue(Math.hypot(x - 7000, y - 6000) > PathFinder.MINE_RADIUS, "too close at " + (int) x + "," + (int) y);
            }
            px = p[0];
            py = p[1];
        }
        assertTrue(PathFinder.nearMine(s, 7100, 6000));
        assertFalse(PathFinder.nearMine(s, 7500, 6000));

        orbit.protocol.gen.LMMineExplodedCommand boom = new orbit.protocol.gen.LMMineExplodedCommand();
        boom.hash = "m1";
        w.apply(boom, 2);
        assertTrue(w.snapshot(2).mines().isEmpty(), "an exploded mine is gone");
    }

    @Test
    void aMineUnderTheShipDoesNotTrapIt() {
        WorldSnapshot s = withMine(5000, 6000, 5050, 6000).snapshot(1);
        List<double[]> path = PathFinder.of(s, orbit.world.maps.MapCatalog.bundled(), false, true)
                .path(5000, 6000, 9000, 6000);
        assertFalse(path.isEmpty());
    }

    @Test
    void aShipInTheCornerOfAMinesZoneLeavesIt() {
        // circling a cube next to a mine (live 2026-11-11, 2-6): 331 from the mine — outside its circle, so the
        // mine still counts, but inside its many-sided outline; every line from there crossed the outline and
        // the ship stood still for a minute
        WorldSnapshot s = withMine(13170, 4625, 13500, 4600).snapshot(1);
        PathFinder finder = PathFinder.of(s, orbit.world.maps.MapCatalog.bundled(), false, true);
        List<double[]> path = finder.path(13170, 4625, 13850, 4700);
        assertFalse(path.isEmpty(), "a way out and round the mine");
        assertTrue(finder.free(path.getFirst()[0], path.getFirst()[1]), "out of the zone first");
        double[] last = path.getLast();
        assertTrue(Math.hypot(last[0] - 13850, last[1] - 4700) < 1, "and on to the goal");
    }

    /** The hero (faction 1) on 1-1 and a station of {@code faction} at (station) — a battle station, type 36. */
    private static orbit.world.World withStation(int heroX, int heroY, int stationX, int stationY, int faction) {
        orbit.world.World w = new orbit.world.World();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.factionId = 1;
        si.x = heroX;
        si.y = heroY;
        w.apply(si, 1);
        orbit.protocol.gen.AssetCreateCommand a = new orbit.protocol.gen.AssetCreateCommand();
        a.assetId = 77;
        a.type = new orbit.protocol.gen.AssetTypeModule();
        a.type.typeValue = 36;
        a.userName = "Lynx";
        a.factionId = faction;
        a.posX = stationX;
        a.posY = stationY;
        w.apply(a, 1);
        return w;
    }

    @Test
    void anEnemyBaseIsFlownRound() {
        WorldSnapshot s = withStation(4000, 6000, 7000, 6000, 2).snapshot(1);
        PathFinder finder = PathFinder.of(s, orbit.world.maps.MapCatalog.bundled(), false, false, 500);
        List<double[]> path = finder.path(4000, 6000, 10000, 6000);
        assertTrue(path.size() > 1, "round it, not straight through");
        double sx = 4000;
        double sy = 6000;
        for (double[] p : path) {
            assertTrue(Navigator.distanceToSegment(7000, 6000, sx, sy, p[0], p[1]) >= 499, "kept 500 off the base");
            sx = p[0];
            sy = p[1];
        }
    }

    @Test
    void ourOwnBaseAndAnOffSwitchFlyStraight() {
        WorldSnapshot own = withStation(4000, 6000, 7000, 6000, 1).snapshot(1);
        assertEquals(1, PathFinder.of(own, orbit.world.maps.MapCatalog.bundled(), false, false, 500)
                .path(4000, 6000, 10000, 6000).size(), "our faction's station does not shoot us");
        WorldSnapshot enemy = withStation(4000, 6000, 7000, 6000, 2).snapshot(1);
        assertEquals(1, PathFinder.of(enemy, orbit.world.maps.MapCatalog.bundled(), false, false, 0)
                .path(4000, 6000, 10000, 6000).size(), "radius 0: the option is off");
    }

    @Test
    void aPortalByAnEnemyBaseIsStillReached() {
        WorldSnapshot s = withStation(4000, 6000, 7000, 6000, 2).snapshot(1);
        List<double[]> path = PathFinder.of(s, orbit.world.maps.MapCatalog.bundled(), false, false, 500)
                .path(4000, 6000, 7200, 6000);
        assertFalse(path.isEmpty());
        double[] last = path.getLast();
        assertTrue(Math.hypot(last[0] - 7200, last[1] - 6000) < 1, "right to the goal, not the zone's edge");
    }

    /** A 10×10 grid on a 21 000 × 13 100 map: a wall of avoided cells across the middle, a gap at the bottom. */
    private static orbit.config.BotConfig.ZoneGrid wall() {
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (int y = 0; y < 10; y++) {
            rows.add(y < 8 ? "....##...." : "..........");
        }
        return new orbit.config.BotConfig.ZoneGrid(10, rows);
    }

    @Test
    void theConfigsAvoidedZoneIsFlownRound() {
        var rects = PathFinder.rects(wall(), 21_000, 13_100);
        assertEquals(1, rects.size(), "the 2×8 block of cells is one rectangle");
        PathFinder p = new PathFinder(rects, rects, 21_000, 13_100);
        var path = p.path(3_000, 3_000, 18_000, 3_000);
        assertTrue(path.size() >= 2, "round the wall, not through it: " + path.size());
        double lowest = path.stream().mapToDouble(q -> q[1]).max().orElse(0);
        assertTrue(lowest > 8 * 1_310, "through the gap at the bottom: " + lowest);
    }

    @Test
    void aGoalInsideTheAvoidedZoneIsStillReached() {
        var rects = PathFinder.rects(wall(), 21_000, 13_100);
        PathFinder p = new PathFinder(rects, rects, 21_000, 13_100);
        var path = p.path(3_000, 3_000, 9_500, 3_000); // a portal inside the avoided cells
        assertTrue(!path.isEmpty());
        double[] end = path.getLast();
        assertEquals(9_500, end[0], 1);
    }

    /** The user's 5-3 avoided zone (DarkBot profile PALLADIUM): all but the palladium field and one-cell lanes. */
    private static orbit.config.BotConfig.ZoneGrid fiveThree() {
        return new orbit.config.BotConfig.ZoneGrid(30, List.of(
                "##############################", "##############################", "##############################",
                "########################.#####", "########################.#####", "########################.#####",
                "########################.#####", "########################.#..##", "########################.#.###",
                "########################.#.###", "########################.#.###", "########################.#.###",
                "########################.#.###", "########################.....#", "####################.........#",
                "####################........##", "####################.#########", "####################.#########",
                "####################..########", "#.###################.########", "#......................#######",
                "#########..............#######", "#########..............#######", "#########..............#######",
                "#########..............#######", "#########..............#######", "#########..............#######",
                "#########..............#######", "##############################", "##############################"));
    }

    @Test
    void theOneCellLanesToTheFieldAreFlown() {
        // the user's lanes: from 5-3's portal (38 200/6 600) to the palladium field
        var info = orbit.world.maps.MapCatalog.bundled().info(93);
        PathFinder p = PathFinder.avoiding(fiveThree(), info.width(), info.height());
        var path = p.path(38_200, 6_600, 23_200, 21_200);
        assertFalse(path.isEmpty(), "a way down the lane");
        double[] end = path.getLast();
        assertEquals(23_200, end[0], 1);
        assertEquals(21_200, end[1], 1);
        double[] at = {38_200, 6_600};
        for (double[] q : path) {
            assertTrue(p.clear(at[0], at[1], q[0], q[1]), "every leg clear of the avoided cells");
            at = q;
        }
    }

    @Test
    void theDamageZoneOfThePalladiumFieldIsFlownInto() {
        // live 2026-11-11: 5-3's palladium field is a damage zone; the bot stopped at its edge
        orbit.world.World world = withMine(10_000, 2_000, 1, 1); // map 1 (21 000 × 13 100), a mine out of the way
        orbit.protocol.gen.MapAddPOICommand poi = new orbit.protocol.gen.MapAddPOICommand();
        poi.poiId = "field";
        poi.poiType = new orbit.protocol.gen.POITypeModule();
        poi.poiType.typeValue = (short) orbit.protocol.gen.POITypeModule.POITYPE_DAMAGE;
        poi.shape = (short) MapAddPOICommand.SHAPETYPE_RECTANGLE;
        poi.shapeCoordinates = List.of(5_000, 7_000, 15_000, 12_000);
        poi.active = true;
        world.apply(poi, 1);
        WorldSnapshot s = world.snapshot(1);
        var maps = orbit.world.maps.MapCatalog.bundled();
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (int r = 0; r < 10; r++) {
            rows.add(r >= 6 && r <= 8 ? "...####..." : "..........");
        }
        var preferred = new orbit.config.BotConfig.ZoneGrid(10, rows);
        double[] end = PathFinder.of(s, maps, false, false, 0, null, preferred).path(10_000, 2_000, 10_000, 8_200).getLast();
        assertEquals(8_200, end[1], 1, "right into the field");
        double[] plain = PathFinder.of(s, maps, false, false, 0, null, null).path(10_000, 2_000, 10_000, 8_200).getLast();
        assertEquals(8_200, plain[1], 1, "a damage zone is open space anyway, as in DarkBot");
    }
}
