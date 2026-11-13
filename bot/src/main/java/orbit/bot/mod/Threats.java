package orbit.bot.mod;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import orbit.bot.run.BotContext;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/**
 * How safe a point is to be in during a fight (docs/FIGHT-MOVEMENT.md, Д2): 0 is clear, lower is worse,
 * {@link #BLOCKED} is never. DarkBot steers its circle off other NPCs' radii; this adds who is dangerous:
 * <ul>
 *   <li>other NPCs by how deep the point is inside their radius — most for those shooting the ship now, then for
 *       the kinds seen attacking first (learned here), little for the rest: they shoot only when touched
 *       (the user, 2026-11-12);</li>
 *   <li>enemy players within their reach;</li>
 *   <li>never: off the map's edge, the avoided zone, the danger circles (offenders, clan stations), mines,
 *       no-access zones;</li>
 *   <li>outside the preferred zone while the ship is in it.</li>
 * </ul>
 */
public final class Threats {

    static final double BLOCKED = -1e9;
    /** The ship keeps this far from the map's edge. */
    static final double MARGIN = 500;
    /** An enemy player's reach. */
    static final double PLAYER_REACH = 700;
    static final double MINE_KEEP = 300;
    /** Weights of a point's depth inside an NPC's radius. */
    static final double SHOOTING_US = 3;
    static final double AGGRESSIVE = 2;
    static final double QUIET = 0.4;
    /** An NPC shooting the ship is kept off this much beyond its radius. */
    static final double SHOOTER_EXTRA = 250;

    /** NPC kinds seen shooting the ship first (it was not shooting them): they attack on sight. */
    private static final Set<String> AGGRESSIVE_KINDS = ConcurrentHashMap.newKeySet();
    /**
     * Kept across runs (live 2026-11-13 on 3-BL: after each restart Attend IX and Steadfast III counted as quiet until
     * they shot again, and the circle flew by them).
     */
    private static final java.nio.file.Path FILE = java.nio.file.Path.of("local", "npc-aggressive.json");
    private static volatile boolean onDisk;

    /** The app keeps the kinds learned; tests do not. */
    public static synchronized void keepOnDisk() {
        onDisk = true;
        try {
            if (java.nio.file.Files.exists(FILE)) {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(FILE.toFile())
                        .forEach(n -> AGGRESSIVE_KINDS.add(n.asText()));
            }
        } catch (java.io.IOException e) {
            // learned again
        }
    }

