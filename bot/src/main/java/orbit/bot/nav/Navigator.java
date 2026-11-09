package orbit.bot.nav;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import orbit.protocol.Packet;
import orbit.protocol.gen.MoveRequest;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flies the hero to a point the way a player does (recordings of 2026-11-09, 2513 move requests):
 * <ul>
 *   <li>far (more than {@value #FAR} units) — one click on the minimap: the target lands on the
 *       minimap's pixel grid, {@code 10 × minimap zoom × map scale} units (all 178 recorded far moves,
 *       client {@code MiniMap.handleMouseDown});</li>
 *   <li>near — one click in space at the point, a few units off like a mouse;</li>
 *   <li>a precise destination (a box, a portal) gets a second, near click once the ship is close.</li>
 * </ul>
 * Each request carries the position the client would show, i.e. the world model's prediction
 * (within ~8 units of the real client), so the server never sees a jump. Requests are at least
 * {@value #MIN_INTERVAL_MS} ms apart: holding the mouse button sends one every ~200 ms.
 *
 * <p>Not thread-safe: call {@link #moveTo} and {@link #tick} from one thread (the bot loop).
 */
public final class Navigator {

    private static final Logger log = LoggerFactory.getLogger(Navigator.class);

    /** Farther than this the player clicks the minimap. */
    static final double FAR = 2_000;
    /** Never two requests closer than this (the client repeats every ~200 ms while the button is held). */
    static final long MIN_INTERVAL_MS = 180;
    /** A space click lands this far off the aimed point at most. */
    static final int CLICK_SPREAD = 12;
    /** Pause before clicking again when the ship stopped short or was moved by the server. */
    static final long RETRY_MIN_MS = 350;
    static final long RETRY_MAX_MS = 900;

    private final Supplier<WorldSnapshot> world;
    private final Consumer<Packet> sender;
    private final MapCatalog catalog;
    private final Random random;
    private final LongSupplier clock;

    private Destination destination;
    private int clickX;
    private int clickY;
    private long lastSendNanos = Long.MIN_VALUE / 2;
    private long retryAtNanos;
    private int clicks;

    /** Where to go; {@code precise} destinations are approached with a final near click. */
    public record Destination(double x, double y, boolean precise) {
    }

    public Navigator(Supplier<WorldSnapshot> world, Consumer<Packet> sender, MapCatalog catalog, Random random,
                     LongSupplier clock) {
        this.world = world;
        this.sender = sender;
        this.catalog = catalog;
        this.random = random;
        this.clock = clock;
    }

    /** Starts flying to a point of the current map; the first click goes out on the next {@link #tick}. */
    public void moveTo(double x, double y, boolean precise) {
        destination = new Destination(x, y, precise);
        retryAtNanos = clock.getAsLong();
        clicks = 0;
    }

    /** Forgets the destination; the ship keeps flying to its last click, like a player letting go. */
    public void stop() {
        destination = null;
    }

    public Destination destination() {
        return destination;
    }

    /** Clicks sent for the current destination. */
    public int clicks() {
        return clicks;
    }

    /** No destination left: reached or stopped. */
    public boolean idle() {
        return destination == null;
    }

    /** Distance from the hero to the destination, or 0 when idle. */
    public double remaining() {
        Destination d = destination;
        if (d == null) {
            return 0;
        }
        WorldSnapshot.Hero h = world.get().hero();
        return Math.hypot(d.x() - h.x(), d.y() - h.y());
    }

    /** Call about ten times a second. */
    public void tick() {
        Destination d = destination;
        if (d == null) {
            return;
        }
        WorldSnapshot w = world.get();
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0 || !h.alive()) {
            return;
        }
        long now = clock.getAsLong();
        double dist = Math.hypot(d.x() - h.x(), d.y() - h.y());
        if (dist <= arriveRadius(h, d) && !h.moving()) {
            log.debug("Прибыл в {}/{}", (int) d.x() / 100, (int) d.y() / 100);
            destination = null;
            return;
        }
        boolean flyingToClick = h.moving() && h.targetX() == clickX && h.targetY() == clickY;
        boolean refine = flyingToClick && d.precise() && clicks > 0 && dist <= FAR
                && Math.hypot(clickX - d.x(), clickY - d.y()) > CLICK_SPREAD * 2;
        if (flyingToClick && !refine) {
            return; // on course
        }
        if (now < retryAtNanos || now - lastSendNanos < MIN_INTERVAL_MS * 1_000_000) {
            return;
        }
        click(w, d, dist);
        lastSendNanos = now;
        // if the ship does not start (or stops short), click again after a human pause
        retryAtNanos = now + (RETRY_MIN_MS + (long) (random.nextDouble() * (RETRY_MAX_MS - RETRY_MIN_MS))) * 1_000_000;
    }

    private void click(WorldSnapshot w, Destination d, double dist) {
        WorldSnapshot.Hero h = w.hero();
        MapCatalog.MapInfo map = catalog.info(h.mapId());
        double tx;
        double ty;
        if (dist > FAR) {
            double grid = minimapGrid(h, map);
            tx = Math.round(d.x() / grid) * grid;
            ty = Math.round(d.y() / grid) * grid;
        } else {
            double angle = random.nextDouble() * Math.PI * 2;
            double r = random.nextDouble() * CLICK_SPREAD;
            tx = d.x() + Math.cos(angle) * r;
            ty = d.y() + Math.sin(angle) * r;
        }
        clickX = (int) Math.round(Math.max(0, Math.min(map.width(), tx)));
        clickY = (int) Math.round(Math.max(0, Math.min(map.height(), ty)));
        MoveRequest m = new MoveRequest();
        m.positionX = h.x();
        m.positionY = h.y();
        m.targetX = clickX;
        m.targetY = clickY;
        sender.accept(m);
        clicks++;
    }

    /** Minimap pixel in map units: {@code 10 × zoom × map scale}. */
    static double minimapGrid(WorldSnapshot.Hero h, MapCatalog.MapInfo map) {
        return 10.0 * Math.max(1, h.minimapScale()) * map.scale();
    }

    /** A precise destination must be reached closely; otherwise one minimap pixel is enough. */
    private double arriveRadius(WorldSnapshot.Hero h, Destination d) {
        return d.precise() ? CLICK_SPREAD * 2 + 10 : Math.max(60, minimapGrid(h, catalog.info(h.mapId())));
    }
}
