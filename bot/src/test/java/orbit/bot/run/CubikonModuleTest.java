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
        sim.npc(60, "-=[ Kristallin ]=-", 8_200, 4_100);
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
}
