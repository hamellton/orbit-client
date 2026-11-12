package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.bot.mod.Attacker;
import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

class AttackerTest {

    static BotConfig config(boolean circle, List<String> fallback, Set<String> flags) {
        return BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, circle, fallback))
                .withNpcs(Map.of("-=[ Target ]=-", new BotConfig.NpcRule(500, 0, true, flags, Set.of(), null)));
    }

    /** Angles (degrees) of the hero round the target over a fight. */
    static double sweep(Sim sim, Attacker a, long ms) {
        double first = Double.NaN;
        double total = 0;
        double last = Double.NaN;
        for (long t = 0; t < ms; t += 100) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
            var h = sim.hero();
            double ang = Math.toDegrees(Math.atan2(h.y() - 6_000, h.x() - 10_000));
            if (!Double.isNaN(last)) {
                double d = ang - last;
                d = (d + 540) % 360 - 180;
                total += d;
            }
            last = ang;
            if (Double.isNaN(first)) {
                first = ang;
            }
        }
        return Math.abs(total);
    }

    @Test
    void circlesRoundTheTargetAtItsRadius() {
        Sim sim = new Sim(config(true, null, Set.of()), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        double swept = sweep(sim, a, 30_000);
        assertTrue(swept > 120, "went a good way round: " + swept);
        var h = sim.hero();
        double r = Math.hypot(h.x() - 10_000, h.y() - 6_000);
        assertTrue(r > 380 && r < 620, "about the radius: " + r);
    }

    @Test
    void noCirclingWhenOffOrFlagged() {
        for (BotConfig c : List.of(config(false, null, Set.of()), config(true, null, Set.of("NO_CIRCLE")))) {
            Sim sim = new Sim(c, 1, 10_500, 6_000);
            sim.lasers("ammunition_laser_lcb-10");
            sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
            Attacker a = new Attacker(sim.ctx);
            a.target(sim.world.snapshot(sim.now).ships().get(7));
            assertTrue(sweep(sim, a, 15_000) < 10, "stays put");
        }
    }

    @Test
    void takesTheSpareAmmunitionWhenTheMainRunsOut() {
        BotConfig c = config(false, List.of("ammunition_laser_ucb-100", "ammunition_laser_mcb-25"), Set.of());
        Sim sim = new Sim(c, 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_mcb-50", "ammunition_laser_ucb-100", "ammunition_laser_mcb-25");
        // LCB-10 (the main one) is not on board at all; UCB-100 is the first spare
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        for (int t = 0; t < 80; t++) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertEquals("ammunition_laser_ucb-100", sim.world.snapshot(sim.now).selected("lasers").id());
    }

    @Test
    void givesUpATargetThatLosesNothingAndDoesNotPickItAgainAtOnce() {
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        boolean kept = true;
        for (int t = 0; t < 400 && kept; t++) { // 40 s; the fake server never takes HP off
            kept = a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(!kept, "dropped after 30 s without damage");
        assertEquals(0, a.targetId());
        assertTrue(a.gaveUp(7));
        sim.now += (Attacker.GIVE_UP_MS + 1_000) * Sim.MS;
        assertTrue(!a.gaveUp(7), "after a while it may be tried again");
    }

    @Test
    void anyLaserAmmunitionWhenNeitherTheMainNorASpareIsOnBoard() {
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_mcb-25", "ammunition_laser_sab-50");
        // the config wants LCB-10, none on board, no spares: MCB-25, not SAB
        assertEquals("ammunition_laser_mcb-25", Attacker.usable(sim.ctx.config().get(), sim.world.snapshot(sim.now),
                "ammunition_laser_lcb-10"));
    }

    @Test
    void givesUpWhenTheAttackNeverStartsEither() {
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.blockAttacks = true; // no ammunition: the game never starts the attack
        boolean kept = true;
        for (int t = 0; t < 400 && kept; t++) {
            kept = a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(!kept, "dropped after 30 s in range without damage");
        assertTrue(a.gaveUp(7));
    }

    @Test
    void farFromTheTargetTheShipFliesInTheTravelModeAndAttacksOnTheApproach() {
        BotConfig.ShipMode attack = new BotConfig.ShipMode(1, null);
        BotConfig.ShipMode travel = new BotConfig.ShipMode(2, null);
        BotConfig c = config(false, null, Set.of());
        BotConfig.General g = c.general();
        c = c.withGeneral(new BotConfig.General(g.workingMap(), attack, travel, travel, g.runFromEnemies(),
                g.runFromEnemiesInSight(), g.rememberEnemiesSec(), g.roamSequential(), g.onlyKillPreferred()));
        Sim sim = new Sim(c, 1, 2_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        a.tick(true);
        assertEquals(travel, sim.ctx.ship().wanted(), "8 000 away: still travelling");
        for (int t = 0; t < 400 && sim.hero().x() < 8_500; t++) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        a.tick(true);
        assertEquals(attack, sim.ctx.ship().wanted(), "close: the attack mode");
    }

    private static orbit.protocol.gen.MenuItemInitializationCommand menuItem(String id, double counter, boolean selected) {
        orbit.protocol.gen.MenuItemInitializationCommand it = new orbit.protocol.gen.MenuItemInitializationCommand();
        it.status = new orbit.protocol.gen.MenuItemStatusCommand();
        it.status.menuItemId = id;
        it.status.counterValue = counter;
        it.status.selected = selected;
        it.status.available = true;
        it.status.activatable = true;
        return it;
    }

    /** Lasers, the launcher (loaded 5 of 5, HSTRM-01 in it, ECO-10 on board) and the CPUs, as on 2026-11-11. */
    private static void launcherMenu(Sim sim, boolean launcherCpu) {
        orbit.protocol.gen.MenuInitializationCommand menu = new orbit.protocol.gen.MenuInitializationCommand();
        orbit.protocol.gen.MenuCategoryInitializationCommand lasers = new orbit.protocol.gen.MenuCategoryInitializationCommand();
        lasers.categoryId = "lasers";
        lasers.menuItems = List.of(menuItem("ammunition_laser_lcb-10", 10_000, true));
        orbit.protocol.gen.MenuCategoryInitializationCommand rl = new orbit.protocol.gen.MenuCategoryInitializationCommand();
        rl.categoryId = "rocket_launchers";
        rl.menuItems = List.of(menuItem("equipment_weapon_rocketlauncher_hst", 5, false),
                menuItem("ammunition_rocketlauncher_hstrm-01", 2_409, true),
                menuItem("ammunition_rocketlauncher_eco-10", 330, false));
        orbit.protocol.gen.MenuCategoryInitializationCommand cpus = new orbit.protocol.gen.MenuCategoryInitializationCommand();
        cpus.categoryId = "cpus";
        cpus.menuItems = List.of(menuItem("equipment_extra_cpu_rllb-x", 0, launcherCpu));
        menu.categories = List.of(lasers, rl, cpus);
        sim.world.apply(menu, sim.now);
    }

    private static List<String> pressed(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.MenuActionRequest)
                .map(p -> ((orbit.protocol.gen.MenuActionRequest) p).menuItemId).toList();
    }

    @Test
    void keepsTheLauncherRocketsAndFiresTheLoadedLauncherWithoutItsCpu() {
        BotConfig c = config(false, null, Set.of());
        BotConfig.Loot l = c.loot();
        c = c.withLoot(new BotConfig.Loot(l.ammo(), l.sab(), l.sabBelow(), l.sabNpcShieldMin(), l.rsb(), l.groupNpcs(),
                l.ignoreFartherThan(), l.rocket(), l.fireRockets(), l.circle(), l.fallback(), "ammunition_rocketlauncher_eco-10", true));
        for (boolean cpu : new boolean[] {false, true}) {
            Sim sim = new Sim(c, 1, 10_400, 6_000);
            launcherMenu(sim, cpu);
            sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
            Attacker a = new Attacker(sim.ctx);
            a.target(sim.world.snapshot(sim.now).ships().get(7));
            for (int t = 0; t < 100; t++) {
                a.tick(true);
                sim.now += 100 * Sim.MS;
            }
            List<String> p = pressed(sim);
            assertTrue(p.contains("ammunition_rocketlauncher_eco-10"), "the configured launcher rockets: " + p);
            assertEquals(!cpu, p.contains("equipment_weapon_rocketlauncher_hst"),
                    (cpu ? "the CPU fires by itself" : "loaded, no CPU: a salvo") + ": " + p);
        }
    }

    private static void otherPlayerShoots(Sim sim, int player, int target) {
        orbit.protocol.gen.ShipCreateCommand c = new orbit.protocol.gen.ShipCreateCommand();
        c.userId = player;
        c.userName = "Other";
        c.factionId = 1;
        c.x = 10_300;
        c.y = 6_300;
        sim.world.apply(c, sim.now);
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = player;
        run.targetId = target;
        sim.world.apply(run, sim.now);
    }

    @Test
    void anNpcAnotherPlayerShootsIsNotOursUnlessItsRuleSaysSo() {
        // the user 2026-11-12: only the first shooter's group gets the reward — except cubes and group NPCs
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_500, 6_000);
        sim.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        otherPlayerShoots(sim, 900, 77);
        var w = sim.world.snapshot(sim.now);
        assertTrue(orbit.bot.mod.Targets.best(sim.config, w, 10_000) == null, "someone else's");
        Sim flagged = new Sim(config(false, null, Set.of("IGNORE_OWNERSHIP")), 1, 10_500, 6_000);
        flagged.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        otherPlayerShoots(flagged, 900, 77);
        assertEquals(77, orbit.bot.mod.Targets.best(flagged.config, flagged.world.snapshot(flagged.now), 10_000).id());
    }

    @Test
    void aTargetGreyedByTheServerMidFightIsDropped() {
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(77));
        sim.run(() -> a.tick(true), 3_000);
        assertEquals(77, a.targetId(), "fighting");
        orbit.protocol.gen.LegacyModule grey = new orbit.protocol.gen.LegacyModule();
        grey.message = "0|n|LSH|77|900"; // another player (900) took it
        sim.world.apply(grey, sim.now);
        a.tick(true);
        assertEquals(0, a.targetId(), "dropped");
        assertTrue(a.gaveUp(77));
    }
}
