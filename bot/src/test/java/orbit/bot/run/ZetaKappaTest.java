package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import orbit.bot.mod.GateModule;
import orbit.config.BotConfig;
import orbit.config.GateCatalog;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.ChrominUpdateCommand;
import orbit.protocol.gen.CollectBoxRequest;
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.ShipSelectionCommand;
import orbit.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

/** ζ and κ as DarkBot's SharedPlugin plays them (ZetaGate, AnyGate) and AstraBot's Zeta chromin farmer. */
class ZetaKappaTest {

    static final int ZETA1 = 71;
    static final int ZETA3 = 73;
    static final int KAPPA = 74;

    static BotConfig withChrominBoxes(BotConfig c) {
        return c.withCollect(new BotConfig.Collect(Map.of("CHROMIN_BOX_3", new BotConfig.BoxRule(true, 0, -100)),
                400, false, true, BotConfig.Collect.CARGO_NONE, 0, null, java.util.List.of()));
    }

    static void hp(Sim sim, int id, int percent) {
        ShipSelectionCommand s = new ShipSelectionCommand();
        s.userId = id;
        s.hitpoints = percent * 10_000;
        s.hitpointsMax = 1_000_000;
        s.shield = 0;
        s.shieldMax = 500_000;
        sim.world.apply(s, sim.now);
    }

    static void chromin(Sim sim, double current, double max) {
        ChrominUpdateCommand c = new ChrominUpdateCommand();
        c.currentAmt = current;
        c.maxAmt = max;
        sim.world.apply(c, sim.now);
    }

    static void box(Sim sim, String hash, int x, int y) {
        AddBoxCommand b = new AddBoxCommand();
        b.hash = hash;
        b.boxType = "CHROMIN_BOX_3";
        b.x = x;
        b.y = y;
        sim.world.apply(b, sim.now);
    }

    @Test
    void zetasRadiiAreSharedPluginsWithTheCaseKept() {
        GateCatalog.Gate zeta = GateCatalog.byKey("zeta");
        assertEquals(590, GateCatalog.defaultRule(zeta, "-=[ StreuneR ]=- ζ31").radius(), 0.1);
        assertEquals(550, GateCatalog.defaultRule(zeta, "-=[ Streuner ]=- ζ30").radius(), 0.1);
        assertEquals(590, GateCatalog.defaultRule(zeta, "( UberStreuneR ) ζ34").radius(), 0.1);
        assertEquals(560, GateCatalog.defaultRule(zeta, "( UberStreuner ) ζ33").radius(), 0.1);
        assertEquals(590, GateCatalog.defaultRule(zeta, "-=[ Devourer ]=- ζ27").radius(), 0.1);
        assertEquals(40, zeta.waves(), "the reward came after wave 40");
        assertTrue(GateCatalog.anyGate(GateCatalog.byKey("kappa")));
        assertFalse(GateCatalog.anyGate(zeta));
    }

