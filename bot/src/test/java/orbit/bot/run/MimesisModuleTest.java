package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import orbit.bot.mod.MimesisModule;
import orbit.bot.mod.MimesisModuleTestAccess;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.PayloadEscortKeyInfoCommand;
import orbit.protocol.gen.PayloadEscortTimeInfoCommand;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-18: the Mimesis escort — the behaviour DarkBot's (DonorPlugin, SharedPlugin), the automation AstraBot's. */
class MimesisModuleTest {

    static final int ESCORT = 436;
    static final int FARM = MapCatalog.bundled().byName("3-4").id();

    static BotConfig config(String json) throws Exception {
        ObjectNode s = (ObjectNode) Json.MAPPER.readTree(json);
        BotConfig c = BotConfig.empty();
        BotConfig.General g = c.general();
        c = new BotConfig(c.schema(), "", BotConfig.Module.MIMESIS, new BotConfig.General(FARM, g.offensive(), g.roam(),
                g.run(), g.runFromEnemies(), g.runFromEnemiesInSight(), g.rememberEnemiesSec(), g.roamSequential(),
                g.onlyKillPreferred()), c.npcs(), c.loot(), c.collect(), c.pet(), c.safety(), c.zones(), c.safetySpots(),
                c.modules(), c.extra(), c.buy(), c.session(), c.flee(), c.group());
        return c.withModuleSettings(MimesisModule.KEY, s);
    }

    static int home() {
        return MapCatalog.bundled().byName("3-1").id();
    }

    static void times(Sim sim, double next, double close, double end) {
        PayloadEscortTimeInfoCommand t = new PayloadEscortTimeInfoCommand();
        t.timeToNextSpawn = next;
        t.timeToGateClose = close;
        t.timeToSessionEnd = end;
        sim.now = Math.max(sim.now, 1_000 * Sim.MS);
        sim.world.apply(t, sim.now);
    }

    static void keys(Sim sim, int n) {
        PayloadEscortKeyInfoCommand k = new PayloadEscortKeyInfoCommand();
        k.keyCount = n;
        sim.world.apply(k, sim.now);
    }

    static void run(Sim sim, MimesisModule m, long ms) {
        for (long t = 0; t < ms; t += 100) {
            m.tick();
            var w = sim.world.snapshot(sim.now);
            for (var p : w.portals().values()) {
                boolean near = Math.hypot(p.x() - w.hero().x(), p.y() - w.hero().y()) < 500;
                if (near != w.activatable().containsKey(p.id())) {
                    orbit.protocol.gen.MapAssetActionAvailableCommand a = new orbit.protocol.gen.MapAssetActionAvailableCommand();
                    a.mapAssetId = p.id();
                    a.activatable = near;
                    sim.world.apply(a, sim.now);
                }
            }
            sim.now += 100 * Sim.MS;
        }
    }

    @Test
    void theServersTimesKeepTheWorld() throws Exception {
        Sim sim = new Sim(config("{}"), FARM, 10_000, 6_000);
        times(sim, 3600, 299, 2699);
        keys(sim, 12);
        WorldSnapshot.Escort e = sim.world.snapshot(sim.now).gates().escort();
        assertEquals(12, e.keys());
        assertEquals(299, e.left(e.gateClose(), sim.now), 0.01);
        assertEquals(289, e.left(e.gateClose(), sim.now + 10_000 * Sim.MS), 0.01, "counted down from when it came");
    }

    @Test
    void theNamesWithDigitsAreTheTablesMimesis() {
        assertEquals("obscured mimesis", MimesisModuleTestAccess.plain("-=[ Obscured M1mes1s ]=-"));
        assertEquals("mimesis turret", MimesisModuleTestAccess.plain("-=[ Mim3si5 Turret ]=-"));
        assertEquals(-90, MimesisModuleTestAccess.priority("-=[ Medic Mim3sis ]=-", true));
        assertEquals(-80, MimesisModuleTestAccess.priority("-=[ Mirror M1m3si5 ]=-", true));
        assertEquals(-50, MimesisModuleTestAccess.priority("-=[ Terror Mime5is ]=-", false));
        assertFalse(MimesisModuleTestAccess.kills("-={EM Freighter}=-"), "the freighter is never shot");
    }

