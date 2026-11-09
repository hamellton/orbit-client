package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.config.BotConfig;
import orbit.protocol.gen.KillScreenOptionModule;
import orbit.protocol.gen.KillScreenOptionTypeModule;
import orbit.protocol.gen.KillScreenPostCommand;
import orbit.protocol.gen.PriceModule;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.UIButtonTriggerRequest;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class BotRunnerTest {

    @Test
    void fliesToTheWorkingMapFirst() throws Exception {
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 25, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 3_000);
        assertEquals(BotRunner.State.TRAVELLING, r.state());
        assertTrue(r.status().contains("3-6"), r.status());
    }

    @Test
    void goesToRepairAtAPortalWhenHurt() throws Exception {
        BotConfig c = CubikonModuleTest.config(90, false)
                .withSafety(new BotConfig.Safety(0.5, 0.9, 0.5, 0, null, null, 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 99;
        hit.victimId = 1;
        hit.victimHitpoints = 30_000;
        hit.victimShield = 50_000;
        sim.world.apply(hit, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 1_000);
        assertEquals(BotRunner.State.REPAIRING, r.state());
        sim.run(r::tick, 60_000);
        var h = sim.hero();
        var portal = MapCatalog.bundled().portals(26).stream()
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - 10_000, p.y() - 6_000))).orElseThrow();
        assertTrue(Math.hypot(h.x() - portal.x(), h.y() - portal.y()) <= Safety.AT_PORTAL, "at the portal: " + h);
    }

    @Test
    void takesTheConfiguredFreeRepairAfterAPause() throws Exception {
        BotConfig c = CubikonModuleTest.config(90, false)
                .withSafety(new BotConfig.Safety(0.5, 0.9, 0.5, 0, null, null, 10, "PORTAL", 5, 0));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        KillScreenPostCommand k = new KillScreenPostCommand();
        k.killerName = "someone";
        k.options = List.of(option(KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR, 0),
                option(KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR, 0),
                option(KillScreenOptionTypeModule.OPTIONTYPE_AT_DEATHLOCATION_REPAIR, 500));
        sim.world.apply(k, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 4_000);
        assertTrue(sim.repairs.isEmpty(), "waits before reviving");
        sim.run(r::tick, 10_000);
        assertEquals(1, sim.repairs.size());
        assertEquals(KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR, sim.repairs.getFirst()[0]);
        assertEquals(3, sim.repairs.getFirst()[1]);
        assertEquals(1, r.deaths());
    }

    @Test
    void stopsAtAPortalAndLogsOut() throws Exception {
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 26, 10_000, 6_000);
        boolean[] out = {false};
        BotRunner r = new BotRunner(sim.ctx, () -> out[0], s -> { });
        sim.run(r::tick, 2_000);
        r.stop("тест");
        sim.run(r::tick, 90_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof UIButtonTriggerRequest u && "logout".equals(u.itemId)));
        out[0] = true;
        sim.run(r::tick, 500);
        assertEquals(BotRunner.State.STOPPED, r.state());
    }

    @Test
    void unsupportedModuleStops() {
        Sim sim = new Sim(BotConfig.empty().withModule(BotConfig.Module.PALLADIUM), 1, 1_000, 1_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 500);
        assertEquals(BotRunner.State.FAILED, r.state());
    }

    static KillScreenOptionModule option(int type, int price) {
        KillScreenOptionModule o = new KillScreenOptionModule();
        o.repairType = new KillScreenOptionTypeModule();
        o.repairType.repairTypeValue = (short) type;
        o.price = new PriceModule();
        o.price.amount = price;
        o.affordableForPlayer = true;
        return o;
    }

    @SuppressWarnings("unused")
    private static ShipInitializationCommand unused() {
        return null;
    }
}