    private static void save() {
        if (!onDisk) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(FILE.toAbsolutePath().getParent());
            new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                    .writeValue(FILE.toFile(), new java.util.TreeSet<>(AGGRESSIVE_KINDS));
        } catch (java.io.IOException e) {
            // kept for this run
        }
    }

    private final BotContext ctx;
    private final WorldSnapshot w;
    private final BotConfig c;
    private final int targetId;
    private final MapCatalog.MapInfo map;
    private final BotConfig.ZoneGrid preferred;
    private final BotConfig.ZoneGrid avoided;
    private final boolean inPreferred;
    private final Set<Integer> group = new java.util.HashSet<>();

    Threats(BotContext ctx, WorldSnapshot w, BotConfig c, int targetId) {
        this.ctx = ctx;
        this.w = w;
        this.c = c;
        this.targetId = targetId;
        WorldSnapshot.Hero h = w.hero();
        this.map = ctx.maps().info(h.mapId());
        BotConfig.Zones z = c.zones().get(h.mapId());
        this.preferred = z == null || z.preferred() == null || z.preferred().isEmpty() ? null : z.preferred();
        this.avoided = z == null || z.avoided() == null || z.avoided().isEmpty() ? null : z.avoided();
        this.inPreferred = preferred != null && preferred.contains(h.x(), h.y(), map.width(), map.height());
        w.group().members().forEach(m -> group.add(m.id()));
        learn(w);
    }

    /** The NPCs shooting the ship though it does not shoot them: their kind attacks on sight. */
    static void learn(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc() && s.attackTargetId() == h.id() && h.attackTargetId() != s.id() && s.name() != null
                    && AGGRESSIVE_KINDS.add(s.name())) {
                save();
            }
        }
    }

    public static boolean aggressive(String name) {
        return name != null && AGGRESSIVE_KINDS.contains(name);
    }

    /** For tests. */
    static void forget() {
        AGGRESSIVE_KINDS.clear();
    }

    /**
     * An NPC that attacks on sight (or shoots the ship) this near the target that circling it would take the ship into
     * its reach: within its own radius plus {@code radius} of the target; {@code null} when none.
     */
    WorldSnapshot.Ship dangerNear(WorldSnapshot.Ship target, double radius) {
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship found = null;
        double best = Double.MAX_VALUE;
        for (WorldSnapshot.Ship o : w.ships().values()) {
            if (!o.npc() || o.id() == targetId || !(aggressive(o.name()) || o.attackTargetId() == h.id())) {
                continue;
            }
            double d = Math.hypot(o.x() - target.x(), o.y() - target.y());
            if (d < Targets.radius(c, o, h.mapId()) + radius && d < best) {
                best = d;
                found = o;
            }
        }
        return found;
    }

    /** True when the ship may not be at the point at all. */
    boolean blocked(double x, double y) {
        return score(x, y) <= BLOCKED;
    }

    double score(double x, double y) {
        if (x < MARGIN || y < MARGIN || x > map.width() - MARGIN || y > map.height() - MARGIN
                || avoided != null && avoided.contains(x, y, map.width(), map.height()) || ctx.nav().inDanger(x, y)
                || orbit.world.maps.ZoneGeometry.blocked(w, x, y)) {
            return BLOCKED;
        }
        for (WorldSnapshot.Mine m : w.mines().values()) {
            if (Math.hypot(m.x() - x, m.y() - y) < MINE_KEEP) {
                return BLOCKED;
            }
        }
        WorldSnapshot.Hero h = w.hero();
        double s = 0;
        for (WorldSnapshot.Ship o : w.ships().values()) {
            if (o.id() == targetId || o.id() == h.id()) {
                continue;
            }
            double d = Math.hypot(o.x() - x, o.y() - y);
            if (o.npc()) {
                // one shooting the ship reaches a little beyond the radius the list keeps from it
                double r = Targets.radius(c, o, h.mapId()) + (o.attackTargetId() == h.id() ? SHOOTER_EXTRA : 0);
                if (d < r) {
                    double weight = o.attackTargetId() == h.id() ? SHOOTING_US : aggressive(o.name()) ? AGGRESSIVE : QUIET;
                    s -= (r - d) * weight;
                }
            } else if (o.kind() == WorldSnapshot.ShipKind.PLAYER && o.factionId() != h.factionId()
                    && o.factionId() != 0 && !group.contains(o.id()) && d < PLAYER_REACH) {
                s -= (PLAYER_REACH - d) * (o.attackTargetId() == h.id() ? SHOOTING_US : 1);
            }
        }
        if (inPreferred && !preferred.contains(x, y, map.width(), map.height())) {
            s -= 800;
        }
        return s;
    }

    /**
     * The nearest allowed point to {@code (x, y)} going round {@code (cx, cy)}: a spiral outwards, the angle turning
     * the way {@code dir} (DarkBot's search for a valid location); {@code null} when none.
     */
    double[] valid(double cx, double cy, double x, double y, int dir) {
        if (!blocked(x, y)) {
            return new double[] {x, y};
        }
        double angle = Math.atan2(y - cy, x - cx);
        double dist = Math.hypot(x - cx, y - cy);
        for (int i = 0; i < 400; i++) {
            angle += dir * 0.3;
            dist += 25;
            double px = cx + Math.cos(angle) * dist;
            double py = cy + Math.sin(angle) * dist;
            if (!blocked(px, py)) {
                return new double[] {px, py};
            }
        }
        return null;
    }
}
