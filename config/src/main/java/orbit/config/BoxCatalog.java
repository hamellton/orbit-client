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
    public static final String BOOTY = "Сундуки (ключи)";
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
        add("UNSTABLE_BLACKLIGHT_CACHE", "нестабильный тайник Blacklight", BL, 0);
        add("ore_8", "палладий", WAIT, 750);
        // chests a key opens: the waits are the ones DarkBot profiles collect them with (5 500 the pirate ones,
        // 5 000 the rest)
        add("PIRATE_BOOTY", "пиратский сундук (зелёный)", BOOTY, 5_500);
        add("PIRATE_BOOTY_GOLD", "пиратский сундук (жёлтый, золотой)", BOOTY, 5_500);
        add("PIRATE_BOOTY_RED", "пиратский сундук (красный)", BOOTY, 5_500);
        add("PIRATE_BOOTY_BLUE", "пиратский сундук (синий)", BOOTY, 5_500);
        add("PIRATE_BOOTY_SILVER", "пиратский сундук (серебряный)", BOOTY, 5_500);
        add("MASQUE_BOOTY_BOX", "сундук «Апокалипсис»", BOOTY, 5_500);
        add("BLACK_BOOTY_BOX", "обсидиановый сундук", BOOTY, 5_000);
        add("PROMETHEUS_BOOTY_BOX", "сундук Прометея", BOOTY, 5_000);
        add("PROSPEROUS_BOOTY_BOX", "сундук процветания", BOOTY, 5_000);
        add("ASTRAL_BOOTY_BOX", "астральный сундук", BOOTY, 5_000);
        add("ASTRAL_PRIME_BOOTY_BOX", "астральный прайм-сундук", BOOTY, 5_000);
        add("EMPYRIAN_BOOTY_BOX", "эмпирейский сундук", BOOTY, 5_000);
        add("LUCENT_ALIEN_EGG_BOOTY_BOX", "сундук «Люцент» (яйцо)", BOOTY, 5_000);
        add("PERSEUS_BLESSING_BOOTY_BOX", "сундук благословения Персея", BOOTY, 5_000);
        add("BLACKLIGHT-CRYPT", "склеп Blacklight", BOOTY, 5_000);
        add("GREEN_ECHO_BOX", "сундук эха (зелёный)", BOOTY, 5_000);
        add("BLUE_ECHO_BOX", "сундук эха (синий)", BOOTY, 5_000);
        add("RED_ECHO_BOX", "сундук эха (красный)", BOOTY, 5_000);
        add("PURPLE_ECHO_BOX", "сундук эха (фиолетовый)", BOOTY, 5_000);
        add("LAUREL_BOX", "лавровый сундук", BOOTY, 5_000);
        add("STREUNER_BOOTY_BOX", "сундук стримеров", BOOTY, 5_000);
        add("CHROMIN_BOX_1", "хроминовый ящик врат (1)", EVENTS, 0);
        add("CHROMIN_BOX_2", "хроминовый ящик врат (2)", EVENTS, 0);
        add("CHROMIN_BOX_3", "хроминовый ящик врат (3)", EVENTS, 0);
        add("DEMON_BOX", "ящик врат VoT", EVENTS, 0);
        add("TREASURE", "сокровище (событие Mimesis)", EVENTS, 0);
        add("COSMIC_CACHE", "космический тайник", EVENTS, 0);
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

    /**
     * The client draws several server types with one picture, its id listing them all
     * ({@code box_SOLAR_CLASH,box_MINDFIRE_BEHEMOTH_BOX_2,…} in the client's collectables XML), and DarkBot names a box by
     * the first of them ({@code Box.update}: the asset id up to the first comma, {@code box_} cut off). The server's
     * type → DarkBot's name, for every such group of the client of 2026-11-18.
     */
    private static final Map<String, String> DARKBOT_NAMES = new LinkedHashMap<>();

    private static void group(String first, String... others) {
        for (String o : others) {
            DARKBOT_NAMES.put(o, first);
        }
    }

    static {
        group("FROM_SHIP", "FROM_SPACEBALL", "FUELCAN");
        group("BONUS_BOX", "RAZER_BOX", "ONE_PLAYER", "BONUS_BOX_HOME");
        group("FLOWER", "MAY_FIRST", "FLOWER_EXCLUSIVE");
        group("SOLAR_CLASH", "THE_STINGER", "UBERANNIHILATOR_BOX", "UBERBARRACUDA_BOX", "UBERBATTLERAY_BOX",
                "UBERINTERCEPTOR_BOX", "UBERSABOTEUR_BOX", "MINDFIRE_BEHEMOTH_BOX_1", "MINDFIRE_BEHEMOTH_BOX_2",
                "MINDFIRE_BEHEMOTH_BOX_3", "INVOKE_XVI_BOX_1", "INVOKE_XVI_BOX_2", "INVOKE_XVI_BOX_3");
        group("PET_ONLY_BOX", "WILDPET_BOX");
        group("HITAC", "HITAC_MINION", "HITAC_EASY", "HITAC_MINION_EASY", "HITAC_UNDERBOSS");
        group("BIRTHDAY", "DREADFYRE_HARBINGER_BOX");
        group("TREASURE", "MIMESIS_AI_MINI_BOSS_BOX", "MIMESIS_AI_BOSS_BOX", "MIMESIS_AI_REFLECTOR_BOX",
                "MIMESIS_AI_TERROR_BOX", "MIMESIS_AI_ASSAILANT_BOX", "MIMESIS_AI_RAIDER_BOX", "MIMESIS_AI_HARDY_BOX",
                "MIMESIS_AI_MEDIC_BOX");
        group("STYXUS_BOX", "CHAROPOS_BOX");
        group("GOP_EASY_BOX", "GOP_NORMAL_BOX");
        group("VIRAL_BOX_A", "VIRAL_BOX_AA", "VIRAL_BOX_AAA");
    }

    /**
     * DarkBot's name of a type the server sends, when it differs ({@link #DARKBOT_NAMES}): DarkBot profiles collect the
     * invoke's and the behemoth's boxes as {@code SOLAR_CLASH}, the server sends {@code INVOKE_XVI_BOX_2} and
     * {@code MINDFIRE_BEHEMOTH_BOX_2} (seen live 2026-11-14). {@code null} when none.
     */
    public static String darkBotName(String serverType) {
        if (serverType == null) {
            return null;
        }
        String name = DARKBOT_NAMES.get(serverType);
        if (name == null && (serverType.startsWith("INVOKE_XVI_BOX") || serverType.startsWith("MINDFIRE_BEHEMOTH_BOX"))) {
            return "SOLAR_CLASH";
        }
        return name;
    }

    /**
     * A booty key as DarkBot names it ({@code Stats.BootyKey}), the server's code of its count ({@code 0|A|BK|12},
     * {@code ServerCommands.BOOTY_KEYS_*}) and the chest it opens — DmPlugin's {@code BootyKeyResourceMapper}, whose
     * green key opens the yellow {@code PIRATE_BOOTY_GOLD}.
     */
    public record BootyKey(String name, String code, String box, String title) {
    }

    private static final List<BootyKey> KEYS = List.of(
            new BootyKey("GREEN", "BK", "PIRATE_BOOTY_GOLD", "зелёные ключи"),
            new BootyKey("BLUE", "BKB", "PIRATE_BOOTY_BLUE", "синие ключи"),
            new BootyKey("RED", "BKR", "PIRATE_BOOTY_RED", "красные ключи"),
            new BootyKey("SILVER", "BKS", "PIRATE_BOOTY_SILVER", "серебряные ключи"),
            new BootyKey("APOCALYPSE", "BKM", "MASQUE_BOOTY_BOX", "ключи «Апокалипсис»"),
            new BootyKey("PROMETHEUS", "BKPR", "PROMETHEUS_BOOTY_BOX", "ключи Прометея"),
            new BootyKey("OBSIDIAN_MICROCHIP", "BKBB", "BLACK_BOOTY_BOX", "обсидиановые микрочипы"),
            new BootyKey("PROSPEROUS_FRAGMENT", "BKP", "PROSPEROUS_BOOTY_BOX", "фрагменты процветания"),
            new BootyKey("ASTRAL", "BKA", "ASTRAL_BOOTY_BOX", "астральные ключи"),
            new BootyKey("ASTRAL_SUPREME", "BKAP", "ASTRAL_PRIME_BOOTY_BOX", "астральные прайм-ключи"),
            new BootyKey("EMPYRIAN", "BKE", "EMPYRIAN_BOOTY_BOX", "эмпирейские ключи"),
            new BootyKey("PERSEUS", "BKPG", "PERSEUS_BLESSING_BOOTY_BOX", "ключи Персея"),
            new BootyKey("LUCENT", "BKL", "LUCENT_ALIEN_EGG_BOOTY_BOX", "ключи «Люцент»"),
            new BootyKey("BLACK_LIGHT_CODE", "BKBLC", "UNSTABLE_BLACKLIGHT_CACHE", "шифры Blacklight"),
            new BootyKey("BLACK_LIGHT_DECODER", "BKBLD", "BLACKLIGHT-CRYPT", "дешифраторы Blacklight"));

    /** DmPlugin's keys, in its order. */
    public static List<BootyKey> bootyKeys() {
        return KEYS;
    }

    /** A key by DarkBot's name ({@code GREEN}), or {@code null}. */
    public static BootyKey bootyKey(String name) {
        return KEYS.stream().filter(k -> k.name().equals(name)).findFirst().orElse(null);
    }

    /** The key that opens a chest of this type (DarkBot's name), or {@code null}. */
    public static BootyKey keyFor(String boxType) {
        return KEYS.stream().filter(k -> k.box().equals(boxType)).findFirst().orElse(null);
    }

    /** The wait DarkBot users set for the type, 0 when none is needed or the type is unknown. */
    public static int waitMs(String type) {
        Box b = of(type);
        return b == null ? 0 : b.waitMs();
    }
}
