package orbit.config;

import java.util.Locale;
import java.util.Map;

/**
 * Russian names of what the bot picks up, for the log, the status and the session's results: the config's box
 * types ({@code BONUS_BOX}…) and ore as {@code ore_<n>} (DarkBot's names: 5-3's palladium is {@code ore_8}).
 */
public final class LootNames {

    private LootNames() {
    }

    private static final Map<String, String> BOXES = Map.ofEntries(
            Map.entry("BONUS_BOX", "бонус-бокс"),
            Map.entry("BONUS_BOX_RED", "красный бонус-бокс"),
            Map.entry("FROM_SHIP", "груз с корабля"),
            Map.entry("FROM_SHIP_BLOCKED", "чужой груз с корабля"),
            Map.entry("PIRATE_BOOTY", "пиратский ящик (зелёный)"),
            Map.entry("PIRATE_BOOTY_BLUE", "пиратский ящик (синий)"),
            Map.entry("PIRATE_BOOTY_RED", "пиратский ящик (красный)"),
            Map.entry("PIRATE_BOOTY_GOLD", "пиратский ящик (золотой)"),
            Map.entry("BLACK_BOOTY_BOX", "чёрный бути-ящик"),
            Map.entry("ASTRAL_BOOTY_BOX", "астральный бути-ящик"),
            Map.entry("ASTRAL_PRIME_BOOTY_BOX", "астральный прайм бути-ящик"),
            Map.entry("EMPYRIAN_BOOTY_BOX", "эмпирейский бути-ящик"),
            Map.entry("MASQUE_BOOTY_BOX", "бути-ящик маскарада"),
            Map.entry("PERSEUS_BLESSING_BOOTY_BOX", "бути-ящик благословения Персея"),
            Map.entry("AURUS_BOX", "ящик Аурус"),
            Map.entry("BATTLEPASS", "ящик боевого пропуска"),
            Map.entry("BIFENON", "бифенон"),
            Map.entry("BIFENON_2", "бифенон"),
            Map.entry("CANDY_CARGO", "конфетный груз"),
            Map.entry("COSMIC_CACHE", "космический тайник"),
            Map.entry("MINI_PUMPKIN", "мини-тыква"),
            Map.entry("POISON_PUSAT_BOX_BLACK", "чёрный ящик Pusat"),
            Map.entry("LUMINIUM", "люминиум"),
            Map.entry("LUMINIUM_2", "люминиум"),
            Map.entry("MUCOSUM", "мукозум"),
            Map.entry("BOLTRUM", "болтрум"),
            Map.entry("SCRAPIUM", "скрапиум"));

    /** The ore's name by the game's number. */
    public static String ore(int type) {
        return switch (type) {
            case 0 -> "прометий";
            case 1 -> "эндуриум";
            case 2 -> "тербий";
            case 3 -> "ксеномит";
            case 4 -> "прометид";
            case 5 -> "дюраниум";
            case 6 -> "промериум";
            case 7 -> "сепром";
            case 8 -> "палладий";
            case 28 -> "осмий";
            default -> "руда №" + type;
        };
    }

    /** «палладий» for {@code ore_8}, «бонус-бокс» for {@code BONUS_BOX}; unknown types readable as they are. */
    public static String of(String key) {
        if (key == null || key.isBlank()) {
            return "ящик";
        }
        if (key.startsWith("ore_")) {
            try {
                return ore(Integer.parseInt(key.substring(4)));
            } catch (NumberFormatException e) {
                return key;
            }
        }
        String known = BOXES.get(key);
        return known != null ? known : key.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
