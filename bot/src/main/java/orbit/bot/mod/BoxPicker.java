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

    /** "palladium": what is being picked up now, for the status. */
    /** The box being taken, {@code null} none or gone. */
    public WorldSnapshot.Collectable currentBox(WorldSnapshot w) {
        return hash == null ? null : w.collectables().get(hash);
    }

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
     * boxes by its spawn (2026-11-14: yellow dots, boxes, at the behemoth's spot were left uncollected; their
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

    /** The config's boxes within {@code within} of (ax, ay) in a fight: the ship's mode stays the fight's. */
    public boolean tickNearInFight(double ax, double ay, double within) {
        keepShipMode = true;
        try {
            return tick(ax, ay, within);
        } finally {
            keepShipMode = false;
        }
    }

    private boolean fight;
    private double fightNpcX;
    private double fightNpcY;
    private double fightNearNpc;

    /**
     * DarkBot's Kill &amp; Collect in a fight ({@code LootNCollectorModule}): the best box of the map (priority, then
     * the nearest) is taken only when it is within the collect radius of the ship — and, for an NPC flagged
     * {@code IGNORE_BOXES}, within {@code nearNpc} of the NPC ({@code nearNpc} &le; 0: no such limit). The ship's mode
     * stays the fight's; the caller keeps shooting.
     */
    public boolean tickFight(double npcX, double npcY, double nearNpc) {
        fight = true;
        fightNpcX = npcX;
        fightNpcY = npcY;
        fightNearNpc = nearNpc;
        keepShipMode = true;
        try {
            return tick(Double.NaN, Double.NaN);
        } finally {
            fight = false;
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
            if (tries == 0) {
                // gone before the ship asked for it (another took it, or the game removed it): not ours (live
                // 2026-11-17 on gb2: a cargo pickup logged thirty times, the cargo quest still 0/100)
                log.info("{} исчез раньше, чем я его взял", orbit.config.LootNames.of(current));
            } else {
                boxes++;
                ctx.stats().boxes.incrementAndGet();
                int n = ctx.stats().picked(orbit.config.LootNames.of(current));
                log.info("Подобрал {} (тип {}, за сессию: {})", orbit.config.LootNames.of(current), current, n);
            }
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
                log.info("Ящик (любой тип): {} у {}/{}", current, box.x() / 100, box.y() / 100);
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
                collectAt = now + ctx.react(100, 300) + 1; // never 0: 0 is «not yet»
            }
            long stay = Math.max(MIN_STAY_MS, waitMs(c, key(box)) + Math.min(1_000, tries * 100L)) * 1_000_000L;
            if (now >= collectAt && (collectedAt == 0 || now - collectedAt > stay)) {
                if (tries >= MAX_TRIES) {
                    ignored.put(hash, now);
                    hash = null;
                    return false;
                }
                ctx.actions().collect(box);
                collectedAt = Math.max(1, now); // never 0: 0 is «not yet»
                tries++;
            }
        }
        return true;
    }

    /**
     * A box the config's list would have taken within {@code within} of (ax, ay) — its types, «contested» and the rest —
     * none chosen ({@code within} &le; 0: anywhere in sight).
     */
    public boolean available(double ax, double ay, double within) {
        double x = anchorX;
        double y = anchorY;
        double r = this.within;
        anchorX = within > 0 ? ax : Double.NaN;
        anchorY = within > 0 ? ay : Double.NaN;
        this.within = within;
        boolean any = within <= 0;
        anywhere = any;
        try {
            return find(ctx.config().get(), ctx.world().get(), ctx.now()) != null;
        } finally {
            anywhere = false;
            anchorX = x;
            anchorY = y;
            this.within = r;
        }
    }

    private WorldSnapshot.Collectable find(BotConfig c, WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        ignored.values().removeIf(t -> now - t > 120_000_000_000L);
        java.util.function.BiPredicate<Integer, Integer> reach = anywhere || fight ? ctx.nav().reachability() : null;
        WorldSnapshot.Collectable best = w.collectables().values().stream()
                .filter(b -> b.kind() == WorldSnapshot.CollectableKind.BOX || !anyType && b.kind() == WorldSnapshot.CollectableKind.ORE)
                .filter(b -> !ignored.containsKey(b.hash()))
                .filter(b -> {
                    if (anyType) {
                        // the behemoth's own boxes and the config's — not a pirate booty a key opens (live 2026-11-14
                        // on the VPS, 1-BL: PIRATE_BOOTY by the spawn, five keys spent)
                        return orbit.config.BoxCatalog.collectable(key(b))
                                && (key(b).startsWith(BEHEMOTH_BOX) || collects(c, w, key(b)));
                    }
                    // mines and fireworks show among the resources but are no boxes (list as of 2026-11-12)
                    return collects(c, w, key(b)) && orbit.config.BoxCatalog.collectable(key(b));
                })
                // not where an NPC flagged IGNORE_BOXES died a moment ago
                // not inside a no-access zone (the ship cannot get there)
                .filter(b -> !orbit.world.maps.ZoneGeometry.blocked(w, b.x(), b.y()))
                // not deep in the config's avoided zone: DarkBot's zones are obstacles and a box counts while the
                // nearest free point is closer than 200 (CollectorModule.canCollect)
                .filter(b -> !avoidedDeep(c, w, b.x(), b.y()))
                // a full hold (the config does something about it): no more boxes that fill it
                .filter(b -> !BotConfig.Collect.fillsCargo(key(b)) || !cargoFull(c, w))
                // NPC cargo only while the enrichment lacks ore (modules.enrich.cargoWhenLow, the leveling quests config)
                .filter(b -> !"FROM_SHIP".equals(key(b)) || cargoWanted(c, w))
                .filter(b -> reach != null ? reach.test(b.x(), b.y()) : Double.isNaN(anchorX)
                        ? Math.hypot(b.x() - h.x(), b.y() - h.y()) <= c.collect().radius()
                        : Math.hypot(b.x() - anchorX, b.y() - anchorY) <= (within > 0 ? within : c.collect().radius()))
                .filter(b -> anyType || !c.collect().ignoreContested() || !contested(w, b))
                .filter(b -> !c.general().avoidMines() || !orbit.bot.nav.PathFinder.nearMine(w, b.x(), b.y()))
                // not by a player who attacked the ship this session (the config's avoid-those-who-shot option)
                .filter(b -> !ctx.nav().inDanger(b.x(), b.y()))
                // DarkBot's collector: the box priority first (lower first), then the nearest
                .min(Comparator.<WorldSnapshot.Collectable>comparingInt(b -> {
                    BotConfig.BoxRule r = rule(c, key(b));
                    return r == null ? 0 : r.priority();
                }).thenComparingDouble(b -> Math.hypot(b.x() - h.x(), b.y() - h.y())))
                .orElse(null);
        if (best != null && fight && (Math.hypot(best.x() - h.x(), best.y() - h.y()) > c.collect().radius()
                || fightNearNpc > 0 && Math.hypot(best.x() - fightNpcX, best.y() - fightNpcY) > fightNearNpc)) {
            return null;
        }
        return best;
    }

    /** The reach DarkBot gives a box by an obstacle: the nearest free point closer than this. */
    static final double FREE_NEAR = 200;

    /** In the config's avoided zone with no free point within {@link #FREE_NEAR} (eight directions looked at). */
    private boolean avoidedDeep(BotConfig c, WorldSnapshot w, double x, double y) {
        int map = w.hero().mapId();
        if (!Zones.avoided(c, map, x, y, ctx.maps())) {
            return false;
        }
        double r = FREE_NEAR - 10;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            if (!Zones.avoided(c, map, x + Math.cos(a) * r, y + Math.sin(a) * r, ctx.maps())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the config takes boxes of this type: its rule, or — for a chest whose key the config watches
     * ({@code collect.bootyKeys}, DmPlugin's {@code BootyKeyCollectorManager}) — whether the account has such a key
     * beyond the reserve and has not spent the session's limit ({@code collect.keyLimits}).
     */
    private boolean collects(BotConfig c, WorldSnapshot w, String type) {
        java.util.Map<String, Integer> keys = w.inventory() == null ? null : w.inventory().bootyKeys();
        Boolean byKeys = c.collect().byKeys(type, keys, ctx.stats().keysSpent(keys));
        if (byKeys != null) {
            return byKeys;
        }
        BotConfig.BoxRule r = rule(c, type);
        return r != null && r.collect();
    }

    /**
     * NPC cargo is wanted: always, unless {@code modules.enrich.cargoWhenLow} (the leveling quests config, a user's choice
     * 2026-11-25) — then only while an enriched item is below its threshold ({@code Enricher.low}).
     */
    static boolean cargoWanted(BotConfig c, WorldSnapshot w) {
        com.fasterxml.jackson.databind.JsonNode e = c.modules().get("enrich");
        if (e == null || !e.path("enabled").asBoolean(false) || !e.path("cargoWhenLow").asBoolean(false)) {
            return true;
        }
        return orbit.bot.run.Enricher.low(e, w.inventory().lab());
    }

    /** The wait on a box: its rule's, else (a chest taken by its keys with no row) the one DarkBot users set for it. */
    static int waitMs(BotConfig c, String type) {
        BotConfig.BoxRule r = rule(c, type);
        return r != null ? r.waitMs() : orbit.config.BoxCatalog.waitMs(type);
    }

    /** The config's rule of a type: its own, else the one of DarkBot's name for it ({@code SOLAR_CLASH}). */
    static BotConfig.BoxRule rule(BotConfig c, String type) {
        BotConfig.BoxRule r = c.collect().boxes().get(type);
        if (r == null) {
            String alias = orbit.config.BoxCatalog.darkBotName(type);
            r = alias == null ? null : c.collect().boxes().get(alias);
        }
        return r;
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

    /**
     * DarkBot's {@code IGNORE_CONTESTED_BOXES} ({@code CollectorModule.isContested}): another player flies to this very
     * box and gets there sooner than the ship would in twice its time (pessimistic: the ship's speed is unsteady).
     * Other ships' speed is not known here: taken as the ship's.
     */
    static boolean contested(WorldSnapshot w, WorldSnapshot.Collectable b) {
        WorldSnapshot.Hero h = w.hero();
        double mine = Math.hypot(b.x() - h.x(), b.y() - h.y()) * 2;
        return w.ships().values().stream().anyMatch(s -> s.kind() == WorldSnapshot.ShipKind.PLAYER && s.id() != h.id()
                && s.moving() && Math.hypot(s.targetX() - b.x(), s.targetY() - b.y()) < 1
                && Math.hypot(b.x() - s.x(), b.y() - s.y()) < mine);
    }
}
