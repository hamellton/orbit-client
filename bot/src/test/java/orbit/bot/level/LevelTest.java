package orbit.bot.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The leveling state from the site's answers and the advisor over the bundled plan (invented answers). */
class LevelTest {

    static final String LF1 = "equipment_weapon_laser_lf-1";
    static final String MP1 = "equipment_weapon_laser_mp-1";
    static final String G79 = "equipment_generator_speed_g3n-7900";

    /** A new account as the site shows it: one Liberator, 1 × LF-1 and 2 × G3N-7900 in both configs. */
    static final String LIST = """
            {"isError":0,"data":{"money":{"uridium":"0","credits":"1.250"},
             "map":{"lootIds":["ship_liberator","ship_nostromo"]},
             "ret":{"hangars":{"0":{"hangarID":"501","hangar_is_active":true,"general":{"ship":{"I":1,"L":0}}}},
              "itemInfo":[{"L":0,"name":"Liberator","C":"ship","levels":[{"slotsets":{"lasers":{"Q":4},"generators":{"Q":6}}}]}],
              "shipInfo":[{"lootId":"ship_liberator","owned":1},{"lootId":"ship_nostromo","owned":0}]}}}
            """;

    static final String HANGAR = """
            {"isError":0,"data":{"map":{"lootIds":["equipment_weapon_laser_lf-1","equipment_generator_speed_g3n-7900",
              "equipment_weapon_laser_mp-1"]},
             "ret":{"items":[{"I":"11","L":0,"EQH":"501"},{"I":"21","L":1,"EQH":"501"},{"I":"22","L":1,"EQH":"501"},
               {"I":"31","L":2,"EQH":null}],
              "hangars":{"0":{"hangarID":"501","config":{
                "1":{"ship":{"EQ":{"lasers":["11"],"generators":["21","22"]}},"drones":{}},
                "2":{"ship":{"EQ":{"lasers":["11"],"generators":["21","22"]}},"drones":{}}}}}}}}
            """;

    static JsonNode json(String s) throws Exception {
        return orbit.config.Json.MAPPER.readTree(s);
    }

    static AccountState newAccount(long credits) throws Exception {
        var list = SiteHangars.list(json(LIST));
        var hangar = SiteHangars.hangar(json(HANGAR), list.activeHangar());
        return SiteHangars.state(list, hangar, 3, credits, 0);
    }

    @Test
    void readsTheSite() throws Exception {
        var list = SiteHangars.list(json(LIST));
        assertEquals(501, list.activeHangar());
        assertEquals("ship_liberator", list.activeShip());
        assertEquals(Set.of("ship_liberator"), list.ownedShips());
        assertEquals(Map.of("lasers", 4, "generators", 6), list.slots());
        assertEquals(1250, list.credits(), "dots between thousands");
        var hangar = SiteHangars.hangar(json(HANGAR), 501);
        assertEquals(List.of(LF1), hangar.fitted().get(1).get("lasers"));
        assertEquals(List.of(G79, G79), hangar.fitted().get(2).get("generators"));
        assertEquals(Map.of(MP1, 1), hangar.spare());
        assertTrue(hangar.shape().contains("drones"), hangar.shape());
    }

    @Test
    void theGameMoneyWins() throws Exception {
        assertEquals(90_000, newAccount(90_000).credits());
        var list = SiteHangars.list(json(LIST));
        assertEquals(1250, SiteHangars.state(list, null, 0, -1, -1).credits(), "the site's when the game tells none");
    }

    @Test
    void firstGoalLasersBuysWhatItCanAboveTheReserve() throws Exception {
        LevelPlan plan = LevelPlan.bundled();
        // 4 slots, one spare MP-1 owned: 3 to buy; 30 000 reserve, 40 000 each → 100 000 buys one
        Advisor.Advice a = Advisor.advise(newAccount(100_000), plan);
        assertEquals("Ц1", a.goal().id());
        assertEquals(3, a.missing());
        assertEquals(1, a.refit(), "the spare MP-1 goes in");
        assertNotNull(a.purchase());
        assertEquals(MP1, a.purchase().item());
        assertEquals(1, a.purchase().amount());
        Advisor.Advice poor = Advisor.advise(newAccount(50_000), plan);
        assertNull(poor.purchase());
        assertTrue(poor.text().contains("копить ещё 20000"), poor.text());
    }

    @Test
    void doneGoalsAreSkippedAndUnknownStops() throws Exception {
        LevelPlan plan = LevelPlan.bundled();
        AccountState s = newAccount(0);
        AccountState lasersDone = new AccountState(s.level(), 0, 0, s.activeHangar(), "ship_nostromo",
                Set.of("ship_nostromo"), Map.of("lasers", 2, "generators", 2),
                Map.of(1, Map.of("lasers", List.of(MP1, MP1), "generators", List.of(G79, G79)),
                        2, Map.of("lasers", List.of(MP1, MP1), "generators", List.of(G79, G79))), Map.of());
        Advisor.Advice a = Advisor.advise(lasersDone, plan);
        assertEquals(List.of("Ц1", "Ц2", "Ц3"), a.done());
        assertEquals("Ц4", a.goal().id());
        assertTrue(a.text().contains("не умею проверить"), a.text());
    }

    @Test
    void unknownEquipmentIsNotDone() throws Exception {
        var list = SiteHangars.list(json(LIST));
        Advisor.Advice a = Advisor.advise(SiteHangars.state(list, null, 1, 0, 0), LevelPlan.bundled());
        assertEquals("Ц1", a.goal().id());
        assertFalse(a.text().contains("купил бы"), a.text());
    }

    @Test
    void theBundledPlanReads() {
        LevelPlan plan = LevelPlan.bundled();
        assertTrue(plan.goals().size() >= 10);
        assertEquals("Ц1", plan.goals().getFirst().id());
        assertTrue(plan.goals().getLast().accepted().contains("equipment_weapon_laser_pr-l"));
        Set<String> ids = new java.util.HashSet<>();
        plan.goals().forEach(g -> assertTrue(ids.add(g.id()), "unique " + g.id()));
    }
}
