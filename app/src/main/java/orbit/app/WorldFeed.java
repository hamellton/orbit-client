package orbit.app;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import orbit.protocol.Packet;
import orbit.protocol.gen.JumpInitiatedCommand;
import orbit.protocol.gen.ReloginCommand;
import orbit.protocol.gen.ShipDestroyedCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the window shows: the world model plus the connection state, fed by whichever source runs
 * (the bot's own connection, the sniffer in observer mode, or a recording).
 */
public final class WorldFeed {

    private static final Logger log = LoggerFactory.getLogger(WorldFeed.class);

    private final World world = new World();
    private final AtomicLong packets = new AtomicLong();
    private final String mode;
    private volatile String state = "Запуск";
    /** What the bot is doing; set by the behaviour modules (stage 4–5), none yet. */
    private volatile String action;
    /** Time base of the model: real time, or the replay clock. */
    private volatile LongSupplier clock = System::nanoTime;

    public WorldFeed(String mode) {
        this.mode = mode;
    }

    public void onPacket(Packet p, long nanos) {
        packets.incrementAndGet();
        int heroBefore = world.snapshot(nanos).hero().id();
        world.apply(p, nanos);
        logEvent(p, heroBefore);
    }

    /** Events worth a line in the window's log; the state line follows them in observer and replay modes. */
    private void logEvent(Packet p, int heroId) {
        MapCatalog maps = MapCatalog.bundled();
        switch (p) {
            case ShipInitializationCommand si -> {
                String where = maps.info(si.mapId).name();
                log.info("Корабль {} на карте {} ({}/{})", si.userName, where, si.x / 100, si.y / 100);
                if (!mode.startsWith("бот")) {
                    state = "В игре: " + where;
                }
            }
            case JumpInitiatedCommand j -> log.info("Прыжок на карту {}", maps.info(j.mapId).name());
            case ReloginCommand r -> log.info("Переход на карту {} (другой сервер)", maps.info(r.mapID).name());
            case ShipDestroyedCommand d when d.destroyedUserId == heroId && heroId != 0 -> {
                log.warn("Корабль уничтожен");
                if (!mode.startsWith("бот")) {
                    state = "Корабль уничтожен";
                }
            }
            default -> {
            }
        }
    }

    public void onSent(Packet p, long nanos) {
        world.applyOutgoing(p, nanos);
    }

    public void setState(String state) {
        this.state = state;
    }

    public String state() {
        return state;
    }

    public void setAction(String action) {
        this.action = action;
    }

    /** Current bot action, or a note when the bot does not control the ship. */
    public String action() {
        String a = action;
        if (a != null) {
            return a;
        }
        return mode.startsWith("бот") ? "нет (бот пока только держит связь)" : "— (корабль ведёт игрок)";
    }

    public String mode() {
        return mode;
    }

    public long packets() {
        return packets.get();
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** The world as of now (by the feed's clock). */
    public WorldSnapshot snapshot() {
        return world.snapshot(now());
    }
}
