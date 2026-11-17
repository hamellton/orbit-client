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
    /**
     * Ore a quest wants from the Skylab now ({@code OreTypeModule} number → amount; empty none): the quest module sets it,
     * the Skylab transport brings it as it brings what the enrichment lacks.
     */
    public final java.util.concurrent.atomic.AtomicReference<java.util.Map<Integer, Long>> questOres =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.Map.of());
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

    /**
     * The counters since {@code base} — a copy for the window after its statistics were reset (the bot's own counters go
     * on untouched: the hour's summary and the day's totals count from them).
     */
    public Stats since(Stats base) {
        Stats d = new Stats();
        if (base == null) {
            base = new Stats();
        }
        d.kills.set(Math.max(0, kills.get() - base.kills.get()));
        d.boxes.set(Math.max(0, boxes.get() - base.boxes.get()));
        d.deaths.set(Math.max(0, deaths.get() - base.deaths.get()));
        d.repairs.set(Math.max(0, repairs.get() - base.repairs.get()));
        d.cubesKilled.set(Math.max(0, cubesKilled.get() - base.cubesKilled.get()));
        for (int i = 0, n = Math.max(0, cubesFought() - base.cubesFought()); i < n; i++) {
            d.cubes.add(i);
        }
        java.util.Map<String, Integer> was = base.pickedSoFar();
        pickedSoFar().forEach((k, v) -> {
            int n = v - was.getOrDefault(k, 0);
            if (n > 0) {
                d.picked.put(k, new AtomicInteger(n));
            }
        });
        d.selling = selling;
        d.cargoMode = cargoMode;
        return d;
    }

    /** A frozen copy of the counters now (the base of a reset). */
    public Stats copy() {
        return since(null);
    }
}
