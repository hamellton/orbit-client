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
    void doesNothingWhileTheLinkIsLostAndGoesOnAfter() throws Exception {
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 25, 10_000, 6_000);
        boolean[] lost = {true};
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setLinkLost(() -> lost[0]);
        sim.run(r::tick, 5_000);
        assertTrue(sim.sent.isEmpty(), sim.sent.toString());
        assertTrue(r.status().contains("Нет связи"), r.status());
        lost[0] = false;
        sim.run(r::tick, 3_000);
        assertEquals(BotRunner.State.TRAVELLING, r.state());
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

    @Test
    void aClickOnTheMapTakesTheShipThereAndTheModuleWaitsAMomentAfter() throws Exception {
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 26, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 2_000);
        r.order(BotRunner.Order.move(12_000, 9_000));
        sim.run(r::tick, 500);
        assertEquals(BotRunner.State.MANUAL, r.state());
        for (int i = 0; i < 200 && !sim.ctx.nav().idle(); i++) {
            sim.run(r::tick, 100);
        }
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 12_000, h.y() - 9_000) < 150, "there: " + h);
        sim.run(r::tick, BotRunner.HOLD_MS - 1_000);
        assertEquals(BotRunner.State.MANUAL, r.state(), "the module waits a moment");
        sim.run(r::tick, (int) BotRunner.HOLD_MS + 1_000);
        assertEquals(BotRunner.State.WORKING, r.state(), "the module goes on after the pause");
    }

    @Test
    void pausedTheShipOnlyFollowsClicks() throws Exception {
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 25, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setPaused(true);
        sim.run(r::tick, 5_000);
        assertEquals(BotRunner.State.PAUSED, r.state(), "does not fly to the working map");
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 10_000, h.y() - 6_000) < 1, "stays: " + h);
        r.order(BotRunner.Order.move(11_000, 6_000));
        sim.run(r::tick, 15_000);
        assertTrue(Math.abs(sim.hero().x() - 11_000) < 150, "went where clicked: " + sim.hero());
        assertEquals(BotRunner.State.PAUSED, r.state(), "and keeps still there");
        r.setPaused(false);
        sim.run(r::tick, 3_000);
        assertEquals(BotRunner.State.TRAVELLING, r.state());
    }
}
