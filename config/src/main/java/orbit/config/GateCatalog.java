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
            new Gate("trinity", "Trinity Trials", 0, 0, 20,
                    List.of(494, 495, 496, 497, 498, 499, 500, 501, 502, 503, 504, 505, 506, 507, 508), 304, false, "⦓",
                    trinity(), Set.of()));

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

    private static final java.util.regex.Pattern WAVE_NUMBER = java.util.regex.Pattern.compile("[αβγδεζκλςђ⦓]\\s*(\\d+)\\s*$");

    /** The symbol and the wave number at the end of a gate NPC's name. */
    private static final java.util.regex.Pattern WAVE = java.util.regex.Pattern.compile("\\s*[αβγδεζκλςђ⦓]\\s*\\d+\\s*$");

    /**
     * The rule for a gate NPC the config does not list: the catalog's radius, to be killed, on that gate's maps;
     * {@code null} when the name is no gate NPC of {@code gate}. Its pet's kamikaze flag as SharedPlugin has it.
     */
    public static BotConfig.NpcRule defaultRule(Gate gate, String name) {
        String bare = gateNpc(name);
        if (gate == null || bare == null) {
            return null;
        }
        Integer radius = gate.npcs().get(bare);
        // held till dead: a wave ends only with its NPCs dead (CARNAGE «holdTargetUntilKill»)
        Set<String> flags = gate.kamikaze().contains(bare)
                ? Set.of(BotConfig.NpcFlag.KAMIKAZE.name(), BotConfig.NpcFlag.HOLD_TARGET.name())
                : Set.of(BotConfig.NpcFlag.HOLD_TARGET.name());
        return new BotConfig.NpcRule(radius != null ? radius : 560, 0, true, flags, Set.copyOf(gate.maps()), null);
    }
}
