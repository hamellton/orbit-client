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
        assertTrue(m.status().startsWith("Квест «Protegit: команда «Удалить»»: убить Protegit 0/35000"), m.status());
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
    void salvageOfNoNamedNpcKillsThePlainNpcsOfTheCompanysOwnMap() {
        // 300011 «Сборщик дураниума» (gb2 2026-11-17): cargo with duranium, no NPC named — a VRU ship on 3-1 kills the
        // Streuner there, not the config's NPCs on its working map (1-1 was set: MMO's home map, ten deaths)
        int map = MapCatalog.bundled().byName("3-1").id();
        BotConfig c = BotConfig.empty().withGeneral(new BotConfig.General(MapCatalog.bundled().byName("1-1").id(), null, null, null,
                false, false, 300, false, false));
        Sim sim = new Sim(c, map, 10_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        take(sim, 503, 3, 23, 100, "0", "0", "0", "0", "5");
        sim.npc(810, "-=[ Streuner ]=-", 10_500, 6_100);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(map, m.workingMap(), MapCatalog.bundled().info(m.workingMap()).name());
        assertEquals(810, sim.world.snapshot(sim.now).hero().selectedId(), m.status());
    }

    @Test
    void cargoThatDoesNotCountSendsTheShipToTheNextOwnMap() {
        // gb2 2026-11-17: Streuner's cargo on 3-1 left «Сборщик дураниума» at 0/100 — after ten boxes another 3-x map
        int map = MapCatalog.bundled().byName("3-1").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        take(sim, 504, 3, 23, 100, "0", "0", "0", "0", "5");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 1_000);
        assertEquals(map, m.workingMap());
        sim.ctx.stats().boxes.addAndGet(QuestModule.CARGO_TRIES);
        sim.run(m::tick, 1_000);
        String now = MapCatalog.bundled().info(m.workingMap()).name();
        assertTrue(m.workingMap() != map && now.startsWith("3-"), now);
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
        assertTrue(m.status().startsWith("Квест «Лордакии - источник неприятностей (3)»: подобрать груз с NPC: Saimon"), m.status());

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

    @Test
    void theOwnCompanysMapComesBeforeAnEnemysOne() {
        // live 2026-11-12 on ru1: EIC on 2-8 sent to MMO's 1-3 for Devolarium (3), who also live on 2-3 and 3-3
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("2-8").id(), 5_000, 5_000);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = MapCatalog.bundled().byName("2-8").id();
        si.factionId = 2;
        si.x = 5_000;
        si.y = 5_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
        take(sim, 601, 3, 6, 2, "3");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals("2-3", MapCatalog.bundled().info(m.workingMap()).name());
    }

    @Test
    void withTheCheckboxThePetsLocatorLeadsToTheQuestsNpc() {
        // UberSaimon (31) on 4-5, none in sight; the locator (on it) shows one far away: fly there
        int map45 = MapCatalog.bundled().byName("4-5").id();
        com.fasterxml.jackson.databind.node.ObjectNode q = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        q.put("locator", true);
        BotConfig c = BotConfig.empty().withModuleSettings("quests", q)
                .withPet(new BotConfig.Pet(true, "GUARD", 13, false, 0));
        Sim sim = new Sim(c, map45, 3_000, 3_000);
        take(sim, 701, 3, 6, 10, "31");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals(31, m.petLocator(), "the locator on the quest's NPC");
        // the pet is out, in the locator mode, and the game sends a point
        orbit.protocol.gen.PetInitializationCommand init = new orbit.protocol.gen.PetInitializationCommand();
        init.hasPet = true;
        init.hasFuel = true;
        init.petIsAlive = true;
        sim.world.apply(init, sim.now);
        orbit.protocol.gen.PetHeroActivationCommand a = new orbit.protocol.gen.PetHeroActivationCommand();
        a.ownerId = 1;
        a.petId = 500;
        a.petName = "P";
        a.x = 3_000;
        a.y = 3_000;
        sim.world.apply(a, sim.now);
        orbit.protocol.gen.PetGearActivationRequest gear = new orbit.protocol.gen.PetGearActivationRequest();
        gear.gearTypeToActivate = new orbit.protocol.gen.PetGearTypeModule();
        gear.gearTypeToActivate.typeValue = 6;
        gear.optParam = 31;
        sim.world.applyOutgoing(gear, sim.now);
        sim.now += 500 * Sim.MS;
        orbit.protocol.gen.PetGearLocatorUpdate u = new orbit.protocol.gen.PetGearLocatorUpdate();
        u.x = 15_000;
        u.y = 9_000;
        sim.world.apply(u, sim.now);
        sim.run(m::tick, 2_000);
        assertTrue(m.status().contains("по пеленгатору"), m.status());
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(h.targetX() > 10_000 && h.targetY() > 6_000, "towards the point: " + h.targetX() + "/" + h.targetY());
        // off the checkbox: the config's pet mode, no locator
        q.put("locator", false);
        assertEquals(0, m.petLocator());
    }

    @Test
    void salvageGoesToTheNearestMapOfAnyOfTheQuestsNpcs() {
        // 310023 (ru1 2026-11-12): kill Sibelon (x-4 only), Saimon (x-3, x-4), then their cargo — on 2-3, where it had
        // just killed Saimon, the ship stays (it went to 2-4, Sibelon's, the first condition)
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("2-3").id(), 5_000, 5_000);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = MapCatalog.bundled().byName("2-3").id();
        si.factionId = 2;
        si.x = 5_000;
        si.y = 5_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
        orbit.protocol.gen.QuestDefinitionModule d = QuestGiverVisitTest.Giver.definition(310023);
        d.types.getFirst().type = 3;
        var sibelon = d.rootCase.modifier.getFirst();
        sibelon.condition.matches = List.of("5"); // Sibelon
        sibelon.condition.state.completed = true;
        var saimon = QuestGiverVisitTest.Giver.definition(310024).rootCase.modifier.getFirst();
        saimon.condition.id = 31002302;
        saimon.condition.matches = List.of("6");
        saimon.condition.state.completed = true;
        var cargo = QuestGiverVisitTest.Giver.definition(310025).rootCase.modifier.getFirst();
        cargo.condition.id = 31002303;
        cargo.condition.displayType = 23;
        cargo.condition.type = 23;
        cargo.condition.matches = List.of("0", "0", "0", "0", "12");
        d.rootCase.modifier = List.of(sibelon, saimon, cargo);
        orbit.protocol.gen.QuestInitializationCommand init = new orbit.protocol.gen.QuestInitializationCommand();
        init.quest = d;
        sim.world.apply(init, sim.now);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals("2-3", MapCatalog.bundled().info(m.workingMap()).name());
    }

    @Test
    void aMineIsNeverPickedUpEvenWhenTheConfigSaysSo() {
        BotConfig c = BotConfig.empty().withCollect(new BotConfig.Collect(java.util.Map.of(
                "mine_3", new BotConfig.BoxRule(true, 0, 0)), 2_000, false, false, BotConfig.Collect.CARGO_NONE, 0, null));
        Sim sim = new Sim(c, MapCatalog.bundled().byName("2-2").id(), 5_000, 5_000);
        orbit.protocol.gen.AddBoxCommand mine = new orbit.protocol.gen.AddBoxCommand();
        mine.hash = "m3";
        mine.boxType = "mine_3";
        mine.x = 5_300;
        mine.y = 5_000;
        sim.world.apply(mine, sim.now);
        orbit.bot.mod.KillModule k = new orbit.bot.mod.KillModule(sim.ctx, false, true);
        sim.run(k::tick, 5_000);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.CollectBoxRequest), "not a box");
    }

    @Test
    void theUsersMarksFirstSkipAndCancel() {
        // the user 2026-11-12: Silent Sabotage (17 500 Saboteur, 122004) opens the 100-quest chain — first; 35 000
        // Protegit (122001) not now; a quest marked «cancel» is cancelled
        com.fasterxml.jackson.databind.node.ObjectNode q = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        q.putArray("first").add(122004);
        q.putArray("skip").add(122001);
        q.putArray("cancel").add(120032);
        int map = MapCatalog.bundled().byName("3-6").id();
        Sim sim = new Sim(BotConfig.empty().withModuleSettings("quests", q), map, 10_000, 6_000);
        take(sim, 122001, 2, 6, 35_000, "12"); // Protegit, right here on 3-6
        take(sim, 122004, 2, 6, 17_500, "60"); // Saboteur, on 5-x
        take(sim, 120032, 2, 28, 750, "0", "0", "0");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 5_000);
        assertTrue(m.status().startsWith("Квест «Безмолвный саботаж»"), "the first one, not the Protegit at hand: " + m.status());
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.QuestCancelRequest r && r.questId == 120032));
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.QuestCancelRequest r && r.questId == 122001),
                "skipped, not cancelled");
        var active = sim.world.snapshot(sim.now).quests().active();
        assertFalse(m.doable(active.get(122001)), "not taken either");
    }

    /** A kill condition bound to maps by a sub-condition «on these maps», as 117079 on ru1 (2026-11-12). */
    static void takeOnMaps(Sim sim, int id, int npc, double target, int... maps) {
        QuestDefinitionModule d = QuestGiverVisitTest.Giver.definition(id);
        d.types.getFirst().type = 3;
        QuestConditionModule c = d.rootCase.modifier.getFirst().condition;
        c.matches = List.of(String.valueOf(npc));
        c.targetValue = target;
        QuestConditionModule on = new QuestConditionModule();
        on.id = c.id + 1;
        on.type = 77;
        on.displayType = 77;
        on.matches = java.util.Arrays.stream(maps).mapToObj(String::valueOf).toList();
        on.state = new orbit.protocol.gen.QuestConditionStateModule();
        c.subConditions = List.of(on);
        orbit.protocol.gen.QuestInitializationCommand init = new orbit.protocol.gen.QuestInitializationCommand();
        init.quest = d;
        sim.world.apply(init, sim.now);
    }

    @Test
    void aResourceIsCollectedFromTheNpcsThatCarryIt() {
        // 122011 «Теория преобразования»: «собрать resource_collectable_hybrid-alloy 1200» — the Blighted, on x-7
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("3-5").id(), 10_000, 6_000);
        take(sim, 805, 2, 67, 1_200, "resource_collectable_hybrid-alloy");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals("3-7", MapCatalog.bundled().info(m.workingMap()).name(), m.status());
        assertTrue(m.status().contains("hybrid-alloy 0/1200"), m.status());
    }

    /** 122011 «Теория преобразования»: Blighted Kristallon (160), Blighted Gygerthrall (155), Hybrid Alloy. */
    static void conversionTheory(Sim sim, double kristallon, double gygerthrall) {
        QuestDefinitionModule d = QuestGiverVisitTest.Giver.definition(122011);
        d.types.getFirst().type = 2;
        List<orbit.protocol.gen.QuestElementModule> all = new java.util.ArrayList<>();
        Object[][] conds = {{6, "160", 230.0, kristallon}, {6, "155", 500.0, gygerthrall},
            {67, "resource_collectable_hybrid-alloy", 1_200.0, 255.0}};
        int i = 1;
        for (Object[] k : conds) {
            QuestConditionModule c = new QuestConditionModule();
            c.id = 122011 * 100 + i++;
            c.type = (short) (int) (Integer) k[0];
            c.displayType = c.type;
            c.matches = List.of((String) k[1]);
            c.targetValue = (Double) k[2];
            c.mandatory = true;
            c.state = new orbit.protocol.gen.QuestConditionStateModule();
            c.state.currentValue = (Double) k[3];
            c.state.completed = c.state.currentValue >= c.targetValue;
            orbit.protocol.gen.QuestElementModule e = new orbit.protocol.gen.QuestElementModule();
            e.questCase = new orbit.protocol.gen.QuestCaseModule();
            e.condition = c;
            all.add(e);
        }
        d.rootCase.modifier = all;
        orbit.protocol.gen.QuestInitializationCommand init = new orbit.protocol.gen.QuestInitializationCommand();
        init.quest = d;
        sim.world.apply(init, sim.now);
    }

    @Test
    void theGygerthrallAreFoughtAlongAndCalledUpByTheKristallon() {
        // the user, 2026-11-17: the alloy drops from the Gygerthrall, which come when the Blighted Kristallon is fought
        int map = MapCatalog.bundled().byName("3-7").id();
        // the Kristallon done: the Gygerthrall's turn — one in sight is the target, not the Kristallon nearer
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        conversionTheory(sim, 230, 365);
        sim.npc(900, "-=[ Blighted Kristallon ]=-", 10_300, 6_000);
        sim.npc(901, "<=< Blighted Gygerthrall >=>", 11_000, 6_300);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(901, sim.world.snapshot(sim.now).hero().selectedId(), m.status());
        // none of them in sight: the Kristallon is fought to call them up
        Sim call = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        call.lasers("ammunition_laser_ucb-100");
        conversionTheory(call, 230, 365);
        call.npc(900, "-=[ Blighted Kristallon ]=-", 10_300, 6_000);
        QuestModule k = new QuestModule(call.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        k.visited(call.now);
        call.run(k::tick, 3_000);
        assertEquals(900, call.world.snapshot(call.now).hero().selectedId(), k.status());
        // the Kristallon's own condition at work: a Gygerthrall that came is fought too
        Sim along = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        along.lasers("ammunition_laser_ucb-100");
        conversionTheory(along, 152, 365);
        along.npc(901, "<=< Blighted Gygerthrall >=>", 10_300, 6_000);
        QuestModule a = new QuestModule(along.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        a.visited(along.now);
        along.run(a::tick, 3_000);
        assertEquals(901, along.world.snapshot(along.now).hero().selectedId(), a.status());
    }

    @Test
    void oreBoundToAMapIsCollectedThere() {
        // «Endurium 30 000 on enemy x-4» (mission 23): the ore where the quest counts it, not the nearest field
        int map34 = MapCatalog.bundled().byName("3-4").id();
        int map14 = MapCatalog.bundled().byName("1-4").id();
        Sim sim = new Sim(BotConfig.empty(), map34, 10_000, 6_000);
        QuestDefinitionModule d = QuestGiverVisitTest.Giver.definition(806);
        d.types.getFirst().type = 2;
        QuestConditionModule c = d.rootCase.modifier.getFirst().condition;
        c.type = 5;
        c.displayType = 5;
        c.matches = List.of("2"); // endurium
        c.targetValue = 30_000;
        QuestConditionModule on = new QuestConditionModule();
        on.id = c.id + 1;
        on.type = 77;
        on.displayType = 77;
        on.matches = List.of(String.valueOf(map14));
        on.state = new orbit.protocol.gen.QuestConditionStateModule();
        c.subConditions = List.of(on);
        orbit.protocol.gen.QuestInitializationCommand init = new orbit.protocol.gen.QuestInitializationCommand();
        init.quest = d;
        sim.world.apply(init, sim.now);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals(map14, m.workingMap());
    }

    @Test
    void refinedOreIsRefinedInTheHoldFromTheRawOneAboard() {
        // «Collect Prometid» (missions 8, 23…): 20 prometium + 10 endurium a unit, refined in the hold
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("3-1").id(), 10_000, 6_000);
        orbit.protocol.gen.AttributeOreCountUpdateCommand o = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        o.oreCountList = List.of(CargoCareTest.ore(0, 210), CargoCareTest.ore(1, 100));
        sim.world.apply(o, sim.now);
        take(sim, 807, 2, 5, 120_000, "11"); // prometid
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 1_000);
        var r = sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.LabRefinementRequest)
                .map(p -> (orbit.protocol.gen.LabRefinementRequest) p).toList();
        assertEquals(1, r.size(), m.status());
        assertEquals(4, r.getFirst().toProduce.oreType.typeValue);
        assertEquals(10, r.getFirst().toProduce.count, "min(210 / 20, 100 / 10)");
    }

    @Test
    void killsBoundToAMapAreDoneOnlyThere() {
        // Saimon live on x-3 and x-4; the ship is on 3-4, but the quest counts them on 3-3 only
        int map34 = MapCatalog.bundled().byName("3-4").id();
        int map33 = MapCatalog.bundled().byName("3-3").id();
        Sim sim = new Sim(BotConfig.empty(), map34, 10_000, 6_000);
        takeOnMaps(sim, 801, 6, 10, map33);
        var quest = sim.world.snapshot(sim.now).quests().active().get(801);
        assertEquals(1, quest.leaves().size(), "the kill is the work, the map its bound");
        assertEquals("убить Saimon (на 3-3)", orbit.world.QuestTexts.condition(quest.leaves().getFirst()));
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals(map33, m.workingMap());
    }

    @Test
    void piratesWithNoMapAreHuntedOn52() {
        // the user 2026-11-12: more chance of a group on 5-2; Saboteur (60) live on 5-1 … 5-3, the ship is on 5-1
        int map51 = MapCatalog.bundled().byName("5-1").id();
        Sim sim = new Sim(BotConfig.empty(), map51, 10_000, 6_000);
        take(sim, 802, 2, 6, 17_500, "60");
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        m.tick();
        assertEquals("5-2", MapCatalog.bundled().info(m.workingMap()).name());
    }

    @Test
    void thePassesFreeButtonsAndNeverAPaidOne() {
        int map = MapCatalog.bundled().byName("3-6").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        // Season Pass: level 2 reached (0 + 90 of 90, then 50 of 90), level 0's free reward unclaimed, gold not bought
        orbit.protocol.gen.SeasonPassStatusCommand st = new orbit.protocol.gen.SeasonPassStatusCommand();
        st.pointsCost = List.of(0, 90, 90);
        st.uridiumCost = List.of();
        sim.world.apply(st, sim.now);
        orbit.protocol.gen.SeasonPointsInfoCommand pts = new orbit.protocol.gen.SeasonPointsInfoCommand();
        pts.points = 140;
        sim.world.apply(pts, sim.now);
        orbit.protocol.gen.SeasonPassRewardInfoCommand ri = new orbit.protocol.gen.SeasonPassRewardInfoCommand();
        orbit.protocol.gen.SeasonPassRewardModule free = new orbit.protocol.gen.SeasonPassRewardModule();
        free.level = 0;
        free.lootId = "x";
        orbit.protocol.gen.SeasonPassRewardModule gold = new orbit.protocol.gen.SeasonPassRewardModule();
        gold.level = 0;
        gold.goldPass = true;
        gold.lootId = "y";
        ri.freeRewardItems = List.of(free);
        ri.goldPassRewardItems = List.of(gold);
        ri.extGoldPassRewardItems = List.of();
        sim.world.apply(ri, sim.now);
        assertEquals(1, sim.world.snapshot(sim.now).quests().passes().seasonClaimable(), "the free one only");
        // a season mission the bot cannot do (kill players), daily, one free reroll left
        orbit.protocol.gen.SeasonPassMissionInfoCommand mi = new orbit.protocol.gen.SeasonPassMissionInfoCommand();
        mi.refreshInfo = new orbit.protocol.gen.RefreshQuestInfo();
        mi.refreshInfo.dailyLeft = 1;
        mi.refreshInfo.nextDailyUridium = 500;
        orbit.protocol.gen.SeasonPassMissionModule mission = new orbit.protocol.gen.SeasonPassMissionModule();
        mission.questDefinitionModule = QuestGiverVisitTest.Giver.definition(700011);
        mission.questDefinitionModule.types.getFirst().type = 3;
        mission.questDefinitionModule.rootCase.modifier.getFirst().condition.displayType = 28;
        mission.missionStatus = new orbit.protocol.gen.SeasonPassMissionStatusModule();
        mi.missionItems = List.of(mission);
        sim.world.apply(mi, sim.now);
        // Galaxy Trek: a sector reached, not received; the Battle Pass not bought, an objective waiting
        orbit.protocol.gen.SpaceSearchSectorRewardsCommand sr = new orbit.protocol.gen.SpaceSearchSectorRewardsCommand();
        orbit.protocol.gen.SpaceSearchSectorRewards sector = new orbit.protocol.gen.SpaceSearchSectorRewards();
        sector.range = 10;
        sector.count = 10;
        sector.unlock = true;
        sr.rewards = List.of(sector);
        sim.world.apply(sr, sim.now);
        orbit.protocol.gen.BattlePassUpdateStatusCommand bp = new orbit.protocol.gen.BattlePassUpdateStatusCommand();
        bp.purchased = false;
        bp.questsToCollectReward = List.of(500456);
        sim.world.apply(bp, sim.now);
        // the diminishing window offers a quest the bot can do (Protegit, here on 3-6)
        orbit.protocol.gen.DiminishingQuestInitializationCommand di = new orbit.protocol.gen.DiminishingQuestInitializationCommand();
        di.quest = QuestGiverVisitTest.Giver.definition(122001);
        di.acceptabilityStatus = new orbit.protocol.gen.QuestAcceptabilityStatusModule();
        di.acceptabilityStatus.type = 1;
        sim.world.apply(di, sim.now);

        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 30_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.SeasonPassRewardClaimAllRequest));
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.SeasonPassRefreshRequest r
                && r.questId == 700011 && r.costType == 0), "a free reroll");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.SeasonPassRefreshRequest r && r.costType != 0),
                "never for uridium");
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.SpaceSearchRequest r && r.action == 3 && r.count == 10));
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.SpaceSearchRequest r && r.action == 1),
                "no search: it spends FTL keys");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.BattlePassCollectRequest), "not bought");
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.DiminishingQuestAcceptQuestRequest r
                && r.questId == 122001 && r.acceptabilityStatus != null));
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.SeasonPassLevelPurchaseRequest));
    }
}
