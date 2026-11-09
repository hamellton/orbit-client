package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.CubikonModule;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.ShipSelectRequest;
import org.junit.jupiter.api.Test;

class CubikonModuleTest {

    static BotConfig config(int waitSeconds, boolean killWhileWaiting) throws Exception {
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
                         "waitSeconds": %d, "collectBoxes": true, "boxesWhileCubikonHpAbove": 0.25, "killWhileWaiting": %s}"""
                        .formatted(waitSeconds, killWhileWaiting)));
    }

    @Test
    void campsOnTheNearestSpotThenTheNext() throws Exception {
        Sim sim = new Sim(config(20, false), 26, 12_000, 4_500);
        CubikonModule m = new CubikonModule(sim.ctx);
        sim.run(m::tick, 8_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 13_400, h.y() - 3_900) <= 450, "at spot 2 (nearest): " + h);
        assertTrue(m.status().contains("жду куб на спауне 2/4"), m.status());
        sim.run(m::tick, 37_000); // waits 20-25 s, flies 4000 units
        h = sim.hero();
        assertTrue(Math.hypot(h.x() - 13_400, h.y() - 7_900) <= 450, "went on to spot 3: " + h);
    }

    @Test
    void goesForACubikonAtOnceWithTheConfiguredAmmo() throws Exception {
        Sim sim = new Sim(config(90, false), 26, 7_500, 3_900);
        sim.lasers("ammunition_laser_lcb-10", "ammunition_laser_ucb-100");
        CubikonModule m = new CubikonModule(sim.ctx);
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
        CubikonModule m = new CubikonModule(sim.ctx);
        sim.run(m::tick, 10_000);
        assertEquals(60, sim.hero().selected());
    }
}
