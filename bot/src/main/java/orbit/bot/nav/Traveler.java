package orbit.bot.nav;

import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import orbit.protocol.Packet;
import orbit.protocol.gen.JumpRequest;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Travels to a map (and a point on it) through portals, the way players do (recordings of
 * 2026-11-09, 10 jumps):
 * <ul>
 *   <li>fly to the portal; the server offers the jump ({@code MapAssetActionAvailableCommand}) about
 *       300–500 units away;</li>
 *   <li>press jump 0.2–1 s later, usually still on the approach ({@code JumpRequest} has no fields:
 *       the server takes the portal the ship is at);</li>
 *   <li>the server answers {@code JumpInitiatedCommand} (0.1 s) and moves the ship ~3.3 s later;</li>
 *   <li>on the new map the ship sits on the portal back; after a short look around it flies on.</li>
 * </ul>
 * Call {@link #tick} about ten times a second from the bot loop, together with nothing else
 * steering the {@link Navigator}.
 */
public final class Traveler {

    private static final Logger log = LoggerFactory.getLogger(Traveler.class);

    /** The jump is pressed only this close to the portal (the server offers it up to ~500). */
    static final double JUMP_RANGE = 450;
    static final long PRESS_MIN_MS = 200;
    static final long PRESS_MAX_MS = 1_000;
    /** No {@code JumpInitiatedCommand} this long after pressing: press again. */
    static final long JUMP_ANSWER_MS = 3_000;
    /** A started jump takes ~3.3 s; give up waiting after this. */
    static final long JUMP_TIMEOUT_MS = 12_000;
    /** Pause on a new map before flying on. */
    static final long ARRIVAL_MIN_MS = 800;
    static final long ARRIVAL_MAX_MS = 2_500;
    /** A catalog portal and the server's portal object are the same within this distance. */
    static final double PORTAL_MATCH = 60;

    public enum State { IDLE, TRAVELLING, ARRIVED, FAILED }

    private final Supplier<WorldSnapshot> world;
    private final Consumer<Packet> sender;
    private final Navigator nav;
    private final MapCatalog catalog;
    private final Random random;
    private final LongSupplier clock;

    private State state = State.IDLE;
    private String failure = "";
    private int targetMap;
    private double targetX = Double.NaN;
    private double targetY = Double.NaN;
    private int lastMap;
    private long resumeAtNanos;
    private long pressAtNanos;
    private long pressedAtNanos;
    private boolean finalLeg;
    private int jumps;

    public Traveler(Supplier<WorldSnapshot> world, Consumer<Packet> sender, Navigator nav, MapCatalog catalog,
                    Random random, LongSupplier clock) {
        this.world = world;
        this.sender = sender;
        this.nav = nav;
        this.catalog = catalog;
        this.random = random;
        this.clock = clock;
    }

    /** Goes to {@code mapId}; with a point ({@code x}, {@code y} not NaN) flies there after arriving. */
    public void travel(int mapId, double x, double y) {
        targetMap = mapId;
        targetX = x;
        targetY = y;
        state = State.TRAVELLING;
        failure = "";
        finalLeg = false;
        pressAtNanos = 0;
        pressedAtNanos = 0;
        jumps = 0;
        nav.stop();
    }

    public State state() {
        return state;
    }

    public String failure() {
        return failure;
    }

    public int jumps() {
        return jumps;
    }

    public void tick() {
        if (state != State.TRAVELLING) {
            return;
        }
        WorldSnapshot w = world.get();
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0 || !h.alive()) {
            return;
        }
        long now = clock.getAsLong();
        if (h.mapId() != lastMap) {
            // arrived somewhere (or started): look around before flying on, forget the old map's plans
            if (lastMap != 0) {
                log.info("Карта {}", catalog.info(h.mapId()).name());
                resumeAtNanos = now + ms(ARRIVAL_MIN_MS, ARRIVAL_MAX_MS);
            }
            lastMap = h.mapId();
            nav.stop();
            pressAtNanos = 0;
            pressedAtNanos = 0;
            finalLeg = false;
        }
        if (now < resumeAtNanos) {
            return;
        }
        if (h.mapId() == targetMap) {
            arrive(h);
            return;
        }
        if (w.jumpingTo() != 0) {
            // the server is moving us: wait, but not forever
            if (pressedAtNanos != 0 && now - pressedAtNanos > JUMP_TIMEOUT_MS * 1_000_000) {
                log.warn("Прыжок не состоялся, пробую снова");
                pressedAtNanos = 0;
                pressAtNanos = 0;
            }
            return;
        }
        List<MapCatalog.Portal> route = Router.route(catalog, h.mapId(), h.x(), h.y(), targetMap, h.factionId());
        if (route.isEmpty()) {
            fail("нет пути с карты " + catalog.info(h.mapId()).name() + " на " + catalog.info(targetMap).name());
            return;
        }
        MapCatalog.Portal portal = route.getFirst();
        double dist = Math.hypot(portal.x() - h.x(), portal.y() - h.y());
        WorldSnapshot.Portal object = serverPortal(w, portal);
        boolean offered = object != null && w.activatable().containsKey(object.id());
        if (offered && dist <= JUMP_RANGE) {
            if (pressedAtNanos != 0 && now - pressedAtNanos < JUMP_ANSWER_MS * 1_000_000) {
                return; // pressed, waiting for the server
            }
            if (pressAtNanos == 0) {
                pressAtNanos = now + ms(PRESS_MIN_MS, PRESS_MAX_MS);
            }
            if (now >= pressAtNanos) {
                sender.accept(new JumpRequest());
                jumps++;
                pressedAtNanos = now;
                pressAtNanos = 0;
                log.info("Прыжок на {}", portal.toName());
            }
        }
        // keep flying onto the portal meanwhile, like a player who clicked it
        if (nav.idle() || nav.destination().x() != portal.x() || nav.destination().y() != portal.y()) {
            if (dist > PORTAL_MATCH) {
                nav.moveTo(portal.x(), portal.y(), true);
            }
        }
        nav.tick();
    }

    private void arrive(WorldSnapshot.Hero h) {
        if (Double.isNaN(targetX)) {
            state = State.ARRIVED;
            return;
        }
        if (!finalLeg) {
            nav.moveTo(targetX, targetY, false);
            finalLeg = true;
        }
        nav.tick();
        if (nav.idle()) {
            state = State.ARRIVED;
        }
    }

    /** The server's object for a catalog portal (same place). */
    private static WorldSnapshot.Portal serverPortal(WorldSnapshot w, MapCatalog.Portal p) {
        for (WorldSnapshot.Portal o : w.portals().values()) {
            if (Math.hypot(o.x() - p.x(), o.y() - p.y()) <= PORTAL_MATCH) {
                return o;
            }
        }
        return null;
    }

    private void fail(String why) {
        log.warn("Перелёт невозможен: {}", why);
        failure = why;
        state = State.FAILED;
        nav.stop();
    }

    private long ms(long min, long max) {
        return (min + (long) (random.nextDouble() * (max - min))) * 1_000_000;
    }
}
