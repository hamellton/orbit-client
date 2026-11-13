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
    private boolean onBehemoth;
    private int[] deadAt;
    private long deadAtNanos;
    private long checkAt;
    private String status = "Бегемот";

    public BehemothModule(BotContext ctx, SpawnMemory memory) {
        this.ctx = ctx;
        this.memory = memory;
        this.attacker = new Attacker(ctx);
        this.boxes = new BoxPicker(ctx);
        this.nanoBase = ctx.now();
    }

    private JsonNode settings() {
        return ctx.config().get().modules().get(BotConfig.Module.BEHEMOTH.key());
    }

    @Override
    public String status() {
        return status;
    }

    /** Wall-clock milliseconds on the bot's clock (a fake clock in tests). */
    long wallMs() {
        return wallBase + (ctx.now() - nanoBase) / 1_000_000;
    }

    @Override
    public int workingMap() {
        WorldSnapshot.Hero h = ctx.world().get().hero();
        if (chosen == 0) {
            chosen = choose(h.mapId(), 0);
        }
        return chosen;
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0 && onBehemoth;
    }

    @Override
    public int fightTarget() {
        return attacker.targetId();
    }

    @Override
    public int petGear() {
        return attacker.fighting(BotConfig.NpcFlag.HP_LINK) ? orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK : -1;
    }

    @Override
    public void pause() {
        attacker.clear();
        boxes.clear();
        arrivedAt = 0;
    }

    @Override
    public void observe() {
        watch(ctx.world().get());
    }

    @Override
    public void tick() {
        BotConfig c = ctx.config().get();
        JsonNode s = settings();
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        watch(w);
        attacker.setAbilityPlan(new Attacker.AbilityPlan(on(s, "useEmp", true), percent(s, "empPercent", 35),
                on(s, "useIsh", true), percent(s, "ishPercent", 60)));

        WorldSnapshot.Ship b = findBehemoth(c, w);
        if (b != null) {
            if (!onBehemoth) {
                log.info("Бегемот на карте — к нему ({} от нас, HP {})", (int) b.distanceTo(h.x(), h.y()), hp(b));
                onBehemoth = true;
            }
            deadAt = null;
            arrivedAt = 0;
            if (attacker.targetId() != b.id()) {
                attacker.target(b);
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
        if (h.mapId() != chosen) {
            return; // the runner flies there first
        }
        if (fightWhileWaiting(c, s, w)) {
            return;
        }
        waitHere(s, h);
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
        if (arrivedAt == 0) {
            arrivedAt = now;
            ctx.nav().stop();
            long due = dueAt(h.mapId(), wall);
            long waitMs = due > wall ? due - wall + change : change;
            waitUntil = now + waitMs * 1_000_000L;
            log.info("Жду бегемота на {}{}", ctx.maps().info(h.mapId()).name(),
                    due > wall ? " — по времени через " + clock(due - wall) : "");
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
                return;
            }
        }
        if (now >= waitUntil) {
            emptyAt.put(h.mapId(), wall);
            int next = choose(h.mapId(), h.mapId());
            log.info("Бегемота на {} нет {} с — {}", ctx.maps().info(h.mapId()).name(), (now - arrivedAt) / 1_000_000_000L,
                    next == h.mapId() ? "жду дальше" : "лечу на " + ctx.maps().info(next).name());
            chosen = next;
            arrivedAt = 0;
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
            if (m == leaving && maps.size() > 1) {
                continue;
            }
            long arrive = wall + (m == here ? 0 : TRAVEL_MS);
            long due = dueAt(m, wall);
            long empty = emptyAt.getOrDefault(m, 0L);
            long at;
            if (due > 0) {
                at = Math.max(due, arrive);
            } else if (empty > 0 && wall - empty < respawn(m)) {
                // looked a while ago and none came: within one respawn of that, on average halfway
                at = Math.max(empty + respawn(m) / 2, arrive) + UNKNOWN_PENALTY_MS;
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
    long dueAt(int map, long wall) {
        long respawn = respawn(map);
        long best = 0;
        for (SpawnMemory.Spawn k : memory.spawns(map)) {
            long at = k.expectedAt(respawn);
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
        }
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc() && isBehemoth(s)) {
                if (!seen.containsKey(s.id())) {
                    memory.seen(h.mapId(), s.x(), s.y(), now);
                    emptyAt.remove(h.mapId());
                }
                seen.put(s.id(), new int[] {s.x(), s.y()});
            }
        }
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
                attacker.clear();
                memory.killed(h.mapId(), p[0], p[1], now);
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
