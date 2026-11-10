package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.CubikonModule;
import orbit.bot.mod.SpawnMemory;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.ShipSelectRequest;
import org.junit.jupiter.api.Test;

class CubikonModuleTest {

    static BotConfig config(int waitSeconds, boolean killWhileWaiting) throws Exception {
        return config(waitSeconds, killWhileWaiting, "ROUND");
    }

    static BotConfig config(int waitSeconds, boolean killWhileWaiting, String mode) throws Exception {
        return BotConfig.empty()
                .withModule(BotConfig.Module.CUBIKON)
                .withGeneral(new BotConfig.General(26, null, null, null, false, false, 300, false, false))
                .withLoot(new BotConfig.Loot("ammunition_laser_ucb-100", null, 0, 0, null, true, 0))
                .withNpcs(Map.of(
                        "-=[ Cubikon ]=-", new BotConfig.NpcRule(550, -100, true, Set.of("IGNORE_OWNERSHIP", "IGNORE_ATTACKED"), Set.of(26), null),
                        "-=[ Protegit ]=-", new BotConfig.NpcRule(500, 50, false, Set.of("PASSIVE"), Set.of(26), null),
                        "-=[ Kristallin ]=-", new BotConfig.NpcRule(550, 0, true, Set.of(), Set.of(26), null)))
                .withModuleSettings("cubikon", Json.MAPPER.readTree("""
                        {"spots": [[7500, 3900], [13400, 3900], [13400, 7900], [7500, 7900]], "arriveDistance": 450,
                         "waitSeconds": %d, "collectBoxes": true, "boxesWhileCubikonHpAbove": 0.25, "killWhileWaiting": %s,
                         "spotMode": "%s", "learnSpawns": true, "waitKillRange": 1500}"""
                        .formatted(waitSeconds, killWhileWaiting, mode)));
    }

