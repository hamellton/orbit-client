package orbit.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The galaxy gates (docs/GALAXY-GATES.md): the builder's number, parts and waves (the client's
 * {@code GalaxyGateBuilderModel}), the gate's maps ({@code maps.json}), the type of its portal on the home x-1
 * ({@code JumpgateCreateCommand.designId}, DarkBot's {@code StarManager}), the symbol in its NPCs' names
 * ({@code -=[ Streuner ]=- α1}) and the NPCs with the radius the profiles on the VPS give them (CARNAGE {@code ABG.json},
 * SharedPlugin, AstraBot {@code Galaxy - Gate.json}).
 */
public final class GateCatalog {

    /**
     * One gate.
     *
     * @param key        the settings' name ({@code alpha}…)
     * @param builderId  {@code gateId} of the builder's packets, 0 for Trinity (its own window)
     * @param portal     {@code designId} of the entry portal on x-1, 0 = no portal (Trinity is entered by a request)
     * @param noCenter   the entry is the portal of that type not at the map's centre (ζ: the escort's 54 is central)
     * @param npcs       the NPCs' bare names (lower case, as {@link BotConfig#bare} of the name without the wave
     *                   number) → radius
     * @param kamikaze   bare names the pet's kamikaze is used on (SharedPlugin AbgGate)
     */
    public record Gate(String key, String name, int builderId, int parts, int waves, List<Integer> maps, int portal,
                       boolean noCenter, String symbol, Map<String, Integer> npcs, Set<String> kamikaze) {

        public boolean on(int mapId) {
            return maps.contains(mapId);
        }
    }

    static Map<String, Integer> abg() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("streuner", 450);
        m.put("lordakia", 500);
        m.put("mordon", 550);
        m.put("saimon", 580);
        m.put("devolarium", 560);
        m.put("kristallin", 605);
        m.put("sibelon", 570);
        m.put("sibelonit", 605);
        m.put("kristallon", 610);
        m.put("protegit", 630);
        m.put("recruit streuner", 450);
        return m;
    }

    static Map<String, Integer> zeta() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("infernal", 550);
        m.put("scorcher", 590);
        m.put("melter", 590);
        m.put("devourer", 590);
        m.put("streuner", 550);
        m.put("boss streuner", 550);
        m.put("uberstreuner", 560);
        m.put("lordakia", 550);
        m.put("boss lordakia", 550);
        m.put("uberlordakia", 550);
        m.put("saimon", 550);
        m.put("boss saimon", 550);
        m.put("ubersaimon", 580);
        m.put("sibelonit", 590);
        m.put("boss sibelonit", 590);
        m.put("ubersibelonit", 590);
        m.put("kristallin", 590);
        m.put("boss kristallin", 590);
        m.put("uberkristallin", 590);
        return m;
    }

    static Map<String, Integer> kappa() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String n : List.of("streuner", "vagrant", "infernal", "marauder", "scorcher", "boss mordon", "outcast",
                "devolarium", "icy", "boss sibelonit", "corsair", "hooligan", "kristallin", "melter", "interceptor",
                "barracuda", "annihilator", "boss lordakium", "boss sibelon", "protegit", "kucurbium", "ubersaimon",
                "convict", "boss kristallin", "demaner", "bosskucurbium", "century falcon")) {
            m.put(n, 560);
        }
        return m;
    }

    /**
     * The Eternal Blacklight gate's NPCs: SharedPlugin's {@code EternalBlacklightGate} npcMap (radius; the rest 560),
     * docs/EBG.md.
     */
    static Map<String, Integer> eternal() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("barrage seeker rocket", 600);
        m.put("strokelight barrage", 600);
        m.put("steadfast iii", 400);
        m.put("abide i", 400);
        m.put("uberkristallon", 645);
        m.put("uberkristallin", 580);
        m.put("ubersibelon", 600);
        m.put("ubersibelonit", 575);
        m.put("uberlordakium", 600);
        m.put("uber annihilator", 580);
        m.put("uber saboteur", 590);
        m.put("boss kristallon", 615);
        m.put("boss kristallin", 575);
        m.put("boss sibelon", 570);
        m.put("ice meteoroid", 615);
        m.put("icy", 600);
        m.put("kucurbium", 600);
        m.put("attend ix", 600);
        m.put("impulse ii", 600);
        return m;
    }

    /** EBG's NPCs never shot (SharedPlugin: PASSIVE) — the boss wave's guards; the boss dies without them. */
    public static final Set<String> EBG_PASSIVE = Set.of("abide i", "steadfast iii");
    /** EBG's first target of the boss wave (SharedPlugin priority −90). */
    public static final String EBG_FIRST = "barrage seeker rocket";
    /** The Eternal CPU that opens EBG's portal on a BL map. */
    public static final String EBG_CPU = "ammunition_ggportal_eternal-blacklight-cpu";
    /** Its recipe in the assembly. */
    public static final String EBG_CPU_RECIPE = "recipe_blueprint-eternal-cpu";
    /** The cycle of EBG's waves (SharedPlugin GATE_CYCLE_WAVES); Uber Kristallons on the 23rd and 47th of each. */
    public static final int EBG_CYCLE = 51;

    static Map<String, Integer> trinity() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String n : List.of("streuner", "lordakia", "mordon", "saimon", "devolarium", "kristallin", "sibelon",
                "sibelonit", "kristallon", "protegit", "barracuda", "boss lordakium", "saboteur", "annihilator",
                "uberkristallin", "boss sibelon", "magma stalker", "spore stalker", "chronospire", "p.e.t. stalker")) {
            m.put(n, 560);
        }
        m.put("uberstreuner", 600);
        m.put("veil stalker", 580);
        m.put("pyrospire", 620);
        m.put("vinespire", 620);
        return m;
    }

    /** LoW's NPCs (docs/LOW.md): the VPS profiles' radii (CARNAGE, AstraBot: all 540; SharedPlugin: Century Falcon 580). */
    static Map<String, Integer> low() {
        Map<String, Integer> m = new java.util.LinkedHashMap<>();
        for (String n : List.of("vagrant", "marauder", "outcast", "corsair", "hooligan", "ravager", "convict")) {
            m.put(n, 540);
        }
        m.put("century falcon", 580);
        m.put("relay", LOW_RELAY_RADIUS);
        return m;
    }

    /** SharedPlugin's LowGate: the relay stations are shot from 400. */
    public static final int LOW_RELAY_RADIUS = 400;
    /** The map of the LoW gate and the type of its portal on x-3 (DarkBot's StarManager: {@code addGG(200, "LoW")}, 34). */
    public static final int LOW_MAP = 200;
    public static final int LOW_PORTAL = 34;
    /**
     * The relay stations' asset ids and SharedPlugin's order of attack ({@code RELAY_IDS}: «in specific spawn order»);
     * the relay's number is the id less {@link #LOW_RELAY_BASE}.
     */
    public static final int LOW_RELAY_BASE = 100_000_100;
    public static final List<Integer> LOW_RELAY_ORDER = List.of(100_000_104, 100_000_102, 100_000_103, 100_000_101);

    public static final List<Gate> ALL = List.of(
            new Gate("alpha", "α Alpha", 1, 34, 40, List.of(51), 2, false, "α", abg(),
                    Set.of("kristallin", "saimon", "sibelonit")),
            new Gate("beta", "β Beta", 2, 48, 40, List.of(52), 3, false, "β", abg(),
                    Set.of("kristallin", "saimon", "sibelonit")),
            new Gate("gamma", "γ Gamma", 3, 82, 40, List.of(53), 4, false, "γ", abg(),
                    Set.of("kristallin", "saimon", "sibelonit")),
            new Gate("delta", "δ Delta", 4, 128, 29, List.of(55), 5, false, "δ", Map.of(), Set.of()),
            new Gate("epsilon", "ε Epsilon", 5, 99, 30, List.of(70), 53, false, "ε", Map.of(), Set.of()),
            new Gate("zeta", "ζ Zeta", 6, 111, 46, List.of(71, 72, 73), 54, true, "ζ", zeta(), Set.of()),
            new Gate("kappa", "κ Kappa", 7, 120, 29, List.of(74), 70, false, "κ", kappa(), Set.of()),
            new Gate("lambda", "λ Lambda", 8, 45, 21, List.of(75), 71, false, "λ", Map.of(), Set.of()),
            new Gate("kronos", "Kronos", 12, 21, 50, List.of(76), 72, false, "ђ", Map.of(), Set.of()),
            new Gate("hades", "Hades", 13, 45, 12, List.of(203), 74, false, "", Map.of(), Set.of()),
            new Gate("kuiper", "Kuiper ς", 19, 100, 54, List.of(300, 301, 302, 303, 304), 82, false, "ς", Map.of(),
                    Set.of()),
            // EBG (docs/EBG.md): no builder — DarkBot's virtual map −4, its portal 54 on 1/2/3-BL opened by an Eternal CPU;
            // its maps are instances 460–465, its waves a cycle of 51 with no end (one life)
            new Gate("eternal", "∞ Eternal Blacklight", -4, 0, EBG_CYCLE, List.of(460, 461, 462, 463, 464, 465), 54,
                    false, "EBG", eternal(), Set.of()),
            // LoW (docs/LOW.md): no builder, the group gate's instance behind portal 34 on x-3; four relays, the Century Falcon
            new Gate("low", "LoW", 0, 0, 0, List.of(LOW_MAP), LOW_PORTAL, false, "", low(), Set.of()),
            new Gate("trinity", "Trinity Trials", 0, 0, 20,
                    List.of(494, 495, 496, 497, 498, 499, 500, 501, 502, 503, 504, 505, 506, 507, 508), 304, false, "⦓",
                    trinity(), Set.of()));

    /**
     * The Mimesis escort's maps (docs/MIMESIS.md; DarkBot's StarManager «Mimesis escort maps», maps.json group
     * PayloadEscort): VRU 430–432, 1439–1445; MMO 433–435, 1446–1452; EIC 436–438, 1453–1459. In December and January
     * 430–445 are the Frozen Labyrinth's (DarkBot) — the escort runs in other months.
     */
    public static final List<Integer> ESCORT_MAPS = escortMaps();
    /** The escort's entry on x-1 and x-8: portal type 54 at (10 500, 6 500) (DarkBot {@code accessOnlyBy(54, 10500, 6500)}). */
    public static final int ESCORT_PORTAL = 54;
    public static final int ESCORT_PORTAL_X = 10_500;
    public static final int ESCORT_PORTAL_Y = 6_500;

    private static List<Integer> escortMaps() {
        List<Integer> m = new java.util.ArrayList<>(List.of(430, 431, 432, 433, 434, 435, 436, 437, 438));
        for (int id = 1439; id <= 1459; id++) {
            m.add(id);
        }
        return List.copyOf(m);
    }

    /** A Mimesis escort map. */
    public static boolean escort(int mapId) {
        return ESCORT_MAPS.contains(mapId);
    }

    /**
     * A map of an instance the bot treats as a gate (DarkBot's {@code addGG}): a galaxy gate or a Mimesis escort map —
     * no shelter in it but its own portals, no logging out amid it, its portals not learned.
     */
    public static boolean instanced(int mapId) {
        return byMap(mapId) != null || escort(mapId);
    }

    /** The portal back home inside a gate ({@code designId} 1, as on the normal maps). */
    public static final int HOME_PORTAL = 1;

    private GateCatalog() {
    }

    public static Gate byKey(String key) {
        if (key == null) {
            return null;
        }
        String k = key.strip().toLowerCase(java.util.Locale.ROOT);
        return ALL.stream().filter(g -> g.key().equals(k)).findFirst().orElse(null);
    }

    public static Gate byMap(int mapId) {
        return ALL.stream().filter(g -> g.on(mapId)).findFirst().orElse(null);
    }

    public static Gate byBuilderId(int id) {
        return id == 0 ? null : ALL.stream().filter(g -> g.builderId() == id).findFirst().orElse(null);
    }

    /**
     * An NPC's bare name without the gate's wave number: {@code -=[ Streuner ]=- α1} → {@code streuner};
     * {@code null} when the name carries no gate symbol.
     */
    public static String gateNpc(String name) {
        if (name == null) {
            return null;
        }
        java.util.regex.Matcher m = WAVE.matcher(name);
        if (!m.find()) {
            return null;
        }
        return BotConfig.bare(name.substring(0, m.start()));
    }

    /** The wave number at the end of a gate NPC's name ({@code -=[ Protegit ]=- α37} → 37), 0 when none. */
    public static int gateWave(String name) {
        if (name == null) {
            return 0;
        }
        java.util.regex.Matcher m = WAVE_NUMBER.matcher(name);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static final java.util.regex.Pattern WAVE_NUMBER = java.util.regex.Pattern.compile("(?:[αβγδεζκλςђ⦓]|EBG)\\s*(\\d+)\\s*$");

    /** The symbol and the wave number at the end of a gate NPC's name. */
    private static final java.util.regex.Pattern WAVE = java.util.regex.Pattern.compile("\\s*(?:[αβγδεζκλςђ⦓]|EBG)\\s*\\d+\\s*$");

    /**
     * The rule for a gate NPC the config does not list: the catalog's radius, to be killed, on that gate's maps;
     * {@code null} when the name is no gate NPC of {@code gate}. Its pet's kamikaze flag as SharedPlugin has it.
     */
    public static BotConfig.NpcRule defaultRule(Gate gate, String name) {
        if (gate != null && "low".equals(gate.key())) {
            return lowRule(gate, name);
        }
        String bare = gateNpc(name);
        if (gate == null || bare == null) {
            return null;
        }
        Integer radius = gate.npcs().get(bare);
        if ("eternal".equals(gate.key())) {
            // SharedPlugin's EBG: every NPC killed but the boss wave's two guards, the seeker rockets first
            boolean passive = EBG_PASSIVE.contains(bare);
            return new BotConfig.NpcRule(radius != null ? radius : 560, EBG_FIRST.equals(bare) ? -90 : 0, !passive,
                    passive ? Set.of() : Set.of(BotConfig.NpcFlag.HOLD_TARGET.name()), Set.copyOf(gate.maps()), null);
        }
        // held till dead: a wave ends only with its NPCs dead (CARNAGE «holdTargetUntilKill»)
        Set<String> flags = gate.kamikaze().contains(bare)
                ? Set.of(BotConfig.NpcFlag.KAMIKAZE.name(), BotConfig.NpcFlag.HOLD_TARGET.name())
                : Set.of(BotConfig.NpcFlag.HOLD_TARGET.name());
        return new BotConfig.NpcRule(radius != null ? radius : 560, 0, true, flags, Set.copyOf(gate.maps()), null);
    }

    /**
     * LoW's NPCs carry no wave number ({@code -=[ Vagrant ]=-}): the rule by the bare name — SharedPlugin's LowGate: all
     * followed aggressively at 540, the Century Falcon at 580 without circling (priority 50), the Vagrant last (100);
     * the relay at 400, after everything else; {@code null} for a name the gate does not know.
     */
    static BotConfig.NpcRule lowRule(Gate gate, String name) {
        String bare = name == null ? null : BotConfig.bare(name);
        Integer radius = bare == null ? null : gate.npcs().get(bare);
        if (radius == null) {
            return null;
        }
        Set<String> flags = new java.util.TreeSet<>(Set.of(BotConfig.NpcFlag.HOLD_TARGET.name()));
        int priority = 0;
        switch (bare) {
            case "century falcon" -> {
                flags.add(BotConfig.NpcFlag.NO_CIRCLE.name());
                flags.add(BotConfig.NpcFlag.HP_LINK.name());
                priority = 50;
            }
            case "relay" -> {
                flags.add(BotConfig.NpcFlag.NO_CIRCLE.name());
                priority = 1_000;
            }
            case "vagrant" -> {
                flags.add(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW.name());
                priority = 100;
            }
            default -> flags.add(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW.name());
        }
        return new BotConfig.NpcRule(radius, priority, true, flags, Set.copyOf(gate.maps()), null);
    }
}
