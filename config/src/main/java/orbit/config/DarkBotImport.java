package orbit.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Converts the user's own DarkBot profiles ({@code configs/<name>.json} of a DarkBot install) into
 * {@link BotConfig}. Only data is read — the profile file and its {@code <name>_README.txt}; never
 * {@code credentials.json} or plugin code. What has no counterpart yet is kept in {@code extra} and
 * listed in the {@link Result#report}.
 *
 * <p>DarkBot facts used (its config format): NPC extras are class-qualified enum names
 * ({@code …NpcExtraIGNORE_OWNERSHIP}); zones are {@code resolution × resolution} bit grids, row
 * major, bit {@code i} = bit {@code i % 8} of byte {@code i / 8} (Java {@code BitSet}); box wait
 * times are in milliseconds; ammunition and formations are slot bar keys — not carried over, since
 * the bot uses items by id and the profile does not say which item is on a key.
 */
public final class DarkBotImport {

    /** Spawn spots of Cubikons on the x-6 maps (the user's working GE5 Cubi Camp profile). */
    static final int[][] CUBIKON_SPOTS = {{7500, 3900}, {13400, 3900}, {13400, 7900}, {7500, 7900}};

    static final String SAB = "ammunition_laser_sab-50";
    static final String RSB = "ammunition_laser_rsb-75";

    public record Result(BotConfig config, List<String> report) {
    }

    private final List<String> report = new ArrayList<>();
    private final Map<String, JsonNode> extra = new TreeMap<>();
    private final Map<String, JsonNode> modules = new TreeMap<>();

    private DarkBotImport() {
    }

    /** Profiles to import from a DarkBot folder (or its {@code configs} folder): backups are skipped. */
    public static List<Path> profiles(Path dir) throws IOException {
        Path configs = Files.isDirectory(dir.resolve("configs")) ? dir.resolve("configs") : dir;
        List<Path> out = new ArrayList<>();
        try (var files = Files.list(configs)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .filter(f -> isProfileName(f.getFileName().toString()))
                    .sorted()
                    .forEach(out::add);
        }
        return out;
    }

    static boolean isProfileName(String file) {
        String n = file.toLowerCase();
        return !n.equals("credentials.json") && !n.contains("_old") && !n.contains("_before_")
                && !n.contains("autosave") && !n.contains("backup");
    }

    /** Profile name from the file name ({@code ABG.json} → {@code ABG}). */
    public static String nameOf(Path file) {
        String n = file.getFileName().toString();
        return n.endsWith(".json") ? n.substring(0, n.length() - 5) : n;
    }

    public static Result read(Path file) throws IOException {
        if (file.getFileName().toString().equalsIgnoreCase("credentials.json")) {
            throw new IOException("credentials.json не импортируется");
        }
        JsonNode root = Json.MAPPER.readTree(file.toFile());
        String description = "";
        Path readme = file.resolveSibling(nameOf(file) + "_README.txt");
        if (Files.exists(readme)) {
            description = Files.readString(readme, StandardCharsets.UTF_8).strip();
        }
        return convert(root, description);
    }

    public static Result convert(JsonNode root, String description) {
        DarkBotImport i = new DarkBotImport();
        BotConfig c = i.run(root, description);
        return new Result(c, List.copyOf(i.report));
    }

    private BotConfig run(JsonNode root, String description) {
        JsonNode general = root.path("GENERAL");
        Set<String> enabled = enabledFeatures(root);
        String moduleClass = general.path("CURRENT_MODULE").asText("");
        BotConfig.Module module = module(moduleClass);
        if (module == BotConfig.Module.UNSUPPORTED) {
            report.add("Модуль " + moduleClass + " пока не поддерживается — конфиг сохранён, модуль не запустится");
            extra.put("darkbot.module", Json.MAPPER.getNodeFactory().textNode(moduleClass));
        }
        BotConfig.General g = general(general);
        Map<String, BotConfig.NpcRule> npcs = npcs(root.path("LOOT").path("NPC_INFOS"),
                root.path("LOOT").path("SAB").path("ENABLED").asBoolean(false));
        BotConfig.Loot loot = loot(root.path("LOOT"));
        BotConfig.Collect collect = collect(root.path("COLLECT"));
        BotConfig.Pet pet = new BotConfig.Pet(root.path("PET").path("ENABLED").asBoolean(false),
                textOrNull(root.path("PET").path("MODULE_ID")));
        BotConfig.Safety safety = safety(general.path("SAFETY"), root.path("CUSTOM_CONFIGS"), enabled);
        Map<Integer, BotConfig.Zones> zones = zones(root.path("PREFERRED"), root.path("AVOIDED"));
        Map<Integer, List<BotConfig.SafetySpot>> spots = safetySpots(root.path("SAFETY"));
        moduleSettings(module, moduleClass, root.path("CUSTOM_CONFIGS"));
        keepRest(root, enabled, moduleClass);
        report.add("Патроны и формации в DarkBot заданы клавишами панели, а боту клавиши не нужны — выберите "
                + "их по названию (вкладки «Общее», «Атака», «Безопасность»); пока не выбраны, бот берёт то, что "
                + "выбрано в игре");
        report.add(0, "Модуль " + module + ", рабочая карта " + g.workingMap() + ", NPC " + npcs.size()
                + ", ящиков " + collect.boxes().size() + ", карт с зонами " + zones.size()
                + ", карт с точками безопасности " + spots.size());
        BotConfig c = new BotConfig(BotConfig.SCHEMA, description, module, g, npcs, loot, collect, pet, safety, zones,
                spots, modules, extra);
        JsonNode running = general.path("RUNNING");
        BotConfig.Flee f = c.flee();
        return c.withFlee(new BotConfig.Flee(f.mode(), null, null, running.path("MAX_SIGHT_DISTANCE").asInt(2_000),
                f.rememberSec(), true, 90));
    }

    static BotConfig.Module module(String cls) {
        String c = cls.toLowerCase();
        if (c.contains("cubi")) {
            return BotConfig.Module.CUBIKON;
        }
        if (c.contains("galaxygate") || c.contains("genericgate") || c.contains("simple_galaxy_gate")) {
            return BotConfig.Module.GALAXY_GATE;
        }
        if (c.contains("palladium")) {
            return BotConfig.Module.PALLADIUM;
        }
        if (c.contains("quest")) {
            return BotConfig.Module.QUESTS;
        }
        if (c.endsWith("lootncollectormodule") || c.endsWith("lootcollectormodule")) {
            return BotConfig.Module.KILL_COLLECT;
        }
        if (c.endsWith(".lootmodule")) {
            return BotConfig.Module.KILL;
        }
        if (c.endsWith(".collectormodule")) {
            return BotConfig.Module.COLLECT;
        }
        return BotConfig.Module.UNSUPPORTED;
    }

    private static Set<String> enabledFeatures(JsonNode root) {
        Set<String> out = new HashSet<>();
        for (JsonNode plugin : root.path("PLUGIN_INFOS")) {
            plugin.path("ENABLED_FEATURES").forEach(f -> out.add(f.asText()));
        }
        return out;
    }

    private BotConfig.General general(JsonNode g) {
        JsonNode running = g.path("RUNNING");
        JsonNode roaming = g.path("ROAMING");
        return new BotConfig.General(g.path("WORKING_MAP").asInt(1), mode(g.path("OFFENSIVE")), mode(g.path("ROAM")),
                mode(g.path("RUN")), running.path("RUN_FROM_ENEMIES").asBoolean(true),
                running.path("RUN_FROM_ENEMIES_SIGHT").asBoolean(false), running.path("REMEMBER_ENEMIES_FOR").asInt(300),
                roaming.path("SEQUENTIAL").asBoolean(false), roaming.path("ONLY_KILL_PREFERRED").asBoolean(false));
    }

    private static BotConfig.ShipMode mode(JsonNode m) {
        if (m.isMissingNode()) {
            return null;
        }
        return new BotConfig.ShipMode(m.path("CONFIG").asInt(1), null);
    }

    private Map<String, BotConfig.NpcRule> npcs(JsonNode infos, boolean sabOn) {
        Map<String, BotConfig.NpcRule> out = new LinkedHashMap<>();
        Set<String> unknownFlags = new LinkedHashSet<>();
        infos.properties().forEach(e -> {
            JsonNode n = e.getValue();
            Set<String> flags = new LinkedHashSet<>();
            for (JsonNode f : n.path("extra").path("flags")) {
                String flag = flagName(f.asText());
                flags.add(flag);
                if (!isKnownFlag(flag)) {
                    unknownFlags.add(flag);
                }
            }
            Set<Integer> maps = new LinkedHashSet<>();
            n.path("mapList").forEach(m -> maps.add(m.asInt()));
            // DarkBot's SAB is one switch for all NPCs: carry it to every NPC that is killed
            out.put(e.getKey(), new BotConfig.NpcRule(n.path("radius").asDouble(500), n.path("priority").asInt(0),
                    n.path("kill").asBoolean(false), flags, maps, null, sabOn && n.path("kill").asBoolean(false)));
        });
        if (!unknownFlags.isEmpty()) {
            report.add("Флаги NPC без поддержки (сохранены): " + String.join(", ", unknownFlags));
        }
        return out;
    }

    /** {@code com.github.manolo8.darkbot.config.NpcExtraPASSIVE} → {@code PASSIVE}. */
    static String flagName(String qualified) {
        for (String marker : new String[] {"NpcExtra", "GateNpcFlag", "NpcFlag"}) {
            int at = qualified.lastIndexOf(marker);
            if (at >= 0) {
                return qualified.substring(at + marker.length());
            }
        }
        int dot = qualified.lastIndexOf('.');
        return qualified.substring(dot + 1);
    }

    private static boolean isKnownFlag(String flag) {
        for (BotConfig.NpcFlag f : BotConfig.NpcFlag.values()) {
            if (f.name().equals(flag)) {
                return true;
            }
        }
        return false;
    }

    private static BotConfig.Loot loot(JsonNode l) {
        JsonNode sab = l.path("SAB");
        JsonNode rsb = l.path("RSB");
        // DarkBot names items by slot bar keys; which item sits on a key is not in the profile, so the
        // items are left to be chosen by name (the "use SAB" choice and its thresholds are kept)
        return new BotConfig.Loot(null,
                sab.path("ENABLED").asBoolean(false) ? SAB : null,
                sab.path("PERCENT").asDouble(0), sab.path("NPC_AMOUNT").asInt(0),
                rsb.path("ENABLED").asBoolean(false) ? RSB : null,
                l.path("GROUP_NPCS").asBoolean(true), l.path("NPC_DISTANCE_IGNORE").asInt(0), null, false,
                true, null); // DarkBot circles round its target unless an NPC has NO_CIRCLE
    }

    private static BotConfig.Collect collect(JsonNode c) {
        Map<String, BotConfig.BoxRule> boxes = new LinkedHashMap<>();
        c.path("BOX_INFOS").properties().forEach(e -> boxes.put(e.getKey(), new BotConfig.BoxRule(
                e.getValue().path("collect").asBoolean(false), e.getValue().path("waitTime").asInt(0),
                e.getValue().path("priority").asInt(0))));
        return new BotConfig.Collect(boxes, c.path("RADIUS").asInt(400), c.path("STAY_AWAY_FROM_ENEMIES").asBoolean(true),
                c.path("IGNORE_CONTESTED_BOXES").asBoolean(true));
    }

    private static BotConfig.Safety safety(JsonNode s, JsonNode custom, Set<String> enabled) {
        BotConfig.Heal heal = null;
        String healing = "dev.shared.do_gamer.behaviour.SimpleHealing";
        if (enabled.contains(healing)) {
            JsonNode h = custom.path(healing);
            heal = new BotConfig.Heal(threshold(h.path("hp")), threshold(h.path("shield")), threshold(h.path("hpPod")));
        }
        return new BotConfig.Safety(s.path("REPAIR_HP_RANGE").path("min").asDouble(0.5),
                s.path("REPAIR_HP_RANGE").path("max").asDouble(0.9), s.path("REPAIR_HP_NO_NPC").asDouble(0.5),
                s.path("REPAIR_TO_SHIELD").asDouble(0), mode(s.path("REPAIR")), heal, s.path("MAX_DEATHS").asInt(10),
                textOrNull(s.path("REVIVE")), s.path("WAIT_BEFORE_REVIVE").asInt(10), s.path("WAIT_AFTER_REVIVE").asInt(10));
    }

    private static double threshold(JsonNode t) {
        return t.path("enabled").asBoolean(false) ? round(t.path("min").asDouble(-1)) : -1;
    }

    private static Map<Integer, BotConfig.Zones> zones(JsonNode preferred, JsonNode avoided) {
        Map<Integer, BotConfig.Zones> out = new TreeMap<>();
        Set<String> maps = new LinkedHashSet<>();
        preferred.fieldNames().forEachRemaining(maps::add);
        avoided.fieldNames().forEachRemaining(maps::add);
        for (String m : maps) {
            BotConfig.ZoneGrid p = grid(preferred.path(m));
            BotConfig.ZoneGrid a = grid(avoided.path(m));
            if (p != null || a != null) {
                out.put(Integer.parseInt(m), new BotConfig.Zones(p, a));
            }
        }
        return out;
    }

    /** DarkBot zone bits → rows of {@code #}/{@code .}; {@code null} if missing or empty. */
    static BotConfig.ZoneGrid grid(JsonNode z) {
        int res = z.path("resolution").asInt(0);
        String data = z.path("data").asText("");
        if (res <= 0 || data.isEmpty()) {
            return null;
        }
        byte[] bits = Base64.getDecoder().decode(data);
        List<String> rows = new ArrayList<>(res);
        for (int y = 0; y < res; y++) {
            StringBuilder row = new StringBuilder(res);
            for (int x = 0; x < res; x++) {
                int i = y * res + x;
                boolean on = i / 8 < bits.length && (bits[i / 8] >> (i % 8) & 1) != 0;
                row.append(on ? '#' : '.');
            }
            rows.add(row.toString());
        }
        BotConfig.ZoneGrid g = new BotConfig.ZoneGrid(res, rows);
        return g.isEmpty() ? null : g;
    }

    private static Map<Integer, List<BotConfig.SafetySpot>> safetySpots(JsonNode safety) {
        Map<Integer, List<BotConfig.SafetySpot>> out = new TreeMap<>();
        safety.properties().forEach(e -> {
            List<BotConfig.SafetySpot> spots = new ArrayList<>();
            for (JsonNode s : e.getValue()) {
                spots.add(new BotConfig.SafetySpot(s.path("type").asText("PORTAL"), s.path("x").asInt(),
                        s.path("y").asInt(), textOrNull(s.path("runMode")), textOrNull(s.path("jumpMode"))));
            }
            if (!spots.isEmpty()) {
                out.put(Integer.parseInt(e.getKey()), spots);
            }
        });
        return out;
    }

    private void moduleSettings(BotConfig.Module module, String moduleClass, JsonNode custom) {
        switch (module) {
            case CUBIKON -> {
                ObjectNode c = Json.MAPPER.createObjectNode();
                ArrayNode spots = c.putArray("spots");
                for (int[] s : CUBIKON_SPOTS) {
                    spots.addArray().add(s[0]).add(s[1]);
                }
                JsonNode camp = custom.path("ge5.cubi_camp").isMissingNode()
                        ? custom.path("eu.ge5.cubicamp.CubiCampModule") : custom.path("ge5.cubi_camp");
                c.put("arriveDistance", camp.path("arriveDistance").asInt(450));
                c.put("waitSeconds", camp.path("waitSeconds").asInt(90));
                c.put("collectBoxes", camp.path("collectBoxes").asBoolean(true));
                c.put("boxesWhileCubikonHpAbove", 0.25);
                boolean kristallins = custom.path(moduleClass).path("KRISTALLINS").asBoolean(false)
                        || camp.path("killWhileWaiting").asBoolean(false);
                c.put("waitKill", kristallins ? "ALL" : "OFF");
                c.put("waitKillRange", 1_500);
                c.put("chaseFromSpot", false);
                c.put("spotMode", "ROUND");
                c.put("learnSpawns", true);
                modules.put(BotConfig.Module.CUBIKON.key(), c);
                report.add("Кубы: 4 спауна x-6, подлёт " + c.get("arriveDistance") + ", ожидание "
                        + c.get("waitSeconds") + " с, бить при ожидании: " + (kristallins ? "всех" : "никого"));
            }
            case GALAXY_GATE -> {
                JsonNode gate = custom.path(moduleClass);
                if (gate.isObject()) {
                    modules.put(BotConfig.Module.GALAXY_GATE.key(), gate.deepCopy());
                    report.add("Врата: gateId " + gate.path("gateId").asInt() + ", камикадзе "
                            + (gate.path("kamikaze").path("enabled").asBoolean() ? "вкл" : "выкл") + ", постройка "
                            + (gate.path("builder").path("enabled").asBoolean() ? "вкл" : "выкл")
                            + " (настройки сохранены как есть)");
                }
            }
            default -> {
                JsonNode own = custom.path(moduleClass);
                if (own.isObject() && !own.isEmpty()) {
                    modules.put(module.key(), own.deepCopy());
                }
            }
        }
    }

    /** Everything else worth keeping: settings of enabled DarkBot features and the small global sections. */
    private void keepRest(JsonNode root, Set<String> enabled, String moduleClass) {
        Set<String> handled = Set.of(moduleClass, "dev.shared.do_gamer.behaviour.SimpleHealing",
                "ge5.cubi_camp", "eu.ge5.cubicamp.CubiCampModule");
        List<String> kept = new ArrayList<>();
        root.path("CUSTOM_CONFIGS").properties().forEach(e -> {
            if (enabled.contains(e.getKey()) && !handled.contains(e.getKey())) {
                extra.put("darkbot.feature." + e.getKey(), e.getValue().deepCopy());
                kept.add(e.getKey().substring(e.getKey().lastIndexOf('.') + 1));
            }
        });
        for (String section : new String[] {"GROUP", "MISCELLANEOUS", "PLAYER_INFOS", "PLAYER_TAGS"}) {
            JsonNode n = root.path(section);
            if (!n.isMissingNode() && !n.isEmpty()) {
                extra.put("darkbot." + section, n.deepCopy());
            }
        }
        if (!kept.isEmpty()) {
            report.add("Сохранены без поддержки (функции DarkBot): " + String.join(", ", kept));
        }
        report.add("Не переносятся: настройки окна и браузера DarkBot (BOT_SETTINGS), выключенные функции плагинов");
    }

    private static String textOrNull(JsonNode n) {
        return n.isMissingNode() || n.isNull() || n.asText().isEmpty() ? null : n.asText();
    }

    /** {@code 0.09999999999999999} → {@code 0.1}. */
    private static double round(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
