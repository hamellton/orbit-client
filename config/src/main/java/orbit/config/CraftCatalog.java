package orbit.config;

import java.util.List;

/**
 * Assembly recipes seen in the game (docs/recon/ASSEMBLY.md): the recipe's loot id, the item it gives (what the craft
 * rule counts) and a name for the config window. Any other recipe id can be typed in the window.
 */
public final class CraftCatalog {

    public record Recipe(String id, String item, String label) {
    }

    public static final List<Recipe> RECIPES = List.of(
            new Recipe("recipe_blueprint-cloakcpu-blacklight", "equipment_extra_cpu_cl04k-xl",
                    "Процессор маскировки CL04K-XL (BL)"),
            new Recipe("recipe_blueprint-rsb75-blacklight", "ammunition_laser_rsb-75", "RSB-75 (BL)"),
            new Recipe("recipe_blueprint-a-bl-laser-ammo", "ammunition_laser_a-bl", "A-BL"),
            new Recipe("recipe_blueprint-seprom-blacklight", "resource_ore_seprom", "Сепром (BL)"),
            new Recipe("recipe_blueprint-eternal-cpu", "", "Процессор ЕБГ (Eternal CPU)"),
            new Recipe("recipe_blueprint-prometheus-laser", "equipment_weapon_laser_pr-l", "Лазер «Прометей»"));

    private CraftCatalog() {
    }

    /** The recipe's name for the window, else its id. */
    public static String label(String id) {
        return RECIPES.stream().filter(r -> r.id().equals(id)).map(Recipe::label).findFirst().orElse(id);
    }

    /** The recipe's id from a name in the window (or the id itself typed in). */
    public static String id(String labelOrId) {
        return RECIPES.stream().filter(r -> r.label().equals(labelOrId)).map(Recipe::id).findFirst().orElse(labelOrId);
    }

    /** The item a known recipe gives, {@code ""} when not known. */
    public static String item(String id) {
        return RECIPES.stream().filter(r -> r.id().equals(id)).map(Recipe::item).findFirst().orElse("");
    }
}