    @Test
    void zetaIsEnteredByItsType54PortalNotTheEscortsAtTheCentre() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"zeta\"]}"), GateModuleTest.home(), 10_000, 6_000);
        GateModuleTest.info(sim, 0, new int[] {6, 0, 3});
        GateModuleTest.portal(sim, 700, 54, 10_500, 6_500); // the Mimesis escort's, at the centre
        GateModuleTest.portal(sim, 701, 54, 3_000, 3_000);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.run(sim, m, 40_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 3_000, h.y() - 3_000) < 500, "at ζ's portal: " + h.x() + "/" + h.y());
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest));
    }

    @Test
    void aDevourerBelowAQuarterItsBoxesArePickedUpAmidTheFight() throws Exception {
        Sim sim = new Sim(withChrominBoxes(GateModuleTest.config("{\"gates\":[\"zeta\"],\"boxRadius\":10000}")), ZETA3,
                10_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        sim.npc(900, "-=[ Devourer ]=- ζ27", 10_500, 6_000);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.run(sim, m, 3_000);
        assertEquals(900, m.fightTarget());
        box(sim, "c1", 12_500, 6_000);
        GateModuleTest.run(sim, m, 2_000);
        assertFalse(sim.sent.stream().anyMatch(p -> p instanceof CollectBoxRequest), "the Devourer healthy: no box yet");
        hp(sim, 900, 20);
        GateModuleTest.run(sim, m, 15_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof CollectBoxRequest q && "c1".equals(q.itemHash)),
                "below 25 %: the box taken, " + m.status());
    }

    @Test
    void theChrominFarmerKeepsTheDevourerAliveAndThenLetsItDestroyTheShip() throws Exception {
        String s = "{\"gates\":[\"zeta\"],\"zetaChrominFarmer\":true,\"chromin\":{\"holdHp\":0.1,\"waitSec\":5}}";
        Sim sim = new Sim(withChrominBoxes(GateModuleTest.config(s)), ZETA3, 8_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        GateModuleTest.info(sim, 0, new int[] {6, 0, 4});
        chromin(sim, 1_000, 160_000);
        sim.npc(900, "-=[ Devourer ]=- ζ27", 12_000, 6_000);
        hp(sim, 900, 5);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.run(sim, m, 3_000);
        assertNotEquals(900, m.fightTarget(), "held below 10 %: not shot");
        assertTrue(m.status().contains("хромин-фарм"), m.status());
        GateModuleTest.run(sim, m, 20_000);
        assertTrue(m.status().contains("жду гибели"), m.status());
        WorldSnapshot.Hero h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 12_000, h.y() - 6_000) <= 900, "by the Devourer: " + h.x() + "/" + h.y());
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_PASSIVE, m.petGear());
    }

    @Test
    void withoutTheEventOrWithTheLastLivesTheDevourerIsKilled() throws Exception {
        String s = "{\"gates\":[\"zeta\"],\"zetaChrominFarmer\":true}";
        Sim off = new Sim(GateModuleTest.config(s), ZETA3, 10_000, 6_000);
        off.lasers("ammunition_laser_ucb-100");
        GateModuleTest.info(off, 0, new int[] {6, 0, 4});
        off.npc(900, "-=[ Devourer ]=- ζ27", 10_500, 6_000);
        hp(off, 900, 5);
        GateModule m = new GateModule(off.ctx);
        GateModuleTest.run(off, m, 3_000);
        assertEquals(900, m.fightTarget(), "no Chromin Rush: as DarkBot");

        Sim last = new Sim(GateModuleTest.config(s), ZETA3, 10_000, 6_000);
        last.lasers("ammunition_laser_ucb-100");
        GateModuleTest.info(last, 0, new int[] {6, 0, 1});
        chromin(last, 1_000, 160_000);
        last.npc(900, "-=[ Devourer ]=- ζ27", 10_500, 6_000);
        hp(last, 900, 5);
        GateModule n = new GateModule(last.ctx);
        GateModuleTest.run(last, n, 15_000);
        assertFalse(n.status().contains("жду гибели"), "the last life is never spent: " + n.status());
    }

    @Test
    void zetasNextMapPortalIsTakenAtOnceWhenNoNpcIsOnTheMap() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"zeta\"],\"boxRadius\":10000}"), ZETA1, 10_000, 6_000);
        GateModuleTest.info(sim, 0, new int[] {6, 0, 3});
        sim.npc(910, "-=[ Streuner ]=-", 16_000, 9_000); // no gate NPC: not attacked, yet on the map
        GateModuleTest.portal(sim, 720, 55, 10_100, 6_000);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.run(sim, m, 5_000);
        assertFalse(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "an NPC on the map: no jump, " + m.status());
        orbit.protocol.gen.ShipRemoveCommand gone = new orbit.protocol.gen.ShipRemoveCommand();
        gone.userId = 910;
        sim.world.apply(gone, sim.now);
        GateModuleTest.run(sim, m, 1_500);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "no quiet wait: " + m.status());
    }

    @Test
    void kappaAsAnyGateWaitsWhereItIsAndLeavesByItsOnlyPortal() throws Exception {
        String s = "{\"gates\":[\"kappa\"],\"anyGate\":{\"npcRadius\":560,\"killAllNpcs\":true,\"jumpToNextMap\":true,"
                + "\"moveToCenter\":false}}";
        Sim sim = new Sim(GateModuleTest.config(s), KAPPA, 14_000, 8_000);
        GateModuleTest.info(sim, 0, new int[] {7, 0, 3});
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.run(sim, m, 10_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 14_000, h.y() - 8_000) < 300, "no centre: " + h.x() + "/" + h.y());
        sim.npc(950, "-=[ Vagrant ]=- κ2", 14_500, 8_000);
        GateModuleTest.run(sim, m, 2_000);
        assertEquals(950, m.fightTarget(), "AnyGate kills them all");
        assertEquals(560, Math.round(orbit.bot.mod.Targets.radius(m.config(),
                sim.world.snapshot(sim.now).ships().get(950), KAPPA)));
        orbit.protocol.gen.ShipRemoveCommand gone = new orbit.protocol.gen.ShipRemoveCommand();
        gone.userId = 950;
        sim.world.apply(gone, sim.now);
        orbit.protocol.gen.GalaxyGateBuilderGateCompleteCommand done = new orbit.protocol.gen.GalaxyGateBuilderGateCompleteCommand();
        done.gateId = 7;
        sim.world.apply(done, sim.now);
        GateModuleTest.portal(sim, 800, 99, 9_000, 6_000); // the way out, its type unknown
        GateModuleTest.run(sim, m, 30_000);
        h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 9_000, h.y() - 6_000) < 500, "at the only portal: " + h.x() + "/" + h.y());
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest));
    }
}
