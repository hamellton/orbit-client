package orbit.bot.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import orbit.protocol.Packet;
import orbit.protocol.gen.JumpInitiatedCommand;
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.JumpgateCreateCommand;
import orbit.protocol.gen.MapAssetActionAvailableCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.UserSettingsCommand;
import orbit.protocol.gen.WindowSettingsModule;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class TravelerTest {

    private static final long MS = 1_000_000L;
    private final MapCatalog catalog = MapCatalog.bundled();

    @Test
    void routesFollowThePortalGraph() {
        List<MapCatalog.Portal> r = Router.route(catalog, 9, 18800, 12500, 12, 3);
        assertEquals(List.of("3-2", "3-4"), r.stream().map(MapCatalog.Portal::toName).toList());
        assertTrue(Router.route(catalog, 9, 0, 0, 9, 3).isEmpty(), "already there");
        List<MapCatalog.Portal> toEnemyHome = Router.route(catalog, 9, 18800, 12500, 1, 3);
        assertEquals("1-1", toEnemyHome.getLast().toName());
        assertTrue(toEnemyHome.size() >= 4);
        // 5-4 is entered only through the portal of one's own faction on 5-2
        List<MapCatalog.Portal> pirates = Router.route(catalog, 92, 10000, 6000, 94, 3);
        assertEquals(1, pirates.size());
        assertEquals(3, pirates.getFirst().faction());
        assertEquals(19300, pirates.getFirst().x());
        assertEquals(5400, pirates.getFirst().y());
    }

    /** A small game server: portals, jump offers, jumps. */
    private final class Server {
        final World world = new World();
        final List<long[]> jumpRequests = new ArrayList<>(); // {time, distance, offeredSince}
        final Map<Integer, MapCatalog.Portal> portalById = new HashMap<>();
        final Map<Integer, Boolean> offered = new HashMap<>();
        long now;
        long jumpAt;
        int jumpTo;
        int fromMap;

        void enterMap(int mapId, double x, double y) {
            ShipInitializationCommand si = new ShipInitializationCommand();
            si.userId = 1;
            si.mapId = mapId;
            si.factionId = 3;
            si.x = (int) x;
            si.y = (int) y;
            si.speed = 400;
            si.hitPoints = 1;
            si.hitMax = 1;
            world.apply(si, now);
            UserSettingsCommand us = new UserSettingsCommand();
            us.windowSettingsModule = new WindowSettingsModule();
            us.windowSettingsModule.minmapScale = 9;
            world.apply(us, now);
            portalById.clear();
            offered.clear();
            int id = mapId * 100;
            for (MapCatalog.Portal p : catalog.portals(mapId)) {
                JumpgateCreateCommand g = new JumpgateCreateCommand();
                g.gateId = ++id;
                g.x = p.x();
                g.y = p.y();
                g.designId = 1;
                g.isVisibleOnMinimap = true;
                world.apply(g, now);
                portalById.put(g.gateId, p);
            }
        }

        void receive(Packet p) {
            if (p instanceof MoveRequest m) {
                world.applyOutgoing(m, now);
            } else if (p instanceof JumpRequest) {
                WorldSnapshot.Hero h = world.snapshot(now).hero();
                for (Map.Entry<Integer, MapCatalog.Portal> e : portalById.entrySet()) {
                    double d = Math.hypot(e.getValue().x() - h.x(), e.getValue().y() - h.y());
                    if (d <= 500) {
                        Long since = world.snapshot(now).activatable().get(e.getKey());
                        jumpRequests.add(new long[] {now, (long) d, since == null ? -1 : now - since});
                        JumpInitiatedCommand ji = new JumpInitiatedCommand();
                        ji.mapId = e.getValue().toId();
                        world.apply(ji, now);
                        jumpAt = now + 3_300 * MS;
                        jumpTo = e.getValue().toId();
                        fromMap = h.mapId();
                        return;
                    }
                }
            }
        }

        void step() {
            WorldSnapshot.Hero h = world.snapshot(now).hero();
            if (jumpTo != 0 && now >= jumpAt) {
                double[] at = Router.arrival(catalog, jumpTo, fromMap);
                int to = jumpTo;
                jumpTo = 0;
                enterMap(to, at[0], at[1]);
                return;
            }
            for (Map.Entry<Integer, MapCatalog.Portal> e : portalById.entrySet()) {
                boolean near = Math.hypot(e.getValue().x() - h.x(), e.getValue().y() - h.y()) <= 480;
                if (near != offered.getOrDefault(e.getKey(), false)) {
                    MapAssetActionAvailableCommand m = new MapAssetActionAvailableCommand();
                    m.mapAssetId = e.getKey();
                    m.activatable = near;
                    world.apply(m, now);
                    offered.put(e.getKey(), near);
                }
            }
        }
    }

    @Test
    void travelsThroughTwoPortalsLikeAPlayer() {
        Server server = new Server();
        server.enterMap(9, 18800, 12500);
        Navigator nav = new Navigator(() -> server.world.snapshot(server.now), server::receive, catalog, new Random(3),
                () -> server.now);
        Traveler t = new Traveler(() -> server.world.snapshot(server.now), server::receive, nav, catalog, new Random(5),
                () -> server.now);
        t.travel(12, 10000, 6000);
        for (long ms = 0; ms < 300_000 && t.state() == Traveler.State.TRAVELLING; ms += 100) {
            server.step();
            t.tick();
            server.now += 100 * MS;
        }
        assertEquals(Traveler.State.ARRIVED, t.state(), t.failure());
        WorldSnapshot.Hero h = server.world.snapshot(server.now).hero();
        assertEquals(12, h.mapId());
        assertTrue(Math.hypot(h.x() - 10000, h.y() - 6000) < 120, "at the point: " + h.x() + "," + h.y());
        assertEquals(2, server.jumpRequests.size(), "one press per portal");
        for (long[] j : server.jumpRequests) {
            assertTrue(j[1] <= Traveler.JUMP_RANGE + 1, "pressed close to the portal: " + j[1]);
            assertTrue(j[2] >= Traveler.PRESS_MIN_MS * MS, "after the server offered the jump, like a reaction");
        }
    }

    @Test
    void unreachableMapFails() {
        Server server = new Server();
        server.enterMap(9, 18800, 12500);
        Navigator nav = new Navigator(() -> server.world.snapshot(server.now), server::receive, catalog, new Random(3),
                () -> server.now);
        Traveler t = new Traveler(() -> server.world.snapshot(server.now), server::receive, nav, catalog, new Random(5),
                () -> server.now);
        t.travel(51, Double.NaN, Double.NaN); // a galaxy gate: not in the portal graph
        t.tick();
        assertEquals(Traveler.State.FAILED, t.state());
    }
}
