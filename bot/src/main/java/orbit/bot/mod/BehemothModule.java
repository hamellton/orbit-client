package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mindfire Behemoth, solo, as AstraBot's «Mindfire Behemoth» module does it (the user, 2026-11-13; its settings from
 * the {@code BEHEMOTH} section of the user's AstraBot profile {@code behe.json}):
 * <ul>
 *   <li>the BL maps in turn ({@code maps}, AstraBot's firstMap/secondMap/thirdMap); on each a safe point near the
 *       behemoth's spawn to wait on (from BeheFarmBySpearhed, then the spawns the bot has seen);</li>
 *   <li>its times: each one seen and each death in sight is remembered ({@link SpawnMemory}, per account); the map
 *       whose behemoth comes soonest is chosen, the flight there counted; one never seen costs a minute more;</li>
 *   <li>no behemoth {@code changeMapSeconds} after the arrival (or after it was due): the next map;</li>
 *   <li>the behemoth (any owner — others' damage counts too) is fought by its row in the NPC list (A-BL, Drill, radius,
 *       RSB, HP Link); EMP-01 below {@code empPercent} of the hull, ISH-01 below {@code ishPercent};</li>
 *   <li>after the kill its box ({@code collectBox}) in the box mode ({@code collectBoxConfig}, {@code collectBoxFormation});</li>
 *   <li>while waiting: other NPCs only when they shoot at us ({@code defenceMode}) or all ({@code killOthers}).</li>
 * </ul>
 * Settings: config section {@code modules.behemoth}.
 */
public final class BehemothModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(BehemothModule.class);

    public static final java.nio.file.Path DEFAULT_FILE = java.nio.file.Path.of("local", "behemoth-spawns.json");
    /** The BL maps: 1-BL, 2-BL, 3-BL. */
    public static final List<Integer> BL_MAPS = List.of(306, 307, 308);
    /**
     * Where to wait and where it spawns (BeheFarmBySpearhed, «confirmed in-game», minimap units × 100); the spawns the
     * bot sees replace these.
     */
    static final Map<Integer, int[]> WAIT = Map.of(306, new int[] {19_100, 7_900}, 307, new int[] {5_200, 5_700},
            308, new int[] {5_700, 2_600});
    static final Map<Integer, int[]> SPAWN = Map.of(306, new int[] {19_400, 5_800}, 307, new int[] {6_900, 5_000},
            308, new int[] {4_700, 3_900});
    /** From one spawn to the next (BeheFarm 900 s; BLCycle ~15 min) until the bot has seen its own. */
    static final long DEFAULT_RESPAWN_MS = 15 * 60_000L;
    /** A flight to another BL map, roughly (BLCycle's travelSeconds 130 for the longest). */
    static final long TRAVEL_MS = 90_000;
    /** A map whose behemoth time is not known: a sure one by the clock wins unless this much later. */
    static final long UNKNOWN_PENALTY_MS = 60_000;
    /** A behemoth that vanishes this close was killed (farther, it may just have left the view). */
    static final double SEEN_DYING = 2_500;
    /** Its box drops a moment after the kill; looked for this long (BeheFarm: 5 s to appear, 20 s at most). */
    static final long BOX_APPEAR_MS = 5_000;
    static final long BOX_MAX_MS = 20_000;
    /** Close enough to the wait point (BeheFarm waitPointRadius 200). */
    static final double ARRIVE = 250;
    /** NPCs fought while waiting: within this of the wait point. */
    static final double LEASH = 1_500;

    private final BotContext ctx;
    private final SpawnMemory memory;
    private final Attacker attacker;
    private final BoxPicker boxes;
    private final long wallBase = System.currentTimeMillis();
    private final long nanoBase;
    /** Behemoths in sight: id → last position. */
    private final Map<Integer, int[]> seen = new HashMap<>();
    private int seenMap;
    /** Maps where the wait ran out with no behemoth (wall ms). */
    private final Map<Integer, Long> emptyAt = new HashMap<>();
    private int chosen;
    private long arrivedAt;
    private long waitUntil;
    /**
     * The map whose wait is counting and since when: a fight by the wait point takes the ship off it and back, and the
     * wait goes on — it does not start again (live 2026-11-13 on the VPS, 2-BL: Impulse II by the point, the 30 s
     * started afresh after each fight).
     */
    private int waitMap;
    private long waitStarted;
    private boolean onBehemoth;
    private int[] deadAt;
    private long deadAtNanos;
    private long checkAt;
    private String status = "Бегемот";
    /** Fights with players: defence, the group's, enemies, the black list (all off unless the config says). */
    private final PlayerFight players;
    /** Maps skipped until then (wall ms): too many deaths there, or too many enemies by the behemoth. */
    private final Map<Integer, Long> skipUntil = new HashMap<>();
    /** Deaths by the behemoth of a map since its last death in sight. */
    private final Map<Integer, Integer> deathsAt = new HashMap<>();
    private int lastDeaths = -1;
    private int fightMap;
    private long fightMapAt;
    private boolean trainTold;
    private String leaderTold;
    /** Learned flights between maps, "from>to" → ms (from leaving to the wait point). */
    private final Map<String, Long> travel = new HashMap<>();
    private int departFrom;
    private long departAt;
    private int departDeaths;
    /**
     * A flight longer is not learned: a repair or a death was on the way (live 2026-11-13 on the VPS: «1-BL → 2-BL: 3 мин
     * 58 с» with a death in between, against 1 мин 25 с).
     */
    static final long TRAVEL_LEARN_MAX_MS = 180_000;
    private boolean holdTold;
    /** The pet's combat beacon before the spawn ({@code beaconBeforeSeconds}). */
    private boolean beaconOn;
    private long cloakAskedAt = Long.MIN_VALUE / 2;
    /** The uridium reward of a kill ({@code rewardUridium}): the last balance, the last behemoth seen here. */
    private double lastUri = -1;
    private long behemothSeenAt;
    private int behemothSeenMap;
    private int[] behemothSeenAtPos;
    private long killedAt;
    private boolean killCounted;
    /** Leave this long before the flight says (a turn, a slow start). */
    static final long LEAD_MS = 5_000;

    public BehemothModule(BotContext ctx, SpawnMemory memory) {
        this.ctx = ctx;
        this.memory = memory;
        this.attacker = new Attacker(ctx);
        this.boxes = new BoxPicker(ctx);
        this.nanoBase = ctx.now();
        this.players = new PlayerFight(ctx);
    }

    private JsonNode settings() {
        return ctx.config().get().modules().get(BotConfig.Module.BEHEMOTH.key());
    }

    @Override
    public String status() {
        return status;
    }

    /** Wall-clock milliseconds on the bot's clock (a fake clock in tests). */
    public long wallMs() {
        return wallBase + (ctx.now() - nanoBase) / 1_000_000;
    }

    @Override
    public int workingMap() {
        WorldSnapshot.Hero h = ctx.world().get().hero();
        if (h.id() == 0 || h.mapId() == 0) {
            return 0; // not in game yet: where the ship is decides the choice
        }
        if (chosen == 0) {
            chosen = choose(h.mapId(), 0);
        }
        if (holding(h.mapId())) {
            return h.mapId();
        }
        if (chosen != h.mapId() && departFrom != h.mapId()) {
            departFrom = h.mapId(); // the flight starts: its time is learned on arrival
            departAt = wallMs();
            departDeaths = ctx.stats().deaths.get();
            holdTold = false;
        }
        return chosen;
    }

    /**
     * {@code arriveOnTime} (CARNAGE's BLCycle: «due in 129s, travel 130s, lead 0s»): the next map's behemoth is due
     * later than the flight there takes — stay on this map (by its wait point) until it is time to go, rather than
     * wait by the spawn in sight of everybody.
     */
    private boolean holding(int here) {
        if (chosen == 0 || chosen == here || !on(settings(), "arriveOnTime", false) || !maps(settings()).contains(here)) {
            return false;
        }
        long wall = wallMs();
        long due = dueAt(chosen, wall);
        return due > 0 && due - wall > travelMs(here, chosen) + LEAD_MS;
    }

    /**
     * The flight between two maps: learned, else {@link #TRAVEL_MS} a jump on the way (live 2026-11-13 on the VPS: from
     * the base 2-8 the ship went to 1-BL through 2-BL — the same 90 s to each made the first in the list win).
     */
    long travelMs(int from, int to) {
        if (from == to) {
            return 0;
        }
        Long learned = travel.get(from + ">" + to);
        if (learned != null) {
            return learned;
        }
        WorldSnapshot.Hero h = ctx.world().get().hero();
        int jumps = 1;
        try {
            jumps = Math.max(1, orbit.bot.nav.Router.route(ctx.maps(), from, h.x(), h.y(), to, h.factionId(),
                    h.level() > 0 ? h.level() : Integer.MAX_VALUE).size());
        } catch (RuntimeException e) {
            // no route known: one jump
        }
        return TRAVEL_MS * jumps;
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0 && onBehemoth || players.targetId() != 0;
    }

    @Override
    public int fightTarget() {
        return players.targetId() != 0 ? players.targetId() : attacker.targetId();
    }

    /**
     * In the behemoth's fight: HP Link (its row's flag) while the hull is below {@code hpLinkPercent} (AstraBot's
     * PET hpLinkMinPercent; 0 = always), otherwise the combat beacon ({@code usePetCombatBeacon}).
     */
    @Override
    public int petGear() {
        if (beaconOn && !onBehemoth) {
            return orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_COMBAT_BEACON;
        }
        if (!onBehemoth) {
            return attacker.fighting(BotConfig.NpcFlag.HP_LINK) ? orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK : -1;
        }
        JsonNode s = settings();
        double hp = ctx.world().get().hero().health().hpPercent();
        double linkBelow = percent(s, "hpLinkPercent", 0);
        boolean link = attacker.fighting(BotConfig.NpcFlag.HP_LINK) && (linkBelow <= 0 || hp >= 0 && hp < linkBelow);
        if (link) {
            return orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK;
        }
        return on(s, "usePetCombatBeacon", false) ? orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_COMBAT_BEACON : -1;
    }

    /** {@code petOnlyOnBehemoth}: out while a behemoth is in sight, in otherwise. */
    @Override
    public Boolean petOut() {
        if (beaconOn) {
            return true;
        }
        if (!on(settings(), "petOnlyOnBehemoth", false)) {
            return null;
        }
        return onBehemoth || findBehemoth(ctx.config().get(), ctx.world().get()) != null;
    }

    @Override
    public void pause() {
        attacker.clear();
        players.clear();
        boxes.clear();
        arrivedAt = 0;
    }

    @Override
    public void observe() {
        watch(ctx.world().get());
        countDeaths();
    }

    @Override
    public void tick() {
        BotConfig c = ctx.config().get();
        JsonNode s = settings();
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        watch(w);
        attacker.setAbilityPlan(new Attacker.AbilityPlan(on(s, "useEmp", true), percent(s, "empPercent", 35),
                on(s, "useIsh", true), percent(s, "ishPercent", 60),
                on(s, "usePrismatic", false) ? percent(s, "prismaticPercent", 50) : -1,
                on(s, "useSpearheadJamx", false) ? number(s, "jamxDistance", 750) : -1));

        countDeaths();
        WorldSnapshot.Ship b = findBehemoth(c, w);
        if (fightPlayers(c, s, w, b)) {
            return;
        }
        if (b != null && skipped(h.mapId())) {
            b = null; // given up here until it comes again: on to another map
            if (chosen == h.mapId()) {
                chosen = choose(h.mapId(), h.mapId());
            }
        }
        if (b != null) {
            if (!onBehemoth) {
                log.info("Бегемот на карте — к нему ({} от нас, HP {})", (int) b.distanceTo(h.x(), h.y()), hp(b));
                onBehemoth = true;
            }
            deadAt = null;
            arrivedAt = 0;
            beaconOn = false;
            fightMap = h.mapId();
            fightMapAt = ctx.now();
            if (attacker.targetId() != b.id()) {
                attacker.target(b);
            }
            if (train(c, s, w, b)) {
                return;
            }
            attacker.tick(true);
            status = "Бой: бегемот, HP " + hp(b);
            return;
        }
        if (onBehemoth) {
            onBehemoth = false;
            attacker.clear();
        }
        if (deadAt != null) {
            if (afterKill(s)) {
                return;
            }
            deadAt = null;
            chosen = choose(h.mapId(), h.mapId());
            arrivedAt = 0;
            if (chosen != h.mapId()) {
                status = "Дальше: " + ctx.maps().info(chosen).name();
                return; // the runner flies there
            }
        }
        if (followLeader(s, w)) {
            return;
        }
        if (h.mapId() != chosen && holding(h.mapId())) {
            if (fightWhileWaiting(c, s, w)) {
                return;
            }
            hold(s, h);
            return;
        }
        if (h.mapId() != chosen) {
            return; // the runner flies there first
        }
        if (fightWhileWaiting(c, s, w)) {
            return;
        }
        waitHere(s, h);
    }

    /** The map is given up until its behemoth comes again. */
    private boolean skipped(int map) {
        Long until = skipUntil.get(map);
        return until != null && until > wallMs();
    }

    private void skip(int map, String why) {
        long until = wallMs() + respawn(map);
        skipUntil.put(map, until);
        deathsAt.remove(map);
        log.info("{}: {} — пропускаю карту до следующего бегемота ({})", ctx.maps().info(map).name(), why,
                clock(until - wallMs()));
    }

    /**
     * Deaths by the behemoth (the fight was there a moment before): {@code trainMode} with {@code deathsPerSpawn} — so
     * many there and the map is skipped until its next behemoth (CARNAGE's DEATH_COUNT, orbithelper's deathCount).
     */
    private void countDeaths() {
        int d = ctx.stats().deaths.get();
        if (lastDeaths >= 0 && d > lastDeaths && fightMap != 0 && ctx.now() - fightMapAt < 90_000_000_000L) {
            JsonNode s = settings();
            int n = deathsAt.merge(fightMap, d - lastDeaths, Integer::sum);
            int limit = (int) number(s, "deathsPerSpawn", 0);
            if (on(s, "trainMode", false) && limit > 0) {
                if (n >= limit) {
                    skip(fightMap, "погиб у бегемота " + n + " раз");
                    if (chosen == fightMap) {
                        chosen = choose(fightMap, fightMap);
                    }
                } else {
                    log.info("Погиб у бегемота на {}: {} из {}", ctx.maps().info(fightMap).name(), n, limit);
                }
            }
            fightMap = 0;
        }
        lastDeaths = d;
    }

    /**
     * A train of others by the behemoth ({@code trainMode}; CARNAGE's BLCycle: their attacks do not make us run,
     * {@code behemothPolicy = IGNORE_ATTACKED_USE_COUNT}): more enemies than {@code trainEnemyLimit} within
     * {@code trainRadius} of it — another map; with {@code avoidEnemyOrbit} the ship stands on the far side of the
     * behemoth from them, at its radius, and shoots from there instead of circling through their ring. True when it
     * took over the tick.
     */
    private boolean train(BotConfig c, JsonNode s, WorldSnapshot w, WorldSnapshot.Ship b) {
        if (!on(s, "trainMode", false)) {
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        double radius = number(s, "trainRadius", 1_500);
        java.util.Set<Integer> group = PlayerFight.group(w);
        List<String> black = names(s, "blacklist");
        List<WorldSnapshot.Ship> enemies = w.ships().values().stream()
                .filter(x -> PlayerFight.enemy(x, h, group, black))
                .filter(x -> Math.hypot(x.x() - b.x(), x.y() - b.y()) <= radius)
                .toList();
        int limit = (int) number(s, "trainEnemyLimit", 0);
        if (limit > 0 && enemies.size() > limit) {
            attacker.clear();
            skip(h.mapId(), "у бегемота врагов " + enemies.size() + " (больше " + limit + ")");
            chosen = choose(h.mapId(), h.mapId());
            onBehemoth = false;
            return true;
        }
        if (enemies.isEmpty()) {
            trainTold = false;
            return false;
        }
        if (!trainTold) {
            trainTold = true;
            log.info("У бегемота чужие: {} — бью дальше{}", enemies.size(),
                    on(s, "avoidEnemyOrbit", true) ? ", стою с другой стороны от них" : "");
        }
        if (!on(s, "avoidEnemyOrbit", true)) {
            return false;
        }
        double ex = enemies.stream().mapToInt(WorldSnapshot.Ship::x).average().orElse(b.x());
        double ey = enemies.stream().mapToInt(WorldSnapshot.Ship::y).average().orElse(b.y());
        double dx = b.x() - ex;
        double dy = b.y() - ey;
        double len = Math.hypot(dx, dy);
        if (len < 1) {
            dx = h.x() - b.x();
            dy = h.y() - b.y();
            len = Math.max(1, Math.hypot(dx, dy));
        }
        double r = Targets.radius(c, b, h.mapId());
        double px = b.x() + dx / len * r;
        double py = b.y() + dy / len * r;
        if (Math.hypot(px - h.x(), py - h.y()) > 150) {
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - px, ctx.nav().destination().y() - py) > 120) {
                ctx.nav().moveTo(px, py, true);
            }
            ctx.nav().tick();
        }
        attacker.tick(false);
        status = "Бой: бегемот, HP " + hp(b) + "; чужих " + enemies.size() + " — стою с другой стороны";
        return true;
    }

    /**
     * Players to fight (all off by default): those shooting at us within {@code respondRadius} ({@code respondPlayers},
     * CARNAGE's PvpHelper / DefenseMode respondAttacks), those shooting at our group within {@code huntRadius}
     * ({@code defendGroup}, DefenseMode helpAttack), the black list in sight ({@code blacklist}, AstraBot's BLACKLIST),
     * any enemy within {@code huntRadius} ({@code huntEnemies}, AstraBot's chaseEnemy / chaseDistance, the CITA
     * follower's ATTACK_PLAYER). By the behemoth: only with {@code enemyFocus = ENEMY} (CARNAGE FOCUS_ENEMY_FIRST),
     * otherwise its fight comes first (BLCycle: their attacks are ignored).
     */
    private boolean fightPlayers(BotConfig c, JsonNode s, WorldSnapshot w, WorldSnapshot.Ship b) {
        boolean respond = on(s, "respondPlayers", false);
        boolean defend = on(s, "defendGroup", false);
        boolean hunt = on(s, "huntEnemies", false);
        List<String> black = names(s, "blacklist");
        if (!respond && !defend && !hunt && black.isEmpty()) {
            players.clear();
            return false;
        }
        boolean enemyFirst = "ENEMY".equals(text(s, "enemyFocus", "BEHEMOTH"));
        if (b != null && !enemyFirst && !skipped(w.hero().mapId())) {
            players.clear();
            return false; // the behemoth first
        }
        String ammo = text(s, "pvpAmmo", "");
        ammo = ammo.isBlank() ? null : ammo;
        if (players.targetId() != 0) {
            if (players.tick(ammo)) {
                attacker.clear();
                status = "Бой с игроком " + players.targetName();
                return true;
            }
        }
        WorldSnapshot.Hero h = w.hero();
        java.util.Set<Integer> group = PlayerFight.group(w);
        double respondR = number(s, "respondRadius", 500);
        double huntR = number(s, "huntRadius", 1_500);
        WorldSnapshot.Ship pick = null;
        String why = null;
        List<WorldSnapshot.Ship> enemies = w.ships().values().stream()
                .filter(x -> PlayerFight.enemy(x, h, group, black))
                .sorted(Comparator.comparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .toList();
        for (WorldSnapshot.Ship e : enemies) {
            double d = e.distanceTo(h.x(), h.y());
            if (respond && d <= respondR && (e.attackTargetId() == h.id() || h.attackers().contains(e.id()))) {
                pick = e;
                why = "стреляет по нам — отвечаю";
                break;
            }
        }
        for (int i = 0; pick == null && i < enemies.size(); i++) {
            WorldSnapshot.Ship e = enemies.get(i);
            double d = e.distanceTo(h.x(), h.y());
            if (defend && d <= huntR && e.attackTargetId() != 0 && group.contains(e.attackTargetId())) {
                pick = e;
                why = "бьёт нашу группу — защищаю";
            } else if (!black.isEmpty() && e.name() != null && black.contains(e.name()) && d <= huntR) {
                pick = e;
                why = "в чёрном списке";
            } else if (hunt && d <= huntR) {
                pick = e;
                why = "враг рядом — атакую";
            }
        }
        if (pick == null) {
            return false;
        }
        attacker.clear();
        players.target(pick, why);
        players.tick(ammo);
        status = "Бой с игроком " + pick.name();
        return true;
    }

    /**
     * {@code followLeader} (AstraBot's followLeader / leaderId, the CITA follower's GROUP_MASTER): the leader is a
     * member of our group by {@code leader} (name or id; empty — the group's leader). On another map — there; on ours
     * and no behemoth — close to him ({@code followDistance}), on what he fights if it is an enemy player in sight. No
     * leader in the group — on our own. True when it took over the tick.
     */
    private boolean followLeader(JsonNode s, WorldSnapshot w) {
        if (!on(s, "followLeader", false)) {
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        String who = text(s, "leader", "").strip();
        WorldSnapshot.Member leader = null;
        for (WorldSnapshot.Member m : w.group().members()) {
            if (m.id() == h.id()) {
                continue;
            }
            boolean byName = !who.isEmpty() && (who.equalsIgnoreCase(m.name()) || who.equals(String.valueOf(m.id())));
            boolean byGroup = who.isEmpty() && m.id() == w.group().leaderId();
            if (byName || byGroup) {
                leader = m;
            }
        }
        if (leader == null || leader.loggedOut()) {
            String told = who.isEmpty() ? "у группы нет лидера" : who + " нет в группе";
            if (!told.equals(leaderTold)) {
                leaderTold = told;
                log.info("За лидером: {} — работаю сам", told);
            }
            return false;
        }
        if (!leader.name().equals(leaderTold)) {
            leaderTold = leader.name();
            log.info("Иду за лидером {}", leader.name());
        }
        if (leader.mapId() != 0 && leader.mapId() != h.mapId()) {
            chosen = leader.mapId();
            status = "За лидером на " + ctx.maps().info(leader.mapId()).name();
            return true; // the runner flies there
        }
        chosen = h.mapId();
        WorldSnapshot.Ship ship = w.ships().get(leader.id());
        double lx = ship != null ? ship.x() : leader.x();
        double ly = ship != null ? ship.y() : leader.y();
        String target = leader.targetName();
        if (target != null && !target.isBlank()) {
            java.util.Set<Integer> group = PlayerFight.group(w);
            WorldSnapshot.Ship t = w.ships().values().stream()
                    .filter(x -> target.equals(x.name()) && PlayerFight.enemy(x, h, group, names(s, "blacklist")))
                    .findFirst().orElse(null);
            if (t != null) {
                attacker.clear();
                players.target(t, "его бьёт лидер");
                String ammo = text(s, "pvpAmmo", "");
                players.tick(ammo.isBlank() ? null : ammo);
                status = "С лидером бью игрока " + t.name();
                return true;
            }
        }
        double keep = number(s, "followDistance", 400);
        double dist = Math.hypot(lx - h.x(), ly - h.y());
        ctx.ship().want(ctx.config().get().general().roam());
        if (dist > keep) {
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - lx, ctx.nav().destination().y() - ly) > keep / 2) {
                ctx.nav().moveTo(lx, ly, dist < 2_500);
            }
            ctx.nav().tick();
        }
        status = "Иду за лидером " + leader.name();
        return true;
    }

    /** A list of names in the settings. */
    private static List<String> names(JsonNode s, String key) {
        List<String> out = new ArrayList<>();
        if (s != null && s.path(key).isArray()) {
            for (JsonNode n : s.path(key)) {
                if (!n.asText("").isBlank()) {
                    out.add(n.asText().strip());
                }
            }
        }
        return out;
    }

    /** Holding on this map until it is time to fly on: by its wait point, cloaked if set. */
    private void hold(JsonNode s, WorldSnapshot.Hero h) {
        long wall = wallMs();
        long leaveIn = dueAt(chosen, wall) - travelMs(h.mapId(), chosen) - LEAD_MS - wall;
        if (!holdTold) {
            holdTold = true;
            log.info("Бегемот на {} через {}, перелёт {} — жду здесь, вылет через {}", ctx.maps().info(chosen).name(),
                    clock(dueAt(chosen, wall) - wall), clock(travelMs(h.mapId(), chosen)), clock(leaveIn));
        }
        int[] p = waitPoint(h.mapId());
        double dist = Math.hypot(p[0] - h.x(), p[1] - h.y());
        BotConfig.General g = ctx.config().get().general();
        if (dist > ARRIVE) {
            ctx.ship().want(g.travel());
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - p[0], ctx.nav().destination().y() - p[1]) > 50) {
                ctx.nav().moveTo(p[0], p[1], dist < 2_500);
            }
            ctx.nav().tick();
        } else {
            ctx.ship().want(g.roam());
            cloak(s, h);
        }
        status = "Жду вылета на " + ctx.maps().info(chosen).name() + ": " + clock(leaveIn);
    }

    /**
     * {@code useCloak} (CARNAGE's BLCycle useCloak, AstraBot's cloak): cloaked while waiting — a CL04K CPU, the biggest
     * there is; {@code cloakFormation} (BLCycle: Heart) is set first. Attacking takes the cloak off by itself.
     */
    private void cloak(JsonNode s, WorldSnapshot.Hero h) {
        if (!on(s, "useCloak", false)) {
            return;
        }
        String formation = formation(text(s, "cloakFormation", ""));
        if (formation != null) {
            ctx.ship().want(new BotConfig.ShipMode(ctx.config().get().general().roam().config(), formation));
        }
        long now = ctx.now();
        if (h.cloaked() || now - cloakAskedAt < 15_000_000_000L) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        for (String id : orbit.config.ItemCatalog.CLOAKS) {
            WorldSnapshot.Item i = w.items().get(id);
            if (i != null && i.available() && !i.blocked() && i.counter() > 0 && i.remainingMs(now) == 0) {
                log.info("Маскировка: {} (осталось {})", orbit.config.ItemCatalog.label(id), (long) i.counter() - 1);
                ctx.actions().useItem(id);
                cloakAskedAt = now;
                return;
            }
        }
        cloakAskedAt = now; // none left: the buy list («Покупки», CL04K-XL) may bring one
    }

    /**
     * {@code beaconBeforeSeconds} (CARNAGE's DMG_BEACON «spawn beacon X s before the main NPC»): that long before the
     * behemoth is due, the ship goes close to its spawn and the pet puts out its combat beacon there. True while that
     * lasts (the ship there or on its way).
     */
    private boolean beacon(JsonNode s, WorldSnapshot.Hero h, long wall) {
        long before = (long) number(s, "beaconBeforeSeconds", 0) * 1_000;
        long due = dueAt(h.mapId(), wall);
        boolean window = before > 0 && due > 0 && wall >= due - before
                && wall <= due + (long) number(s, "changeMapSeconds", 30) * 1_000;
        if (!window) {
            beaconOn = false;
            return false;
        }
        if (!beaconOn) {
            beaconOn = true;
            log.info("Бегемот через {} — ставлю боевой маяк питомца у места появления", clock(Math.max(0, due - wall)));
        }
        int[] sp = spawnPoint(h.mapId());
        double dist = Math.hypot(sp[0] - h.x(), sp[1] - h.y());
        if (dist > 400) {
            ctx.ship().want(ctx.config().get().general().travel());
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - sp[0], ctx.nav().destination().y() - sp[1]) > 100) {
                ctx.nav().moveTo(sp[0], sp[1], true);
            }
            ctx.nav().tick();
            status = "Лечу ставить маяк у места появления";
            return true;
        }
        ctx.nav().stop();
        ctx.ship().want(ctx.config().get().general().roam());
        status = "Маяк у места появления, бегемот через " + clock(Math.max(0, due - wall));
        arrivedAt = 0; // the wait counts afresh after the window
        return true;
    }

    /** Where the behemoth appears on the map: seen, else BeheFarm's point, else the wait point. */
    int[] spawnPoint(int map) {
        List<SpawnMemory.Spawn> known = memory.spawns(map);
        if (!known.isEmpty()) {
            return new int[] {known.get(0).x(), known.get(0).y()};
        }
        int[] sp = SPAWN.get(map);
        return sp != null ? sp.clone() : waitPoint(map);
    }

    /**
     * The kill's uridium ({@code rewardUridium}, CARNAGE's BLCycle: «behemoth_uri_reward», > 5 000): the balance rises
     * that much within a minute of a behemoth in sight — the kill is ours for sure; one that left the view without the
     * server's word is recorded dead then (its timer starts), and its box is looked for.
     */
    private void credit(WorldSnapshot.Hero h, long now) {
        double uri = h.uridium();
        double min = number(settings(), "rewardUridium", 5_000);
        if (lastUri >= 0 && min > 0 && uri - lastUri >= min && behemothSeenMap == h.mapId() && now - behemothSeenAt < 60_000) {
            log.info("Бегемот засчитан: +{} уридия", String.format("%,.0f", uri - lastUri).replace(',', ' '));
            if (killedAt < behemothSeenAt) {
                // it left the view without the server's word: dead now
                int[] p = behemothSeenAtPos;
                memory.killed(h.mapId(), p[0], p[1], now);
                deathsAt.remove(h.mapId());
                deadAt = p;
                deadAtNanos = ctx.now();
                killedAt = now;
                killCounted = false;
                seen.clear();
            }
            if (!killCounted) {
                ctx.stats().kills.incrementAndGet();
                killCounted = true;
            }
        }
        lastUri = uri;
    }

    /** The box after the kill (true while busy with it). */
    private boolean afterKill(JsonNode s) {
        if (!on(s, "collectBox", true)) {
            return false;
        }
        long since = (ctx.now() - deadAtNanos) / 1_000_000;
        if (since > BOX_MAX_MS) {
            return false;
        }
        int cfg = (int) number(s, "collectBoxConfig", 2);
        String formation = formation(text(s, "collectBoxFormation", "Veteran"));
        ctx.ship().want(new BotConfig.ShipMode(cfg, formation));
        if (boxes.tick(deadAt[0], deadAt[1])) {
            status = "Подбираю " + boxes.currentName() + " с бегемота";
            return true;
        }
        if (since < BOX_APPEAR_MS) {
            ctx.nav().stop();
            status = "Жду ящик с бегемота";
            return true;
        }
        return false;
    }

    /** Other NPCs while no behemoth: those shooting at us ({@code defenceMode}) or all ({@code killOthers}). */
    private boolean fightWhileWaiting(BotConfig c, JsonNode s, WorldSnapshot w) {
        boolean all = on(s, "killOthers", false);
        boolean defence = on(s, "defenceMode", true);
        if (!all && !defence) {
            attacker.clear();
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        int[] p = waitPoint(h.mapId());
        if (attacker.targetId() != 0) {
            WorldSnapshot.Ship t = w.ships().get(attacker.targetId());
            boolean leashed = t == null || Math.hypot(t.x() - p[0], t.y() - p[1]) <= LEASH;
            if (leashed && attacker.tick(true)) {
                status = "Бой у точки ожидания";
                return true;
            }
            attacker.clear();
        }
        WorldSnapshot.Ship t = w.ships().values().stream()
                .filter(WorldSnapshot.Ship::npc)
                .filter(x -> !isBehemoth(x))
                .filter(x -> Math.hypot(x.x() - p[0], x.y() - p[1]) <= LEASH)
                .filter(x -> !attacker.gaveUp(x.id()))
                .filter(x -> all ? Targets.attackable(c, x, h.mapId())
                        : (x.attackTargetId() == h.id() || h.attackers().contains(x.id())) && !x.cloaked())
                .min(Comparator.comparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .orElse(null);
        if (t == null) {
            return false;
        }
        if (!all) {
            log.info("{} стреляет по нам у точки ожидания — отвечаю", t.name());
        }
        attacker.target(t);
        attacker.tick(true);
        status = "Бой у точки ожидания";
        return true;
    }

    /** Flies to the map's wait point and waits; the wait over and no behemoth: the next map. */
    private void waitHere(JsonNode s, WorldSnapshot.Hero h) {
        if (beacon(s, h, wallMs())) {
            return;
        }
        int[] p = waitPoint(h.mapId());
        double dist = Math.hypot(p[0] - h.x(), p[1] - h.y());
        BotConfig.General g = ctx.config().get().general();
        long now = ctx.now();
        if (dist > ARRIVE) {
            arrivedAt = 0;
            ctx.ship().want(g.travel());
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - p[0], ctx.nav().destination().y() - p[1]) > 50) {
                ctx.nav().moveTo(p[0], p[1], dist < 2_500);
            }
            ctx.nav().tick();
            status = "Лечу ждать бегемота: " + p[0] / 100 + "/" + p[1] / 100;
            return;
        }
        ctx.ship().want(g.roam());
        long wall = wallMs();
        long change = (long) number(s, "changeMapSeconds", 30) * 1_000;
        if (arrivedAt == 0 && departFrom != 0 && departFrom != h.mapId() && departAt > 0) {
            long took = wall - departAt;
            if (took > 10_000 && took < TRAVEL_LEARN_MAX_MS && ctx.stats().deaths.get() == departDeaths) {
                String key = departFrom + ">" + h.mapId();
                Long before = travel.get(key);
                travel.put(key, before == null ? took : (before + took) / 2);
                log.info("Перелёт {} → {}: {}", ctx.maps().info(departFrom).name(), ctx.maps().info(h.mapId()).name(),
                        clock(took));
            }
            departFrom = 0;
            departAt = 0;
        }
        cloak(s, h);
        if (arrivedAt == 0) {
            arrivedAt = now;
            ctx.nav().stop();
            if (waitMap != h.mapId()) {
                waitMap = h.mapId();
                waitStarted = now;
                long due = dueAt(h.mapId(), wall);
                long waitMs = due > wall ? due - wall + change : change;
                waitUntil = now + waitMs * 1_000_000L;
                log.info("Жду бегемота на {}{}", ctx.maps().info(h.mapId()).name(),
                        due > wall ? " — по времени через " + clock(due - wall) : "");
            }
        }
        if (now >= checkAt) {
            checkAt = now + 5_000_000_000L;
            int better = choose(h.mapId(), 0);
            if (better != h.mapId() && dueAt(h.mapId(), wall) <= 0) {
                // here nothing is due by the clock, there one is: leave now, the flight counted
                log.info("На {} бегемот по времени раньше — перелетаю", ctx.maps().info(better).name());
                emptyAt.put(h.mapId(), wall);
                chosen = better;
                arrivedAt = 0;
                waitMap = 0;
                return;
            }
        }
        if (now >= waitUntil) {
            emptyAt.put(h.mapId(), wall);
            int next = choose(h.mapId(), h.mapId());
            log.info("Бегемота на {} нет {} с — {}", ctx.maps().info(h.mapId()).name(), (now - waitStarted) / 1_000_000_000L,
                    next == h.mapId() ? "жду дальше" : "лечу на " + ctx.maps().info(next).name());
            chosen = next;
            arrivedAt = 0;
            waitMap = 0;
            return;
        }
        status = "Жду бегемота, " + clock((waitUntil - now) / 1_000_000);
    }

    /**
     * The map to go to: where the behemoth can be caught soonest, the flight there counted. {@code leaving} is not
     * chosen while another map is (the wait there just ran out).
     */
    int choose(int here, int leaving) {
        List<Integer> maps = maps(settings());
        long wall = wallMs();
        int best = 0;
        long bestAt = Long.MAX_VALUE;
        for (int m : maps) {
            if (m == leaving && maps.size() > 1 || skipped(m) && maps.stream().anyMatch(x -> !skipped(x))) {
                continue;
            }
            long arrive = wall + travelMs(here, m);
            long due = dueAt(m, wall);
            long empty = emptyAt.getOrDefault(m, 0L);
            long at;
            if (due > 0) {
                at = Math.max(due, arrive);
            } else if (empty > 0 && wall - empty < respawn(m)) {
                // looked a while ago and none came: within one respawn of that, on average halfway; never later than a map
                // nobody has looked at yet (live 2026-11-13 on the VPS: a tie sent the ship back to 1-BL, 3-BL unseen)
                at = Math.max(empty + respawn(m) / 2, arrive) + UNKNOWN_PENALTY_MS + 1_000;
            } else {
                at = arrive + UNKNOWN_PENALTY_MS;
            }
            if (at < bestAt) {
                bestAt = at;
                best = m;
            }
        }
        if (best == 0) {
            best = maps.isEmpty() ? here : maps.get(0);
        }
        return best;
    }

    /** When the map's behemoth is due by its last death in sight, or 0 when not known (or long past). */
    public long dueAt(int map, long wall) {
        long respawn = respawn(map);
        long best = 0;
        boolean roll = on(settings(), "arriveOnTime", false);
        long grace = (long) number(settings(), "changeMapSeconds", 30) * 1_000 + 60_000;
        for (SpawnMemory.Spawn k : memory.spawns(map)) {
            long at = k.expectedAt(respawn);
            if (roll && at > 0 && at + grace < wall && k.lastSeen() < at) {
                // nobody watched it come (BLCycle's «unwatched rollover»): taken by others then, the next one a respawn on
                at += respawn * ((wall - grace - at) / respawn + 1);
            }
            if (at > 0 && at + respawn > wall && (best == 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private long respawn(int map) {
        long learned = memory.mapRespawn(map);
        long minutes = (long) number(settings(), "respawnMinutes", 0);
        return minutes > 0 ? minutes * 60_000 : learned > 0 ? learned : DEFAULT_RESPAWN_MS;
    }

    /** Where to wait on the map: the config's, else BeheFarm's; a map not in the table — the spawn seen there. */
    int[] waitPoint(int map) {
        JsonNode p = settings() == null ? null : settings().path("waitPoints").path(String.valueOf(map));
        if (p != null && p.isArray() && p.size() == 2) {
            return new int[] {p.get(0).asInt(), p.get(1).asInt()};
        }
        int[] w = WAIT.get(map);
        if (w != null) {
            return w.clone();
        }
        List<SpawnMemory.Spawn> known = memory.spawns(map);
        if (!known.isEmpty()) {
            return new int[] {known.get(0).x(), known.get(0).y()};
        }
        WorldSnapshot.Hero h = ctx.world().get().hero();
        return new int[] {h.x(), h.y()};
    }

    /** Remembers behemoths appearing and dying in sight. */
    private void watch(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = wallMs();
        if (h.mapId() != seenMap) {
            seen.clear();
            seenMap = h.mapId();
            waitMap = 0; // another map: a wait here later starts afresh
        }
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc() && isBehemoth(s)) {
                if (!seen.containsKey(s.id())) {
                    memory.seen(h.mapId(), s.x(), s.y(), now);
                    emptyAt.remove(h.mapId());
                }
                seen.put(s.id(), new int[] {s.x(), s.y()});
                behemothSeenAt = now;
                behemothSeenMap = h.mapId();
                behemothSeenAtPos = new int[] {s.x(), s.y()};
            }
        }
        credit(h, now);
        seen.entrySet().removeIf(e -> {
            if (w.ships().containsKey(e.getKey())) {
                return false;
            }
            int[] p = e.getValue();
            boolean dead = w.destroyed().containsKey(e.getKey()) || Math.hypot(p[0] - h.x(), p[1] - h.y()) <= SEEN_DYING;
            if (dead) {
                boolean ours = attacker.targetId() == e.getKey() && attacker.shooting();
                log.info("Бегемот у {}/{} уничтожен{}", p[0] / 100, p[1] / 100, ours ? "" : " (не нами)");
                if (ours) {
                    ctx.stats().kills.incrementAndGet();
                }
                killedAt = now;
                killCounted = ours;
                attacker.clear();
                memory.killed(h.mapId(), p[0], p[1], now);
                deathsAt.remove(h.mapId());
                deadAt = p;
                deadAtNanos = ctx.now();
                ctx.nav().stop();
            }
            return true;
        });
    }

    static boolean isBehemoth(WorldSnapshot.Ship s) {
        return s.name() != null && s.name().toLowerCase(java.util.Locale.ROOT).contains("behemoth");
    }

    /** The nearest live behemoth the list allows (any owner: the damage counts for everyone who hit it). */
    static WorldSnapshot.Ship findBehemoth(BotConfig c, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        return w.ships().values().stream()
                .filter(WorldSnapshot.Ship::npc)
                .filter(BehemothModule::isBehemoth)
                .filter(x -> {
                    BotConfig.NpcRule r = Targets.rule(c, x, h.mapId());
                    return r == null || r.kill();
                })
                .min(Comparator.comparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    /** The maps to go round ({@code maps}), the BL maps when none are set. */
    static List<Integer> maps(JsonNode s) {
        List<Integer> out = new ArrayList<>();
        if (s != null && s.path("maps").isArray()) {
            for (JsonNode m : s.path("maps")) {
                if (m.asInt() > 0 && !out.contains(m.asInt())) {
                    out.add(m.asInt());
                }
            }
        }
        return out.isEmpty() ? BL_MAPS : out;
    }

    /** A formation by its name in AstraBot («Veteran») or its item id. */
    static String formation(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        if (name.startsWith("drone_formation_")) {
            return name;
        }
        for (orbit.config.ItemCatalog.Item i : orbit.config.ItemCatalog.FORMATIONS) {
            if (i.label().toLowerCase(java.util.Locale.ROOT).startsWith(name.strip().toLowerCase(java.util.Locale.ROOT) + " (")) {
                return i.id();
            }
        }
        return null;
    }

    private static String hp(WorldSnapshot.Ship s) {
        double p = s.health().hpPercent();
        return p < 0 ? "?" : Math.round(p * 100) + " %";
    }

    private static String clock(long ms) {
        long sec = Math.max(0, ms / 1_000);
        return sec >= 60 ? sec / 60 + " мин " + sec % 60 + " с" : sec + " с";
    }

    private static boolean on(JsonNode s, String key, boolean def) {
        return s == null || !s.has(key) ? def : s.get(key).asBoolean(def);
    }

    private static double number(JsonNode s, String key, double def) {
        return s == null || !s.has(key) ? def : s.get(key).asDouble(def);
    }

    /** A percent setting (35) as a fraction; 0 or less = off (-1). */
    private static double percent(JsonNode s, String key, double def) {
        double v = number(s, key, def);
        return v <= 0 ? -1 : v / 100;
    }

    private static String text(JsonNode s, String key, String def) {
        return s == null || !s.has(key) ? def : s.get(key).asText(def);
    }
}
