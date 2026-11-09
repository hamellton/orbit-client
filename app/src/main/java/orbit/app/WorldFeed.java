package orbit.app;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import orbit.protocol.Packet;
import orbit.world.World;
import orbit.world.WorldSnapshot;

/**
 * What the window shows: the world model plus the connection state, fed by whichever source runs
 * (the bot's own connection, the sniffer in observer mode, or a recording).
 */
public final class WorldFeed {

    private final World world = new World();
    private final AtomicLong packets = new AtomicLong();
    private final String mode;
    private volatile String state = "Запуск";
    /** Time base of the model: real time, or the replay clock. */
    private volatile LongSupplier clock = System::nanoTime;

    public WorldFeed(String mode) {
        this.mode = mode;
    }

    public void onPacket(Packet p, long nanos) {
        packets.incrementAndGet();
        world.apply(p, nanos);
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
