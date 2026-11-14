package orbit.bot.mod;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import orbit.bot.run.BotContext;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Picks up boxes the config wants ({@code collect.boxes}) within its radius: fly onto the box, wait
 * the box's time, collect. Skips boxes another player is closer to, if the config says so, and boxes
 * that did not disappear after two tries.
 */
public final class BoxPicker {

    private static final Logger log = LoggerFactory.getLogger(BoxPicker.class);

    private final BotContext ctx;
    private String hash;
    /** Clicks on one box before it is left alone for two minutes. */
    static final int MAX_TRIES = 4;
    /** One box is given up after this long by it, whatever holds the ship off it… */
    static final long MAX_BOX_NS = 15_000_000_000L;
    /**
     * …counted from the ship coming this close (live 2026-11-14 on the VPS, 1-BL: boxes 6 000 from the portal, the
     * flight round the server's zones ate the 15 s and the box was left for two minutes, three times)…
     */
    static final double NEAR_BOX = 400;
    /** …and the whole way to it at most this long. */
    static final long MAX_WAY_NS = 60_000_000_000L;
    private long startedAt;
    private long nearAt;
    /** Boxes near this spot are left until then ({@code IGNORE_BOXES}). */
    private double leaveX;
    private double leaveY;
    private long leaveUntil;
    static final double LEAVE_RADIUS = 600;

    /** Leaves the boxes near (x, y) until {@code until} (nanos): an NPC flagged {@code IGNORE_BOXES} died there. */
    public void leaveAround(double x, double y, long until) {
        leaveX = x;
        leaveY = y;
        leaveUntil = until;
    }
    /** Before clicking a box again at least this long (a box without a wait of its own). */
    static final long MIN_STAY_MS = 700;
    /** The config's name of the box being taken ({@code ore_8}, {@code BONUS_BOX}). */
    private String current = "";
    private long collectAt;
    private long collectedAt;
    private int tries;
    private int boxes;
    private final Map<String, Long> ignored = new HashMap<>();
    private double anchorX = Double.NaN;
    private double anchorY = Double.NaN;
    private double within = -1;

    public BoxPicker(BotContext ctx) {
        this.ctx = ctx;
    }

    public int boxes() {
        return boxes;
    }

    public boolean busy() {
        return hash != null;
    }

    /** «палладий»: what is being picked up now, for the status. */
    public String currentName() {
        return orbit.config.LootNames.of(current);
    }

    public void clear() {
        hash = null;
    }

    private boolean anywhere;
    private boolean anyType;
    /** The behemoth's boxes: {@code MINDFIRE_BEHEMOTH_BOX_2} seen on 1-, 2- and 3-BL. */
    static final String BEHEMOTH_BOX = "MINDFIRE_BEHEMOTH_BOX";

    /**
     * Boxes of any type within {@code within} of (ax, ay), the config's box list and «contested» aside: the behemoth's
     * boxes by its spawn (the user, 2026-11-14: «на месте бегемота жёлтые точки — коробки, но он их не собирает»; their
     * type is not in the train configs' list and a train always stands by them). Mines and fireworks still not.
     */
    public boolean tickAnyType(double ax, double ay, double within) {
        anyType = true;
        try {
            return tick(ax, ay, within);
        } finally {
            anyType = false;
        }
    }

    private boolean keepShipMode;

    /**
     * {@link #tickAnyType} in a fight: the ship's config and formation stay the fight's (the caller keeps shooting).
     */
    public boolean tickInFight(double ax, double ay, double within) {
        keepShipMode = true;
        try {
            return tickAnyType(ax, ay, within);
        } finally {
            keepShipMode = false;
        }
    }

    /** True while busy with a box (the caller should not move the ship). Boxes near the ship. */
    public boolean tick() {
        return tick(Double.NaN, Double.NaN);
    }

    /**
     * Any box in sight the ship can fly to, not only those in the collect radius — DarkBot's collector when there
     * is no fight (its radius counts only next to a target): the palladium module roams and takes all it sees.
     */
    public boolean tickAnywhere() {
        anywhere = true;
        try {
            return tick(Double.NaN, Double.NaN);
        } finally {
            anywhere = false;
        }
    }

