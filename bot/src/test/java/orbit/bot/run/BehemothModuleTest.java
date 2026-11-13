package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.BehemothModule;
import orbit.bot.mod.SpawnMemory;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.ShipSelectRequest;
import org.junit.jupiter.api.Test;

class BehemothModuleTest {

    static final String BEHEMOTH = "\\\\ Mindfire Behemoth //";

    static BotConfig config(int changeMapSeconds) throws Exception {
        return BotConfig.empty()
                .withModule(BotConfig.Module.BEHEMOTH)
                .withGeneral(new BotConfig.General(308, null, null, null, false, false, 300, false, false))
                .withLoot(new BotConfig.Loot("ammunition_laser_ucb-100", null, 0, 0, null, true, 0))
                .withNpcs(Map.of(
                        BEHEMOTH, new BotConfig.NpcRule(590, -100, true, Set.of("IGNORE_ATTACKED", "HP_LINK"),
                                Set.of(306, 307, 308), "ammunition_laser_a-bl"),
                        "\\\\ Impulse II //", new BotConfig.NpcRule(590, 0, false, Set.of("PASSIVE"), Set.of(306, 307, 308), null)))
                .withModuleSettings("behemoth", Json.MAPPER.readTree("""
                        {"maps": [306, 307, 308], "changeMapSeconds": %d, "collectBox": true, "collectBoxConfig": 2,
                         "collectBoxFormation": "Veteran", "defenceMode": true, "killOthers": false}"""
                        .formatted(changeMapSeconds)));
    }

    @Test
    void waitsNearTheSpawnOfTheMapItIsOn() throws Exception {
        Sim sim = new Sim(config(30), 308, 10_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        assertEquals(308, m.workingMap(), "nothing known anywhere: the map it is on, no flight");
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 300, "at 3-BL's wait point: " + h);
        assertTrue(m.status().startsWith("Жду бегемота"), m.status());
    }

    @Test
    void noBehemothForTheWaitThenAnotherMap() throws Exception {
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 20_000);
        assertEquals(308, m.workingMap(), "still waiting");
        sim.run(m::tick, 20_000);
        assertTrue(m.workingMap() != 308, "30 s and none: another BL map, " + m.workingMap());
    }

    @Test
    void goesWhereTheBehemothIsDueSoonest() throws Exception {
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        long now = System.currentTimeMillis();
        mem.killed(308, 4_700, 3_900, now - 60_000); // here: back in 14 min
        mem.killed(306, 19_400, 5_800, now - 14 * 60_000); // 1-BL: back in a minute
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        assertEquals(306, m.workingMap());
    }

    @Test
    void fightsTheBehemothWhoeverOwnsItThenItsBoxThenTheNextMap() throws Exception {
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100", "ammunition_laser_a-bl");
        SpawnMemory mem = SpawnMemory.inMemory();
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        m.workingMap();
        sim.run(m::tick, 1_000);
        sim.npc(40, "\\\\ Impulse II //", 5_900, 2_700);
        sim.npc(77, "\\\\ Mindfire Behemoth //", 4_700, 3_900);
        sim.run(m::tick, 15_000);
        assertEquals(77, sim.hero().selected(), m.status());
        assertTrue(m.busy());
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK, m.petGear(), "HP Link by its row");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest s && s.targetId == 40), "Impulse never clicked");
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.run(m::tick, 2_000);
        assertTrue(m.status().contains("ящик"), m.status());
        assertEquals(1, mem.spawns(308).size());
        assertTrue(mem.spawns(308).getFirst().lastKill() > 0, "its death remembered");
        sim.run(m::tick, 30_000);
        assertTrue(m.workingMap() != 308, "here it is back in 15 min: another map first, " + m.workingMap());
    }

    @Test
    void anNpcShootingAtUsWhileWaitingIsFoughtBack() throws Exception {
        Sim sim = new Sim(config(300), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.npc(40, "\\\\ Impulse II //", 6_000, 2_700);
        sim.run(m::tick, 3_000);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest s && s.targetId == 40), "quiet: left alone");
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = 40;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(40, sim.hero().selected(), m.status());
    }
}
