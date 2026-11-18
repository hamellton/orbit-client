package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import orbit.bot.mod.LowBoard;
import orbit.bot.mod.LowModule;
import orbit.config.BotConfig;
import orbit.config.GateCatalog;
import orbit.config.Json;
import orbit.protocol.gen.AssetCreateCommand;
import orbit.protocol.gen.AssetTypeModule;
import orbit.protocol.gen.GroupInitializationCommand;
import orbit.protocol.gen.GroupMemberInitializationCommand;
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.LeaveGroupRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.LocationModule;
import orbit.protocol.gen.MapAssetActionAvailableCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.ShipRemoveCommand;
import orbit.protocol.gen.ShipSelectRequest;
import orbit.world.World;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-18: LoW «в точности как у даркбот, но оставлять вагрантов 3 шт; лидер сам проходит, группа после захода выходит». */
class LowModuleTest {

    static final int X3 = MapCatalog.bundled().byName("3-3").id();

    @BeforeEach
    void boards() {
        LowBoard.reset();
    }

    static BotConfig config(String json) throws Exception {
        ObjectNode s = (ObjectNode) Json.MAPPER.readTree(json);
        BotConfig c = BotConfig.empty();
        c = new BotConfig(c.schema(), "", BotConfig.Module.LOW, c.general(), c.npcs(), c.loot(), c.collect(),
                c.pet(), c.safety(), c.zones(), c.safetySpots(), c.modules(), c.extra(), c.buy(), c.session(), c.flee(),
                c.group());
        return c.withModuleSettings(BotConfig.Module.LOW.key(), s);
    }

    /** A simulated account named {@code name} on {@code map}. */
    static Sim sim(BotConfig c, String name, int map, int x, int y) {
        Sim sim = new Sim(c, map, x, y);
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.userName = name;
        si.mapId = map;
        si.factionId = 3;
        si.x = x;
        si.y = y;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        si.shield = 50_000;
        si.shieldMax = 50_000;
        sim.world.apply(si, sim.now);
        sim.now = 1_000 * Sim.MS;
        return sim;
    }

    /** The group: {id, name, map, x, y} per member, the first the leader. */
    static void group(Sim sim, Object[]... members) {
        GroupInitializationCommand g = new GroupInitializationCommand();
        g.id = 9;
        g.leaderId = (int) members[0][0];
        for (Object[] m : members) {
            GroupMemberInitializationCommand mi = new GroupMemberInitializationCommand();
            mi.id = (int) m[0];
            mi.name = (String) m[1];
            LocationModule loc = new LocationModule();
            loc.mapId = (int) m[2];
            loc.x = (int) m[3];
            loc.y = (int) m[4];
            mi.location = loc;
            g.members.add(mi);
        }
        sim.world.apply(g, sim.now);
    }

    static void relay(Sim sim, int id, int x, int y) {
        AssetCreateCommand a = new AssetCreateCommand();
        a.assetId = id;
        a.type = new AssetTypeModule();
        a.type.typeValue = World.RELAY_STATION;
        a.posX = x;
        a.posY = y;
        sim.world.apply(a, sim.now);
    }

    static void text(Sim sim, String t) {
        LegacyModule l = new LegacyModule();
        l.message = "0|A|STD|" + t;
        sim.world.apply(l, sim.now);
    }

    static void run(Sim sim, LowModule m, long ms) {
        for (long t = 0; t < ms; t += 100) {
            m.tick();
            var w = sim.world.snapshot(sim.now);
            for (var p : w.portals().values()) {
                boolean near = Math.hypot(p.x() - w.hero().x(), p.y() - w.hero().y()) < 500;
                if (near != w.activatable().containsKey(p.id())) {
                    MapAssetActionAvailableCommand a = new MapAssetActionAvailableCommand();
                    a.mapAssetId = p.id();
                    a.activatable = near;
                    sim.world.apply(a, sim.now);
                }
            }
            sim.now += 100 * Sim.MS;
        }
    }

    static int lastSelected(Sim sim) {
        int id = 0;
        for (var p : sim.sent) {
            if (p instanceof ShipSelectRequest s) {
                id = s.targetId;
            }
        }
        return id;
    }

