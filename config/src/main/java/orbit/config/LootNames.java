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
            Map.entry("PIRATE_BOOTY_GOLD", "пиратский ящик (жёлтый, золотой)"),
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

    private static final String MIMESIS = "MIMESIS_AI_";

    /** {@code MINI_BOSS} → «Mini Boss». */
    private static String title(String words) {
        StringBuilder sb = new StringBuilder();
        for (String w : words.toLowerCase(Locale.ROOT).split("_")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    /**
     * The name a picked box was counted under before its type had a name of its own ({@code "mimesis ai medic box"}
     * from {@code MIMESIS_AI_MEDIC_BOX}) → the name it has now; other names as they are.
     */
    public static String renamed(String counted) {
        if (counted != null && counted.startsWith("mimesis ai ") && counted.endsWith(" box")) {
            return of(counted.toUpperCase(Locale.ROOT).replace(' ', '_'));
        }
        return counted;
    }

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

    /** The Russian name of a box or resource type ({@code SCRAPIUM} → scrapium), or {@code null} when none is known. */
    public static String known(String key) {
        return key == null ? null : BOXES.get(key.toUpperCase(Locale.ROOT));
    }

    /** The Russian name (palladium for {@code ore_8}, bonus box for {@code BONUS_BOX}); unknown types as they are. */
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
        if (known == null && key.startsWith(MIMESIS) && key.endsWith("_BOX") && key.length() > MIMESIS.length() + 4) {
            // the escort's boxes by the NPC that left them: MIMESIS_AI_MINI_BOSS_BOX → «ящик Mimesis: Mini Boss»
            known = "ящик Mimesis: " + title(key.substring(MIMESIS.length(), key.length() - 4));
        }
        if (known == null && BoxCatalog.of(key) != null) {
            known = BoxCatalog.of(key).name();
        }
        return known != null ? known : key.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
