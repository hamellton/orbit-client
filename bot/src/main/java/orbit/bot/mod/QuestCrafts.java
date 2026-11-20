package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.config.RecipeCosts;
import orbit.world.Quests;
import orbit.world.WorldSnapshot;

/**
 * "Collect N crafted" ({@link Quests#COLLECT_BUILT_RECIPE}, Season Pass 700030 "25", 700031 "50"; crafting by
 * recipes as AstraBot's season module's «Item craft»): while such a condition is
 * open the assembly builds one batch after another of a cheap recipe, and {@code CraftWatch} collects each — a collect is
 * what counts. The recipe the condition names, else the first of {@code modules.quests.craftRecipes} (default
 * {@link #DEFAULTS}) that is free in the assembly and whose price the account has (uridium too: spending it is fine);
 * never one whose price is not known. On with the Quests module or with quests alongside ({@link QuestsAlongside});
 * {@code modules.quests.craft} false turns it off.
 */
public final class QuestCrafts {

    /**
     * In this order (credits, Seprom or RSB preferred): recipe → the item it makes (for its
     * name). The Precision Targeter is the client's only recipe for credits alone (20 000); then RSB-75 and Seprom as
     * AstraBot crafts them (the friend's VPS logs: {@code seprom-blacklight}, {@code rsb75-blacklight} — uridium, rinusk,
     * Blacklight trace).
     */
    static final Map<String, String> DEFAULTS = new java.util.LinkedHashMap<>();

    static {
        DEFAULTS.put("recipe_blueprint-tech-precisiontargeter", "tech_precision-targeter");
        DEFAULTS.put("recipe_blueprint-rsb75-blacklight", "ammunition_laser_rsb-75");
        DEFAULTS.put("recipe_blueprint-seprom-blacklight", "resource_ore_seprom");
    }

    private QuestCrafts() {
    }

    /** Crafting for quests is on for this config: the quest module or quests alongside, {@code craft} not off. */
    public static boolean on(BotConfig c) {
        JsonNode s = settings(c);
        return (c.module() == BotConfig.Module.QUESTS || QuestsAlongside.on(c)) && s.path("craft").asBoolean(true);
    }

    /** The batch to build now for an open «collect built recipes» condition, {@code null} none. */
    public static BotConfig.CraftRule craftRule(BotConfig c, WorldSnapshot w) {
        if (!on(c)) {
            return null;
        }
        for (Quests.Quest q : w.quests().active().values()) {
            for (Quests.Condition cond : q.all()) {
                if (cond.type() == Quests.COLLECT_BUILT_RECIPE && !cond.completed()) {
                    String r = recipe(c, w, cond);
                    if (r != null) {
                        return new BotConfig.CraftRule(r, DEFAULTS.get(r), 0, 1);
                    }
                }
            }
        }
        return null;
    }

    /**
     * The recipe for the condition: one already building or ready first (it is collected), else the first one free in
     * the assembly with its price at hand; {@code null} when none can be built now.
     */
    static String recipe(BotConfig c, WorldSnapshot w, Quests.Condition cond) {
        Map<String, WorldSnapshot.Recipe> recipes = w.inventory().recipes();
        List<String> wanted = candidates(c, cond);
        for (String r : wanted) {
            WorldSnapshot.Recipe rec = recipes.get(r);
            if (rec != null && (rec.state() == WorldSnapshot.RecipeState.BUILDING
                    || rec.state() == WorldSnapshot.RecipeState.READY) && allowed(r)) {
                return r;
            }
        }
        for (String r : wanted) {
            WorldSnapshot.Recipe rec = recipes.get(r);
            if (rec != null && rec.state() == WorldSnapshot.RecipeState.AVAILABLE && allowed(r)
                    && orbit.bot.run.CraftWatch.lacking(w, r, 1) == null && known(w, r)) {
                return r;
            }
        }
        return null;
    }

    /** The condition's own recipe, else the config's list, else the defaults. */
    static List<String> candidates(BotConfig c, Quests.Condition cond) {
        if (!cond.matches().isEmpty() && !cond.matches().getFirst().isBlank()) {
            String m = cond.matches().getFirst().strip().toLowerCase(Locale.ROOT);
            return List.of(m.startsWith("recipe_") ? m : "recipe_blueprint-" + m);
        }
        JsonNode list = settings(c).path("craftRecipes");
        if (list.isArray() && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            list.forEach(n -> out.add(n.asText().strip()));
            return out;
        }
        return List.copyOf(DEFAULTS.keySet());
    }

    /** A price known. */
    static boolean allowed(String recipe) {
        List<RecipeCosts.Part> parts = RecipeCosts.of(recipe);
        return !parts.isEmpty();
    }

    /** Every resource of the price counted by the game (an untold count is no proof one has it). */
    private static boolean known(WorldSnapshot w, String recipe) {
        for (RecipeCosts.Part p : RecipeCosts.of(recipe)) {
            if ("currency_credits".equals(p.item()) || "currency_uridium".equals(p.item())) {
                if (("currency_credits".equals(p.item()) ? w.hero().credits() : w.hero().uridium()) < 0) {
                    return false;
                }
                continue;
            }
            if (!w.inventory().resources().containsKey(p.item())) {
                return false;
            }
        }
        return true;
    }

    /** Whether such a condition can be worked on now (a batch building, ready, or one can be started). */
    public static boolean possible(BotConfig c, WorldSnapshot w, Quests.Condition cond) {
        return on(c) && recipe(c, w, cond) != null;
    }

    private static JsonNode settings(BotConfig c) {
        JsonNode s = c.modules().get(BotConfig.Module.QUESTS.key());
        return s == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : s;
    }
}