    /**
     * Like {@link #tick()}, but only boxes within the collect radius of (ax, ay) — e.g. the spawn spot,
     * so picking up one box never leads to the next one farther and farther away.
     */
    public boolean tick(double ax, double ay) {
        return tick(ax, ay, -1);
    }

    /**
     * Like {@link #tick(double, double)}, but with {@code within} &gt; 0 only boxes that close to the anchor
     * (e.g. inside a target's laser reach, so the ship keeps shooting while it picks them up).
     */
    public boolean tick(double ax, double ay, double within) {
        anchorX = ax;
        anchorY = ay;
        this.within = within;
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        BotConfig c = ctx.config().get();
        long now = ctx.now();
        if (hash != null && !w.collectables().containsKey(hash)) {
            boxes++;
            ctx.stats().boxes.incrementAndGet();
            int n = ctx.stats().picked(orbit.config.LootNames.of(current));
            log.info("Подобрал {} (тип {}, за сессию: {})", orbit.config.LootNames.of(current), current, n);
            hash = null;
        }
        if (hash == null) {
            WorldSnapshot.Collectable box = find(c, w, now);
            if (box == null) {
                return false;
            }
            hash = box.hash();
            current = key(box);
            nearAt = 0;
            if (anyType) {
                log.info("Ящик у места бегемота: тип {} у {}/{}", current, box.x() / 100, box.y() / 100);
            }
            startedAt = now;
            collectAt = 0;
            collectedAt = 0;
            tries = 0;
            if (!keepShipMode) {
                ctx.ship().want(c.general().roam());
            }
            ctx.nav().moveTo(box.x(), box.y(), true);
        }
        WorldSnapshot.Collectable box = w.collectables().get(hash);
        if (box != null && nearAt == 0 && Math.hypot(box.x() - h.x(), box.y() - h.y()) <= NEAR_BOX) {
            nearAt = now;
        }
        if (box != null && (nearAt != 0 ? now - nearAt > MAX_BOX_NS : now - startedAt > MAX_WAY_NS)) {
            // live 2026-11-13 on 3-BL: a box by a no-access wall, the ship jittering at the nearest free point never
            // came still, so no click counted — it stood 100 s
            log.info("Не могу подобрать {} — бросаю", orbit.config.LootNames.of(current));
            ignored.put(hash, now);
            hash = null;
            ctx.nav().stop();
            return false;
        }
        ctx.nav().tick();
        if (box != null && ctx.nav().idle() && !h.moving()) {
            // DarkBot's collector: click at once (a human moment), then stay the box's wait (collect.boxes waitMs,
            // ore_8 800 ms) plus 100 ms a retry while the game takes it; still there after that — click again
            if (collectAt == 0) {
                collectAt = now + ctx.pause(100, 300);
            }
            BotConfig.BoxRule r = c.collect().boxes().get(key(box));
            long stay = Math.max(MIN_STAY_MS, (r == null ? 0 : r.waitMs()) + Math.min(1_000, tries * 100L)) * 1_000_000L;
            if (now >= collectAt && (collectedAt == 0 || now - collectedAt > stay)) {
                if (tries >= MAX_TRIES) {
                    ignored.put(hash, now);
                    hash = null;
                    return false;
                }
                ctx.actions().collect(box);
                collectedAt = now;
                tries++;
            }
        }
        return true;
    }

