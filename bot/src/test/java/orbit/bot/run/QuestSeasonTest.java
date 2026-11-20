package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import orbit.bot.mod.QuestCrafts;
import orbit.bot.mod.QuestModule;
import orbit.bot.mod.QuestsAlongside;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.AssemblyBuildRequest;
import orbit.protocol.gen.AssemblyRecipeAvailableStatusModule;
import orbit.protocol.gen.AssemblyRecipeContentModule;
import orbit.protocol.gen.AssemblyRecipeModule;
import orbit.protocol.gen.QuestDefinitionModule;
import orbit.protocol.gen.SeasonPassRefreshRequest;
import orbit.world.Quests;
import orbit.world.maps.MapCatalog;
import orbit.world.maps.NpcMaps;
import org.junit.jupiter.api.Test;

/**
 * As DmPlugin and AstraBot do it: no reroll of the missions that count other missions, cargo boxes, crafting for
 * "collect built recipes", quests alongside another module.
 */
class QuestSeasonTest {

    static final String PT = "recipe_blueprint-tech-precisiontargeter";
    static final String SEPROM = "recipe_blueprint-seprom-blacklight";

    static BotConfig config(BotConfig.Module module, String quests) throws Exception {
        return BotConfig.empty().withModule(module).withModuleSettings(BotConfig.Module.QUESTS.key(),
                (ObjectNode) Json.MAPPER.readTree(quests));
    }

    /** A running Season Pass mission with one condition; {@code questType} 3 daily, 6 weekly, 9 seasonal. */
    static void season(Sim sim, int id, int questType, int conditionType, double target, String... matches) {
        orbit.protocol.gen.SeasonPassMissionInfoCommand mi = new orbit.protocol.gen.SeasonPassMissionInfoCommand();
        mi.refreshInfo = new orbit.protocol.gen.RefreshQuestInfo();
        mi.refreshInfo.dailyLeft = 1;
        mi.refreshInfo.weeklyLeft = 1;
        orbit.protocol.gen.SeasonPassMissionModule mission = new orbit.protocol.gen.SeasonPassMissionModule();
        QuestDefinitionModule d = QuestGiverVisitTest.Giver.definition(id);
        d.types.getFirst().type = (short) questType;
        var c = d.rootCase.modifier.getFirst().condition;
        c.displayType = (short) conditionType;
        c.type = c.displayType;
        c.matches = List.of(matches);
        c.targetValue = target;
        mission.questDefinitionModule = d;
        mission.missionStatus = new orbit.protocol.gen.SeasonPassMissionStatusModule();
        mi.missionItems = List.of(mission);
        sim.world.apply(mi, sim.now);
    }

    static void recipe(Sim sim, String... ids) {
        AssemblyRecipeContentModule c = new AssemblyRecipeContentModule();
        c.recipes = new java.util.ArrayList<>();
        for (String id : ids) {
            AssemblyRecipeModule r = new AssemblyRecipeModule();
            r.recipeLootId = id;
            r.status = new AssemblyRecipeAvailableStatusModule();
            c.recipes.add(r);
        }
        sim.world.apply(c, sim.now);
    }

    static void credits(Sim sim, int credits) {
        orbit.protocol.gen.AttributeCreditsUpdateCommand money = new orbit.protocol.gen.AttributeCreditsUpdateCommand();
        money.credits = credits;
        money.uridium = 1_000_000;
        sim.world.apply(money, sim.now);
    }

    /** Seprom BL's price besides its uridium (the client's: 1 250 rinusk, 250 Blacklight trace) {@code times} over. */
    static void sepromPrice(Sim sim, double times) {
        RecipeCostsTest.resource(sim, "resource_collectable_rinusk", 1_250 * times);
        RecipeCostsTest.resource(sim, "resource_collectable_blacklight-trace", 250 * times);
    }

