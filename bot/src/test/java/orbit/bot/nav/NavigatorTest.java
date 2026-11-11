package orbit.bot.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.protocol.gen.BeaconCommand;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.POITypeModule;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.UserSettingsCommand;
import orbit.protocol.gen.WindowSettingsModule;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import orbit.world.maps.ZoneGeometry;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

/** Flies on the real world model with a virtual clock. */
class NavigatorTest {

    private static final long MS = 1_000_000L;

    private long now;
    private final World world = new World();
    private final List<MoveRequest> sent = new ArrayList<>();
    private final List<Long> sentAt = new ArrayList<>();

    private Navigator navigator(int mapId, int x, int y) {
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = mapId;
        si.x = x;
        si.y = y;
        si.speed = 300;
        si.hitPoints = 1;
        si.hitMax = 1;
        world.apply(si, now);
        UserSettingsCommand us = new UserSettingsCommand();
        us.windowSettingsModule = new WindowSettingsModule();
        us.windowSettingsModule.minmapScale = 9;
        world.apply(us, now);
        return new Navigator(() -> world.snapshot(now), p -> {
            MoveRequest m = (MoveRequest) p;
            sent.add(m);
            sentAt.add(now);
            world.applyOutgoing(m, now);
        }, MapCatalog.bundled(), new Random(7), () -> now);
    }

    /** Ticks every 100 ms until idle or the time limit. */
    private void fly(Navigator nav, long limitMs) {
        for (long t = 0; t < limitMs && !nav.idle(); t += 100) {
            nav.tick();
            now += 100 * MS;
        }
    }

    @Test
    void farMoveIsOneMinimapClickOnTheGrid() {
        Navigator nav = navigator(9, 18800, 12500);
        nav.moveTo(2111, 2222, false);
        fly(nav, 120_000);
        assertTrue(nav.idle(), "arrived");
        assertEquals(1, sent.size(), "one click for a plain far move");
        MoveRequest m = sent.getFirst();
        assertEquals(0, m.targetX % 90, "minimap grid of zoom 9 on a scale-1 map");
        assertEquals(0, m.targetY % 90);
        assertEquals(18800, m.positionX, "position is where the ship is");
        assertEquals(12500, m.positionY);
    }

    @Test
    void gridFollowsTheMapScale() {
        Navigator nav = navigator(16, 21000, 13100); // 4-4, scale 2
        nav.moveTo(28000, 25124, false);
        nav.tick();
        assertEquals(0, sent.getFirst().targetX % 180);
        assertEquals(0, sent.getFirst().targetY % 180);
    }

    @Test
    void preciseDestinationGetsAFinalNearClick() {
        Navigator nav = navigator(9, 18800, 12500);
        nav.moveTo(2000, 2000, true); // the portal to 3-2
        fly(nav, 120_000);
        assertTrue(nav.idle());
        assertTrue(sent.size() >= 2, "minimap click, then a click on the portal: " + sent.size());
        WorldSnapshotProbe.assertNear(world.snapshot(now).hero(), 2000, 2000, Navigator.CLICK_SPREAD * 2 + 10);
        for (int i = 1; i < sentAt.size(); i++) {
            assertTrue(sentAt.get(i) - sentAt.get(i - 1) >= Navigator.MIN_INTERVAL_MS * MS, "no faster than a held mouse button");
        }
        // every request starts from where the previous one put the ship by then: no jumps
        for (MoveRequest m : sent.subList(1, sent.size())) {
            assertTrue(m.positionX > 1500 && m.positionY > 1500, "position follows the flight");
        }
    }

    @Test
    void nearMoveIsASpaceClickCloseToThePoint() {
        Navigator nav = navigator(9, 10000, 6000);
        nav.moveTo(10500, 6300, false);
        fly(nav, 20_000);
        assertEquals(1, sent.size());
        MoveRequest m = sent.getFirst();
        assertTrue(Math.hypot(m.targetX - 10500, m.targetY - 6300) <= Navigator.CLICK_SPREAD + 1);
    }

    @Test
    void fliesAroundANoAccessZone() {
        Navigator nav = navigator(9, 3000, 6500);
        MapAddPOICommand poi = new MapAddPOICommand();
        poi.poiId = "wall";
        poi.poiType = new POITypeModule();
        poi.poiType.typeValue = (short) POITypeModule.POITYPE_NO_ACCESS;
        poi.shape = (short) MapAddPOICommand.SHAPETYPE_CIRCLE;
        poi.shapeCoordinates = List.of(10500, 6500, 2500);
        poi.active = true;
        world.apply(poi, now);
        WorldSnapshot.Zone zone = world.snapshot(now).zones().get("wall");

        nav.moveTo(18000, 6500, false);
        for (long t = 0; t < 120_000 && !nav.idle(); t += 100) {
            nav.tick();
            now += 100 * MS;
            WorldSnapshot.Hero h = world.snapshot(now).hero();
            assertTrue(Math.hypot(h.x() - 10500, h.y() - 6500) > 2500 + 20, "inside the zone at " + h.x() + "," + h.y());
            assertTrue(!ZoneGeometry.contains(zone, h.x(), h.y()));
        }
        assertTrue(nav.idle(), "arrived");
        assertTrue(sent.size() >= 3, "a turn at each corner: " + sent.size());
        for (int i = 1; i < sentAt.size(); i++) {
            assertTrue(sentAt.get(i) - sentAt.get(i - 1) >= Navigator.MIN_INTERVAL_MS * MS);
        }
    }

    @Test
    void clicksAgainWhenTheServerMovesTheShip() {
        Navigator nav = navigator(9, 10000, 6000);
        nav.moveTo(15000, 6000, false);
        nav.tick();
        assertEquals(1, sent.size());
        now += 2_000 * MS;
        // a jump far away (e.g. pushed back): the world takes the beacon, the course is lost
        BeaconCommand b = new BeaconCommand();
        b.positionX = 3000;
        b.positionY = 9000;
        b.positionAheadX = 3000;
        b.positionAheadY = 9000;
        world.apply(b, now);
        fly(nav, 60_000);
        assertTrue(nav.idle());
        assertTrue(sent.size() >= 2, "a new click from the new position");
        assertEquals(3000, sent.get(1).positionX);
    }

    @Test
    void aNewDestinationIsClickedAtOnceWhileStillFlyingToTheOldOne() {
        // «Выйти у портала» mid-flight (live 2026-11-11): the ship flew on to the old point first
        Navigator nav = navigator(9, 2000, 2000);
        nav.moveTo(18000, 2000, false);
        nav.tick();
        assertEquals(1, sent.size());
        now += 3_000 * MS; // flying east, far from there yet
        nav.tick();
        nav.moveTo(2500, 11000, true); // a portal elsewhere
        for (int i = 0; i < 20 && sent.size() < 2; i++) {
            now += 100 * MS;
            nav.tick();
        }
        assertEquals(2, sent.size(), "the new destination is clicked within two seconds");
        assertTrue(sent.getLast().targetY > 6000, "towards the new one: " + sent.getLast().targetY);
        assertTrue(world.snapshot(now).hero().moving());
    }
}
