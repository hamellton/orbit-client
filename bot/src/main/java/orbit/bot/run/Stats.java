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
    /** What was picked up, by name («палладий», «бонус-бокс»), in the order first seen. */
    private final java.util.Map<String, AtomicInteger> picked = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    /** One more of {@code name} picked up; returns the session's count of it. */
    public int picked(String name) {
        return picked.computeIfAbsent(name, k -> new AtomicInteger()).incrementAndGet();
    }

    /** A copy of what was picked up: name → count. */
    public java.util.Map<String, Integer> pickedSoFar() {
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        synchronized (picked) {
            picked.forEach((k, v) -> out.put(k, v.get()));
        }
        return out;
    }
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
    /**
     * Portals that did not let the ship through ({@code map:x:y}): a jump asked and nothing happened (live
     * 2026-11-12: 5-2 → 5-4, twice under fire). Kept for the run, the account's whole stay.
     */
    public final java.util.Set<String> closedPortals = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Cubikons that died while the bot was shooting at them. */
    public final AtomicInteger cubesKilled = new AtomicInteger();
}