    @Test
    void betweenTheRunsTheKeysAreFarmedThenToX1BeforeTheHour() throws Exception {
        Sim sim = new Sim(config("{\"startTravelSeconds\":180}"), FARM, 10_000, 6_000);
        keys(sim, 5);
        times(sim, 1200, 0, 0);
        MimesisModule m = new MimesisModule(sim.ctx);
        assertEquals(FARM, m.workingMap(), "20 min to the freighter: farming");
        times(sim, 150, 0, 0);
        assertEquals(home(), m.workingMap(), "2.5 min to it: to x-1");
        keys(sim, 0);
        assertEquals(FARM, m.workingMap(), "no key: farming on");
    }

    @Test
    void theEntryOpenTheShipJumpsIntoTheEscortsPortal() throws Exception {
        Sim sim = new Sim(config("{}"), home(), 9_000, 6_000);
        keys(sim, 3);
        times(sim, 3600, 299, 2699);
        GateModuleTest.portal(sim, 900, 54, 10_500, 6_500);
        MimesisModule m = new MimesisModule(sim.ctx);
        run(sim, m, 15_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "jumped");
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 10_500, h.y() - 6_500) < 500, "at the portal: " + h.x() + "/" + h.y());
    }

    @Test
    void theTargetIsTakenNearTheFreighterOnly() throws Exception {
        Sim sim = new Sim(config("{}"), ESCORT, 10_000, 6_000);
        sim.npc(10, "-={EM Freighter}=-", 10_000, 6_000);
        sim.npc(11, "-=[ Raider Mimes1s ]=-", 13_500, 6_000); // 3 500 off the freighter
        sim.npc(12, "-=[ Obscured M1mes1s ]=-", 11_500, 6_000);
        MimesisModule m = new MimesisModule(sim.ctx);
        run(sim, m, 1_000);
        assertEquals(12, m.fightTarget(), "the one within 2 000 of the freighter");
        orbit.protocol.gen.ShipRemoveCommand gone = new orbit.protocol.gen.ShipRemoveCommand();
        gone.userId = 12;
        sim.world.apply(gone, sim.now);
        run(sim, m, 1_000);
        assertTrue(m.fightTarget() != 11, "not the far Raider");
    }

    @Test
    void withNothingAboutTheShipCirclesTheFreighter() throws Exception {
        Sim sim = new Sim(config("{}"), ESCORT, 4_000, 3_000);
        sim.npc(10, "-={EM Freighter}=-", 10_000, 6_000);
        MimesisModule m = new MimesisModule(sim.ctx);
        run(sim, m, 30_000);
        var h = sim.world.snapshot(sim.now).hero();
        double d = Math.hypot(h.x() - 10_000, h.y() - 6_000);
        assertTrue(d < 1_000, "by the freighter: " + d);
    }

    @Test
    void theFreighterGoneAndNothingLeftOutByThePortal() throws Exception {
        Sim sim = new Sim(config("{}"), ESCORT, 10_000, 6_000);
        GateModuleTest.portal(sim, 901, 1, 12_000, 6_000);
        MimesisModule m = new MimesisModule(sim.ctx);
        run(sim, m, 15_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "out");
    }

    @Test
    void theBattlePassRewardsAreCollectedButNoSeasonRerollOrDiminishingQuest() throws Exception {
        Sim sim = new Sim(config("{}"), FARM, 10_000, 6_000);
        times(sim, 1200, 0, 0);
        orbit.protocol.gen.BattlePassUpdateStatusCommand bp = new orbit.protocol.gen.BattlePassUpdateStatusCommand();
        bp.purchased = true;
        bp.questsToCollectReward = List.of(500456);
        sim.world.apply(bp, sim.now);
        orbit.protocol.gen.DiminishingQuestInitializationCommand di = new orbit.protocol.gen.DiminishingQuestInitializationCommand();
        di.quest = QuestGiverVisitTest.Giver.definition(122001);
        di.acceptabilityStatus = new orbit.protocol.gen.QuestAcceptabilityStatusModule();
        di.acceptabilityStatus.type = 1;
        sim.world.apply(di, sim.now);
        MimesisModule m = new MimesisModule(sim.ctx);
        run(sim, m, 10_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.BattlePassCollectRequest), "collected");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.DiminishingQuestAcceptQuestRequest),
                "the quest module's work");
    }

    @Test
    void onTheFarmTheTargetIsKeptWhenAMoreImportantOneComes() throws Exception {
        Sim sim = new Sim(config("{}"), FARM, 10_000, 6_000);
        times(sim, 1200, 0, 0);
        sim.npc(20, "-=[ Cloned Mim3sis ]=-", 10_400, 6_000);
        MimesisModule m = new MimesisModule(sim.ctx);
        run(sim, m, 2_000);
        assertEquals(20, m.fightTarget(), "the Cloned taken");
        sim.npc(21, "-=[ Hexor M1mesis ]=-", 10_600, 6_200); // priority 0, the Cloned 3
        run(sim, m, 3_000);
        assertEquals(20, m.fightTarget(), "kept, as DarkBot");
    }

    @Test
    void asDarkBotTheMimesisAreCircledAndNoEarlyRepair() throws Exception {
        Sim sim = new Sim(config("{}"), FARM, 10_000, 6_000);
        sim.npc(30, "-=[ Reflector Mimesi5 ]=-", 10_500, 6_000);
        MimesisModule m = new MimesisModule(sim.ctx);
        assertFalse(m.earlyRepair(), "DarkBot's SafetyFinder: only below the threshold");
        BotConfig.NpcRule r = m.config().npc("-=[ Reflector Mimesi5 ]=-");
        assertEquals("CIRCLE", r.move(), "DarkBot's LootModule circles");
        assertEquals(-50, r.priority());
    }

    @Test
    void emaaOnTheHeavyOnesAsAstraBotAndNoneWithoutIt() throws Exception {
        Sim sim = new Sim(config("{}"), FARM, 10_000, 6_000);
        sim.npc(40, "-=[ Terror Mime5is ]=-", 10_500, 6_000);
        sim.npc(41, "-=[ Hexor M1mesis ]=-", 10_600, 6_000);
        MimesisModule m = new MimesisModule(sim.ctx);
        assertNull(m.config().npc("-=[ Terror Mime5is ]=-").ammo(), "no EMAA-20 on board: the config's ammunition");
        sim.lasers("ammunition_laser_ucb-100", MimesisModule.EMAA);
        assertEquals(MimesisModule.EMAA, m.config().npc("-=[ Terror Mime5is ]=-").ammo());
        assertNull(m.config().npc("-=[ Hexor M1mesis ]=-").ammo(), "the small one: UCB-100 as AstraBot");
        sim.config = config("{\"emaa\":{\"mode\":\"all\"}}");
        assertEquals(MimesisModule.EMAA, m.config().npc("-=[ Hexor M1mesis ]=-").ammo());
    }

    @Test
    void theEmaaCraftRuleWhenOn() throws Exception {
        assertNull(MimesisModule.craftRule(config("{}")));
        BotConfig.CraftRule r = MimesisModule.craftRule(config("{\"craftEmaa\":{\"on\":true,\"below\":20000}}"));
        assertNotNull(r);
        assertEquals(MimesisModule.EMAA, r.item());
        assertEquals(MimesisModule.EMAA_RECIPE, r.recipe());
        assertEquals(20_000, r.below());
    }
}
