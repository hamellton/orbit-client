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
    /** In LEARNED mode a spawn due back within this long is worth flying to. */
    static final long DUE_AHEAD_MS = 15 * 60_000L;
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
        watch(w, setting(s, "learnSpawns", true));

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
            boolean onBox = boxesOk && boxes.tick();
            attacker.tick(!onBox);
            status = onBox ? "Cubikon: подбираю ящик" : "Cubikon: бой";
            return;
        }
        if (onCube) {
            onCube = false;
            spot = null; // pick the next spot afresh
        }
        if (attacker.targetId() != 0 && !attacker.tick(true)) {
            attacker.clear();
        }
        if (attacker.targetId() != 0) {
            status = "бью, пока жду куб";
            return;
        }
        String waitKill = waitKill(s);
        if (!"OFF".equals(waitKill)) {
            WorldSnapshot.Ship t = waitTarget(c, w, waitKill, setting(s, "waitKillRange", DEFAULT_WAIT_KILL_RANGE));
            if (t != null) {
                attacker.target(t);
                attacker.tick(true);
                status = "бью, пока жду куб";
                return;
            }
        }
        if (setting(s, "collectBoxes", true) && boxes.tick()) {
            status = "подбираю ящик";
            return;
        }
        camp(s, h);
    }

    /** {@code OFF}, {@code ATTACKERS} (only NPCs that shoot at us) or {@code ALL}; old configs: {@code killWhileWaiting}. */
    static String waitKill(JsonNode s) {
        if (s != null && s.has("waitKill")) {
            return s.get("waitKill").asText("OFF");
        }
        return setting(s, "killWhileWaiting", false) ? "ALL" : "OFF";
    }

    /**
     * An NPC to fight while waiting: allowed by the list, within {@code range} of the spot we wait on (not
     * of the ship, so the camp never drifts off), and with {@code ATTACKERS} only one that shoots at us.
     */
    private WorldSnapshot.Ship waitTarget(BotConfig c, WorldSnapshot w, String mode, double range) {
        WorldSnapshot.Hero h = w.hero();
        double cx = spot != null ? spot[0] : h.x();
        double cy = spot != null ? spot[1] : h.y();
        return w.ships().values().stream()
                .filter(x -> Targets.attackable(c, x, h.mapId()))
                .filter(x -> Math.hypot(x.x() - cx, x.y() - cy) <= range)
                .filter(x -> !"ATTACKERS".equals(mode) || x.attackTargetId() == h.id() || h.attackers().contains(x.id()))
                .min(Comparator.<WorldSnapshot.Ship>comparingInt(x -> Targets.rule(c, x, h.mapId()).priority())
                        .thenComparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .orElse(null);
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
            if (learn && Math.hypot(p[0] - h.x(), p[1] - h.y()) <= SEEN_DYING) {
                log.info("Cubikon у {}/{} уничтожен", p[0] / 100, p[1] / 100);
                memory.killed(h.mapId(), p[0], p[1], now);
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
        ctx.ship().want(ctx.config().get().general().roam());
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
            long wall = wallMs();
            List<SpawnMemory.Spawn> known = memory.spawns(h.mapId());
            SpawnMemory.Spawn due = known.stream()
                    .filter(k -> k.expectedAt() > 0 && k.expectedAt() > wall - DUE_GRACE_MS && k.expectedAt() < wall + DUE_AHEAD_MS)
                    .min(Comparator.comparingLong(SpawnMemory.Spawn::expectedAt)).orElse(null);
            if (due != null) {
                spot = new int[] {due.x(), due.y()};
                long untilDue = Math.max(0, due.expectedAt() + DUE_GRACE_MS - wall) * 1_000_000L;
                waitUntil = wait == Long.MAX_VALUE ? Long.MAX_VALUE : now + Math.max(wait, untilDue);
                log.info("Куб на {}/{} ожидается через {} мин — лечу туда", due.x() / 100, due.y() / 100,
                        Math.max(0, due.expectedAt() - wall) / 60_000);
                return;
            }
            spots = merged(spots, known);
        }
        if (roundIndex < 0 || roundIndex >= spots.size()) {
            roundIndex = learned ? 0 : nearest(spots, h);
        } else {
            roundIndex = (roundIndex + 1) % spots.size();
        }
        spot = spots.get(roundIndex);
        waitUntil = wait == Long.MAX_VALUE ? Long.MAX_VALUE : now + wait;
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

    private static int nearest(List<int[]> spots, WorldSnapshot.Hero h) {
        int best = 0;
        for (int i = 1; i < spots.size(); i++) {
            if (Math.hypot(spots.get(i)[0] - h.x(), spots.get(i)[1] - h.y())
                    < Math.hypot(spots.get(best)[0] - h.x(), spots.get(best)[1] - h.y())) {
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