    @Test
    void aMissionThatCountsOtherMissionsIsNeverRerolled() throws Exception {
        Sim sim = new Sim(config(BotConfig.Module.QUESTS, "{}"), MapCatalog.bundled().byName("3-6").id(), 10_000, 6_000);
        season(sim, 700028, 3, Quests.COMPLETE_SEASON_PASS_QUESTS, 10, "2"); // daily, one free reroll left
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        m.visited(sim.now);
        sim.run(m::tick, 30_000);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof SeasonPassRefreshRequest), "it grows by itself");
    }

    @Test
    void theMimesisObjectivesCountTheParentNotTheEventBound() {
        Quests.Condition event = new Quests.Condition(2, Quests.DURING_EVENT, List.of("MimesisMutiny"), 0, 0, true,
                false, false, List.of());
        Quests.Condition objectives = new Quests.Condition(1, Quests.COMPLETED_OBJECTIVES, List.of(), 15, 0, true, false,
                true, List.of(event));
        Quests.Quest q = new Quests.Quest(700023, List.of(9), List.of(objectives), List.of(), 0);
        assertEquals(List.of(objectives), q.leaves(), "during the event only bounds it");
        assertTrue(orbit.bot.mod.QuestTargets.growing(q));
    }

    @Test
    void cargoBoxesAreDoneByKillingAndPickingTheCargo() throws Exception {
        Sim sim = new Sim(config(BotConfig.Module.QUESTS, "{}"), MapCatalog.bundled().byName("3-2").id(), 10_000, 6_000);
        season(sim, 700037, 9, Quests.CARGO, 1_500);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        assertTrue(m.doable(sim.world.snapshot(sim.now).quests().active().get(700037)));
        m.visited(sim.now);
        m.tick();
        int map = m.workingMap();
        assertTrue(map > 0, m.status());
        assertTrue(MapCatalog.bundled().info(map).name().startsWith("3-"), "the company's own maps (VRU)");
    }

    @Test
    void aBuiltRecipeConditionCraftsForCreditsFirstThenSeprom() throws Exception {
        Sim sim = new Sim(config(BotConfig.Module.QUESTS, "{}"), 26, 7_500, 3_900);
        season(sim, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(sim, PT, SEPROM);
        credits(sim, 10_000);
        QuestModule m = new QuestModule(sim.ctx, NpcMaps.shared(), orbit.world.maps.OreMaps.inMemory());
        assertFalse(m.doable(sim.world.snapshot(sim.now).quests().active().get(700030)),
                "10 000 credits of 20 000, no rinusk told: nothing to craft");
        sepromPrice(sim, 1);
        assertEquals(SEPROM, QuestCrafts.craftRule(sim.config, sim.world.snapshot(sim.now)).recipe(),
                "uridium is fine (user's choice)");
        credits(sim, 5_000_000);
        assertTrue(m.doable(sim.world.snapshot(sim.now).quests().active().get(700030)));
        CraftWatch w = new CraftWatch(sim.ctx);
        CraftWatchTest.runFor(sim, w, 30_000);
        AssemblyBuildRequest b = (AssemblyBuildRequest) sim.sent.stream().filter(AssemblyBuildRequest.class::isInstance)
                .findFirst().orElseThrow();
        assertEquals(PT, b.recipeLootId, "credits first");
        assertEquals(1.0, b.amount);
    }

    @Test
    void rsbBeforeSepromWhenTheCreditsAreShort() throws Exception {
        // user's choice of order: Precision Targeter, RSB-75 BL, Seprom BL
        Sim sim = new Sim(config(BotConfig.Module.QUESTS, "{}"), 26, 7_500, 3_900);
        season(sim, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(sim, PT, SEPROM, "recipe_blueprint-rsb75-blacklight");
        credits(sim, 10_000);
        sepromPrice(sim, 10);
        assertEquals("recipe_blueprint-rsb75-blacklight",
                QuestCrafts.craftRule(sim.config, sim.world.snapshot(sim.now)).recipe());
    }

    @Test
    void notWithoutTheUridiumNorWithTheCraftOff() throws Exception {
        Sim sim = new Sim(config(BotConfig.Module.QUESTS, "{\"craftRecipes\":[\"recipe_blueprint-rsb75-blacklight\"]}"),
                26, 7_500, 3_900);
        season(sim, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(sim, "recipe_blueprint-rsb75-blacklight");
        RecipeCostsTest.resource(sim, "resource_collectable_rinusk", 100_000);
        RecipeCostsTest.resource(sim, "resource_collectable_blacklight-trace", 100_000);
        orbit.protocol.gen.AttributeCreditsUpdateCommand poor = new orbit.protocol.gen.AttributeCreditsUpdateCommand();
        poor.credits = 1_000_000;
        poor.uridium = 3_000;
        sim.world.apply(poor, sim.now);
        assertNull(QuestCrafts.craftRule(sim.config, sim.world.snapshot(sim.now)), "RSB-75 BL wants 4 000 uridium");
        credits(sim, 1_000_000);
        assertEquals("recipe_blueprint-rsb75-blacklight",
                QuestCrafts.craftRule(sim.config, sim.world.snapshot(sim.now)).recipe());
        Sim off = new Sim(config(BotConfig.Module.QUESTS, "{\"craft\":false}"), 26, 7_500, 3_900);
        season(off, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(off, PT);
        credits(off, 5_000_000);
        assertNull(QuestCrafts.craftRule(off.config, off.world.snapshot(off.now)));
        Sim kill = new Sim(config(BotConfig.Module.KILL_COLLECT, "{}"), 26, 7_500, 3_900);
        season(kill, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(kill, PT);
        credits(kill, 5_000_000);
        assertNull(QuestCrafts.craftRule(kill.config, kill.world.snapshot(kill.now)), "another module, alongside off");
    }

    @Test
    void alongsideAnotherModuleTheCargoIsPickedTheCraftRunsAndNothingIsRerolled() throws Exception {
        Sim sim = new Sim(config(BotConfig.Module.KILL_COLLECT, "{\"alongside\":true}"), 26, 7_500, 3_900);
        season(sim, 700037, 3, Quests.CARGO, 1_500);
        QuestsAlongside a = new QuestsAlongside(sim.ctx);
        BotConfig adjusted = a.adjust(sim.config);
        assertTrue(adjusted.collect().boxes().get("FROM_SHIP").collect(), "the cargo of its kills");
        assertTrue(adjusted.collect().boxes().get("CANDY_CARGO").collect(), "the events' cargo too");
        assertSame(adjusted, a.adjust(sim.config), "kept while nothing changed");
        season(sim, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(sim, PT);
        credits(sim, 5_000_000);
        assertNotNull(QuestCrafts.craftRule(sim.config, sim.world.snapshot(sim.now)));
        for (long t = 0; t < 30_000; t += 100) {
            a.tick(false);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof SeasonPassRefreshRequest), "no rerolls alongside");
        Sim plain = new Sim(config(BotConfig.Module.KILL_COLLECT, "{}"), 26, 7_500, 3_900);
        season(plain, 700037, 3, Quests.CARGO, 1_500);
        assertSame(plain.config, new QuestsAlongside(plain.ctx).adjust(plain.config), "alongside off: the config as is");
        Sim quests = new Sim(config(BotConfig.Module.QUESTS, "{\"alongside\":true}"), 26, 7_500, 3_900);
        assertFalse(QuestsAlongside.on(quests.config), "the quest module does it all itself");
        // the Mimesis module: cargo on its farm map, never in the escort; its crafts too
        Sim mim = new Sim(config(BotConfig.Module.MIMESIS, "{\"alongside\":true}"), 26, 7_500, 3_900);
        season(mim, 700037, 3, Quests.CARGO, 1_500);
        assertTrue(new QuestsAlongside(mim.ctx).adjust(mim.config).collect().boxes().get("FROM_SHIP").collect());
        Sim escort = new Sim(config(BotConfig.Module.MIMESIS, "{\"alongside\":true}"), 430, 7_500, 3_900);
        season(escort, 700037, 3, Quests.CARGO, 1_500);
        assertSame(escort.config, new QuestsAlongside(escort.ctx).adjust(escort.config), "not in the escort");
        season(mim, 700030, 9, Quests.COLLECT_BUILT_RECIPE, 25);
        recipe(mim, PT);
        credits(mim, 5_000_000);
        assertNotNull(QuestCrafts.craftRule(mim.config, mim.world.snapshot(mim.now)));
    }
}
