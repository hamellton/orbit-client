package orbit.config;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a recipe of the assembly costs, as the client knows it (its {@code ItemDataProvider}: the recipe's first
 * {@code RecipeCosts}, bundled as {@code orbit/config/recipes.json}): the game's packets tell only whether a recipe is
 * free, not what it takes — a start without the resources is ignored in silence (live 2026-11-16 on gb2: the Eternal CPU
 * started three times, nothing came).
 */
public final class RecipeCosts {

    /** One part of the price: an item ({@code currency_credits}, {@code currency_uridium}, a resource…) and how many. */
    public record Part(String item, long amount) {
    }

    private static volatile Map<String, List<Part>> costs;

    private RecipeCosts() {
    }

    /** The price of one batch of {@code recipe}, empty when not known. */
    public static List<Part> of(String recipe) {
        return all().getOrDefault(recipe, List.of());
    }

    private static Map<String, List<Part>> all() {
        if (costs == null) {
            Map<String, List<Part>> m = new HashMap<>();
            try (InputStream in = RecipeCosts.class.getResourceAsStream("/orbit/config/recipes.json")) {
                if (in != null) {
                    JsonNode root = Json.MAPPER.readTree(in);
                    root.path("recipes").properties().forEach(e -> {
                        List<Part> parts = new ArrayList<>();
                        e.getValue().path("costs").forEach(p -> parts.add(new Part(p.path("item").asText(),
                                p.path("amount").asLong())));
                        m.put(e.getKey(), List.copyOf(parts));
                    });
                }
            } catch (IOException e) {
                // no prices: every start is tried
            }
            costs = Map.copyOf(m);
        }
        return costs;
    }
}
