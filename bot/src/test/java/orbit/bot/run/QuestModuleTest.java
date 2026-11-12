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
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(map, m.workingMap());
        assertEquals(501, sim.world.snapshot(sim.now).hero().selectedId(), "the quest's NPC, not the config's Streuner");
        assertTrue(m.status().startsWith("Квест 122001: убить Protegit 0/35000"), m.status());
    }

    @Test
    void onlyQuestsOfKillsOfKnownNpcsOrMapsAreDoable() {
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("3-1").id(), 5_000, 5_000);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
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
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.tick();
        assertTrue(m.status().startsWith("Квесты:"), m.status());
        assertEquals(0, m.workingMap(), "the visit flies itself");
    }

    @Test
    void damageToNpcsIsDoneByKillingThem() {
        int map = MapCatalog.bundled().byName("3-6").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        take(sim, 201, 3, 29, 100_000, "1", "12", "1", "0");
        sim.npc(501, "-=[ Protegit ]=-", 10_600, 6_200);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(501, sim.world.snapshot(sim.now).hero().selectedId());
        assertTrue(m.status().startsWith("Квест 201: урон по Protegit"), m.status());
    }

    @Test
    void bonusBoxesAreCollectedWhereTheShipIs() {
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("3-2").id(), 5_000, 5_000);
        take(sim, 202, 3, 55, 10);
        orbit.protocol.gen.AddBoxCommand box = new orbit.protocol.gen.AddBoxCommand();
        box.hash = "b1";
        box.boxType = "BONUS_BOX";
        box.x = 5_400;
        box.y = 5_100;
        sim.world.apply(box, sim.now);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 6_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.CollectBoxRequest r && "b1".equals(r.itemHash)),
                "the bonus box picked up: " + m.status());
    }

    @Test
    void oreIsCollectedAndThenSoldWhenAQuestSaysSo() {
        int map = MapCatalog.bundled().byName("3-2").id();
        Sim sim = new Sim(BotConfig.empty(), map, 5_000, 5_000);
        orbit.world.maps.OreMaps ores = orbit.world.maps.OreMaps.inMemory();
        take(sim, 203, 3, 5, 20, "1"); // collect 20 prometium (the client's old number 1 = ore type 0)
        orbit.protocol.gen.AddOreCommand ore = new orbit.protocol.gen.AddOreCommand();
        ore.hash = "o1";
        ore.oreType = new orbit.protocol.gen.OreTypeModule();
        ore.oreType.typeValue = 0;
        ore.x = 5_300;
        ore.y = 5_000;
        sim.world.apply(ore, sim.now);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), ores);
        m.visited(sim.now);
        sim.run(m::tick, 6_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p.getClass().getSimpleName().contains("Collect")
                || p.getClass().getSimpleName().contains("Harvest")), "the prometium picked: " + m.status());

        // a sale quest with prometium aboard: the cargo plan sells it although the hold is not full
        Sim sell = new Sim(BotConfig.empty(), map, 5_000, 5_000);
        take(sell, 204, 3, 52, 100, "1");
        orbit.protocol.gen.AttributeOreCountUpdateCommand aboard = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        orbit.protocol.gen.OreCountModule pr = new orbit.protocol.gen.OreCountModule();
        pr.oreType = new orbit.protocol.gen.OreTypeModule();
        pr.oreType.typeValue = 0;
        pr.count = 250;
        aboard.oreCountList = List.of(pr);
        sell.world.apply(aboard, sell.now);
        QuestModule s = new QuestModule(sell.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        s.visited(sell.now);
        s.tick();
        CargoPlan plan = s.cargoPlan(sell.config);
        assertEquals(List.of(0), plan.ores());
        assertTrue(plan.always());
        assertEquals(BotConfig.Collect.CARGO_BASE, plan.mode());
    }

    @Test
    void kindsSwitchedOffAreNotTaken() {
        com.fasterxml.jackson.databind.node.ObjectNode q = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        q.putArray("do").add("MAP");
        Sim sim = new Sim(BotConfig.empty().withModuleSettings("quests", q), MapCatalog.bundled().byName("3-1").id(), 5_000, 5_000);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        take(sim, 301, 3, 6, 4, "12");
        take(sim, 302, 2, 34, 1, String.valueOf(MapCatalog.bundled().byName("3-3").id()));
        var active = sim.world.snapshot(sim.now).quests().active();
        assertFalse(m.doable(active.get(301)), "killing switched off");
        assertTrue(m.doable(active.get(302)));
    }

    @Test
    void aQuestOfMapsNoPortalLeadsToIsNotDoableEvenWithItsConditionNotRequired() {
        // live 2026-11-12 on ru1: 312003 «on R-Zone 1 … 10», its condition not mandatory, was taken
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("2-8").id(), 5_000, 5_000);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        take(sim, 312003, 3, 16, 1, "150", "151", "152");
        sim.world.snapshot(sim.now).quests().active().get(312003);
        orbit.protocol.gen.QuestInitializationCommand init = new orbit.protocol.gen.QuestInitializationCommand();
        init.quest = QuestGiverVisitTest.Giver.definition(312003);
        init.quest.types.getFirst().type = 3;
        var c = init.quest.rootCase.modifier.getFirst().condition;
        c.displayType = 16;
        c.type = 16;
        c.matches = List.of("150", "151");
        c.mandatory = false;
        sim.world.apply(init, sim.now);
        assertFalse(m.doable(sim.world.snapshot(sim.now).quests().active().get(312003)));
    }

    @Test
    void bossesAreHuntedElsewhereThanOn45() {
        // the user 2026-11-12: bosses live on 4-5 too, but are easier on their own maps (Boss Saimon: x-3, x-4)
        int map45 = MapCatalog.bundled().byName("4-5").id();
        Sim sim = new Sim(BotConfig.empty(), map45, 10_000, 6_000);
        take(sim, 401, 3, 6, 3, "17");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertTrue(m.workingMap() != map45 && m.workingMap() != 0, MapCatalog.bundled().info(m.workingMap()).name());
        // an Uber one only lives there
        Sim uber = new Sim(BotConfig.empty(), map45, 10_000, 6_000);
        take(uber, 402, 3, 6, 10, "31");
        QuestModule u = new QuestModule(uber.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        u.visited(uber.now);
        u.tick();
        assertEquals(map45, u.workingMap());
    }

    @Test
    void anNpcWrittenOtherwiseInSightIsStillTheTarget() {
        int map45 = MapCatalog.bundled().byName("4-5").id();
        Sim sim = new Sim(BotConfig.empty(), map45, 10_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        take(sim, 403, 3, 6, 10, "31");
        sim.npc(700, "( Uber Saimon )", 10_500, 6_100); // the table writes «( UberSaimon )»
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(700, sim.world.snapshot(sim.now).hero().selectedId());
    }

    @Test
    void aMapWithoutTheNpcsForTenMinutesIsLeftForAnother() {
        int map = MapCatalog.bundled().byName("3-4").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        take(sim, 404, 3, 6, 15, "6"); // Saimon: x-3 and x-4
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals(map, m.workingMap());
        for (int t = 0; t < 10 * 60 * 10 + 20; t++) {
            m.tick();
            sim.now += 100 * Sim.MS;
        }
        assertTrue(m.workingMap() != map && m.workingMap() != 0, "another x-3/x-4 map: "
                + MapCatalog.bundled().info(m.workingMap()).name());
    }

    @Test
    void salvageKillsTheNpcsAndPicksTheirCargo() {
        // 310023 (ru1 2026-11-12): «salvage 100» — the cargo boxes of killed NPCs (SalvageCondition: faction, ship
        // type, NPC number, loot type, ore); here the NPC is Saimon (6), who lives on x-3 and x-4
        int map = MapCatalog.bundled().byName("3-4").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        take(sim, 501, 3, 23, 100, "0", "0", "6", "0", "0");
        sim.npc(800, "-=[ Saimon ]=-", 10_500, 6_100);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(800, sim.world.snapshot(sim.now).hero().selectedId());
        assertTrue(m.status().startsWith("Квест 501: подобрать груз с NPC: Saimon"), m.status());

        // the cargo it leaves is picked up
        Sim box = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        take(box, 502, 3, 23, 100, "0", "0", "6", "0", "0");
        orbit.protocol.gen.AddBoxCommand cargo = new orbit.protocol.gen.AddBoxCommand();
        cargo.hash = "c1";
        cargo.boxType = "FROM_SHIP";
        cargo.x = 10_300;
        cargo.y = 6_000;
        box.world.apply(cargo, box.now);
        QuestModule b = new QuestModule(box.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        b.visited(box.now);
        box.run(b::tick, 5_000);
        assertTrue(box.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.CollectBoxRequest r && "c1".equals(r.itemHash)),
                b.status());
    }
}
