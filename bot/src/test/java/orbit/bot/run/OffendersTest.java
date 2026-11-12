package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.config.BotConfig;
import orbit.protocol.gen.HarvestRequest;
import org.junit.jupiter.api.Test;

class OffendersTest {

    // live 2026-11-12 on int1 (5-3): one player killed the palladium ship five times in nine minutes
    private static BotConfig config(boolean avoid) {
        return BotConfig.empty().withModule(BotConfig.Module.PALLADIUM)
                .withGeneral(new BotConfig.General(93, null, null, null, false, false, 300, false, false))
                .withCollect(new BotConfig.Collect(java.util.Map.of("ore_8", new BotConfig.BoxRule(true, 800, 0)), 400, false, true))
                .withFlee(new BotConfig.Flee(BotConfig.FleeMode.OFF, null, null, 2_000, 60, true, 90, avoid, 2_500));
    }

    private static void player(Sim sim, int id, String name, int x, int y) {
        orbit.protocol.gen.ShipCreateCommand c = new orbit.protocol.gen.ShipCreateCommand();
        c.userId = id;
        c.userName = name;
        c.factionId = 1;
        c.x = x;
        c.y = y;
        sim.world.apply(c, sim.now);
    }

    private static void ore(Sim sim, String hash, int x, int y) {
        orbit.protocol.gen.AddOreCommand ore = new orbit.protocol.gen.AddOreCommand();
        ore.hash = hash;
        ore.oreType = new orbit.protocol.gen.OreTypeModule();
        ore.oreType.typeValue = 8;
        ore.x = x;
        ore.y = y;
        sim.world.apply(ore, sim.now);
    }

    private static void shoots(Sim sim, int attacker) {
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = attacker;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
    }

    private static java.util.List<String> harvested(Sim sim, int from) {
        return sim.sent.subList(from, sim.sent.size()).stream().filter(p -> p instanceof HarvestRequest)
                .map(p -> ((HarvestRequest) p).itemHash).toList();
    }

    @Test
    void aPlayerWhoShotIsAvoidedAndTheWorkGoesOnAtTheOtherSide() {
        Sim sim = new Sim(config(true), 93, 20_000, 7_000);
        player(sim, 77, "Hunter", 21_500, 7_000);
        ore(sim, "near", 22_000, 7_000);
        ore(sim, "far", 14_000, 7_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        shoots(sim, 77);
        sim.run(r::tick, 1_000);
        assertTrue(r.status().startsWith("Ухожу от Hunter"), r.status());
        sim.run(r::tick, 30_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 21_500, h.y() - 7_000) > 2_500, "out of his circle: " + h.x() + "/" + h.y());
        assertEquals(java.util.Set.of("far"), new java.util.HashSet<>(harvested(sim, 0)), "the ore by him is left, the far one is taken");
    }

    @Test
    void withoutTheTickNothingChanges() {
        Sim sim = new Sim(config(false), 93, 20_000, 7_000);
        player(sim, 77, "Hunter", 21_500, 7_000);
        ore(sim, "near", 20_600, 7_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        shoots(sim, 77);
        sim.run(r::tick, 8_000);
        assertFalse(r.status().startsWith("Ухожу"), r.status());
        assertTrue(harvested(sim, 0).contains("near"), "works by him as before");
    }

    @Test
    void theKillerIsRememberedAfterTheRevive() {
        Sim sim = new Sim(config(true), 93, 20_000, 7_000);
        player(sim, 78, "-TЄЯЯØЯ-", 23_000, 7_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.tick();
        orbit.protocol.gen.KillScreenPostCommand k = new orbit.protocol.gen.KillScreenPostCommand();
        k.killerName = "-TЄЯЯØЯ-";
        sim.world.apply(k, sim.now);
        r.tick();
        // back in game, he shows up again with another id after a relog: known by name
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 93;
        si.factionId = 3;
        si.x = 20_000;
        si.y = 7_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
        player(sim, 99, "-TЄЯЯØЯ-", 21_000, 7_000);
        Offenders o = new Offenders(sim.ctx);
        o.observe(sim.world.snapshot(sim.now)); // a fresh memory does not know him…
        assertEquals(0, o.circles().size());
        sim.run(r::tick, 500); // …the runner's does
        assertTrue(r.status().startsWith("Ухожу от -TЄЯЯØЯ-") || sim.ctx.nav().inDanger(21_000, 7_000), r.status());
    }

    @Test
    void aShipChangeKeepsWhoAttackedAndTheDeaths() {
        Sim sim = new Sim(config(true), 93, 20_000, 7_000);
        player(sim, 77, "Hunter", 26_000, 7_000);
        BotRunner first = new BotRunner(sim.ctx, () -> false, s -> { });
        shoots(sim, 77);
        first.tick();
        // the next login on the other ship: a new runner going on with the session
        Sim next = new Sim(config(true), 93, 20_000, 7_000);
        BotRunner second = new BotRunner(next.ctx, () -> false, s -> { });
        second.carryOver(first);
        player(next, 77, "Hunter", 21_000, 7_000);
        second.tick();
        assertTrue(second.status().startsWith("Ухожу от Hunter"), second.status());
        assertEquals(first.deaths(), second.deaths());
    }

    private static void station(Sim sim, int id, int faction, int x, int y) {
        orbit.protocol.gen.AssetCreateCommand a = new orbit.protocol.gen.AssetCreateCommand();
        a.assetId = id;
        a.type = new orbit.protocol.gen.AssetTypeModule();
        a.type.typeValue = (short) Offenders.BATTLESTATION;
        a.factionId = faction;
        a.clanId = 500;
        a.clanTag = "CBS";
        a.posX = x;
        a.posY = y;
        sim.world.apply(a, sim.now);
    }

    @Test
    void anotherCompanysBattleStationIsKeptClearOfOwnIsNot() {
        // Blacklight: the clans' stations fire at the ship (2026-11-12); avoiding players is off here
        Sim sim = new Sim(config(false), 93, 20_000, 7_000);
        station(sim, 900, 1, 21_000, 7_000);
        ore(sim, "under", 21_200, 7_000);
        ore(sim, "far", 14_000, 7_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 1_000);
        assertTrue(r.status().startsWith("Ухожу от станция [CBS]"), r.status());
        sim.run(r::tick, 30_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 21_000, h.y() - 7_000) > BotConfig.Flee.STATION_RADIUS, h.x() + "/" + h.y());
        assertEquals(java.util.Set.of("far"), new java.util.HashSet<>(harvested(sim, 0)));

        Sim own = new Sim(config(false), 93, 20_000, 7_000);
        station(own, 901, 3, 21_000, 7_000); // the ship's company
        ore(own, "under", 20_600, 7_000);
        BotRunner r2 = new BotRunner(own.ctx, () -> false, s -> { });
        own.run(r2::tick, 8_000);
        assertTrue(harvested(own, 0).contains("under"), "the own company's station is no danger");
    }

    @Test
    void anNpcShootingTheShipThatIsNotFoughtIsLeft() {
        // live 2026-11-13: roaming for Impulse II, Abide I (not on the list) shot the ship to death
        Sim sim = new Sim(config(false), 93, 20_000, 7_000);
        sim.npc(55, "\\\\ Abide I //", 20_600, 7_000);
        ore(sim, "far", 14_000, 7_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = 55;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
        sim.run(r::tick, 300);
        assertTrue(r.status().startsWith("Ухожу от \\\\ Abide I //"), r.status());
        sim.run(r::tick, 20_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 20_600, h.y() - 7_000) > 560, "out of its reach: " + h.x() + "/" + h.y());
    }
}
