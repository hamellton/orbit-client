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
    void theTimeUpWaitsForTheFightToEnd() throws Exception {
        // live 2026-11-13 on 2-7: the time ran out by Boss Kristallon at 70 %, the ship stood to log out and died
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 26, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 2_000);
        sim.npc(55, "-=[ Shooter ]=-", 10_400, 6_000);
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = 55;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
        r.stopWhenFree("время вышло");
        sim.run(r::tick, 3_000);
        assertTrue(r.state() != BotRunner.State.STOPPING, "still fighting: " + r.state());
        orbit.protocol.gen.ShipDestroyedCommand dead = new orbit.protocol.gen.ShipDestroyedCommand();
        dead.destroyedUserId = 55;
        sim.world.apply(dead, sim.now);
        sim.run(r::tick, 500);
        assertEquals(BotRunner.State.STOPPING, r.state());
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
    void logsOutByTheNearestPortalOfAnyFaction() throws Exception {
        // live 2026-11-11 on 5-3: the own faction's portal far left, another faction's close by
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 93, 30_000, 15_000);
        for (int[] g : new int[][] {{1, 2_000, 9_500, 1}, {2, 33_000, 15_500, 0}}) {
            orbit.protocol.gen.JumpgateCreateCommand j = new orbit.protocol.gen.JumpgateCreateCommand();
            j.gateId = g[0];
            j.x = g[1];
            j.y = g[2];
            j.factionId = g[0] == 1 ? 1 : 2;
            j.isVisibleOnMinimap = g[3] == 1;
            sim.world.apply(j, sim.now);
        }
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 500);
        r.stop("тест");
        sim.run(r::tick, 20_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 33_000, h.y() - 15_500) <= Safety.AT_PORTAL + 50, "at the near portal: " + h);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof UIButtonTriggerRequest u && "logout".equals(u.itemId)));
    }

    @Test
    void unsupportedModuleStops() {
        Sim sim = new Sim(BotConfig.empty().withModule(BotConfig.Module.GALAXY_GATE), 1, 1_000, 1_000);
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

    @Test
    void oneMapAwayIsNotWorthAJumpCpu() throws Exception {
        MapCatalog maps = MapCatalog.bundled();
        int map18 = maps.portals(18).stream().filter(p -> p.toName().equals("1-8")).findFirst().orElseThrow().toId();
        var portal = maps.portals(map18).stream().filter(p -> p.toId() == 18).findFirst().orElseThrow();
        Sim sim = new Sim(CubikonModuleTest.config(90, false), map18, portal.x(), portal.y());
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        assertEquals(1, r.portalHops(18), "1-8 → 1-6: one portal");
        assertTrue(r.portalHops(18) < BotRunner.CPU_FROM_HOPS);
    }

    /** Killed (portal 200, spot 300, base free on offer), revived as the runner picks, alive again; the picked type. */
    private static int dieAndRevive(Sim sim, BotRunner r) {
        KillScreenPostCommand k = new KillScreenPostCommand();
        k.killerName = "camper";
        k.options = List.of(option(KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR, 0),
                option(KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR, 200),
                option(KillScreenOptionTypeModule.OPTIONTYPE_AT_DEATHLOCATION_REPAIR, 300));
        sim.world.apply(k, sim.now);
        int before = sim.repairs.size();
        for (int t = 0; t < 300 && sim.repairs.size() == before; t++) {
            sim.run(r::tick, 100);
        }
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 26;
        si.factionId = 3;
        si.x = 10_000;
        si.y = 6_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
        sim.run(r::tick, 1_000);
        return sim.repairs.getLast()[0];
    }

    @Test
    void killedAgainAndAgainAfterPaidRevivesTheShipRevivesAtTheBaseOnce() throws Exception {
        BotConfig c = CubikonModuleTest.config(90, false)
                .withSafety(new BotConfig.Safety(0.5, 0.9, 0.5, 0, null, null, 0, "PORTAL", 0, 0, 2, 3));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        int portal = KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR;
        int base = KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR;
        assertEquals(portal, dieAndRevive(sim, r));
        assertEquals(portal, dieAndRevive(sim, r), "killed again a few seconds later: the second paid one");
        assertEquals(base, dieAndRevive(sim, r), "the limit: the base");
        assertEquals(portal, dieAndRevive(sim, r), "the count starts again");
    }

    @Test
    void livingLongerThanTheWindowAfterAPaidReviveStartsTheCountAgain() throws Exception {
        BotConfig c = CubikonModuleTest.config(90, false)
                .withSafety(new BotConfig.Safety(0.5, 0.9, 0.5, 0, null, null, 0, "PORTAL", 0, 0, 2, 3));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        int portal = KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR;
        assertEquals(portal, dieAndRevive(sim, r));
        assertEquals(portal, dieAndRevive(sim, r));
        sim.now += 4 * 60_000 * Sim.MS; // four quiet minutes
        assertEquals(portal, dieAndRevive(sim, r), "not «in a row» any more");
    }

    @Test
    void thePauseStopsTheShipAndTheEndOfItFliesOnAtOnce() throws Exception {
        // live 2026-11-11: paused, the ship flew on to the module's last click
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 26, 18_000, 11_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 6_000);
        assertTrue(sim.world.snapshot(sim.now).hero().moving(), "flying to a spawn");
        r.setPaused(true);
        sim.run(r::tick, 1_000);
        var at = sim.hero();
        sim.run(r::tick, 2_000);
        assertEquals(at, sim.hero(), "stands still while paused");
        int sent = sim.sent.size();
        r.setPaused(false);
        sim.run(r::tick, 500);
        assertTrue(sim.sent.subList(sent, sim.sent.size()).stream().anyMatch(p -> p instanceof orbit.protocol.gen.MoveRequest),
                "flies on within half a second");
    }

    @Test
    void stoppingWithALowHullFliesStraightOutWithoutRepairing() throws Exception {
        // live 2026-11-11: stopped right after a revive at the portal; the ship repaired first, then left
        BotConfig c = CubikonModuleTest.config(90, false)
                .withSafety(new BotConfig.Safety(0.5, 0.9, 0.5, 0, null, null, 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 1_000);
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 99;
        hit.victimId = 1;
        hit.victimHitpoints = 12_000;
        hit.victimShield = 0;
        sim.world.apply(hit, sim.now);
        r.stop("по кнопке");
        boolean out = false;
        for (int t = 0; t < 900 && !out; t++) {
            sim.run(r::tick, 100);
            out = sim.sent.stream().anyMatch(p -> p instanceof UIButtonTriggerRequest u && "logout".equals(u.itemId));
        }
        assertTrue(out, "logged out with 12% HP, not waiting for the repair");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.JumpRequest), "no escape jump");
        assertTrue(!r.status().contains("Ремонт"), r.status());
    }
}