    @Test
    void campsOnTheNearestSpotThenTheNext() throws Exception {
        Sim sim = new Sim(config(20, false), 26, 12_000, 4_500);
        CubikonModule m = new CubikonModule(sim.ctx, orbit.bot.mod.SpawnMemory.inMemory());
        sim.run(m::tick, 8_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 13_400, h.y() - 3_900) <= 450, "at spot 2 (nearest): " + h);
        assertTrue(m.status().contains("жду куб на спауне 134/39"), m.status());
        sim.run(m::tick, 37_000); // waits 20-25 s, flies 4000 units
        h = sim.hero();
        assertTrue(Math.hypot(h.x() - 13_400, h.y() - 7_900) <= 450, "went on to spot 3: " + h);
    }

    @Test
    void goesForACubikonAtOnceWithTheConfiguredAmmo() throws Exception {
        Sim sim = new Sim(config(90, false), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_lcb-10", "ammunition_laser_ucb-100");
        CubikonModule m = new CubikonModule(sim.ctx, orbit.bot.mod.SpawnMemory.inMemory());
        sim.run(m::tick, 2_000);
        sim.npc(50, "-=[ Protegit ]=-", 8_000, 4_000);
        sim.npc(77, "-=[ Cubikon ]=-", 10_500, 6_500);
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertEquals(77, h.selected());
        assertEquals(77, h.attacking());
        assertTrue(Math.hypot(h.x() - 10_500, h.y() - 6_500) <= 650, "within the Cubikon's radius: " + h);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest s && s.targetId == 50), "Protegit never clicked");
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof MenuActionRequest a && a.menuItemId.equals("ammunition_laser_ucb-100")),
                "switched to the config's ammunition");
    }

    @Test
    void waitKillsOnlyWhatTheListAllows() throws Exception {
        Sim sim = new Sim(config(0, true), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100");
        sim.npc(50, "-=[ Protegit ]=-", 7_800, 3_900);
        sim.npc(60, "-=[ Kristallin ]=-", 8_000, 4_000);
        CubikonModule m = new CubikonModule(sim.ctx, orbit.bot.mod.SpawnMemory.inMemory());
        sim.run(m::tick, 10_000);
        assertEquals(60, sim.hero().selected());
    }

    @Test
    void remembersWhereCubikonsAppearAndHowLongTheyTakeToComeBack() throws Exception {
        Sim sim = new Sim(config(0, false), 26, 10_000, 9_000);
        sim.lasers("ammunition_laser_ucb-100");
        SpawnMemory memory = SpawnMemory.inMemory();
        CubikonModule m = new CubikonModule(sim.ctx, memory);
        sim.npc(77, "-=[ Cubikon ]=-", 10_500, 9_500);
        sim.run(m::tick, 3_000);
        assertEquals(1, memory.spawns(26).size());
        assertEquals(10_500, memory.spawns(26).getFirst().x());
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.run(m::tick, 600_000); // 10 minutes later…
        sim.npc(78, "-=[ Cubikon ]=-", 10_520, 9_480); // …the next one, same spawn
        sim.run(m::tick, 1_000);
        SpawnMemory.Spawn sp = memory.spawns(26).getFirst();
        assertEquals(1, memory.spawns(26).size());
        assertEquals(2, sp.seen());
        assertEquals(1, sp.respawnMs().size());
        long minutes = sp.respawnEstimate() / 60_000;
        assertTrue(minutes == 10, "respawn ~10 min, got " + minutes);
    }

    @Test
    void learnedModeFliesToTheSpawnDueBackSoonest() throws Exception {
        Sim sim = new Sim(config(90, false, "LEARNED"), 26, 7_500, 3_900);
        SpawnMemory memory = SpawnMemory.inMemory();
        CubikonModule m = new CubikonModule(sim.ctx, memory);
        long wall = m.wallMs();
        // a spawn off the default spots: killed 9 min ago, comes back after ~10 min
        memory.seen(26, 16_000, 10_000, wall - 40 * 60_000);
        memory.killed(26, 16_000, 10_000, wall - 30 * 60_000);
        memory.seen(26, 16_000, 10_000, wall - 20 * 60_000);
        memory.killed(26, 16_000, 10_000, wall - 9 * 60_000);
        sim.run(m::tick, 40_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 16_000, h.y() - 10_000) <= 450, "waiting where the cube is due: " + h);
    }

    @Test
    void learnedSpawnsComeFirstAndReplaceNearbyConfiguredSpots() {
        SpawnMemory memory = SpawnMemory.inMemory();
        memory.seen(26, 7_600, 4_000, 1_000); // next to spot 1
        memory.seen(26, 16_000, 10_000, 2_000);
        var list = CubikonModule.merged(CubikonModule.spots(null), memory.spawns(26));
        assertEquals(5, list.size());
        assertEquals(16_000, list.get(0)[0]);
        assertEquals(7_600, list.get(1)[0]);
    }

    @Test
    void spawnMemoryIsSaved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.nio.file.Path f = dir.resolve("spawns.json");
        SpawnMemory a = SpawnMemory.open(f);
        a.seen(26, 7_500, 3_900, 1_000);
        a.killed(26, 7_500, 3_900, 2_000);
        SpawnMemory b = SpawnMemory.open(f);
        assertEquals(a.spawns(26), b.spawns(26));
    }

    @Test
    void waitKillsOnlyAttackersNearTheSpot() throws Exception {
        BotConfig c = config(0, false);
        var settings = c.moduleSettings("cubikon");
        settings.put("waitKill", "ATTACKERS");
        settings.put("waitKillRange", 800);
        Sim sim = new Sim(c.withModuleSettings("cubikon", settings), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100");
        sim.npc(60, "-=[ Kristallin ]=-", 7_900, 4_000);   // near, peaceful
        sim.npc(61, "-=[ Kristallin ]=-", 10_000, 3_900);  // shoots at us, but far from the spot
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        orbit.protocol.gen.AttackLaserRunCommand far = new orbit.protocol.gen.AttackLaserRunCommand();
        far.attackerId = 61;
        far.targetId = 1;
        sim.world.apply(far, sim.now);
        sim.run(m::tick, 6_000);
        assertEquals(0, sim.hero().selected(), "nobody near the spot attacks: stay passive");
        orbit.protocol.gen.AttackLaserRunCommand near = new orbit.protocol.gen.AttackLaserRunCommand();
        near.attackerId = 60;
        near.targetId = 1;
        sim.world.apply(near, sim.now);
        sim.run(m::tick, 6_000);
        assertEquals(60, sim.hero().selected(), "the one attacking us near the spot");
    }

    @Test
    void afterACubikonDiesGoesToTheNextSpawnWhoeverShoots() throws Exception {
        BotConfig c = config(0, false);
        var settings = c.moduleSettings("cubikon");
        settings.put("waitKill", "ATTACKERS");
        Sim sim = new Sim(c.withModuleSettings("cubikon", settings), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100");
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.npc(77, "-=[ Cubikon ]=-", 7_600, 4_000);
        sim.run(m::tick, 3_000);
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.npc(60, "-=[ Kristallin ]=-", 7_700, 4_100);
        orbit.protocol.gen.AttackLaserRunCommand shot = new orbit.protocol.gen.AttackLaserRunCommand();
        shot.attackerId = 60;
        shot.targetId = 1;
        sim.world.apply(shot, sim.now);
        sim.run(m::tick, 25_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 13_400, h.y() - 3_900) <= 450, "on the next spawn (spot 2): " + h);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest x && x.targetId == 60),
                "did not stop to fight the Kristallin");
    }

    @Test
    void dropsATargetThatLeadsAwayFromTheSpawn() throws Exception {
        BotConfig c = config(0, false);
        var settings = c.moduleSettings("cubikon");
        settings.put("waitKill", "ALL");
        settings.put("waitKillRange", 1_000);
        Sim sim = new Sim(c.withModuleSettings("cubikon", settings), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100");
        sim.npc(60, "-=[ Kristallin ]=-", 8_000, 3_900);
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.run(m::tick, 5_000);
        assertEquals(60, sim.hero().selected());
        // it flees far away from the spot
        orbit.protocol.gen.MoveCommand mv = new orbit.protocol.gen.MoveCommand();
        mv.userId = 60;
        mv.x = 12_000;
        mv.y = 3_900;
        mv.timeToTarget = 3_000;
        sim.world.apply(mv, sim.now);
        sim.run(m::tick, 20_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 7_500, h.y() - 3_900) <= 600, "back on the spawn: " + h);
    }

    @Test
    void goesBackFromSabWhenNoAmmunitionIsConfigured() throws Exception {
        BotConfig base = config(0, false);
        BotConfig c = base.withLoot(new BotConfig.Loot(null, "ammunition_laser_sab-50", 0.92, 0, null, true, 0))
                .withNpcs(java.util.Map.of(
                        "-=[ Cubikon ]=-", new BotConfig.NpcRule(550, -100, true, java.util.Set.of("IGNORE_OWNERSHIP"), java.util.Set.of(26), null, true),
                        "-=[ Kristallin ]=-", new BotConfig.NpcRule(550, 0, true, java.util.Set.of(), java.util.Set.of(26), null, false)));
        var settings = c.moduleSettings("cubikon");
        settings.put("waitKill", "ALL");
        Sim sim = new Sim(c.withModuleSettings("cubikon", settings), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100", "ammunition_laser_sab-50");
        // own shield low, the cube has shield: SAB on the cube
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 99;
        hit.victimId = 1;
        hit.victimHitpoints = 100_000;
        hit.victimShield = 10_000;
        sim.world.apply(hit, sim.now);
        orbit.protocol.gen.ShipCreateCommand cube = new orbit.protocol.gen.ShipCreateCommand();
        cube.userId = 77;
        cube.npc = true;
        cube.userName = "-=[ Cubikon ]=-";
        cube.x = 7_700;
        cube.y = 3_900;
        sim.world.apply(cube, sim.now);
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.run(m::tick, 6_000);
        assertEquals("ammunition_laser_sab-50", sim.world.snapshot(sim.now).selected("lasers").id(), "SAB on the cube");
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.npc(60, "-=[ Kristallin ]=-", 13_700, 3_900); // at the next spawn the bot moves on to
        sim.run(m::tick, 40_000);
        assertEquals(60, sim.hero().selected());
        assertEquals("ammunition_laser_ucb-100", sim.world.snapshot(sim.now).selected("lasers").id(),
                "UCB again on the Kristallin");
    }

    @Test
    void doesNotFlyAfterMobsWhileWaiting() throws Exception {
        BotConfig c = config(0, false);
        var settings = c.moduleSettings("cubikon");
        settings.put("waitKill", "ALL");
        Sim sim = new Sim(c.withModuleSettings("cubikon", settings), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100");
        sim.npc(60, "-=[ Kristallin ]=-", 8_400, 3_900); // inside the leash, out of reach
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.run(m::tick, 10_000);
        var h = sim.hero();
        assertEquals(0, h.selected(), "not worth flying to");
        assertTrue(Math.hypot(h.x() - 7_500, h.y() - 3_900) <= 450, "stayed on the spawn: " + h);
    }

    @Test
    void remembersACubikonKilledWhileTheShipIsAwayRepairing() throws Exception {
        Sim sim = new Sim(config(0, false), 26, 2_000, 2_000);
        SpawnMemory memory = SpawnMemory.inMemory();
        CubikonModule m = new CubikonModule(sim.ctx, memory);
        sim.npc(77, "-=[ Cubikon ]=-", 13_400, 3_900); // far away, still in the server's list
        m.observe();
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.now += 100 * Sim.MS;
        m.observe(); // only watching: safety steers the ship
        assertTrue(memory.spawns(26).getFirst().lastKill() > 0, "the death is remembered");
    }

    @Test
    void picksUpBoxesOnlyAroundTheSpawn() throws Exception {
        BotConfig c = config(0, false).withCollect(new BotConfig.Collect(
                java.util.Map.of("SCRAPIUM", new BotConfig.BoxRule(true, 0, 0)), 400, false, false));
        Sim sim = new Sim(c, 26, 7_500, 3_900);
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.run(m::tick, 2_000);
        int[][] chain = {{7_800, 3_900}, {8_150, 3_900}, {8_500, 3_900}}; // each within 400 of the last
        for (int i = 0; i < chain.length; i++) {
            orbit.protocol.gen.AddBoxCommand b = new orbit.protocol.gen.AddBoxCommand();
            b.hash = "b" + i;
            b.boxType = "SCRAPIUM";
            b.x = chain[i][0];
            b.y = chain[i][1];
            sim.world.apply(b, sim.now);
        }
        sim.run(() -> {
            m.tick();
            for (var p : sim.sent) {
                if (p instanceof orbit.protocol.gen.CollectBoxRequest r) {
                    orbit.protocol.gen.RemoveCollectableCommand rc = new orbit.protocol.gen.RemoveCollectableCommand();
                    rc.hash = r.itemHash;
                    rc.collected = true;
                    sim.world.apply(rc, sim.now);
                }
            }
            sim.sent.removeIf(p -> p instanceof orbit.protocol.gen.CollectBoxRequest);
        }, 30_000);
        var left = sim.world.snapshot(sim.now).collectables().keySet();
        assertTrue(left.contains("b1") && left.contains("b2"), "boxes beyond 400 of the spawn left alone: " + left);
        assertTrue(!left.contains("b0"), "the one by the spawn picked up");
    }

    @Test
    void chasesOnTheLeashAndComesBack() throws Exception {
        BotConfig c = config(0, false);
        var settings = c.moduleSettings("cubikon");
        settings.put("waitKill", "ATTACKERS");
        settings.put("waitKillRange", 1_500);
        settings.put("chaseFromSpot", true);
        Sim sim = new Sim(c.withModuleSettings("cubikon", settings), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_ucb-100");
        sim.npc(60, "-=[ Kristallin ]=-", 8_600, 3_900); // 1100 from the spot: out of reach, on the leash
        orbit.protocol.gen.AttackLaserRunCommand shot = new orbit.protocol.gen.AttackLaserRunCommand();
        shot.attackerId = 60;
        shot.targetId = 1;
        sim.world.apply(shot, sim.now);
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.run(m::tick, 8_000);
        var h = sim.hero();
        assertEquals(60, h.selected(), "goes for it");
        assertTrue(h.x() > 7_800, "flew towards it: " + h);
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 60;
        sim.world.apply(d, sim.now);
        sim.run(m::tick, 10_000);
        h = sim.hero();
        assertTrue(Math.hypot(h.x() - 7_500, h.y() - 3_900) <= 450, "back on the spawn after the kill: " + h);
    }

    @Test
    void theMapRespawnIsTheShortestSeenAnywhere() {
        SpawnMemory memory = SpawnMemory.inMemory();
        long t = 10_000_000;
        memory.seen(26, 7_500, 4_000, t);
        memory.killed(26, 7_500, 4_000, t + 60_000);
        memory.seen(26, 7_500, 4_000, t + 60_000 + 607_000);   // 10:07 — one was missed
        memory.seen(26, 13_400, 4_000, t);
        memory.killed(26, 13_400, 4_000, t + 60_000);
        memory.seen(26, 13_400, 4_000, t + 60_000 + 300_000);  // 5:00
        assertEquals(300_000, memory.mapRespawn(26));
        memory.killed(26, 7_500, 4_000, t + 1_000_000);
        var s = memory.spawns(26).stream().filter(x -> x.x() == 7_500).findFirst().orElseThrow();
        assertEquals(t + 1_300_000, s.expectedAt(memory.mapRespawn(26)));
    }

    @Test
    void keepsTheConfiguredRocketsAndNoneWhereTheListSaysSo() throws Exception {
        BotConfig base = config(0, false);
        BotConfig c = base.withLoot(new BotConfig.Loot("ammunition_laser_ucb-100", null, 0, 0, null, true, 0,
                "ammunition_rocket_plt-2026", false));
        Sim sim = new Sim(c, 26, 7_500, 3_900);
        sim.menu("ammunition_laser_ucb-100", "ammunition_rocket_plt-2021", "ammunition_rocket_plt-2026");
        sim.npc(77, "-=[ Cubikon ]=-", 7_700, 3_900);
        CubikonModule m = new CubikonModule(sim.ctx, SpawnMemory.inMemory());
        sim.run(m::tick, 8_000);
        assertEquals("ammunition_rocket_plt-2026", sim.world.snapshot(sim.now).selected("rockets").id());

        // the Cubikon's own setting: no rockets — nothing is changed
        var npcs = new java.util.TreeMap<>(c.npcs());
        var cube = npcs.get("-=[ Cubikon ]=-");
        npcs.put("-=[ Cubikon ]=-", new BotConfig.NpcRule(cube.radius(), cube.priority(), true, cube.flags(), cube.maps(),
                null, false, BotConfig.Loot.NO_ROCKETS));
        Sim sim2 = new Sim(c.withNpcs(npcs), 26, 7_500, 3_900);
        sim2.menu("ammunition_laser_ucb-100", "ammunition_rocket_plt-2021", "ammunition_rocket_plt-2026");
        sim2.npc(77, "-=[ Cubikon ]=-", 7_700, 3_900);
        CubikonModule m2 = new CubikonModule(sim2.ctx, SpawnMemory.inMemory());
        sim2.run(m2::tick, 8_000);
        assertEquals("ammunition_rocket_plt-2021", sim2.world.snapshot(sim2.now).selected("rockets").id());
        assertTrue(sim2.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.AttackRocketRequest));
    }

    @Test
    void checksTheUnknownSpawnsInsteadOfPingPongingBetweenTwoKnownOnes() throws Exception {
        Sim sim = new Sim(config(0, false, "LEARNED"), 26, 13_400, 3_900);
        SpawnMemory memory = SpawnMemory.inMemory();
        CubikonModule m = new CubikonModule(sim.ctx, memory);
        long wall = m.wallMs();
        // the two top spawns: both died recently, back in about 5 minutes
        memory.seen(26, 7_500, 3_900, wall - 20 * 60_000);
        memory.killed(26, 7_500, 3_900, wall - 15 * 60_000);
        memory.seen(26, 7_500, 3_900, wall - 10 * 60_000); // 5:00
        memory.killed(26, 7_500, 3_900, wall - 30_000);
        memory.seen(26, 13_400, 3_900, wall - 60_000);
        memory.killed(26, 13_400, 3_900, wall - 1_000);
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(h.y() > 6_000, "went to check a bottom spawn, not the other top one: " + h);
    }

    @Test
    void doesNotShuttleBetweenSpawnsItJustSawEmpty() throws Exception {
        Sim sim = new Sim(config(0, false, "LEARNED"), 26, 7_500, 3_900);
        SpawnMemory memory = SpawnMemory.inMemory();
        CubikonModule m = new CubikonModule(sim.ctx, memory);
        long wall = m.wallMs();
        memory.seen(26, 13_400, 7_900, wall - 20 * 60_000); // a 5-minute respawn is known for the map
        memory.killed(26, 13_400, 7_900, wall - 15 * 60_000);
        memory.seen(26, 13_400, 7_900, wall - 10 * 60_000);
        java.util.List<int[]> visited = new java.util.ArrayList<>();
        int[] last = null;
        for (int t = 0; t < 1_800; t++) { // three minutes
            m.tick();
            sim.now += 100 * Sim.MS;
            var h = sim.hero();
            for (int[] sp : CubikonModule.spots(null)) {
                if (Math.hypot(h.x() - sp[0], h.y() - sp[1]) < 450 && sp != last && (last == null || last[0] != sp[0] || last[1] != sp[1])) {
                    visited.add(sp);
                    last = sp;
                }
            }
        }
        assertTrue(visited.size() <= 6, "visits each empty spawn about once, then waits: " + visited.size());
    }
}
