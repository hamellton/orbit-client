package orbit.bot.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The Skylab's next upgrade by the plan (the new account's page of 2026-11-25 as the starting point). */
class SkylabPlannerTest {

    static final List<String> MAIN = List.of("baseModule", "solarModule", "storageModule", "terbiumCollector",
            "enduriumCollector", "prometiumCollector");

    static SkylabPlanner.State fresh(Map<String, Integer> levels, Set<String> running) {
        Map<String, SkylabPlanner.Price> prices = new HashMap<>();
        for (String m : List.of("baseModule", "solarModule", "storageModule")) {
            prices.put(m, new SkylabPlanner.Price(16_352, 707, 707, 707, 138, 1_487));
        }
        for (String m : List.of("terbiumCollector", "enduriumCollector", "prometiumCollector")) {
            prices.put(m, new SkylabPlanner.Price(13_081, 566, 566, 566, 138, 1_189));
        }
        prices.put("promeriumRefinery", new SkylabPlanner.Price(13_750, 125, 125, 125, 180, 1_250));
        return new SkylabPlanner.State(levels, prices, running,
                Map.of("prometium", 105_600L, "endurium", 105_600L, "terbium", 105_600L));
    }

    static Map<String, Integer> all(int level) {
        Map<String, Integer> m = new HashMap<>();
        MAIN.forEach(x -> m.put(x, level));
        return m;
    }

    static List<SkylabPlanner.Stage> stages() throws Exception {
        try (var in = LevelPlan.class.getResourceAsStream(LevelPlan.RESOURCE)) {
            return SkylabPlanner.stages(orbit.config.Json.MAPPER.readTree(in));
        }
    }

    @Test
    void theBaseFirstThenTheOthersByTurns() throws Exception {
        SkylabPlanner.Decision d = SkylabPlanner.next(fresh(all(1), Set.of()), stages(), 100_000, 0);
        assertEquals("baseModule", d.module(), "all at 1: the base may not be passed — it goes first");
        assertFalse(d.instant());
        Map<String, Integer> l = all(1);
        l.put("baseModule", 2);
        assertEquals("solarModule", SkylabPlanner.next(fresh(l, Set.of()), stages(), 100_000, 0).module(), "then by the stage's order");
    }

    @Test
    void oneAtATimeAndNoMoneyNoUpgrade() throws Exception {
        assertNull(SkylabPlanner.next(fresh(all(1), Set.of("baseModule")), stages(), 100_000, 0).module());
        SkylabPlanner.Decision poor = SkylabPlanner.next(fresh(all(1), Set.of()), stages(), 10_000, 0);
        assertNull(poor.module());
        assertTrue(poor.reason().contains("нужно 16352"), poor.reason());
    }

    @Test
    void urgentOnlyForPromeriumWithPlentyOfUridium() throws Exception {
        Map<String, Integer> l = all(5);
        l.put("prometidRefinery", 3);
        l.put("duraniumRefinery", 3);
        l.put("xenoModule", 3);
        l.put("promeriumRefinery", 0);
        SkylabPlanner.Decision rich = SkylabPlanner.next(fresh(l, Set.of()), stages(), 100_000, 1_000_000);
        assertEquals("promeriumRefinery", rich.module());
        assertTrue(rich.instant(), "1 000 000 − 1 250 leaves more than 800 000");
        assertFalse(SkylabPlanner.next(fresh(l, Set.of()), stages(), 100_000, 800_500).instant(), "not enough left");
    }
}
