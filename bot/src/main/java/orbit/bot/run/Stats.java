package orbit.bot.run;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * What the bot did this session (rewards come from the world, {@code WorldSnapshot.rewards}). Updated by the
 * bot loop, read by the window: counters are atomic.
 */
public final class Stats {

    /** NPCs the bot was shooting at when they died. */
    public final AtomicInteger kills = new AtomicInteger();
    public final AtomicInteger boxes = new AtomicInteger();
    /** A full hold is being sold right now ({@code CargoCare}): the modules may wait for the room it makes. */
    public volatile boolean selling;
    /** The way a full hold is handled now (the module's or the config's), for the box picker; null = the config's. */
    public volatile String cargoMode;
    public final AtomicInteger deaths = new AtomicInteger();
    /** Trips to a portal to repair. */
    public final AtomicInteger repairs = new AtomicInteger();
    private final java.util.Set<Integer> cubes = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A Cubikon the bot attacked (each one counted once). */
    public void cubeFought(int id) {
        cubes.add(id);
    }

    /** Cubikons fought. */
    public int cubesFought() {
        return cubes.size();
    }
    /** Cubikons that died while the bot was shooting at them. */
    public final AtomicInteger cubesKilled = new AtomicInteger();
}
