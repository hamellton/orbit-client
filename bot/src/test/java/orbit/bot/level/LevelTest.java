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
    static final String SLL2 = "equipment_weapon_laser_sll-02";
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
              "equipment_weapon_laser_sll-02","ammunition_laser_lcb-10"]},
             "ret":{"items":[{"I":"11","L":0,"EQH":"501"},{"I":"21","L":1,"EQH":"501"},{"I":"22","L":1,"EQH":"501"},
               {"I":"31","L":2,"EQH":null},{"I":"41","L":3,"EQH":null,"Q":5000}],
              "hangars":{"0":{"hangarID":"501","general":{"ship":{"L":0},"drones":[]},"config":{
                "1":{"ship":{"EQ":{"lasers":["11"],"generators":["21","22"]}},"drones":{}},
                "2":{"ship":{"EQ":{"lasers":["11"],"generators":["21","22"]}},"drones":{}}}}}}}}
            """;

    static JsonNode json(String s) throws Exception {
        return orbit.config.Json.MAPPER.readTree(s);
    }

    /**
     * The bundled plan without its credit goal «X formation» (Ц0, added 2026-11-26) and the P.E.T./Moth goals: the chain
     * tests below are about the lasers, the ship and the drones as they were.
     */
    static LevelPlan chainPlan() {
        LevelPlan p = LevelPlan.bundled();
        return new LevelPlan(p.version(), p.reserveCredits(), p.reserveUridium(), p.goals().stream()
                .filter(g -> !java.util.Set.of("Ц0", "Ц3а", "Ц3б", "Ц3в").contains(g.id())).toList(), p.fit(), p.ships());
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
        assertEquals(Map.of(SLL2, 1, "ammunition_laser_lcb-10", 5000), hangar.spare(), "ammunition by its amount");
        assertEquals(0, hangar.drones());
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
        LevelPlan plan = chainPlan();
        // 4 slots, one spare scalpel II owned: 3 to buy; 30 000 reserve, 300 000 each → 400 000 buys one
        Advisor.Advice a = Advisor.advise(newAccount(400_000), plan);
        assertEquals("Ц1", a.goal().id());
        assertEquals(3, a.missing());
        assertEquals(1, a.refit(), "the spare scalpel goes in");
        assertNotNull(a.purchase());
        assertEquals(SLL2, a.purchase().item());
        assertEquals(1, a.purchase().amount());
        Advisor.Advice poor = Advisor.advise(newAccount(310_000), plan);
        assertNull(poor.purchase());
        assertTrue(poor.text().contains("копить ещё 20000"), poor.text());
    }

    @Test
    void doneGoalsAreSkippedAndUnknownStops() throws Exception {
        LevelPlan plan = chainPlan();
        AccountState s = newAccount(0);
        AccountState lasersDone = new AccountState(s.level(), 0, 0, s.activeHangar(), "ship_nostromo",
                Set.of("ship_nostromo"), Map.of("lasers", 2, "generators", 2),
                Map.of(1, Map.of("lasers", List.of(SLL2, SLL2), "generators", List.of(G79, G79)),
                        2, Map.of("lasers", List.of(SLL2, SLL2), "generators", List.of(G79, G79))), Map.of(), 0);
        Advisor.Advice a = Advisor.advise(lasersDone, plan);
        assertEquals(List.of("Ц1", "Ц2", "Ц3"), a.done());
        assertEquals("Ц4", a.goal().id());
        assertEquals(6, a.missing(), "Flax up to six");
        assertTrue(a.text().contains("дронов 0 из 6"), a.text());
        AccountState oneDrone = new AccountState(s.level(), 0, 0, s.activeHangar(), "ship_nostromo", Set.of("ship_nostromo"),
                lasersDone.slots(), lasersDone.fitted(), Map.of(), 6);
        Advisor.Advice next = Advisor.advise(oneDrone, plan);
        assertEquals("Ц5", next.goal().id(), "Goliath next");
        AccountState goliath = new AccountState(s.level(), 0, 0, s.activeHangar(), "ship_goliath", Set.of("ship_goliath"),
                lasersDone.slots(), lasersDone.fitted(), Map.of(), 6);
        Advisor.Advice drones = Advisor.advise(goliath, plan);
        assertEquals("Ц6", drones.goal().id(), "eight drones next");
        assertTrue(drones.text().contains("дронов 6 из 8"), drones.text());
        AccountState bastion = new AccountState(30, 0, 0, s.activeHangar(), "ship_g-bastion", Set.of("ship_g-bastion"),
                Map.of("lasers", 2, "generators", 2),
                Map.of(1, Map.of("lasers", List.of("equipment_weapon_laser_lf-3", "equipment_weapon_laser_lf-3"),
                                "generators", List.of(G79, G79)),
                        2, Map.of("lasers", List.of("equipment_weapon_laser_lf-3", "equipment_weapon_laser_lf-3"),
                                "generators", List.of(G79, G79))), Map.of(), 8);
        Advisor.Advice aegis = Advisor.advise(bastion, plan);
        assertTrue(aegis.done().contains("Ц5"), "a Goliath design is a Goliath");
        assertEquals("Ц8", aegis.goal().id(), "Aegis after LF-3");
        assertTrue(aegis.text().contains("ship_a-elite"), aegis.text());
        assertTrue(aegis.text().contains("попутно: П1"), "side goals reported: " + aegis.text());
        AccountState withAegis = new AccountState(30, 0, 0, s.activeHangar(), "ship_g-bastion",
                Set.of("ship_g-bastion", "ship_aegis"), bastion.slots(), bastion.fitted(), Map.of(), 8);
        Advisor.Advice zeta = Advisor.advise(withAegis, plan);
        assertEquals("Ц10", zeta.goal().id(), "level 30 passes the palladium goal");
        assertTrue(zeta.text().contains("не умею проверить"), "an unknown check stops: " + zeta.text());
    }

    @Test
    void unknownEquipmentIsNotDone() throws Exception {
        var list = SiteHangars.list(json(LIST));
        Advisor.Advice a = Advisor.advise(SiteHangars.state(list, null, 1, 0, 0), chainPlan());
        assertEquals("Ц1", a.goal().id());
        assertFalse(a.text().contains("купил бы"), a.text());
    }

    @Test
    void theBundledPlanReads() {
        LevelPlan plan = LevelPlan.bundled();
        assertTrue(plan.goals().size() >= 10);
        assertEquals("Ц0", plan.goals().getFirst().id(), "the formation X first (a user's choice 2026-11-26)");
        LevelPlan.Goal last = plan.goals().stream().filter(g -> !g.side()).reduce((x, y) -> y).orElseThrow();
        assertTrue(last.accepted().contains("equipment_weapon_laser_pr-l"), "the chain ends with the Prometheus lasers");
        Set<String> ids = new java.util.HashSet<>();
        plan.goals().forEach(g -> assertTrue(ids.add(g.id()), "unique " + g.id()));
    }

    @Test
    void uridiumBuysTheNextGoalWhileTheFirstWaitsForCredits() throws Exception {
        LevelPlan plan = chainPlan();
        var list = SiteHangars.list(json(LIST));
        var hangar = SiteHangars.hangar(json(HANGAR), list.activeHangar());
        // 50 000 credits (no scalpel), 4 500 uridium: two G3N-7900 at 2 000 (Ц3: 6 slots, 2 fitted, 4 to get)
        AccountState s = SiteHangars.state(list, hangar, 3, 50_000, 4_500);
        // Ц2 (Nostromo) would come before Ц3: give a Goliath so the chain's credit goal is Ц1 and Ц3 is the uridium one
        AccountState withGoliath = new AccountState(s.level(), s.credits(), s.uridium(), s.activeHangar(), s.activeShip(),
                Set.of("ship_liberator", "ship_g-bastion"), s.slots(), s.fitted(), s.spare(), 6);
        Advisor.Advice a = Advisor.advise(withGoliath, plan);
        assertEquals("Ц1", a.goal().id());
        assertNull(a.purchase(), "no scalpel for 50 000");
        assertNotNull(a.other(), a.text());
        assertEquals(G79, a.other().item());
        assertEquals(2, a.other().amount());
        assertTrue(a.other().uridium());
        assertNull(Advisor.advise(SiteHangars.state(list, hangar, 3, 50_000, 1_000), plan).other(), "not enough uridium");
    }
}
