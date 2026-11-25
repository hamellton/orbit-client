package orbit.bot.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which items go where in configs 1 and 2 (invented hangar views). */
class FitTest {

    static final String LF1 = "equipment_weapon_laser_lf-1";
    static final String SLL2 = "equipment_weapon_laser_sll-02";
    static final String LF3 = "equipment_weapon_laser_lf-3";
    static final String G79 = "equipment_generator_speed_g3n-7900";
    static final String G69 = "equipment_generator_speed_g3n-6900";
    static final String SHIELD = "equipment_generator_shield_sg3n-a02";

    static SiteHangars.HangarInfo view(List<SiteHangars.Owned> items, Map<String, List<String>> c1, Map<String, List<String>> c2) {
        return new SiteHangars.HangarInfo(Map.of(), Map.of(), 0, "", items, Map.of(1, c1, 2, c2));
    }

    static SiteHangars.Owned own(String id, String loot, int hangar) {
        return new SiteHangars.Owned(id, loot, hangar);
    }

    @Test
    void freeSlotsTakeTheBestSparesInBothConfigs() {
        var h = view(List.of(own("11", LF1, 7), own("12", SLL2, 0), own("13", LF3, 0), own("99", SLL2, 8)),
                Map.of("lasers", List.of("11")), Map.of("lasers", List.of()));
        List<FitPlanner.Move> m = FitPlanner.plan(h, 7, Map.of("lasers", 2), LevelPlan.bundled());
        // the best two owned here: LF-3 and the scalpel (the one fitted in hangar 8 is not ours to take)
        assertEquals(List.of("снять equipment_weapon_laser_lf-1 (11) из конфиг 1 lasers",
                "поставить equipment_weapon_laser_lf-3 (13) в конфиг 1 lasers",
                "поставить equipment_weapon_laser_sll-02 (12) в конфиг 1 lasers",
                "поставить equipment_weapon_laser_lf-3 (13) в конфиг 2 lasers",
                "поставить equipment_weapon_laser_sll-02 (12) в конфиг 2 lasers"),
                m.stream().map(FitPlanner.Move::describe).toList());
    }

    @Test
    void aFreeSlotNeedsNothingTakenOut() {
        var h = view(List.of(own("11", LF1, 7), own("12", SLL2, 0)), Map.of("lasers", List.of("11")),
                Map.of("lasers", List.of("11")));
        List<FitPlanner.Move> m = FitPlanner.plan(h, 7, Map.of("lasers", 4), LevelPlan.bundled());
        assertEquals(2, m.size(), m.toString());
        assertTrue(m.stream().allMatch(FitPlanner.Move::toShip), "four slots, two items: both in, nothing out");
    }

    @Test
    void enginesReplaceAShieldAndWorseEngines() {
        var h = view(List.of(own("21", SHIELD, 7), own("22", G69, 7), own("23", G79, 0), own("24", G79, 0)),
                Map.of("generators", List.of("21", "22")), Map.of("generators", List.of("21", "22")));
        List<FitPlanner.Move> m = FitPlanner.plan(h, 7, Map.of("generators", 2), LevelPlan.bundled());
        List<String> c1 = m.stream().filter(x -> x.config() == 1).map(FitPlanner.Move::describe).toList();
        assertEquals(List.of("снять equipment_generator_shield_sg3n-a02 (21) из конфиг 1 generators",
                "снять equipment_generator_speed_g3n-6900 (22) из конфиг 1 generators",
                "поставить equipment_generator_speed_g3n-7900 (23) в конфиг 1 generators",
                "поставить equipment_generator_speed_g3n-7900 (24) в конфиг 1 generators"), c1);
    }

    @Test
    void allInPlaceMeansNoMoves() {
        var h = view(List.of(own("13", LF3, 7)), Map.of("lasers", List.of("13")), Map.of("lasers", List.of("13")));
        assertTrue(FitPlanner.plan(h, 7, Map.of("lasers", 1), LevelPlan.bundled()).isEmpty());
    }

    @Test
    void theRealNewAccountIsInPlace() throws Exception {
        String hangar = """
                {"data":{"map":{"lootIds":["equipment_weapon_laser_lf-1","equipment_generator_speed_g3n-7900"]},
                 "ret":{"items":[{"I":"1","L":0,"EQH":"5"},{"I":"2","L":1,"EQH":"5"},{"I":"3","L":1,"EQH":"5"}],
                  "hangars":{"0":{"hangarID":"5","general":{"drones":[]},"config":{
                    "1":{"ship":{"EQ":{"lasers":["1"],"generators":["2","3"]}}},
                    "2":{"ship":{"EQ":{"lasers":["1"],"generators":["2","3"]}}}}}}}}}
                """;
        var h = SiteHangars.hangar(orbit.config.Json.MAPPER.readTree(hangar), 5);
        assertEquals(List.of(new SiteHangars.Owned("1", LF1, 5), new SiteHangars.Owned("2", G79, 5),
                new SiteHangars.Owned("3", G79, 5)), h.items());
        assertTrue(FitPlanner.plan(h, 5, Map.of("lasers", 4, "generators", 6), LevelPlan.bundled()).isEmpty(),
                "a Liberator with one LF-1 and two engines has nothing better to fit");
    }
}
