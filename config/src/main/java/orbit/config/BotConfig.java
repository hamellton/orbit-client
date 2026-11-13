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
        Map<String, JsonNode> extra,
        Buy buy,
        Session session,
        Flee flee,
        GroupRules group) {

    public static final int SCHEMA = 3;

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
        buy = buy == null ? Buy.OFF : buy;
        session = session == null ? Session.ENDLESS : session;
        flee = flee == null ? Flee.from(general) : flee;
        group = group == null ? GroupRules.OFF : group;
    }

    public BotConfig(int schema, String description, Module module, General general, Map<String, NpcRule> npcs,
                     Loot loot, Collect collect, Pet pet, Safety safety, Map<Integer, Zones> zones,
                     Map<Integer, List<SafetySpot>> safetySpots, Map<String, JsonNode> modules,
                     Map<String, JsonNode> extra, Buy buy, Session session, Flee flee) {
        this(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra,
                buy, session, flee, null);
    }

    public BotConfig withGroup(GroupRules g) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots,
                modules, extra, buy, session, flee, g);
    }

    /**
     * Group invitations and the group (AstraBot's INVITATION section).
     *
     * @param accept       {@link Accept}: whose invitations to accept
     * @param rejectOthers reject the invitations not accepted (else let them time out)
     * @param invite       invite the listed players who are not in the group
     * @param players      names (any case) or {@code [TAG]} clans: whom to accept, invite, keep
     * @param leaveWithoutListed leave a group with none of the listed players in it
     * @param kickUnlisted as the leader, remove members not on the list
     * @param leader       as the leader, hand the lead to this player when in the group ("" = no)
     * @param openInvites  as the leader: {@code true} everybody may invite, {@code false} only the leader,
     *                     {@code null} leave it
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record GroupRules(Accept accept, boolean rejectOthers, boolean invite, Set<String> players,
                             boolean leaveWithoutListed, boolean kickUnlisted, String leader, Boolean openInvites) {

        public static final GroupRules OFF = new GroupRules(Accept.OFF, false, false, null, false, false, null, null);

        public GroupRules {
            accept = accept == null ? Accept.OFF : accept;
            players = players == null ? Set.of() : java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(players));
            leader = leader == null ? "" : leader.strip();
        }
    }

    public enum Accept {
        /** do not accept (and with {@code rejectOthers}: reject all — like blocking invitations) */
        OFF,
        /** only from the listed players */
        LIST,
        /** from anybody */
        ANY
    }

    public BotConfig(int schema, String description, Module module, General general, Map<String, NpcRule> npcs,
                     Loot loot, Collect collect, Pet pet, Safety safety, Map<Integer, Zones> zones,
                     Map<Integer, List<SafetySpot>> safetySpots, Map<String, JsonNode> modules,
                     Map<String, JsonNode> extra, Buy buy, Session session) {
        this(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra,
                buy, session, null);
    }

    public BotConfig withFlee(Flee f) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots,
                modules, extra, buy, session, f, group);
    }

    /**
     * Running from players.
     *
     * @param mode        {@link FleeMode}
     * @param players     names (exact, any case) or clan tags in brackets ({@code [TAG]}) for {@code LIST}
     * @param never       never run from these (friends, own clans), same notation
     * @param sight       {@code ENEMIES_IN_SIGHT}: an enemy this close counts
     * @param rememberSec stay away this long after the enemy is out of sight
     * @param jump        jump through the portal when the enemy follows or shoots there
     * @param waitSec     wait this long on the other side before going back
     * @param avoid       keep away from the players who shot at the ship or killed it this session (whatever
     *                    {@code mode} says): work on, but no nearer than {@code avoidRadius} to one in sight
     * @param avoidRadius how far from such a player
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    /**
     * @param stationRadius keep this far from another company's clan battle stations (they fire at the ship;
     *                      Blacklight, 2026-11-12); 0 = off, {@code null} in an older file = {@link #STATION_RADIUS}
     * @param npcs          get out of the reach of an NPC that shoots the ship though the config does not fight it
     *                      (live 2026-11-13: Abide I killed the ship roaming for Impulse II); {@code null} = on
     */
    public record Flee(FleeMode mode, Set<String> players, Set<String> never, int sight, int rememberSec, boolean jump,
                       int waitSec, boolean avoid, int avoidRadius, Integer stationRadius, Boolean npcs) {

        public Flee(FleeMode mode, Set<String> players, Set<String> never, int sight, int rememberSec, boolean jump,
                    int waitSec, boolean avoid, int avoidRadius, Integer stationRadius) {
            this(mode, players, never, sight, rememberSec, jump, waitSec, avoid, avoidRadius, stationRadius, null);
        }

        public static final int AVOID_RADIUS = 2_500;
        public static final int STATION_RADIUS = 1_600;

        public Flee(FleeMode mode, Set<String> players, Set<String> never, int sight, int rememberSec, boolean jump,
                    int waitSec, boolean avoid, int avoidRadius) {
            this(mode, players, never, sight, rememberSec, jump, waitSec, avoid, avoidRadius, null);
        }

        public static final Flee OFF = new Flee(FleeMode.OFF, null, null, 2_000, 60, true, 90);

        public Flee(FleeMode mode, Set<String> players, Set<String> never, int sight, int rememberSec, boolean jump,
                    int waitSec) {
            this(mode, players, never, sight, rememberSec, jump, waitSec, false, AVOID_RADIUS);
        }

        public Flee {
            mode = mode == null ? FleeMode.OFF : mode;
            players = players == null ? Set.of() : java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(players));
            never = never == null ? Set.of() : java.util.Collections.unmodifiableSortedSet(new java.util.TreeSet<>(never));
            sight = sight <= 0 ? 2_000 : sight;
            rememberSec = Math.max(0, rememberSec);
            waitSec = Math.max(0, waitSec);
            avoidRadius = avoidRadius <= 0 ? AVOID_RADIUS : avoidRadius;
            stationRadius = stationRadius == null ? STATION_RADIUS : Math.max(0, stationRadius);
            npcs = npcs == null || npcs;
        }

        /** From the older switches in {@link General} (and DarkBot's RUNNING section). */
        static Flee from(General g) {
            if (g == null || !g.runFromEnemies()) {
                return OFF;
            }
            return new Flee(g.runFromEnemiesInSight() ? FleeMode.ENEMIES_IN_SIGHT : FleeMode.ATTACKERS, null, null, 2_000,
                    g.rememberEnemiesSec(), true, 90);
        }
    }

    public enum FleeMode {
        /** ignore players */
        OFF,
        /** only players shooting at the ship */
        ATTACKERS,
        /** any enemy player within {@link Flee#sight} */
        ENEMIES_IN_SIGHT,
        /** only the listed players and clans (seen within sight or attacking) */
        LIST
    }

    public BotConfig(int schema, String description, Module module, General general, Map<String, NpcRule> npcs,
                     Loot loot, Collect collect, Pet pet, Safety safety, Map<Integer, Zones> zones,
                     Map<Integer, List<SafetySpot>> safetySpots, Map<String, JsonNode> modules,
                     Map<String, JsonNode> extra, Buy buy) {
        this(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra,
                buy, null);
    }

    public BotConfig withSession(Session ss) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots,
                modules, extra, buy, ss, flee, group);
    }

    /**
     * Sessions like a player's (AstraBot's break time): work {@code workMinutes}, then a break of
     * {@code breakMinutes}, both moved by up to {@code jitterMinutes}; {@code workMinutes} 0 = no limit.
     */
    public record Session(int workMinutes, int breakMinutes, int jitterMinutes) {

        public static final Session ENDLESS = new Session(0, 0, 0);

        public Session {
            workMinutes = Math.max(0, workMinutes);
            breakMinutes = Math.max(0, breakMinutes);
            jitterMinutes = Math.max(0, jitterMinutes);
        }
    }

    public BotConfig(int schema, String description, Module module, General general, Map<String, NpcRule> npcs,
                     Loot loot, Collect collect, Pet pet, Safety safety, Map<Integer, Zones> zones,
                     Map<Integer, List<SafetySpot>> safetySpots, Map<String, JsonNode> modules,
                     Map<String, JsonNode> extra) {
        this(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra,
                null);
    }

    public BotConfig withBuy(Buy b) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots,
                modules, extra, b, session, flee, group);
    }

    /**
     * Buying ammunition when it runs low: for each item, buy {@code amount} when fewer than {@code below}
     * are left; never spend below {@code keepCredits} / {@code keepUridium}. Boosters too (AstraBot's
     * BOOSTERS): each listed shop code ({@code DMG-B01}) is bought again when less than
     * {@code boosterMinutes} of it is left (0 = when it has run out).
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Buy(boolean enabled, List<BuyRule> items, double keepCredits, double keepUridium,
                      List<String> boosters, int boosterMinutes) {

        public static final Buy OFF = new Buy(false, List.of(), 0, 0);

        public Buy {
            items = items == null ? List.of() : List.copyOf(items);
            boosters = boosters == null ? List.of() : List.copyOf(boosters);
            boosterMinutes = Math.max(0, boosterMinutes);
        }

        public Buy(boolean enabled, List<BuyRule> items, double keepCredits, double keepUridium) {
            this(enabled, items, keepCredits, keepUridium, List.of(), 0);
        }
    }

    /** @param item menu item id ({@code ammunition_laser_lcb-10}) */
    public record BuyRule(String item, int below, int amount) {
    }

    /** An empty config: kill and collect on 1-1 with default thresholds. */
    public static BotConfig empty() {
        return new BotConfig(SCHEMA, "", Module.KILL_COLLECT, null, null, null, null, null, null, null, null, null, null);
    }

    public BotConfig withModule(Module m) {
        return new BotConfig(schema, description, m, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withGeneral(General g) {
        return new BotConfig(schema, description, module, g, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withNpcs(Map<String, NpcRule> n) {
        return new BotConfig(schema, description, module, general, n, loot, collect, pet, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withSafety(Safety s) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, s, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withDescription(String d) {
        return new BotConfig(schema, d, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withLoot(Loot l) {
        return new BotConfig(schema, description, module, general, npcs, l, collect, pet, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withCollect(Collect c) {
        return new BotConfig(schema, description, module, general, npcs, loot, c, pet, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withPet(Pet p) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, p, safety, zones, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withZones(Map<Integer, Zones> z) {
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, z, safetySpots, modules, extra, buy, session, flee, group);
    }

    public BotConfig withModuleSettings(String key, JsonNode settings) {
        Map<String, JsonNode> m = new TreeMap<>(modules);
        m.put(key, settings);
        return new BotConfig(schema, description, module, general, npcs, loot, collect, pet, safety, zones, safetySpots, m, extra, buy, session, flee, group);
    }

    /** Settings of a module, or an empty object. */
    public ObjectNode moduleSettings(String key) {
        JsonNode n = modules.get(key);
        return n instanceof ObjectNode o ? o.deepCopy() : Json.MAPPER.createObjectNode();
    }

    /** The rule for an NPC by its in-game name ({@code -=[ Streuner ]=-}), or {@code null}. */
    /**
     * The rule for an NPC as the game names it ({@code -=[ Protegit ]=-}); a rule under the bare name
     * ({@code Protegit}, as the game's NPC table has it) counts too.
     */
    public NpcRule npc(String name) {
        NpcRule r = npcs.get(name);
        if (r != null || name == null) {
            return r;
        }
        String core = bare(name);
        for (Map.Entry<String, NpcRule> e : npcs.entrySet()) {
            if (bare(e.getKey()).equals(core)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** An NPC name without the game's decoration: {@code -=[ Protegit ]=-} → {@code protegit}. */
    public static String bare(String name) {
        return BARE.computeIfAbsent(name, n -> n.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT));
    }

    /** {@link #bare} is asked for every ship every tick: names repeat, the answers are kept. */
    private static final java.util.concurrent.ConcurrentHashMap<String, String> BARE = new java.util.concurrent.ConcurrentHashMap<>();

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
     * @param baseRadius     keep this far from enemy stations: other factions' battle stations, their modules,
     *                       headquarters and station turrets ({@code 0} = fly anywhere); AstraBot's {@code radiusForBases}
     */
    public record General(int workingMap, ShipMode offensive, ShipMode roam, ShipMode run,
                          boolean runFromEnemies, boolean runFromEnemiesInSight, int rememberEnemiesSec,
                          boolean roamSequential, boolean onlyKillPreferred, boolean runModeTravel,
                          boolean runModeChase, boolean avoidMines, int baseRadius) {

        /** Enemy bases kept off by default, like AstraBot. */
        public static final int DEFAULT_BASE_RADIUS = 500;

        public General(int workingMap, ShipMode offensive, ShipMode roam, ShipMode run, boolean runFromEnemies,
                       boolean runFromEnemiesInSight, int rememberEnemiesSec, boolean roamSequential,
                       boolean onlyKillPreferred, boolean runModeTravel, boolean runModeChase) {
            this(workingMap, offensive, roam, run, runFromEnemies, runFromEnemiesInSight, rememberEnemiesSec,
                    roamSequential, onlyKillPreferred, runModeTravel, runModeChase, false, DEFAULT_BASE_RADIUS);
        }

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
            baseRadius = Math.max(0, baseRadius);
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
     * @param launcher rocket launcher rockets for this NPC; {@code null} = {@link Loot#launcher},
     *                 {@link Loot#NO_ROCKETS} = none
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo,
                          boolean sab, String rocket, String launcher, String move, String formation) {

        /** @param formation the drone formation fighting it ({@code drone_formation_…}), {@code null} = the config's */
        public NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo,
                       boolean sab, String rocket, String launcher, String move) {
            this(radius, priority, kill, flags, maps, ammo, sab, rocket, launcher, move, null);
        }

        public NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo,
                       boolean sab, String rocket, String launcher) {
            this(radius, priority, kill, flags, maps, ammo, sab, rocket, launcher, null);
        }

        /** How to move fighting this NPC: its own choice, else {@code fallback} (the config's). */
        public FightMove fightMove(FightMove fallback) {
            FightMove m = FightMove.parse(move);
            return m != null ? m : fallback;
        }

        public NpcRule(double radius, int priority, boolean kill, Set<String> flags, Set<Integer> maps, String ammo,
                       boolean sab, String rocket) {
            this(radius, priority, kill, flags, maps, ammo, sab, rocket, null);
        }

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

    /**
     * What the NPC list's flags and the kill tab add to a fight (AstraBot's «Invoke XVI» profile, 2026-11-13).
     *
     * @param ishBelow     ISH-01 (flag {@code USE_ISH}) when the hull is below this share
     * @param avoidTrains  leave a target with a train by it: {@code trainPlayers} or more players not of the group
     *                     within {@code trainRadius} (AstraBot «avoidTrain»)
     * @param emp          how much EMP-01 may be spent (flag {@code USE_EMP}); {@code null} = the usual
     * @param ish          how much ISH-01 may be spent (flag {@code USE_ISH}); {@code null} = the usual
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FightOptions(double ishBelow, boolean avoidTrains, int trainPlayers, int trainRadius, Spend emp,
                               Spend ish) {

        public static final FightOptions DEFAULT = new FightOptions(0.6, false, 3, 1500, null, null);

        public FightOptions(double ishBelow, boolean avoidTrains, int trainPlayers, int trainRadius) {
            this(ishBelow, avoidTrains, trainPlayers, trainRadius, null, null);
        }

        public FightOptions {
            ishBelow = ishBelow <= 0 || ishBelow > 1 ? 0.6 : ishBelow;
            trainPlayers = trainPlayers <= 0 ? 3 : trainPlayers;
            trainRadius = trainRadius <= 0 ? 1500 : trainRadius;
        }

        public Spend empSpend() {
            return emp == null ? Spend.EMP : emp;
        }

        public Spend ishSpend() {
            return ish == null ? Spend.ISH : ish;
        }
    }

    /**
     * How much of an ability item the bot may spend (the user, 2026-11-13: limits for EMP-01 and ISH-01).
     *
     * @param perFight at most this many times a fight, 0 = no limit
     * @param gapSeconds at least this long between two uses
     * @param perHour  at most this many an hour, 0 = no limit
     * @param perDay   at most this many in 24 hours, 0 = no limit
     * @param keep     never spend the last this many on the account
     */
    public record Spend(int perFight, int gapSeconds, int perHour, int perDay, int keep) {

        public static final Spend EMP = new Spend(1, 5, 0, 0, 0);
        public static final Spend ISH = new Spend(0, 5, 0, 0, 0);

        public Spend {
            perFight = Math.max(0, perFight);
            gapSeconds = Math.max(1, gapSeconds);
            perHour = Math.max(0, perHour);
            perDay = Math.max(0, perDay);
            keep = Math.max(0, keep);
        }
    }

    /** Known NPC flags (DarkBot's NPC extras and gate flags under the same names). */
    /** How the ship moves while it fights an NPC (docs/FIGHT-MOVEMENT.md). */
    public enum FightMove {
        /** by the NPC's speed, seen by the bot: fast — circle, slow — hold the distance, still — stand */
        AUTO("Авто (по скорости NPC)"),
        CIRCLE("Кружить вокруг цели"),
        KITE("Держать дистанцию (отходить)"),
        STAND("Стоять на радиусе");

        public final String label;

        FightMove(String label) {
            this.label = label;
        }

        /** {@code null} for nothing or an unknown word. */
        public static FightMove parse(String s) {
            if (s == null || s.isBlank()) {
                return null;
            }
            try {
                return valueOf(s.strip().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

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
        KAMIKAZE,
        /** do not fly round it */
        NO_CIRCLE,
        /** EMP-01 once a fight with it, in range (AstraBot «Use EMP»: other players lose their lock on it) */
        USE_EMP,
        /** ISH-01 fighting it when the hull is below the config's threshold (AstraBot «Use ISH») */
        USE_ISH,
        /** the pet in HP Link while it is fought (AstraBot «HP Link») */
        HP_LINK
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
     * @param circle      fly round the target at its radius (NPCs flagged {@code NO_CIRCLE} excepted)
     * @param fallback    ammunition to take, in order, when the wanted one has run out
     * @param launcher    rocket launcher rockets to keep selected ({@code null} = as in the game, {@link #NO_ROCKETS} = none)
     * @param fireLauncher the bot fires the launcher itself when it is loaded (ships without the launcher CPU)
     * @param move        how to move in a fight ({@link FightMove}); {@code null} in an older file = {@code circle}
     *                    on → circle, off → stand
     * @param runCircle   the run configuration to catch a nearly dead target (below 25 %) that got farther than
     *                    twice the radius (DarkBot's {@code RUN_CONFIG_IN_CIRCLE})
     * @param skipCrowded not to take a target among other NPCs that attack on sight (off: taken like DarkBot does;
     *                    the user, 2026-11-13: skipping them left the ship flying about for nothing)
     * @param smartCircle points looked at each way to turn the circle (DarkBot's {@code MAX_CIRCLE_ITERATIONS}, 0 =
     *                    never turn; {@code null} = {@link #SMART_CIRCLE})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                       boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                       List<String> fallback, String launcher, boolean fireLauncher, String move, Boolean runCircle,
                       Boolean skipCrowded, Integer smartCircle, FightOptions options) {

        /** DarkBot's usual «smart circling iterations». */
        public static final int SMART_CIRCLE = 5;

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                    boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                    List<String> fallback, String launcher, boolean fireLauncher, String move, Boolean runCircle,
                    Boolean skipCrowded, Integer smartCircle) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, circle,
                    fallback, launcher, fireLauncher, move, runCircle, skipCrowded, smartCircle, null);
        }

        /** The fight options of the list's flags (ISH threshold, trains), the usual ones when none. */
        public FightOptions fight() {
            return options == null ? FightOptions.DEFAULT : options;
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                    boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                    List<String> fallback, String launcher, boolean fireLauncher, String move, Boolean runCircle,
                    Boolean skipCrowded) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, circle,
                    fallback, launcher, fireLauncher, move, runCircle, skipCrowded, null);
        }

        /** The points looked at each way to turn the circle. */
        public int circleIterations() {
            return smartCircle == null ? SMART_CIRCLE : Math.max(0, smartCircle);
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                    boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                    List<String> fallback, String launcher, boolean fireLauncher, String move, Boolean runCircle) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, circle,
                    fallback, launcher, fireLauncher, move, runCircle, (Boolean) null);
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                    boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                    List<String> fallback, String launcher, boolean fireLauncher, String move) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, circle,
                    fallback, launcher, fireLauncher, move, (Boolean) null);
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                    boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                    List<String> fallback, String launcher, boolean fireLauncher) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, circle,
                    fallback, launcher, fireLauncher, (String) null);
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb,
                    boolean groupNpcs, int ignoreFartherThan, String rocket, boolean fireRockets, boolean circle,
                    List<String> fallback) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, circle,
                    fallback, null, false);
        }

        public static final String NO_ROCKETS = "none";
        public static final Loot DEFAULT = new Loot(null, null, 0, 0, null, true, 0);

        public Loot {
            fallback = fallback == null ? List.of() : List.copyOf(fallback);
            runCircle = runCircle != null && runCircle;
        }

        /** Whether a target among NPCs that attack on sight is left; off unless set. */
        public boolean skipsCrowded() {
            return skipCrowded != null && skipCrowded;
        }

        /** The config's way of moving in a fight. */
        public FightMove fightMove() {
            FightMove m = FightMove.parse(move);
            return m != null ? m : circle ? FightMove.CIRCLE : FightMove.STAND;
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb, boolean groupNpcs,
                    int ignoreFartherThan) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, null, false);
        }

        public Loot(String ammo, String sab, double sabBelow, int sabNpcShieldMin, String rsb, boolean groupNpcs,
                    int ignoreFartherThan, String rocket, boolean fireRockets) {
            this(ammo, sab, sabBelow, sabNpcShieldMin, rsb, groupNpcs, ignoreFartherThan, rocket, fireRockets, false, null);
        }
    }

    /**
     * @param boxes  box type ({@code BONUS_BOX}, {@code CARGO_BOX}…) → rule
     * @param radius collect boxes within this distance of the hero
     * @param cargo  what to do when the cargo hold is full: {@link #CARGO_NONE}, {@link #CARGO_STOP} (no more boxes
     *               that fill it), {@link #CARGO_BASE} (sell at the own base's refinery), {@link #CARGO_PET} (sell
     *               through the pet's trade pod)
     * @param cargoFullAt the hold counts as full at this much free space or less
     * @param sellOres the ores sold ({@code OreTypeModule} numbers: 0 prometium, 1 endurium, 2 terbium, 4 prometid,
     *                 5 duranium, 6 promerium, 7 seprom…)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Collect(Map<String, BoxRule> boxes, int radius, boolean stayAwayFromEnemies,
                          boolean ignoreContested, String cargo, int cargoFullAt, List<Integer> sellOres) {

        public static final String CARGO_NONE = "NONE";
        public static final String CARGO_STOP = "STOP";
        public static final String CARGO_BASE = "BASE";
        public static final String CARGO_PET = "PET";
        /** The raw and refined ores sold by default; promerium and seprom are kept for upgrades. */
        public static final List<Integer> DEFAULT_SELL = List.of(0, 1, 2, 4, 5);
        public static final Collect DEFAULT = new Collect(null, 400, true, true);

        public Collect {
            boxes = boxes == null ? Map.of() : sorted(boxes);
            radius = radius <= 0 ? 400 : radius;
            cargo = cargo == null || cargo.isBlank() ? CARGO_NONE : cargo;
            sellOres = sellOres == null ? DEFAULT_SELL : List.copyOf(sellOres);
        }

        public Collect(Map<String, BoxRule> boxes, int radius, boolean stayAwayFromEnemies, boolean ignoreContested) {
            this(boxes, radius, stayAwayFromEnemies, ignoreContested, CARGO_NONE, 0, null);
        }

        /**
         * A box that fills the cargo hold: a ship's cargo ({@code FROM_SHIP…}), ore ({@code ore_…}) and the ore
         * resources (Mucosum, Boltrum, Prismatium, Scrapium, Luminium, Bifenon… — ore types in the game).
         */
        public static boolean fillsCargo(String boxType) {
            if (boxType == null) {
                return false;
            }
            String t = boxType.toUpperCase(java.util.Locale.ROOT);
            return t.startsWith("FROM_SHIP") || t.startsWith("ORE_") || CARGO_ORES.contains(t.replaceFirst("_\\d+$", ""));
        }

        private static final java.util.Set<String> CARGO_ORES = java.util.Set.of("PROMETIUM", "ENDURIUM", "TERBIUM",
                "XENOMIT", "PROMETID", "DURANIUM", "PROMERIUM", "SEPROM", "PALLADIUM", "MUCOSUM", "BOLTRUM", "SCRAPIUM",
                "PRISMATIUM", "LUMINIUM", "BIFENON", "OSMIUM", "CHROMIN");
    }

    /** @param waitMs wait on the box this long before the pickup (some boxes need it) */
    public record BoxRule(boolean collect, int waitMs, int priority) {
    }

    /**
     * @param gear the pet's mode ({@code PetGearTypeModule} name without the prefix, e.g. {@code GUARD},
     *             {@code ENEMY_LOCATOR}, {@code KAMIKAZE})
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Pet(boolean enabled, String gear, int locatorTarget, boolean noRepair, double comboRepairBelow,
                      Kamikaze kamikaze) {

        /** The locator's NPC number when none is set: Cubikon (matched by the locator's points, 2026-11-11). */
        public static final int CUBIKON_LOCATOR = 13;
        public static final Pet DEFAULT = new Pet(false, null);

        /**
         * @param locatorTarget the NPC the enemy locator looks for (the game's NPC number, e.g. 13 = Cubikon;
         *                      0 = {@link #CUBIKON_LOCATOR})
         * @param noRepair      do not repair the pet when it is destroyed (repairing is the default)
         * @param comboRepairBelow the combo ship repair below this hero HP fraction, 0 = off
         */
        public Pet {
            locatorTarget = locatorTarget <= 0 ? CUBIKON_LOCATOR : locatorTarget;
            kamikaze = kamikaze == null ? Kamikaze.OFF : kamikaze;
        }

        public Pet(boolean enabled, String gear) {
            this(enabled, gear, CUBIKON_LOCATOR, false, 0, null);
        }

        public Pet(boolean enabled, String gear, int locatorTarget, boolean noRepair, double comboRepairBelow) {
            this(enabled, gear, locatorTarget, noRepair, comboRepairBelow, null);
        }
    }

    /**
     * The pet's kamikaze: its mode while NPCs crowd round it, the config's mode again after.
     *
     * @param enabled  use it at all
     * @param minNpcs  this many NPCs within {@code radius} of the pet (0 = not a reason)
     * @param flagged  an NPC flagged {@link NpcFlag#KAMIKAZE} in the NPC list within {@code radius}
     * @param radius   around the pet
     * @param everyMinutes not again sooner after the pet blew up (it is repaired each time); 0 = as soon as the game's
     *                     cooldown allows
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Kamikaze(boolean enabled, int minNpcs, boolean flagged, int radius, int everyMinutes) {

        public static final Kamikaze OFF = new Kamikaze(false, 0, true, 600, 0);

        public Kamikaze {
            radius = radius <= 0 ? 600 : radius;
            everyMinutes = Math.max(0, everyMinutes);
        }
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
     * @param paidRevives   a revive at the portal or on the spot this many times in a row at most, then one at the
     *                      base (free) and the count starts again; 0 = no limit
     * @param paidReviveMinutes «in a row»: killed again within this many minutes after a paid revive
     * @param escapeHp      on the repair trip, at the portal under fire: jump below this HP fraction; -1 = never
     *                      ({@code null} in an older file = {@link #DEFAULT_ESCAPE_HP})
     * @param oneWayJumps   jump (escaping or fleeing) also through a portal with no portal back (5-2 → 5-3,
     *                      5-3 → 4-4): the way back is then the whole loop round the maps; {@code null} = no
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Safety(double repairHpMin, double repairHpMax, double repairHpNoNpc, double repairToShield,
                         ShipMode repairMode, Heal heal, int maxDeaths, String revive,
                         int waitBeforeReviveSec, int waitAfterReviveSec, int paidRevives, int paidReviveMinutes,
                         Double escapeHp, Boolean oneWayJumps) {

        public static final int DEFAULT_PAID_REVIVES = 2;
        public static final int DEFAULT_PAID_REVIVE_MINUTES = 3;
        public static final double DEFAULT_ESCAPE_HP = 0.25;
        public static final Safety DEFAULT = new Safety(0.5, 0.9, 0.5, 0, null, null, 10, "PORTAL", 10, 10);

        public Safety(double repairHpMin, double repairHpMax, double repairHpNoNpc, double repairToShield,
                      ShipMode repairMode, Heal heal, int maxDeaths, String revive,
                      int waitBeforeReviveSec, int waitAfterReviveSec, int paidRevives, int paidReviveMinutes) {
            this(repairHpMin, repairHpMax, repairHpNoNpc, repairToShield, repairMode, heal, maxDeaths, revive,
                    waitBeforeReviveSec, waitAfterReviveSec, paidRevives, paidReviveMinutes, null, null);
        }

        public Safety(double repairHpMin, double repairHpMax, double repairHpNoNpc, double repairToShield,
                      ShipMode repairMode, Heal heal, int maxDeaths, String revive,
                      int waitBeforeReviveSec, int waitAfterReviveSec) {
            this(repairHpMin, repairHpMax, repairHpNoNpc, repairToShield, repairMode, heal, maxDeaths, revive,
                    waitBeforeReviveSec, waitAfterReviveSec, DEFAULT_PAID_REVIVES, DEFAULT_PAID_REVIVE_MINUTES);
        }

        public Safety {
            repairMode = repairMode == null ? new ShipMode(2, null) : repairMode;
            heal = heal == null ? Heal.DEFAULT : heal;
            revive = revive == null ? "PORTAL" : revive;
            escapeHp = escapeHp == null ? DEFAULT_ESCAPE_HP : escapeHp;
            oneWayJumps = oneWayJumps != null && oneWayJumps;
        }

        /** The same with the portal-jump settings. */
        public Safety withJumps(double escapeHp, boolean oneWayJumps) {
            return new Safety(repairHpMin, repairHpMax, repairHpNoNpc, repairToShield, repairMode, heal, maxDeaths,
                    revive, waitBeforeReviveSec, waitAfterReviveSec, paidRevives, paidReviveMinutes, escapeHp,
                    oneWayJumps);
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