    @Test
    void theLeaderWaitsForTheGroupAtThePortalAndJumpsFirst() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"]}"), "Lead", X3, 5_000, 5_000);
        GateModuleTest.portal(sim, 700, GateCatalog.LOW_PORTAL, 2_000, 2_000);
        LowModule m = new LowModule(sim.ctx);
        assertEquals(X3, m.workingMap());
        run(sim, m, 30_000);
        assertFalse(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "no jump without the group");
        assertEquals(LowBoard.Phase.GATHER, LowBoard.of("Lead").phase());
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 2_000, h.y() - 2_000) < 1_500, "near the portal: " + h.x() + "/" + h.y());
        // two in the group, one still away: no jump
        group(sim, new Object[] {1, "Lead", X3, 2_500, 2_500}, new Object[] {2, "A", X3, 2_400, 2_300},
                new Object[] {3, "B", 26, 10_000, 10_000});
        run(sim, m, 10_000);
        assertFalse(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "B is on another map");
        group(sim, new Object[] {1, "Lead", X3, 2_500, 2_500}, new Object[] {2, "A", X3, 2_400, 2_300},
                new Object[] {3, "B", X3, 2_600, 2_200});
        run(sim, m, 20_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "jumped first");
        assertEquals(LowBoard.Phase.ENTERING, LowBoard.of("Lead").phase());
    }

    @Test
    void aMemberJumpsAfterTheLeader() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"]}"), "A", X3, 5_000, 5_000);
        GateModuleTest.portal(sim, 700, GateCatalog.LOW_PORTAL, 2_000, 2_000);
        LowModule m = new LowModule(sim.ctx);
        assertEquals(0, m.workingMap(), "nothing to do till the leader gathers");
        LowBoard.of("Lead").helper("x", "");
        group(sim, new Object[] {5, "Lead", X3, 2_500, 2_500}, new Object[] {1, "A", X3, 5_000, 5_000});
        // the leader gathers: to the portal, no jump yet
        setPhase("Lead", LowBoard.Phase.GATHER);
        assertEquals(X3, m.workingMap());
        run(sim, m, 20_000);
        assertFalse(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest));
        // the leader is in the gate (the group packets say so): after him
        group(sim, new Object[] {5, "Lead", GateCatalog.LOW_MAP, 1_000, 12_000}, new Object[] {1, "A", X3, 5_000, 5_000});
        run(sim, m, 20_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "followed the leader");
    }

    static void setPhase(String leader, LowBoard.Phase p) throws Exception {
        var mth = LowBoard.class.getDeclaredMethod("set", LowBoard.Phase.class, long.class);
        mth.setAccessible(true);
        mth.invoke(LowBoard.of(leader), p, System.currentTimeMillis());
    }

    @Test
    void relaysOnlyWhenNoNpcIsLeftAndInSharedPluginsOrder() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"],\"waitForAll\":false}"), "Lead",
                GateCatalog.LOW_MAP, 10_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        group(sim, new Object[] {1, "Lead", GateCatalog.LOW_MAP, 10_000, 6_000},
                new Object[] {2, "A", GateCatalog.LOW_MAP, 10_100, 6_000}, new Object[] {3, "B", GateCatalog.LOW_MAP, 9_900, 6_000});
        relay(sim, GateCatalog.LOW_RELAY_BASE + 1, 8_000, 6_000);
        relay(sim, GateCatalog.LOW_RELAY_BASE + 4, 14_000, 6_000);
        sim.npc(50, "-=[ Vagrant ]=-", 10_600, 6_000);
        LowModule m = new LowModule(sim.ctx);
        run(sim, m, 5_000);
        assertEquals(50, lastSelected(sim), "the Vagrant first, no relay while an NPC lives");
        ShipRemoveCommand gone = new ShipRemoveCommand();
        gone.userId = 50;
        sim.world.apply(gone, sim.now);
        run(sim, m, 30_000);
        assertEquals(GateCatalog.LOW_RELAY_BASE + 4, lastSelected(sim), "relay 4 before relay 1 (SharedPlugin's order)");
        assertTrue(m.status().contains("реле 4"), m.status());
    }

    @Test
    void relay1WaitsTillEverybodyIsIn() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"]}"), "Lead", GateCatalog.LOW_MAP, 10_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        group(sim, new Object[] {1, "Lead", GateCatalog.LOW_MAP, 10_000, 6_000},
                new Object[] {2, "A", X3, 2_000, 2_000}, new Object[] {3, "B", GateCatalog.LOW_MAP, 9_900, 6_000});
        relay(sim, GateCatalog.LOW_RELAY_BASE + 4, 11_000, 6_000);
        LowModule m = new LowModule(sim.ctx);
        run(sim, m, 10_000);
        assertEquals(0, lastSelected(sim), "A is not in yet: the relay is not touched");
        assertTrue(m.status().contains("жду, пока все войдут"), m.status());
        text(sim, "The LoW Gate has been brought online!");
        run(sim, m, 10_000);
        assertEquals(GateCatalog.LOW_RELAY_BASE + 4, lastSelected(sim), "online: the relay");
        assertEquals(LowBoard.Phase.STARTED, LowBoard.of("Lead").phase());
    }

    @Test
    void threeVagrantsAreLeftAliveRoundTheFalcon() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"],\"waitForAll\":false,\"leaveVagrants\":3}"),
                "Lead", GateCatalog.LOW_MAP, 10_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(60, "-=[ Century Falcon ]=-", 11_500, 6_000);
        sim.npc(61, "-=[ Vagrant ]=-", 10_200, 6_000);
        sim.npc(62, "-=[ Vagrant ]=-", 10_000, 6_200);
        sim.npc(63, "-=[ Vagrant ]=-", 9_800, 6_000);
        sim.npc(64, "-=[ Vagrant ]=-", 10_000, 5_800);
        LowModule m = new LowModule(sim.ctx);
        run(sim, m, 3_000);
        int first = sim.sent.stream().filter(p -> p instanceof ShipSelectRequest).mapToInt(p -> ((ShipSelectRequest) p).targetId)
                .findFirst().orElse(0);
        assertTrue(first >= 61 && first <= 64, "four Vagrants about: the nearest is thinned first (Kekka), got " + first);
        ShipRemoveCommand gone = new ShipRemoveCommand();
        gone.userId = first;
        sim.world.apply(gone, sim.now);
        int before = sim.sent.size();
        run(sim, m, 5_000);
        List<Integer> later = sim.sent.subList(before, sim.sent.size()).stream().filter(p -> p instanceof ShipSelectRequest)
                .map(p -> ((ShipSelectRequest) p).targetId).toList();
        assertEquals(60, m.fightTarget(), "three left: the Falcon is fought");
        assertTrue(later.stream().allMatch(id -> id == 60), "three left: only the Falcon, the Vagrants let be: " + later);
        assertTrue(m.status().contains("оставляю 3"), m.status());
    }

    @Test
    void aHelperLeavesTheGroupOnceTheGateIsOnThenGetsOutAndRests() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"],\"mode\":\"solo\"}"), "A",
                GateCatalog.LOW_MAP, 1_000, 12_000);
        group(sim, new Object[] {5, "Lead", GateCatalog.LOW_MAP, 1_100, 12_000}, new Object[] {1, "A", GateCatalog.LOW_MAP, 1_000, 12_000},
                new Object[] {6, "B", GateCatalog.LOW_MAP, 900, 12_000});
        sim.npc(70, "-=[ Marauder ]=-", 3_000, 11_000);
        LowModule m = new LowModule(sim.ctx);
        run(sim, m, 3_000);
        assertFalse(sim.sent.stream().anyMatch(p -> p instanceof LeaveGroupRequest), "not before the start");
        text(sim, "The LoW Gate has been brought online!");
        run(sim, m, 15_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof LeaveGroupRequest), "left the group after the start");
        sim.world.apply(new orbit.protocol.gen.GroupCloseCommand(), sim.now);
        run(sim, m, 10_000);
        assertTrue(m.status().contains("жду гибели"), m.status());
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 3_000, h.y() - 11_000) < 1_500, "close to the NPC, not shooting: " + h.x() + "/" + h.y());
        assertNull(m.restRequest());
        // destroyed, revived at the base: the rest till the leader gathers again
        sim.ctx.stats().deaths.incrementAndGet();
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.userName = "A";
        si.mapId = MapCatalog.bundled().byName("3-1").id();
        si.factionId = 3;
        si.x = 1_000;
        si.y = 1_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
        setPhase("Lead", LowBoard.Phase.STARTED);
        run(sim, m, 2_000);
        assertNotNull(m.restRequest(), "rests till the next run");
        assertFalse(m.restRequest().wake().getAsBoolean());
        setPhase("Lead", LowBoard.Phase.GATHER);
        assertTrue(m.restRequest().wake().getAsBoolean(), "the leader gathers: back in");
    }

    @Test
    void aHelperDiesInTheRadiationWhenTheMapShowsIt() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"],\"mode\":\"solo\"}"), "A",
                GateCatalog.LOW_MAP, 3_000, 11_000);
        // the map's border as the server lays it: radiation outside the rectangle 1 000…20 000 × 1 000…12 100
        orbit.protocol.gen.MapAddPOICommand z = new orbit.protocol.gen.MapAddPOICommand();
        z.poiId = "rad";
        z.poiType = new orbit.protocol.gen.POITypeModule();
        z.poiType.typeValue = orbit.protocol.gen.POITypeModule.POITYPE_RADIATION;
        z.shape = (short) orbit.protocol.gen.MapAddPOICommand.SHAPETYPE_RECTANGLE;
        z.shapeCoordinates = new java.util.ArrayList<>(List.of(1_000, 1_000, 20_000, 1_000, 20_000, 12_100, 1_000, 12_100));
        z.inverted = true;
        z.active = true;
        sim.world.apply(z, sim.now);
        sim.npc(70, "-=[ Marauder ]=-", 10_000, 6_000);
        LowModule m = new LowModule(sim.ctx);
        run(sim, m, 1_000);
        text(sim, "The LoW Gate has been brought online!");
        run(sim, m, 40_000);
        assertTrue(m.status().contains("радиации"), m.status());
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(orbit.world.maps.ZoneGeometry.inAny(sim.world.snapshot(sim.now),
                orbit.protocol.gen.POITypeModule.POITYPE_RADIATION, h.x(), h.y()), "in the radiation: " + h.x() + "/" + h.y());
    }

    @Test
    void theRunIsCountedWhenCompleted() throws Exception {
        Sim sim = sim(config("{\"leader\":\"Lead\",\"members\":[\"A\",\"B\"],\"waitForAll\":false}"), "Lead",
                GateCatalog.LOW_MAP, 10_000, 6_000);
        LowModule m = new LowModule(sim.ctx);
        run(sim, m, 1_000);
        text(sim, "You’ve completed the LoW Gate. Great job!");
        run(sim, m, 1_000);
        assertTrue(m.status().contains("пройдены"), m.status());
        assertEquals(1, LowBoard.of("Lead").runs());
        assertEquals(LowBoard.Phase.DONE, LowBoard.of("Lead").phase());
    }

    @Test
    void theCatalogKnowsLowsNpcs() {
        GateCatalog.Gate low = GateCatalog.byMap(GateCatalog.LOW_MAP);
        assertNotNull(low);
        assertEquals(GateCatalog.LOW_PORTAL, low.portal());
        BotConfig.NpcRule falcon = GateCatalog.defaultRule(low, "-=[ Century Falcon ]=-");
        assertEquals(580, falcon.radius());
        assertTrue(falcon.has(BotConfig.NpcFlag.NO_CIRCLE));
        BotConfig.NpcRule relay = GateCatalog.defaultRule(low, World.RELAY_NAME);
        assertEquals(GateCatalog.LOW_RELAY_RADIUS, relay.radius());
        assertNull(GateCatalog.defaultRule(low, "-=[ Streuner ]=-"));
        assertEquals(List.of(100_000_104, 100_000_102, 100_000_103, 100_000_101), GateCatalog.LOW_RELAY_ORDER);
    }
}
