package orbit.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The boxes and resources the game shows on the map, by the type the config names them with (DarkBot's names): the
 * Russian name, the group, the wait DarkBot users set for them, and whether they can be picked up at all (the user's
 * list, 2026-11-12). {@code mine_3} and {@code firework} show in the resource list but are not collectable.
 */
public final class BoxCatalog {

    private BoxCatalog() {
    }

    /** One known type. {@code waitMs} 0 — no wait needed. */
    public record Box(String type, String name, String group, int waitMs, boolean collectable) {
    }

    public static final String PLAIN = "Обычные";
    public static final String BL = "Карты BL";
    public static final String WAIT = "С ожиданием";
    public static final String EVENTS = "События";
    public static final String NOT_BOXES = "Не собирать";
    public static final String UNKNOWN = "Неизвестно, что дают";

    private static final Map<String, Box> BOXES = new LinkedHashMap<>();

    private static void add(String type, String name, String group, int waitMs) {
        BOXES.put(type, new Box(type, name, group, waitMs, !NOT_BOXES.equals(group)));
    }

    static {
        add("ore_0", "прометий", PLAIN, 0);
        add("ore_1", "эндуриум", PLAIN, 0);
        add("ore_2", "тербий", PLAIN, 0);
        add("scrapium", "металлолом (скрапиум)", PLAIN, 0);
        add("SCRAPIUM", "металлолом (скрапиум)", PLAIN, 0);
        add("BOLTRUM", "плазмид (болтрум)", PLAIN, 0);
        add("MUCOSUM", "мукозум", PLAIN, 0);
        add("PRISMATIUM", "призматиум", PLAIN, 0);
        add("FROM_SHIP", "груз с корабля", PLAIN, 0);
        add("BIFENON_2", "бифенон с кубиконов", PLAIN, 0);
        add("BIFENON", "бифенон", PLAIN, 0);
        add("AURUS_BOX", "ящик Аурус", PLAIN, 0);
        add("BONUS_BOX", "бонус-бокс", PLAIN, 0);
        add("ALIEN_EGG", "бонус-бокс (яйцо)", PLAIN, 0);
        add("GIFT_BOXES", "бонус-бокс (подарок)", PLAIN, 0);
        add("UBERANNIHILATOR_BOX", "ящик Uber Annihilator", PLAIN, 0);
        add("UBERBARRACUDA_BOX", "ящик Uber Barracuda", PLAIN, 0);
        add("UBERBATTLERAY_BOX", "ящик Uber Battleray", PLAIN, 0);
        add("UBERINTERCEPTOR_BOX", "ящик Uber Interceptor", PLAIN, 0);
        add("UBERSABOTEUR_BOX", "ящик Uber Saboteur", PLAIN, 0);
        add("HYBRID_ALLOY_BOX", "ящик с Gygerthrall (гибридный сплав)", PLAIN, 0);
        add("INDOCTRINE_OIL_BOX", "ящик врат X-7 (масло индоктрина)", PLAIN, 0);
        add("SOLAR_CLASH", "ящик с инвоков и бегемота", BL, 0);
        add("ore_8", "палладий", WAIT, 750);
        add("PIRATE_BOOTY", "пиратский бути-ящик (зелёный)", WAIT, 5_500);
        add("PIRATE_BOOTY_RED", "пиратский бути-ящик (красный)", WAIT, 5_500);
        add("PIRATE_BOOTY_GOLD", "пиратский бути-ящик (золотой)", WAIT, 5_500);
        add("PIRATE_BOOTY_BLUE", "пиратский бути-ящик (синий)", WAIT, 5_500);
        add("MASQUE_BOOTY_BOX", "бути-ящик «Апокалипсис»", WAIT, 5_500);
        add("BLACK_BOOTY_BOX", "обсидиановый бути-ящик", WAIT, 5_500);
        add("CHROMIN_BOX_1", "хроминовый ящик врат (1)", EVENTS, 0);
        add("CHROMIN_BOX_2", "хроминовый ящик врат (2)", EVENTS, 0);
        add("CHROMIN_BOX_3", "хроминовый ящик врат (3)", EVENTS, 0);
        add("DEMON_BOX", "ящик врат VoT", EVENTS, 0);
        add("TREASURE", "сокровище (событие Mimesis)", EVENTS, 0);
        add("BIRTHDAY", "подарок на день рождения", EVENTS, 0);
        add("mine_3", "мина", NOT_BOXES, 0);
        add("firework", "фейерверк", NOT_BOXES, 0);
        add("UBA_EXPO_BOX", "ящик UBA Expo", UNKNOWN, 0);
        add("HELIX_BOX", "ящик Helix", UNKNOWN, 0);
    }

    /** A known type, or {@code null}. */
    public static Box of(String type) {
        return type == null ? null : BOXES.get(type);
    }

    /** All known types, in the list's order (by group). */
    public static List<Box> all() {
        return List.copyOf(BOXES.values());
    }

    /** False for what the game shows in the resource list but cannot be picked up (mines, fireworks). */
    public static boolean collectable(String type) {
        Box b = of(type);
        return b == null || b.collectable();
    }

    /** The wait DarkBot users set for the type, 0 when none is needed or the type is unknown. */
    public static int waitMs(String type) {
        Box b = of(type);
        return b == null ? 0 : b.waitMs();
    }
}
