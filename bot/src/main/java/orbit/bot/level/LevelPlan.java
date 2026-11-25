package orbit.bot.level;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The leveling plan (docs/LEVELING.md): sub-goals in order towards the Prometheus lasers, each with a check of «done» and
 * how to get it. Bundled read-only as {@link #RESOURCE}; the bot works on the first goal not done.
 *
 * @param reserveCredits credits never spent on the plan (ammunition, repairs)
 * @param reserveUridium uridium never spent on the plan
 * @param fit            per ship slotset ({@code lasers}, {@code generators}) the items it takes, best first; both configs
 *                       get the best (a user's choice: lasers and engines in configs 1 and 2, shields go in the drones)
 */
public record LevelPlan(int version, long reserveCredits, long reserveUridium, List<Goal> goals,
                        Map<String, List<String>> fit) {

    public static final String RESOURCE = "/orbit/bot/level/plan.json";

    public LevelPlan {
        goals = List.copyOf(goals);
        fit = fit == null ? Map.of() : Map.copyOf(fit);
    }

    /** How a goal is checked. */
    public enum Kind {
        /** every slot of {@code slotset} in each of {@code configs} holds one of {@code accepted} */
        FILL,
        /** the account owns one of {@code accepted} ships */
        SHIP,
        /** the pilot's level is at least {@code min} */
        LEVEL,
        /** the active ship has at least {@code min} drones (what they fit is not read yet) */
        DRONES,
        /** the bot cannot check it yet ({@code why}); the advisor stops there */
        UNKNOWN
    }

    /** A purchase in the shop: the item, its price per unit and whether it costs uridium. */
    public record Buy(String item, long price, boolean uridium) {
    }

    /**
     * One sub-goal.
     *
     * @param auction loot ids to bid on for this goal (the hourly auction; not done yet)
     * @param farm    what earns the money for it (a note for the log until configs are switched by goal)
     * @param side    a goal on the side: reported beside the current one, taken when it comes, never blocking the chain
     */
    public record Goal(String id, String title, Kind kind, String slotset, List<Integer> configs, Set<String> accepted,
                       int min, String why, Buy buy, List<String> auction, String farm, boolean side) {

        public Goal {
            configs = configs == null || configs.isEmpty() ? List.of(1, 2) : List.copyOf(configs);
            accepted = accepted == null ? Set.of() : java.util.Collections.unmodifiableSet(new LinkedHashSet<>(accepted));
            auction = auction == null ? List.of() : List.copyOf(auction);
            why = why == null ? "" : why;
            farm = farm == null ? "" : farm;
        }
    }

    /** The bundled plan. */
    public static LevelPlan bundled() {
        try (InputStream in = LevelPlan.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("нет плана прокачки " + RESOURCE);
            }
            return read(orbit.config.Json.MAPPER.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("план прокачки не читается: " + e.getMessage(), e);
        }
    }

    public static LevelPlan read(JsonNode root) {
        List<Goal> goals = new ArrayList<>();
        for (JsonNode g : root.path("goals")) {
            JsonNode check = g.path("check");
            Kind kind = Kind.valueOf(check.path("kind").asText("UNKNOWN").toUpperCase(java.util.Locale.ROOT));
            List<Integer> configs = new ArrayList<>();
            check.path("configs").forEach(c -> configs.add(c.asInt()));
            Set<String> accepted = new LinkedHashSet<>();
            check.path("accepted").forEach(a -> accepted.add(a.asText()));
            JsonNode b = g.path("buy");
            Buy buy = b.isObject() ? new Buy(b.path("item").asText(), b.path("price").asLong(),
                    "uridium".equals(b.path("currency").asText())) : null;
            List<String> auction = new ArrayList<>();
            g.path("auction").forEach(a -> auction.add(a.asText()));
            goals.add(new Goal(g.path("id").asText(), g.path("title").asText(), kind, check.path("slotset").asText(""),
                    configs, accepted, check.path("min").asInt(), check.path("why").asText(""), buy, auction,
                    g.path("farm").asText(""), g.path("side").asBoolean(false)));
        }
        Map<String, List<String>> fit = new java.util.LinkedHashMap<>();
        root.path("fit").properties().forEach(e -> {
            List<String> order = new ArrayList<>();
            e.getValue().forEach(x -> order.add(x.asText()));
            fit.put(e.getKey(), List.copyOf(order));
        });
        return new LevelPlan(root.path("version").asInt(1), root.path("reserveCredits").asLong(),
                root.path("reserveUridium").asLong(), goals, fit);
    }
}
