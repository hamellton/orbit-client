package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void onlyTheServersGreyMakesAnNpcSomebodyElses() {
        // the user 2026-11-13: a player shooting it or its lost hull says nothing (he may have left it); the server's
        // LSH says, its USH frees it again; cubes and group NPCs are fought anyway
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_500, 6_000);
        sim.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        otherPlayerShoots(sim, 900, 77);
        assertEquals(77, orbit.bot.mod.Targets.best(sim.config, sim.world.snapshot(sim.now), 10_000).id(), "no grey: ours");
        orbit.protocol.gen.LegacyModule grey = new orbit.protocol.gen.LegacyModule();
        grey.message = "0|n|LSH|77|900";
        sim.world.apply(grey, sim.now);
        assertTrue(orbit.bot.mod.Targets.best(sim.config, sim.world.snapshot(sim.now), 10_000) == null, "greyed");
        orbit.protocol.gen.LegacyModule free = new orbit.protocol.gen.LegacyModule();
        free.message = "0|n|USH|77";
        sim.world.apply(free, sim.now);
        assertEquals(77, orbit.bot.mod.Targets.best(sim.config, sim.world.snapshot(sim.now), 10_000).id(), "freed");
        Sim flagged = new Sim(config(false, null, Set.of("IGNORE_OWNERSHIP")), 1, 10_500, 6_000);
        flagged.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        flagged.world.apply(grey, flagged.now);
        assertEquals(77, orbit.bot.mod.Targets.best(flagged.config, flagged.world.snapshot(flagged.now), 10_000).id());
    }

    private static BotConfig invoke(java.util.Set<String> flags, String formation) {
        return BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, false, null))
                .withNpcs(Map.of("-=[ Target ]=-", new BotConfig.NpcRule(500, 0, true, flags, Set.of(), null, false, null,
                        null, null, formation)));
    }

    private static long used(Sim sim, String id) {
        return sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.MenuActionRequest m && id.equals(m.menuItemId)).count();
    }

    @Test
    void empOnceAFightAndIshWhenTheHullIsLow() {
        // AstraBot's «Invoke XVI»: Use EMP, Use ISH (the user, 2026-11-13)
        Sim sim = new Sim(invoke(Set.of("USE_EMP", "USE_ISH"), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ammunition_specialammo_emp-01", "equipment_extra_cpu_ish-01");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.run(() -> a.tick(true), 4_000);
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"), "EMP once");
        assertEquals(0, used(sim, "equipment_extra_cpu_ish-01"), "no ISH at full hull");
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 7;
        hit.victimId = 1;
        hit.victimHitpoints = sim.world.snapshot(sim.now).hero().health().hpMax() * 0.4;
        sim.world.apply(hit, sim.now);
        sim.run(() -> a.tick(true), 1_000);
        assertEquals(1, used(sim, "equipment_extra_cpu_ish-01"), "ISH below 60 %");
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"), "still one EMP");
    }

    @Test
    void energyLeechAndTheShipsFightAbilityWhenReadyNeverATravelOne() {
        // AstraBot «Use Energy Leech», «Use Ship Ability» (the user, 2026-11-13)
        Sim sim = new Sim(invoke(Set.of("USE_LEECH", "USE_ABILITY"), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "tech_energy-leech", "ability_citadel_travel", "ability_venom");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.run(() -> a.tick(true), 4_000);
        assertTrue(used(sim, "tech_energy-leech") >= 1, "the leech");
        assertTrue(used(sim, "ability_venom") >= 1, "Venom's ability");
        assertEquals(0, used(sim, "ability_citadel_travel"), "no help in a fight");
    }

    @Test
    void aModulesPlanUsesJamxInItsDistanceAndThePrismaticShieldBelowItsHull() {
        // AstraBot's behemoth module: useSpearheadJamx / jamxDistance, usePrismatic / prismaticPercent
        Sim sim = new Sim(invoke(Set.of(), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ability_spearhead_jam-x", "ability_spectrum");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.setAbilityPlan(new Attacker.AbilityPlan(false, -1, false, -1, 0.5, 750));
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.run(() -> a.tick(true), 3_000);
        assertTrue(used(sim, "ability_spearhead_jam-x") >= 1, "JAM-X within 750");
        assertEquals(0, used(sim, "ability_spectrum"), "full hull: no prismatic shield");
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 7;
        hit.victimId = 1;
        hit.victimHitpoints = sim.world.snapshot(sim.now).hero().health().hpMax() * 0.4;
        sim.world.apply(hit, sim.now);
        sim.run(() -> a.tick(true), 1_000);
        assertEquals(1, used(sim, "ability_spectrum"), "below 50 %: the prismatic shield");
    }

    @Test
    void anNpcsOwnFormationIsWantedFightingIt() {
        Sim sim = new Sim(invoke(Set.of(), "drone_formation_f-3d-dr"), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.run(() -> a.tick(true), 500);
        assertEquals("drone_formation_f-3d-dr", sim.ctx.ship().wanted().formation());
    }

    @Test
    void aTrainByTheTargetIsSeen() {
        // AstraBot «avoidTrain»: 3 players not of the group within 1 500 of it
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_500, 6_000);
        sim.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        for (int i = 0; i < 3; i++) {
            orbit.protocol.gen.ShipCreateCommand c = new orbit.protocol.gen.ShipCreateCommand();
            c.userId = 900 + i;
            c.userName = "Train" + i;
            c.factionId = 1;
            c.x = 10_400 + i * 100;
            c.y = 6_600;
            sim.world.apply(c, sim.now);
        }
        var w = sim.world.snapshot(sim.now);
        assertTrue(orbit.bot.mod.Targets.train(w, w.ships().get(77), 3, 1_500));
        assertTrue(!orbit.bot.mod.Targets.train(w, w.ships().get(77), 4, 1_500), "three are no four");
        assertTrue(!orbit.bot.mod.Targets.train(w, w.ships().get(77), 3, 300), "not that near");
    }

    @Test
    void thePetIsInHpLinkWhileAFlaggedNpcIsFought() {
        // AstraBot's «HP Link» on Invoke XVI
        Sim sim = new Sim(invoke(Set.of("HP_LINK"), null).withModule(BotConfig.Module.KILL_COLLECT), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        orbit.bot.mod.KillModule m = new orbit.bot.mod.KillModule(sim.ctx, true, false);
        assertEquals(-1, m.petGear(), "no fight: the config's mode");
        sim.run(m::tick, 1_000);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK, m.petGear());
    }

    @Test
    void aTargetOfAHigherPriorityComingInSightIsTakenAtOnce() {
        // the user, 2026-11-13: «почему бьёт импульсов, а не сразу летит на инвока»
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.KILL_COLLECT)
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, false, null))
                .withNpcs(Map.of("-=[ Invoke ]=-", new BotConfig.NpcRule(500, -95, true, Set.of(), Set.of(), null),
                        "-=[ Impulse ]=-", new BotConfig.NpcRule(500, 10, true, Set.of(), Set.of(), null)));
        Sim sim = new Sim(c, 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Impulse ]=-", 10_000, 6_000);
        orbit.bot.mod.KillModule m = new orbit.bot.mod.KillModule(sim.ctx, true, false);
        sim.run(m::tick, 1_000);
        assertEquals(7, m.fightTarget(), "the only one there");
        sim.npc(8, "-=[ Invoke ]=-", 11_200, 6_000);
        sim.run(m::tick, 300);
        assertEquals(8, m.fightTarget(), "the higher priority at once");
    }

    @Test
    void aTargetDestroyedBeforeTheAttackWasConfirmedIsAKill() {
        // live 2026-11-13 on 2-3: Lordakia died in the first volley, the reward came, the fight was «пропала из вида»
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(77, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(77));
        sim.run(() -> a.tick(true), 500);
        orbit.protocol.gen.ShipDestroyedCommand dead = new orbit.protocol.gen.ShipDestroyedCommand();
        dead.destroyedUserId = 77;
        sim.world.apply(dead, sim.now);
        a.tick(true);
        assertEquals(0, a.targetId(), "done");
        assertEquals(1, a.kills());
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

    @Test
    void aFleeingTargetIsKeptInRange() {
        // live 2026-11-12: Boss Kristallon ran, the ship stopped at the radius of where it had been, fell behind and
        // never fired; it flies 400, the NPC 300 away from it in a straight line
        Sim sim = new Sim(config(true, null, Set.of()), 1, 10_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(77, "-=[ Target ]=-", 10_450, 6_000);
        orbit.protocol.gen.MoveCommand mv = new orbit.protocol.gen.MoveCommand();
        mv.userId = 77;
        mv.x = 10_450 + 6_000;
        mv.y = 6_000;
        mv.timeToTarget = 20_000; // 300 a second
        sim.world.apply(mv, sim.now);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(77));
        int inRange = 0;
        int ticks = 0;
        for (long t = 0; t < 18_000; t += 100) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
            if (t > 3_000) { // after catching up
                var w = sim.world.snapshot(sim.now);
                var npc = w.ships().get(77);
                ticks++;
                if (npc.distanceTo(w.hero().x(), w.hero().y()) <= 500) {
                    inRange++;
                }
            }
        }
        assertTrue(inRange > ticks * 0.8, "in range " + inRange + " of " + ticks + " ticks");
    }

    @Test
    void aRadiusLeftAtZeroIsTheRecommendedOne() {
        BotConfig c = BotConfig.empty().withNpcs(Map.of(
                "..::{ Boss Kristallon }::..", new BotConfig.NpcRule(0, 0, true, Set.of(), Set.of(), null),
                "-=[ Kristallon ]=-", new BotConfig.NpcRule(700, 0, true, Set.of(), Set.of(), null)));
        Sim sim = new Sim(c, 23, 10_000, 6_000);
        sim.npc(1, "..::{ Boss Kristallon }::..", 10_200, 6_000);
        sim.npc(2, "-=[ Kristallon ]=-", 10_300, 6_000);
        sim.npc(3, "-=[ Somebody ]=-", 10_400, 6_000);
        var w = sim.world.snapshot(sim.now);
        assertEquals(615, orbit.bot.mod.Targets.radius(c, w.ships().get(1), 23), "DarkBot's recommendation");
        assertEquals(700, orbit.bot.mod.Targets.radius(c, w.ships().get(2), 23), "the user's own");
        assertEquals(560, orbit.bot.mod.Targets.radius(c, w.ships().get(3), 23), "neither");
    }

    /** The closest the chasing NPC came over a fight (it flies at the ship, 300/s; the ship does 400/s). */
    static double closest(Sim sim, Attacker a, long ms) {
        double min = Double.MAX_VALUE;
        for (long t = 0; t < ms; t += 100) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
            var w = sim.world.snapshot(sim.now);
            var n = w.ships().get(7);
            var h = w.hero();
            double d = Math.hypot(h.x() - n.x(), h.y() - n.y());
            if (t > 3_000) {
                min = Math.min(min, d);
            }

            if (t % 500 == 0) {
                orbit.protocol.gen.MoveCommand m = new orbit.protocol.gen.MoveCommand();
                m.userId = 7;
                m.x = h.x();
                m.y = h.y();
                m.timeToTarget = (int) (d / 300 * 1000);
                sim.world.apply(m, sim.now);
            }
        }
        return min;
    }

    @Test
    void holdingTheRadiusKeepsAChasingNpcOff() {
        // Blacklight NPCs chase (the user, 2026-11-12): backing off keeps them near the radius, standing lets them in
        BotConfig kite = BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, false, null,
                        null, false, "KITE"))
                .withNpcs(Map.of("-=[ Target ]=-", new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null)));
        Sim sim = new Sim(kite, 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        double kept = closest(sim, a, 20_000);
        assertTrue(kept > 400, "kept near the radius (backing off at 0.85 of it): " + kept);

        Sim stand = new Sim(config(false, null, Set.of()), 1, 10_500, 6_000);
        stand.lasers("ammunition_laser_lcb-10");
        stand.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker b = new Attacker(stand.ctx);
        b.target(stand.world.snapshot(stand.now).ships().get(7));
        assertTrue(closest(stand, b, 20_000) < 150, "standing, it comes in");
    }

    private static BotConfig moving(String move) {
        return BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, false, null,
                        null, false, move))
                .withNpcs(Map.of("-=[ Target ]=-", new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null),
                        "-=[ Other ]=-", new BotConfig.NpcRule(500, 5, false, Set.of(), Set.of(), null)));
    }

    /** Another NPC shooting the ship. */
    private static void shooter(Sim sim, int id, int x, int y) {
        sim.npc(id, "-=[ Other ]=-", x, y);
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = id;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
    }

    @Test
    void circlingKeepsOffAnotherNpcThatShootsTheShip() {
        // docs/FIGHT-MOVEMENT.md Д1+Д2: the circle turns away from the other NPC's radius
        Sim sim = new Sim(moving("CIRCLE"), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        shooter(sim, 8, 10_000, 5_250);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        int near = 0;
        int ticks = 0;
        for (long t = 0; t < 30_000; t += 100) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
            var h = sim.hero();
            if (t > 2_000) {
                ticks++;
                if (Math.hypot(h.x() - 10_000, h.y() - 5_250) < 400) {
                    near++;
                }
            }
        }
        assertTrue(near < ticks * 0.1, "inside the other's radius " + near + " of " + ticks + " ticks");
    }

    @Test
    void byAStillTargetTheShipStandsOnTheSafeSide() {
        Sim sim = new Sim(moving("STAND"), 1, 10_000, 5_400);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        shooter(sim, 8, 10_000, 5_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        for (long t = 0; t < 15_000; t += 100) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        var h = sim.hero();
        double fromTarget = Math.hypot(h.x() - 10_000, h.y() - 6_000);
        assertTrue(fromTarget < 520, "within its radius: " + fromTarget);
        double fromShooter = Math.hypot(h.x() - 10_000, h.y() - 5_000);
        assertTrue(fromShooter > 700, "out of the shooter's reach (500 + 250): " + fromShooter);
    }

    @Test
    void autoStandsByATargetThatNeverMoves() {
        Sim sim = new Sim(moving("AUTO"), 1, 10_450, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Still ]=-", 10_000, 6_000); // a kind no other test has seen moving
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sweep(sim, a, 6_000); // circling while it is not known
        assertTrue(sweep(sim, a, 10_000) < 25, "stands once it is seen still");
    }

    @Test
    void aTargetTooStrongToKillBeforeTheRepairIsLeftAndItsKindSkipped() {
        // live 2026-11-13: Boss Kristallon on LCB-10 — 30 % of it for 50 % of the hull, healed back by the next try
        BotConfig c = BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, true, null))
                // another kind to fight: the only kind of a list is never skipped
                .withNpcs(Map.of("-=[ Strong ]=-", new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null),
                        "-=[ Weak ]=-", new BotConfig.NpcRule(500, 5, true, Set.of(), Set.of(), null)))
                .withSafety(new BotConfig.Safety(0.3, 0.9, 0.3, 0, null, null, 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Strong ]=-", 10_000, 6_000);
        orbit.protocol.gen.ShipSelectionCommand sel = new orbit.protocol.gen.ShipSelectionCommand();
        sel.userId = 7;
        sel.hitpoints = 1_000_000;
        sel.hitpointsMax = 1_000_000;
        sel.shield = 0;
        sel.shieldMax = 0;
        sim.world.apply(sel, sim.now);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        double target = 1_000_000;
        double hull = 100_000;
        boolean gone = false;
        for (int t = 0; t < 400 && !gone; t++) { // judged after 30 s
            target -= 300; // 0.3 % a second
            hull -= 200;   // 2 % a second
            orbit.protocol.gen.AttackHitCommand onNpc = new orbit.protocol.gen.AttackHitCommand();
            onNpc.victimId = 7;
            onNpc.victimHitpoints = target;
            sim.world.apply(onNpc, sim.now);
            orbit.protocol.gen.AttackHitCommand onUs = new orbit.protocol.gen.AttackHitCommand();
            onUs.victimId = 1;
            onUs.attackerId = 7;
            onUs.victimHitpoints = hull;
            onUs.victimShield = 50_000;
            sim.world.apply(onUs, sim.now);
            gone = !a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(gone, "left");
        assertTrue(a.tooStrong("-=[ Strong ]=-"), "its kind is skipped a while");
    }

    private static void hull(Sim sim, double fraction) {
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 7;
        hit.victimId = 1;
        hit.victimHitpoints = sim.world.snapshot(sim.now).hero().health().hpMax() * fraction;
        sim.world.apply(hit, sim.now);
    }

    @Test
    void underATrainTheAbilityFirstThenIshThenEmpNeverTogether() {
        // the user, 2026-11-14: «навык, потом пониже HP стенка, потом импульс — не одновременно»
        Sim sim = new Sim(invoke(Set.of(), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ability_citadel-plus_prismatic-endurance", "equipment_extra_cpu_ish-01",
                "ammunition_specialammo_emp-01");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.setAbilityPlan(new Attacker.AbilityPlan(true, 0.35, true, 0.6, 0.5, -1, 3, false));
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.run(() -> a.tick(true), 2_000);
        assertEquals(0, used(sim, "ability_citadel-plus_prismatic-endurance"), "nobody shoots: nothing");
        for (int i = 0; i < 3; i++) {
            otherPlayerShoots(sim, 900 + i, 1);
        }
        sim.run(() -> a.tick(true), 1_000);
        assertEquals(1, used(sim, "ability_citadel-plus_prismatic-endurance"), "three players: the ability first");
        assertEquals(0, used(sim, "equipment_extra_cpu_ish-01"), "full hull: no ISH yet");
        hull(sim, 0.5);
        sim.run(() -> a.tick(true), 1_500);
        assertEquals(1, used(sim, "equipment_extra_cpu_ish-01"), "below 60 %: ISH");
        assertEquals(0, used(sim, "ammunition_specialammo_emp-01"), "not EMP with it");
        hull(sim, 0.3);
        sim.run(() -> a.tick(true), 1_000);
        assertEquals(0, used(sim, "ammunition_specialammo_emp-01"), "ISH still holds: no EMP");
        sim.run(() -> a.tick(true), 3_000);
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"), "below 35 % after the ISH: EMP");
    }

    @Test
    void asCarnageTheAbilityAtOnceThenAPlayerShootsEmpAndRightAfterIsh() {
        // the user, 2026-11-14: «как подлетает к бегемоту сразу включает навык, когда начинают бить другие корабли, а не
        // нпс, — импульс, сразу же после импульса — стенка»
        Sim sim = new Sim(invoke(Set.of(), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ability_citadel-plus_prismatic-endurance", "ability_citadel_fortify",
                "equipment_extra_cpu_ish-01", "ammunition_specialammo_emp-01");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.setAbilityPlan(new Attacker.AbilityPlan(true, 0.35, true, 0.6, -1, -1, 3, false, false, 2_000, true, true, true));
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        sim.run(() -> a.tick(true), 1_500);
        assertEquals(1, used(sim, "ability_citadel-plus_prismatic-endurance"), "at the behemoth: the prismatic at once");
        assertEquals(0, used(sim, "ability_citadel_fortify"), "not Fortify: it roots the ship");
        assertEquals(0, used(sim, "ammunition_specialammo_emp-01") + used(sim, "equipment_extra_cpu_ish-01"),
                "no player shoots: neither EMP nor ISH, full hull or not");
        otherPlayerShoots(sim, 900, 1);
        sim.run(() -> a.tick(true), 1_500);
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"), "one player shoots: EMP");
        assertEquals(1, used(sim, "equipment_extra_cpu_ish-01"), "and the ISH right after it, by no HP threshold");
    }

    @Test
    void empUnderFireOnTheWay() {
        Sim sim = new Sim(invoke(Set.of(), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ammunition_specialammo_emp-01");
        Attacker a = new Attacker(sim.ctx);
        assertFalse(a.empUnderFire(sim.world.snapshot(sim.now)), "nobody shoots");
        otherPlayerShoots(sim, 900, 1);
        assertTrue(a.empUnderFire(sim.world.snapshot(sim.now)), "a player shoots: EMP");
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"));
    }

    @Test
    void noEmpWhenNoPlayerShoots() {
        // live 2026-11-14 on the VPS, 3-BL: come at 29 % after a train on the way, EMP at once with nobody shooting
        Sim sim = new Sim(invoke(Set.of(), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ammunition_specialammo_emp-01");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.setAbilityPlan(new Attacker.AbilityPlan(true, 0.35, false, 0, -1, -1, 3, false));
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        hull(sim, 0.3);
        sim.run(() -> a.tick(true), 3_000);
        assertEquals(0, used(sim, "ammunition_specialammo_emp-01"), "nobody shoots: EMP kept");
        otherPlayerShoots(sim, 900, 1);
        sim.run(() -> a.tick(true), 1_000);
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"), "a player shoots: EMP");
    }

    @Test
    void empByHpAloneWhenTurnedOff() {
        Sim sim = new Sim(invoke(Set.of(), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ammunition_specialammo_emp-01");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.setAbilityPlan(new Attacker.AbilityPlan(true, 0.35, false, 0, -1, -1, 3, false, false, 3_000, false));
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        hull(sim, 0.3);
        sim.run(() -> a.tick(true), 3_000);
        assertEquals(1, used(sim, "ammunition_specialammo_emp-01"), "empOnlyPlayers off: EMP by HP");
    }

    @Test
    void offTheBehemothNothingIsSpent() {
        Sim sim = new Sim(invoke(Set.of("USE_EMP", "USE_ISH", "USE_ABILITY"), null), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10", "ability_venom", "equipment_extra_cpu_ish-01", "ammunition_specialammo_emp-01");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.setAbilityPlan(Attacker.AbilityPlan.OFF);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        hull(sim, 0.3);
        sim.run(() -> a.tick(true), 4_000);
        assertEquals(0, used(sim, "ability_venom") + used(sim, "equipment_extra_cpu_ish-01")
                + used(sim, "ammunition_specialammo_emp-01"), "nothing spent off the behemoth");
    }
}