    private WorldSnapshot.Collectable find(BotConfig c, WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        ignored.values().removeIf(t -> now - t > 120_000_000_000L);
        java.util.function.BiPredicate<Integer, Integer> reach = anywhere ? ctx.nav().reachability() : null;
        return w.collectables().values().stream()
                .filter(b -> b.kind() == WorldSnapshot.CollectableKind.BOX || !anyType && b.kind() == WorldSnapshot.CollectableKind.ORE)
                .filter(b -> !ignored.containsKey(b.hash()))
                .filter(b -> {
                    if (anyType) {
                        // the behemoth's own boxes and the config's — not a pirate booty a key opens (live 2026-11-14
                        // on the VPS, 1-BL: PIRATE_BOOTY by the spawn, five keys spent)
                        BotConfig.BoxRule rule = c.collect().boxes().get(key(b));
                        return orbit.config.BoxCatalog.collectable(key(b))
                                && (key(b).startsWith(BEHEMOTH_BOX) || rule != null && rule.collect());
                    }
                    BotConfig.BoxRule r = c.collect().boxes().get(key(b));
                    // mines and fireworks show among the resources but are no boxes (the user's list, 2026-11-12)
                    return r != null && r.collect() && orbit.config.BoxCatalog.collectable(key(b));
                })
                // not where an NPC flagged IGNORE_BOXES died a moment ago
                .filter(b -> now >= leaveUntil || Math.hypot(b.x() - leaveX, b.y() - leaveY) > LEAVE_RADIUS)
                // not inside a no-access zone (the ship cannot get there)
                .filter(b -> !orbit.world.maps.ZoneGeometry.blocked(w, b.x(), b.y()))
                // not in the config's avoided zone (DarkBot's «avoided zones»)
                .filter(b -> !Zones.avoided(c, w.hero().mapId(), b.x(), b.y(), ctx.maps()))
                // a full hold (the config does something about it): no more boxes that fill it
                .filter(b -> !BotConfig.Collect.fillsCargo(key(b)) || !cargoFull(c, w))
                .filter(b -> reach != null ? reach.test(b.x(), b.y()) : Double.isNaN(anchorX)
                        ? Math.hypot(b.x() - h.x(), b.y() - h.y()) <= c.collect().radius()
                        : Math.hypot(b.x() - anchorX, b.y() - anchorY) <= (within > 0 ? within : c.collect().radius()))
                .filter(b -> anyType || !c.collect().ignoreContested() || !contested(w, b))
                .filter(b -> !c.general().avoidMines() || !orbit.bot.nav.PathFinder.nearMine(w, b.x(), b.y()))
                // not by a player who attacked the ship this session (the config's «Обходить тех, кто стрелял»)
                .filter(b -> !ctx.nav().inDanger(b.x(), b.y()))
                // DarkBot's collector: the box priority first (lower first), then the nearest
                .min(Comparator.<WorldSnapshot.Collectable>comparingInt(b -> {
                    BotConfig.BoxRule r = c.collect().boxes().get(key(b));
                    return r == null ? 0 : r.priority();
                }).thenComparingDouble(b -> Math.hypot(b.x() - h.x(), b.y() - h.y())))
                .orElse(null);
    }

    /**
     * The config's name of a collectable: the box's type, or {@code ore_<n>} for ore (DarkBot's names: the
     * palladium of 5-3 is {@code ore_8}, sent as {@code AddOreCommand}).
     */
    static String key(WorldSnapshot.Collectable b) {
        return b.kind() == WorldSnapshot.CollectableKind.ORE ? "ore_" + b.oreType() : b.boxType();
    }

    boolean cargoFull(BotConfig c, WorldSnapshot w) {
        String mode = ctx.stats().cargoMode != null ? ctx.stats().cargoMode : c.collect().cargo();
        return !BotConfig.Collect.CARGO_NONE.equals(mode) && w.hero().cargoMax() > 0
                && w.cargoFree() <= c.collect().cargoFullAt();
    }

    /** Another player is closer to the box than we are. */
    private static boolean contested(WorldSnapshot w, WorldSnapshot.Collectable b) {
        WorldSnapshot.Hero h = w.hero();
        double mine = Math.hypot(b.x() - h.x(), b.y() - h.y());
        return w.ships().values().stream().anyMatch(s -> s.kind() == WorldSnapshot.ShipKind.PLAYER
                && Math.hypot(b.x() - s.x(), b.y() - s.y()) < Math.min(mine, 300));
    }
}
