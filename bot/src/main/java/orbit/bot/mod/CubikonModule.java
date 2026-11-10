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
 * The Cubikon camp on the x-6 maps (the user's working DarkBot profile, GE5 Cubi Camp logic):
 * <ul>
 *   <li>a live Cubikon on the map — even one others fight — is attacked at once;</li>
 *   <li>its boxes are picked up while it has more than {@code boxesWhileCubikonHpAbove} HP;</li>
 *   <li>no Cubikon: other NPCs within {@code waitKillRange} of the spot ({@code waitKill}: {@code OFF},
 *       {@code ATTACKERS} — only those shooting at us, {@code ALL}), boxes, otherwise
 *       the spawn spots: fly within {@code arriveDistance}, wait {@code waitSeconds} (0 = until a Cubikon
 *       shows up), next spot;</li>
 *   <li>a Cubikon that dies in sight (whoever killed it): its boxes, then straight to the next spawn,
 *       no fights on the way;</li>
 *   <li>NPCs the list marks "не бить" (Protegit) are never shot.</li>
 * </ul>
 * Spots ({@code spotMode}): {@code ROUND} — the config's spots in turn, nearest first; {@code LEARNED} —
 * also the spawns the bot has seen ({@link SpawnMemory}), going where a Cubikon is due back soonest,
 * otherwise where they appeared most recently. With {@code learnSpawns} every new Cubikon and every
 * death in sight is remembered. Settings: config section {@code modules.cubikon}.
 */
public final class CubikonModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(CubikonModule.class);

    static final int[][] DEFAULT_SPOTS = {{7500, 3900}, {13400, 3900}, {13400, 7900}, {7500, 7900}};
    static final double DEFAULT_WAIT_KILL_RANGE = 1_500;
    /** A Cubikon that vanishes this close was killed (farther, it may just have left the view). */
    static final double SEEN_DYING = 2_500;
    /** See {@link #chooseLearned}. */
    static final long UNKNOWN_PENALTY_MS = 60_000;
    /** A look around on a spawn of unknown state before choosing again. */
    static final long LOOK_MS = 5_000;

    /** The server shows ships about this far: a spawn this close with no cube in the list is empty. */
    static final double IN_VIEW = 1_800;
    /** …and still worth waiting for this long after it was due. */
    static final long DUE_GRACE_MS = 3 * 60_000L;

    private final BotContext ctx;
    private final SpawnMemory memory;
    private final Attacker attacker;
    private final BoxPicker boxes;
    private final long wallBase = System.currentTimeMillis();
    private final long nanoBase;
    /** Cubikons in sight: id → last position. */
    private final Map<Integer, int[]> cubes = new HashMap<>();
    private int cubesMap;
    private int[] spot;
    private int roundIndex = -1;
    private long arrivedAt;
    private long waitUntil;
    private boolean onCube;
    /** A Cubikon just died here: head for the next spawn at once, no fights on the way. */
    private int[] deadAt;
    private boolean moveOn;
    private String status = "кубы";

    public CubikonModule(BotContext ctx, SpawnMemory memory) {
        this.ctx = ctx;
        this.memory = memory;
        this.attacker = new Attacker(ctx);
        this.boxes = new BoxPicker(ctx);
        this.nanoBase = ctx.now();
    }

    @Override
    public String status() {
        return status + " (убито " + attacker.kills() + ", ящиков " + boxes.boxes() + ")";
    }

    @Override
    public int workingMap() {
        return ctx.config().get().general().workingMap();
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0;
    }

    @Override
    public void pause() {
        attacker.clear();
        boxes.clear();
        arrivedAt = 0;
        spot = null;
    }

    /** Wall-clock milliseconds on the bot's clock (a fake clock in tests). */
    public long wallMs() {
        return wallBase + (ctx.now() - nanoBase) / 1_000_000;
    }

    @Override
    public void tick() {
        BotConfig c = ctx.config().get();
        JsonNode s = c.modules().get(BotConfig.Module.CUBIKON.key());
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        watch(w, setting(s, "learnSpawns", true)); // also from observe(): watching twice in a tick is harmless

        WorldSnapshot.Ship cube = findCubikon(c, w);
        if (cube != null) {
            if (!onCube) {
                log.info("Cubikon на карте — к нему ({} от нас)", (int) cube.distanceTo(h.x(), h.y()));
                onCube = true;
            }
            arrivedAt = 0;
            attacker.target(cube);
            double hp = cube.health().hpPercent();
            boolean boxesOk = setting(s, "collectBoxes", true)
                    && (hp < 0 || hp > setting(s, "boxesWhileCubikonHpAbove", 0.25));
            boolean onBox = boxesOk && boxes.tick(cube.x(), cube.y());
            attacker.tick(!onBox);
            status = onBox ? "Cubikon: подбираю ящик" : "Cubikon: бой";
            return;
        }
        if (onCube) {
            onCube = false;
            spot = null; // pick the next spot afresh
        }
        if (moveOn) {
            // the cube is dead: on to the next spawn whoever shoots at us; its boxes are still picked up
            attacker.clear();
            if (setting(s, "collectBoxes", true) && boxes.tick(deadAt[0], deadAt[1])) {
                status = "подбираю ящик с куба";
                return;
            }
            camp(s, h);
            return;
        }
        if (spot == null) {
            // the spot first: fights while waiting are tied to it
            choose(s, h, "LEARNED".equals(text(s, "spotMode", "ROUND")), (int) setting(s, "waitSeconds", 90));
        }
        double leash = setting(s, "waitKillRange", DEFAULT_WAIT_KILL_RANGE);
        if (attacker.targetId() != 0 && !onLeash(w, leash)) {
            log.info("Цель уводит от спауна — бросаю её и возвращаюсь");
            attacker.clear();
            ctx.nav().stop();
        }
        boolean chase = setting(s, "chaseFromSpot", false);
        if (attacker.targetId() != 0 && !chase && !inReach(w)) {
            attacker.clear(); // it moved off: standing on the spawn, no chase
        }
        if (attacker.targetId() != 0 && !attacker.tick(chase)) {
            attacker.clear(); // killed: camp() flies back to the spot
        }
        if (attacker.targetId() != 0) {
            status = "бью, пока жду куб";
            return;
        }
        String waitKill = waitKill(s);
        if (!"OFF".equals(waitKill)) {
            WorldSnapshot.Ship t = waitTarget(c, w, waitKill, leash, chase);
            if (t != null) {
                attacker.target(t);
                attacker.tick(chase);
                status = "бью, пока жду куб";
                return;
            }
        }
        if (setting(s, "collectBoxes", true) && spot != null && boxes.tick(spot[0], spot[1])) {
            status = "подбираю ящик";
            return;
        }
        camp(s, h);
    }

    @Override
    public void observe() {
        JsonNode s = ctx.config().get().modules().get(BotConfig.Module.CUBIKON.key());
        watch(ctx.world().get(), setting(s, "learnSpawns", true));
    }

    /** {@code OFF}, {@code ATTACKERS} (only NPCs that shoot at us) or {@code ALL}; old configs: {@code killWhileWaiting}. */
    static String waitKill(JsonNode s) {
        if (s != null && s.has("waitKill")) {
            return s.get("waitKill").asText("OFF");
        }
        return setting(s, "killWhileWaiting", false) ? "ALL" : "OFF";
    }

    /**
     * An NPC to fight while waiting: allowed by the list, within {@code range} of the spot we wait on and —
     * unless {@code chaseFromSpot} — within reach of the standing ship; with {@code ATTACKERS} only one that
     * shoots at us. Chasing stays on the leash ({@link #onLeash}): past it, or after the kill, back to the spot.
     */
    private WorldSnapshot.Ship waitTarget(BotConfig c, WorldSnapshot w, String mode, double range, boolean chase) {
        WorldSnapshot.Hero h = w.hero();
        double cx = spot != null ? spot[0] : h.x();
        double cy = spot != null ? spot[1] : h.y();
        return w.ships().values().stream()
                .filter(x -> Targets.attackable(c, x, h.mapId()))
                .filter(x -> Math.hypot(x.x() - cx, x.y() - cy) <= range)
                .filter(x -> chase || x.distanceTo(h.x(), h.y()) <= Targets.radius(c, x, h.mapId()) + REACH_EXTRA)
                .filter(x -> !"ATTACKERS".equals(mode) || x.attackTargetId() == h.id() || h.attackers().contains(x.id()))
                .min(Comparator.<WorldSnapshot.Ship>comparingInt(x -> Targets.rule(c, x, h.mapId()).priority())
                        .thenComparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    /** Laser reach while standing on the spot: the NPC's radius plus a little. */
    static final double REACH_EXTRA = 150;

    /** The wait-fight target is still within reach of the standing ship. */
    private boolean inReach(WorldSnapshot w) {
        WorldSnapshot.Ship t = w.ships().get(attacker.targetId());
        WorldSnapshot.Hero h = w.hero();
        return t == null || t.distanceTo(h.x(), h.y())
                <= Targets.radius(ctx.config().get(), t, h.mapId()) + REACH_EXTRA;
    }

    /** Both the target and the ship stay within {@code leash} of the spot we wait on. */
    private boolean onLeash(WorldSnapshot w, double leash) {
        if (spot == null) {
            return true;
        }
        WorldSnapshot.Ship t = w.ships().get(attacker.targetId());
        WorldSnapshot.Hero h = w.hero();
        boolean targetNear = t == null || Math.hypot(t.x() - spot[0], t.y() - spot[1]) <= leash;
        boolean shipNear = Math.hypot(h.x() - spot[0], h.y() - spot[1]) <= leash + 300;
        return targetNear && shipNear;
    }

    /** Remembers Cubikons appearing and dying in sight. */
    private void watch(WorldSnapshot w, boolean learn) {
        WorldSnapshot.Hero h = w.hero();
        if (h.mapId() != cubesMap) {
            cubes.clear(); // a new map: what we tracked is not dead, just elsewhere
            cubesMap = h.mapId();
        }
        long now = wallMs();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc() && isCubikon(s)) {
                if (!cubes.containsKey(s.id()) && learn) {
                    memory.seen(h.mapId(), s.x(), s.y(), now);
                }
                cubes.put(s.id(), new int[] {s.x(), s.y()});
            }
        }
        cubes.entrySet().removeIf(e -> {
            if (w.ships().containsKey(e.getKey())) {
                return false;
            }
            int[] p = e.getValue();
            // the server says so (wherever we are), or it vanished right next to us
            boolean dead = w.destroyed().containsKey(e.getKey()) || Math.hypot(p[0] - h.x(), p[1] - h.y()) <= SEEN_DYING;
            if (dead) {
                log.info("Cubikon у {}/{} уничтожен — лечу на следующий спаун", p[0] / 100, p[1] / 100);
                if (attacker.targetId() == e.getKey() && attacker.shooting()) {
                    ctx.stats().cubesKilled.incrementAndGet();
                    ctx.stats().kills.incrementAndGet();
                    attacker.clear();
                }
                deadAt = p;
                moveOn = true;
                spot = null;
                ctx.nav().stop();
                if (learn) {
                    memory.killed(h.mapId(), p[0], p[1], now);
                }
            }
            return true;
        });
    }

    /** Waits on a spawn spot; picks the next one when the wait is over. */
    private void camp(JsonNode s, WorldSnapshot.Hero h) {
        int arrive = (int) setting(s, "arriveDistance", 450);
        int waitSec = (int) setting(s, "waitSeconds", 90);
        boolean learned = "LEARNED".equals(text(s, "spotMode", "ROUND"));
        long now = ctx.now();
        if (spot == null) {
            choose(s, h, learned, waitSec);
        }
        double dist = Math.hypot(spot[0] - h.x(), spot[1] - h.y());
        BotConfig.General g = ctx.config().get().general();
        ctx.ship().want(dist > arrive ? g.travel() : g.roam());
        if (dist > arrive) {
            arrivedAt = 0;
            if (ctx.nav().idle() || ctx.nav().destination().x() != spot[0] || ctx.nav().destination().y() != spot[1]) {
                // a far minimap click lands on its grid; the last stretch is a click in space
                ctx.nav().moveTo(spot[0], spot[1], dist < 2_500);
            }
            ctx.nav().tick();
            status = "лечу на спаун " + spot[0] / 100 + "/" + spot[1] / 100;
            return;
        }
        if (arrivedAt == 0) {
            arrivedAt = now;
            moveOn = false;
            ctx.nav().stop();
            log.info("На спауне {}/{} — жду куб", spot[0] / 100, spot[1] / 100);
        }
        if (waitUntil != Long.MAX_VALUE && now >= waitUntil) {
            spot = null;
            arrivedAt = 0;
            return;
        }
        status = "жду куб на спауне " + spot[0] / 100 + "/" + spot[1] / 100;
    }

    private void choose(JsonNode s, WorldSnapshot.Hero h, boolean learned, int waitSec) {
        long now = ctx.now();
        long wait = waitSec <= 0 ? Long.MAX_VALUE : waitSec * 1_000_000_000L + ctx.pause(0, 5_000);
        List<int[]> spots = spots(s);
        if (learned) {
            chooseLearned(spots, h, waitSec);
            return;
        }
        if (moveOn && deadAt != null) {
            // the spawn after the one whose cube just died
            int dead = nearest(spots, deadAt[0], deadAt[1]);
            roundIndex = (dead + 1) % spots.size();
            if (spots.size() > 1 && nearDead(spots.get(roundIndex)[0], spots.get(roundIndex)[1])) {
                roundIndex = (roundIndex + 1) % spots.size();
            }
        } else if (roundIndex < 0 || roundIndex >= spots.size()) {
            roundIndex = learned ? 0 : nearest(spots, h.x(), h.y());
        } else {
            roundIndex = (roundIndex + 1) % spots.size();
        }
        spot = spots.get(roundIndex);
        waitUntil = wait == Long.MAX_VALUE ? Long.MAX_VALUE : now + wait;
    }

    /**
     * LEARNED: the spawn where a Cubikon can be caught soonest. A spawn whose cube died in sight is ready at
     * death + respawn; one whose state is unknown may hold a cube right now, so it is checked first. The
     * flight there counts (at the ship's speed); an unknown spawn costs {@link #UNKNOWN_PENALTY_MS} more, a cube
     * due by the clock being surer. The wait lasts until the cube is due plus a margin, or a short look on an
     * unknown spawn — then the choice is made again (empty spawns in sight are skipped).
     */
    private void chooseLearned(List<int[]> configured, WorldSnapshot.Hero h, int waitSec) {
        long now = ctx.now();
        long wall = wallMs();
        List<SpawnMemory.Spawn> known = memory.spawns(h.mapId());
        long respawn = memory.mapRespawn(h.mapId());
        double speed = Math.max(100, h.speed());
        int[] best = null;
        long bestAt = Long.MAX_VALUE;
        long bestReady = 0;
        for (int[] p : merged(configured, known)) {
            if (nearDead(p[0], p[1])) {
                continue; // its cube just died
            }
            long ready = 0;
            for (SpawnMemory.Spawn k : known) {
                if (Math.hypot(k.x() - p[0], k.y() - p[1]) <= SpawnMemory.SAME_SPAWN && k.expectedAt(respawn) > 0
                        && k.expectedAt(respawn) + DUE_GRACE_MS > wall) {
                    ready = k.expectedAt(respawn);
                }
            }
            double dist = Math.hypot(p[0] - h.x(), p[1] - h.y());
            if (ready == 0 && dist < IN_VIEW) {
                continue; // in sight and no cube: nothing to check there
            }
            long arrive = wall + (long) (dist / speed * 1_000);
            // a cube due by the clock is surer than one that may be there: unknown spawns pay a penalty
            long at = ready > 0 ? Math.max(ready, arrive) : arrive + UNKNOWN_PENALTY_MS;
            if (at < bestAt) {
                bestAt = at;
                best = p;
                bestReady = ready;
            }
        }
        if (best == null) {
            best = new int[] {h.x(), h.y()}; // everything near is empty and nothing is due: wait here
        }
        spot = best;
        long margin = DUE_GRACE_MS;
        long waitMs = bestReady > 0 ? Math.max(0, bestReady - wall) + margin
                : respawn > 0 ? LOOK_MS
                : waitSec > 0 ? waitSec * 1_000L : Long.MAX_VALUE / 2_000_000;
        waitUntil = now + waitMs * 1_000_000L + ctx.pause(0, 5_000);
        if (bestReady > 0) {
            log.info("Куб на {}/{} ожидается через {} с — лечу туда", best[0] / 100, best[1] / 100,
                    Math.max(0, bestReady - wall) / 1_000);
        } else {
            log.info("Проверяю спаун {}/{} — что там, неизвестно", best[0] / 100, best[1] / 100);
        }
    }

    /** Learned spawns (most recent first) followed by the config's spots not near any of them. */
    public static List<int[]> merged(List<int[]> configured, List<SpawnMemory.Spawn> known) {
        List<int[]> out = new ArrayList<>();
        known.stream().sorted(SpawnMemory.byRecent()).forEach(k -> out.add(new int[] {k.x(), k.y()}));
        for (int[] p : configured) {
            if (out.stream().noneMatch(o -> Math.hypot(o[0] - p[0], o[1] - p[1]) <= SpawnMemory.SAME_SPAWN)) {
                out.add(p);
            }
        }
        return out;
    }

    static boolean isCubikon(WorldSnapshot.Ship s) {
        return s.name() != null && s.name().toLowerCase().contains("cubikon");
    }

    /** The nearest live Cubikon the list allows to attack (any owner, as the camp does). */
    static WorldSnapshot.Ship findCubikon(BotConfig c, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        return w.ships().values().stream()
                .filter(WorldSnapshot.Ship::npc)
                .filter(CubikonModule::isCubikon)
                .filter(x -> {
                    BotConfig.NpcRule r = Targets.rule(c, x, h.mapId());
                    return r == null || r.kill();
                })
                .min(Comparator.comparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    public static List<int[]> spots(JsonNode s) {
        List<int[]> out = new ArrayList<>();
        if (s != null && s.path("spots").isArray()) {
            for (JsonNode p : s.path("spots")) {
                out.add(new int[] {p.path(0).asInt(), p.path(1).asInt()});
            }
        }
        if (out.isEmpty()) {
            for (int[] p : DEFAULT_SPOTS) {
                out.add(p.clone());
            }
        }
        return out;
    }

    private boolean nearDead(double x, double y) {
        return moveOn && deadAt != null && Math.hypot(x - deadAt[0], y - deadAt[1]) <= SpawnMemory.SAME_SPAWN;
    }

    private static int nearest(List<int[]> spots, double x, double y) {
        int best = 0;
        for (int i = 1; i < spots.size(); i++) {
            if (Math.hypot(spots.get(i)[0] - x, spots.get(i)[1] - y)
                    < Math.hypot(spots.get(best)[0] - x, spots.get(best)[1] - y)) {
                best = i;
            }
        }
        return best;
    }

    private static boolean setting(JsonNode s, String key, boolean def) {
        return s == null || !s.has(key) ? def : s.get(key).asBoolean(def);
    }

    private static double setting(JsonNode s, String key, double def) {
        return s == null || !s.has(key) ? def : s.get(key).asDouble(def);
    }

    private static String text(JsonNode s, String key, String def) {
        return s == null || !s.has(key) ? def : s.get(key).asText(def);
    }
}
