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

    public void clear() {
        hash = null;
    }

    /** True while busy with a box (the caller should not move the ship). Boxes near the ship. */
    public boolean tick() {
        return tick(Double.NaN, Double.NaN);
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
            log.info("Ящик собран ({})", boxes);
            hash = null;
        }
        if (hash == null) {
            WorldSnapshot.Collectable box = find(c, w, now);
            if (box == null) {
                return false;
            }
            hash = box.hash();
            collectAt = 0;
            collectedAt = 0;
            tries = 0;
            ctx.ship().want(c.general().roam());
            ctx.nav().moveTo(box.x(), box.y(), true);
        }
        WorldSnapshot.Collectable box = w.collectables().get(hash);
        ctx.nav().tick();
        if (box != null && ctx.nav().idle() && !h.moving()) {
            if (collectAt == 0) {
                BotConfig.BoxRule r = c.collect().boxes().get(box.boxType());
                collectAt = now + ctx.pause(100, 300) + (r == null ? 0 : r.waitMs() * 1_000_000L);
            }
            if (now >= collectAt && now - collectedAt > 3_000_000_000L) {
                if (tries >= 2) {
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
        return w.collectables().values().stream()
                .filter(b -> b.kind() == WorldSnapshot.CollectableKind.BOX)
                .filter(b -> !ignored.containsKey(b.hash()))
                .filter(b -> {
                    BotConfig.BoxRule r = c.collect().boxes().get(b.boxType());
                    return r != null && r.collect();
                })
                // not in the config's avoided zone (DarkBot's «avoided zones»)
                .filter(b -> !Zones.avoided(c, w.hero().mapId(), b.x(), b.y(), ctx.maps()))
                // a full hold (the config does something about it): no more boxes that fill it
                .filter(b -> !BotConfig.Collect.fillsCargo(b.boxType()) || !cargoFull(c, w))
                .filter(b -> Double.isNaN(anchorX)
                        ? Math.hypot(b.x() - h.x(), b.y() - h.y()) <= c.collect().radius()
                        : Math.hypot(b.x() - anchorX, b.y() - anchorY) <= (within > 0 ? within : c.collect().radius()))
                .filter(b -> !c.collect().ignoreContested() || !contested(w, b))
                .filter(b -> !c.general().avoidMines() || !orbit.bot.nav.PathFinder.nearMine(w, b.x(), b.y()))
                .min(Comparator.comparingDouble(b -> Math.hypot(b.x() - h.x(), b.y() - h.y())))
                .orElse(null);
    }

    static boolean cargoFull(BotConfig c, WorldSnapshot w) {
        return !BotConfig.Collect.CARGO_NONE.equals(c.collect().cargo()) && w.hero().cargoMax() > 0
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
