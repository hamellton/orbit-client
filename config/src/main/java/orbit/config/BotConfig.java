package orbit.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What the bot does: one named configuration ({@code local/configs/<name>.json}). Accounts are kept
 * apart (credentials in the vault, the chosen config in {@link AccountSettings}), so one config can
 * serve several accounts.
 *
 * <p>The sections follow the modules (as in AstraBot and DarkBot), so the user's DarkBot profiles
 * convert without losses ({@link DarkBotImport}). Settings of a single module (Cubikon, gates…) live
 * in {@link #modules} under the module's key until that module gets its own typed section.
 *
 * <p>Records are immutable; edit with the {@code with…} copies. Missing sections read as defaults.
 *
 * @param schema    schema version ({@link #SCHEMA}); older files are migrated on load
 * @param module    the main module: {@link Module}
 * @param modules   per-module settings, key = {@link Module#key()}
 * @param extra     imported settings not converted yet (kept so nothing is lost; shown by the import report)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record BotConfig(
        int schema,
        String description,
        Module module,
        General general,
        Map<String, NpcRule> npcs,
        Loot loot,
        Collect collect,
        Pet pet,
        Safety safety,
        Map<Integer, Zones> zones,
        Map<Integer, List<SafetySpot>> safetySpots,
        Map<String, JsonNode> modules,
        Map<String, JsonNode> extra) {

    public static final int SCHEMA = 1;

    public BotConfig {
        schema = schema == 0 ? SCHEMA : schema;
        description = description == null ? "" : description;
        module = module == null ? Module.KILL_COLLECT : module;
        general = general == null ? General.DEFAULT : general;
        npcs = npcs == null ? Map.of() : sorted(npcs);
        loot = loot == null ? Loot.DEFAULT : loot;
        collect = collect == null ? Collect.DEFAULT : collect;
        pet = pet == null ? Pet.DEFAULT : pet;
        safety = safety == null ? Safety.DEFAULT : safety;
        zones = zones == null ? Map.of() : sorted(zones);
        safetySpots = safetySpots == null ? Map.of() : sorted(safetySpots);
        modules = modules == null ? Map.of() : sorted(modules);
        extra = extra == null ? Map.of() : sorted(extra);
    }

    /** An empty config: kill and collect on 1-1 with default thresholds. */
    public static BotConfig empty() {
        return new BotConfig(SCHEMA, "", Module.KILL_COLLECT, null, null, null, null, null, null, null, null, null, null);
    }

    public BotConfig withModule(Module m) {
        return new BotConfig(schema, description, m, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withGeneral(General g) {
        return new BotConfig(schema, description, module, g, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withNpcs(Map<String, NpcRule> n) {
        return new BotConfig(schema, description, module, general, n, loot, collect, pet, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withSafety(Safety s) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, s, zones, safetySpots, modules, extra);
    }

    public BotConfig withDescription(String d) {
        return new BotConfig(schema, d, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withLoot(Loot l) {
        return new BotConfig(schema, description, module, general, npcs, l, collect, pet, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withCollect(Collect c) {
        return new BotConfig(schema, description, module, general, npcs, loot, c, pet, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withPet(Pet p) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, p, safety, zones, safetySpots, modules, extra);
    }

    public BotConfig withZones(Map<Integer, Zones> z) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, z, safetySpots, modules, extra);
    }

    public BotConfig withModuleSettings(String key, JsonNode settings) {
        Map<String, JsonNode> m = new TreeMap<>(modules);
        m.put(key, settings);
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, m, extra);
    }

    /** Settings of a module, or an empty object. */
    public ObjectNode moduleSettings(String key) {
        JsonNode n = modules.get(key);
        return n instanceof ObjectNode o ? o.deepCopy() : Json.MAPPER.createObjectNode();
    }

    /** The rule for an NPC by its in-game name ({@code -=[ Streuner ]=-}), or {@code null}. */
    public NpcRule npc(String name) {
        return npcs.get(name);
    }

    /** The main modules. Keys are stable (they name the {@link #modules} sections). */
    public enum Module {
        KILL_COLLECT("kill_collect"),
        KILL("kill"),
        COLLECT("collect"),
        CUBIKON("cubikon"),
        GALAXY_GATE("gate"),
        PALLADIUM("palladium"),
        QUESTS("quests"),
        /** Imported from DarkBot, no counterpart yet; the source class is in {@code extra.darkbot.module}. */
        UNSUPPORTED("unsupported");

        private final String key;

        Module(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /**
     * Ship configuration (1 or 2) and drone formation (item id, e.g. {@code drone_formation_f-09-mo};
     * {@code null} = leave as it is). Items are used by id, wherever they are in the game's bars.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ShipMode(int config, String formation) {

        public ShipMode {
            config = config == 2 ? 2 : 1;
        }
    }

    /**
     * @param workingMap map id ({@link orbit.world.maps.MapCatalog})
     * @param offensive  while attacking
     * @param roam       while flying around / collecting
     * @param run        while running away
     * @param runFromEnemies   run from enemy players that attack or come close
     * @param rememberEnemiesSec how long a seen enemy counts
     * @param roamSequential roam the map in order instead of at random
     * @param runModeTravel  fly between maps and spots in the {@code run} mode (fast), not {@code roam}
     * @param runModeChase   catch up with a target that is far beyond its radius in the {@code run} mode
     */
    public record General(int workingMap, ShipMode offensive, ShipMode roam, ShipMode run,
                          boolean runFromEnemies, boolean runFromEnemiesInSight, int rememberEnemiesSec,
                          boolean roamSequential, boolean onlyKillPreferred, boolean runModeTravel,
                          boolean runModeChase) {

        public General(int workingMap, ShipMode offensive, ShipMode roam, ShipMode run, boolean runFromEnemies,
                       boolean runFromEnemiesInSight, int rememberEnemiesSec, boolean roamSequential,
                       boolean onlyKillPreferred) {
            this(workingMap, offensive, roam, run, runFromEnemies, runFromEnemiesInSight, rememberEnemiesSec,
                    roamSequential, onlyKillPreferred, false, false);
        }

        /** The mode for flying somewhere (between maps, to a spot). */
        public ShipMode travel() {
            return runModeTravel ? run : roam;
        }

        public static final General DEFAULT = new General(1, null, null, null, true, false, 300, false, false);

        public General {
            workingMap = workingMap == 0 ? 1 : workingMap;
            offensive = offensive == null ? new ShipMode(1, null) : offensive;
            roam = roam == null ? new ShipMode(1, null) : roam;
            run = run == null ? new ShipMode(2, null) : run;
            rememberEnemiesSec = rememberEnemiesSec <= 0 ? 300 : rememberEnemiesSec;
        }
    }

    /**
     * How one NPC kind is treated.
     *
     * @param radius   distance to keep while attacking
     * @param priority lower first (DarkBot convention: -100 before 0)
     * @param kill     attack it at all
     * @param flags    {@link NpcFlag} names; unknown imported flags are kept as they were
     * @param maps     map ids where the rule applies; empty = everywhere
     * @param ammo     ammunition item id for this NPC; {@code null} = {@link Loot#ammo}
     * @param sab      use {@link Loot#sab} on this NPC while the own shield is low
     * @param rocket   rocket type for this NPC; {@code null} = {@link Loot#rocket}, {@link Loot#NO_ROCKETS} = none
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo,
                          boolean sab, String rocket) {

        public NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo) {
            this(radius, priority, kill, flags, maps, ammo, false, null);
        }

        public NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo,
                       boolean sab) {
            this(radius, priority, kill, flags, maps, ammo, sab, null);
        }

        public NpcRule {
            // sorted, so saved files do not change from run to run
            flags = flags == null ? Set.of() : java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(flags));
            maps = maps == null ? Set.of() : java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(maps));
        }

        public boolean has(NpcFlag f) {
            return flags.contains(f.name());
        }

        public boolean appliesOn(int mapId) {
            return maps.isEmpty() || maps.contains(mapId);
        }
    }

    /** Known NPC flags (DarkBot's NPC extras and gate flags under the same names). */
    public enum NpcFlag {
        /** attack even if another player shot at it */
        IGNORE_OWNERSHIP,
        /** attack even if it is attacked by others */
        IGNORE_ATTACKED,
        /** do not pick up its boxes */
        IGNORE_BOXES,
        /** never shoots first: do not run from it */
        PASSIVE,
        /** follow it when it runs: aim where it flies, catch up in the run mode */
        AGGRESSIVE_FOLLOW,
        /** prefer it as the second target of a group */
        ATTACK_SECOND,
        /** the pet uses the NPC locator to find it */
        PET_LOCATOR,
        /** a moment for the pet's kamikaze (gates) */
        KAMIKAZE
    }

    /**
     * Items are menu item ids ({@code ammunition_laser_ucb-100}): the bot uses them with a packet,
     * no slot bar keys involved.
     *
     * @param ammo        default laser ammunition; {@code null} = whatever is selected in the game
     * @param sab         shield-absorbing ammunition when the own shield is low
     * @param sabBelow    use SAB while the shield is below this fraction
     * @param sabNpcShieldMin only against NPCs with at least this much shield
     * @param rsb         rapid salvo when ready
     * @param groupNpcs   attack NPCs that stand together one after another
     * @param ignoreFartherThan ignore NPCs farther than this
     * @param rocket      rocket type to keep selected ({@code null} = as in the game, {@link #NO_ROCKETS} = none)
     * @param fireRockets the bot launches rockets itself (ships without the automatic rocket CPU)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                       boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets) {

        public static final String NO_ROCKETS = "none";
        public static final Loot DEFAULT = new Loot(null, null, 0, 0, null, true, 0);

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb, boolean groupNpcs,
                    int ignoreFartherThan) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, null, false);
        }
    }

    /**
     * @param boxes  box type ({@code BONUS_BOX}, {@code CARGO_BOX}…) → rule
     * @param radius collect boxes within this distance of the hero
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Collect(Map<String, BoxRule> boxes, int radius, boolean stayAwayFromEnemies,
                          boolean ignoreContested) {

        public static final Collect DEFAULT = new Collect(null, 400, true, true);

        public Collect {
            boxes = boxes == null ? Map.of() : sorted(boxes);
            radius = radius <= 0 ? 400 : radius;
        }
    }

    /** @param waitMs wait on the box this long before the pickup (some boxes need it) */
    public record BoxRule(boolean collect, int waitMs, int priority) {
    }

    /**
     * @param gear the pet's mode ({@code PetGearTypeModule} name without the prefix, e.g. {@code GUARD},
     *             {@code ENEMY_LOCATOR}, {@code KAMIKAZE})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pet(boolean enabled, String gear) {

        public static final Pet DEFAULT = new Pet(false, null);
    }

    /**
     * When to repair and what to do after a death.
     *
     * @param repairHpMin   go repair below this HP fraction while attacked
     * @param repairHpMax   repair up to this HP fraction
     * @param repairHpNoNpc go repair below this with no target around
     * @param repairToShield also wait for this shield fraction
     * @param repairMode    ship mode while repairing / running to repair
     * @param heal          use heal items (repair robot, pod) without stopping
     * @param maxDeaths     stop the bot after this many deaths; 0 or less = no limit (DarkBot: -1)
     * @param revive        {@code BASE}, {@code PORTAL} or {@code SPOT} (DarkBot names)
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Safety(double repairHpMin, double repairHpMax, double repairHpNoNpc, double repairToShield,
                         ShipMode repairMode, Heal heal, int maxDeaths, String revive,
                         int waitBeforeReviveSec, int waitAfterReviveSec) {

        public static final Safety DEFAULT = new Safety(0.5, 0.9, 0.5, 0, null, null, 10, "PORTAL", 10, 10);

        public Safety {
            repairMode = repairMode == null ? new ShipMode(2, null) : repairMode;
            heal = heal == null ? Heal.DEFAULT : heal;
            revive = revive == null ? "PORTAL" : revive;
        }
    }

    /** Healing on the go: HP / shield thresholds ({@code -1} = off). */
    public record Heal(double hpBelow, double shieldBelow, double hpPodBelow) {

        public static final Heal DEFAULT = new Heal(0.9, -1, -1);
    }

    /**
     * Preferred and avoided areas of one map as grids of {@code resolution × resolution} cells over
     * the map (the way DarkBot stores them); a row is a string of {@code #} (in the zone) and {@code .}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Zones(ZoneGrid preferred, ZoneGrid avoided) {
    }

    /** See {@link Zones}. Row 0 is the top of the map. */
    public record ZoneGrid(int resolution, List<String> rows) {

        public ZoneGrid {
            rows = rows == null ? List.of() : List.copyOf(rows);
        }

        public boolean contains(double x, double y, double mapWidth, double mapHeight) {
            int cx = (int) (x / mapWidth * resolution);
            int cy = (int) (y / mapHeight * resolution);
            if (cy < 0 || cy >= rows.size() || cx < 0) {
                return false;
            }
            String row = rows.get(cy);
            return cx < row.length() && row.charAt(cx) == '#';
        }

        public boolean isEmpty() {
            return rows.stream().noneMatch(r -> r.indexOf('#') >= 0);
        }
    }

    /**
     * A place to run to (a portal, a station): {@code run} when to run there, {@code jump} when to
     * jump through it — {@code ALWAYS}, {@code ENEMY_IN_SIGHT}, {@code NEVER}… (DarkBot names).
     */
    public record SafetySpot(String type, int x, int y, String run, String jump) {
    }

    static <K, V> Map<K, V> sorted(Map<K, V> m) {
        return java.util.Collections.unmodifiableMap(new TreeMap<>(m));
    }
}
