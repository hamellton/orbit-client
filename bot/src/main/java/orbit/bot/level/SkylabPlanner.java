package orbit.bot.level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Skylab's next upgrade by the plan's stages (docs/SKYLAB-LEVELING.md, stages A–D with the user's consent 2026-11-25):
 * a pure function — it starts nothing.
 *
 * <ul>
 *   <li>one upgrade at a time: one running — wait;</li>
 *   <li>the first stage with a module below its level; in it the lowest module first, the stage's order among equals
 *       (evenly, by turns);</li>
 *   <li>no module above the base module: the base first;</li>
 *   <li>credits — what is left after the reserve and the equipment (the caller's {@code credits}); the raw ores — the
 *       Skylab's own storage;</li>
 *   <li>urgent (uridium) only for {@link #URGENT} and only with more than {@link #URIDIUM_KEEP} uridium left after it.</li>
 * </ul>
 */
public final class SkylabPlanner {

    public static final Set<String> URGENT = Set.of("promeriumRefinery", "sepromRefinery");
    public static final long URIDIUM_KEEP = 800_000;

    private SkylabPlanner() {
    }

    /** The next level's price: credits, the three raw ores, minutes, the urgent one's uridium. */
    public record Price(long credits, long prometium, long endurium, long terbium, int minutes, long instantUridium) {
    }

    /** What the Skylab is: levels, prices, upgrades running, the storage's raw ores by name. */
    public record State(Map<String, Integer> levels, Map<String, Price> prices, Set<String> running, Map<String, Long> storage) {
    }

    /** One stage of the plan: module → the level to reach. */
    public record Stage(String name, Map<String, Integer> targets) {
    }

    /** The decision: a module to upgrade ({@code null}: wait), urgent or not, and why. */
    public record Decision(String module, boolean instant, String reason) {
    }

    public static Decision next(State s, List<Stage> stages, long credits, long uridium) {
        if (!s.running().isEmpty()) {
            return new Decision(null, false, "идёт улучшение: " + String.join(", ", s.running()));
        }
        int base = s.levels().getOrDefault("baseModule", 0);
        for (Stage st : stages) {
            List<String> below = new ArrayList<>();
            st.targets().forEach((m, target) -> {
                if (s.levels().getOrDefault(m, 0) < target) {
                    below.add(m);
                }
            });
            if (below.isEmpty()) {
                continue;
            }
            List<String> order = new ArrayList<>(st.targets().keySet());
            below.sort(Comparator.<String>comparingInt(m -> s.levels().getOrDefault(m, 0)).thenComparingInt(order::indexOf));
            String m = below.getFirst();
            if (!m.equals("baseModule") && s.levels().getOrDefault(m, 0) + 1 > base) {
                m = "baseModule"; // nothing above the base module
            }
            Price p = s.prices().get(m);
            if (p == null) {
                return new Decision(null, false, "этап " + st.name() + ": у «" + m + "» нет цены на странице");
            }
            long pr = s.storage().getOrDefault("prometium", 0L);
            long en = s.storage().getOrDefault("endurium", 0L);
            long te = s.storage().getOrDefault("terbium", 0L);
            if (pr < p.prometium() || en < p.endurium() || te < p.terbium()) {
                return new Decision(null, false, "этап " + st.name() + ": на «" + m + "» не хватает руды на складе");
            }
            if (credits < p.credits()) {
                return new Decision(null, false, "этап " + st.name() + ": на «" + m + "» нужно " + p.credits()
                        + " кредитов, свободно " + Math.max(0, credits) + " (после резерва и снаряжения)");
            }
            boolean instant = URGENT.contains(m) && p.instantUridium() > 0 && uridium - p.instantUridium() > URIDIUM_KEEP;
            return new Decision(m, instant, "этап " + st.name() + ": «" + m + "» " + s.levels().getOrDefault(m, 0) + " → "
                    + (s.levels().getOrDefault(m, 0) + 1) + " за " + p.credits() + " кредитов"
                    + (instant ? " и " + p.instantUridium() + " уридия срочно" : ", " + p.minutes() + " мин"));
        }
        return new Decision(null, false, "все этапы плана Скайлаба пройдены");
    }

    /** The plan's stages from {@code plan.json} → {@code skylab}: [{name, targets: {module: level}}]. */
    public static List<Stage> stages(com.fasterxml.jackson.databind.JsonNode root) {
        List<Stage> out = new ArrayList<>();
        for (var st : root.path("skylab")) {
            Map<String, Integer> t = new java.util.LinkedHashMap<>();
            st.path("targets").properties().forEach(e -> t.put(e.getKey(), e.getValue().asInt()));
            out.add(new Stage(st.path("name").asText(), t));
        }
        return out;
    }
}
