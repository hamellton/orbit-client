package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.bot.mod.QuestModule;
import orbit.config.BotConfig;
import orbit.protocol.gen.QuestConditionModule;
import orbit.protocol.gen.QuestDefinitionModule;
import orbit.world.maps.MapCatalog;
import orbit.world.maps.NpcMaps;
import org.junit.jupiter.api.Test;

class QuestModuleTest {

    /** A taken quest with one condition. */
    static void take(Sim sim, int id, int questType, int conditionType, double target, String... matches) {
        QuestDefinitionModule d = QuestGiverVisitTest.Giver.definition(id);
        d.types.getFirst().type = (short) questType;
        QuestConditionModule c = d.rootCase.modifier.getFirst().condition;
        c.displayType = (short) conditionType;
        c.type = c.displayType;
        c.matches = List.of(matches);
        c.targetValue = target;
        orbit.protocol.gen.QuestInitializationCommand init = new orbit.protocol.gen.QuestInitializationCommand();
        init.quest = d;
        sim.world.apply(init, sim.now);
    }

    @Test
    void aKillConditionIsTheKillModuleOnTheMapOfThoseNpcsWithOnlyThemListed() {
        // Protegit (NPC 12) lives on 3-6 in the bundled table: the ship is there, a Protegit and a Streuner in sight
        int map = MapCatalog.bundled().byName("3-6").id();
        BotConfig c = BotConfig.empty().withNpcs(java.util.Map.of("-=[ Streuner ]=-",
                new BotConfig.NpcRule(500, 0, true, java.util.Set.of(), java.util.Set.of(), null)));
        Sim sim = new Sim(c, map, 10_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        take(sim, 122001, 2, 6, 35_000, "12");
        sim.npc(500, "-=[ Streuner ]=-", 10_200, 6_000);
        sim.npc(501, "-=[ Protegit ]=-", 10_600, 6_200);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(map, m.workingMap());
        assertEquals(501, sim.world.snapshot(sim.now).hero().selectedId(), "the quest's NPC, not the config's Streuner");
        assertTrue(m.status().startsWith("Квест 122001: убить Protegit 0/35000"), m.status());
    }

    @Test
    void onlyQuestsOfKillsOfKnownNpcsOrMapsAreDoable() {
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("3-1").id(), 5_000, 5_000);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared());
        take(sim, 101, 3, 6, 4, "12"); // Protegit
        take(sim, 102, 2, 28, 15, "0", "0", "0"); // kill players
        take(sim, 103, 6, 79, 2, "0", "51"); // a galaxy gate
        take(sim, 104, 2, 34, 1, String.valueOf(MapCatalog.bundled().byName("3-3").id())); // visit 3-3
        take(sim, 105, 3, 6, 4, "999"); // an NPC nobody has seen
        var q = sim.world.snapshot(sim.now).quests().active();
        assertTrue(m.doable(q.get(101)));
        assertFalse(m.doable(q.get(102)));
        assertFalse(m.doable(q.get(103)));
        assertTrue(m.doable(q.get(104)));
        assertFalse(m.doable(q.get(105)));
    }

    @Test
    void theFirstTickGoesToTheQuestGiver() {
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("3-8").id(), 5_000, 5_000);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared());
        m.tick();
        assertTrue(m.status().startsWith("Квесты:"), m.status());
        assertEquals(0, m.workingMap(), "the visit flies itself");
    }
}
