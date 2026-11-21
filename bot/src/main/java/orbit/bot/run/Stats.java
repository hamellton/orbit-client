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
    /** What was picked up, by name (e.g. palladium, bonus box), in the order first seen. */
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
    /** Booty keys as last seen (server code → count) and how many of each went this session (every drop counted). */
    private final java.util.Map<String, Integer> keysSeen = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> keysSpent = new java.util.HashMap<>();

    /**
     * The booty keys spent this session (server code → count), brought up to date with the counts the server sends now:
     * a count lower than the last one seen is that many spent, a higher one (keys bought or won) spends nothing.
     */
    public synchronized java.util.Map<String, Integer> keysSpent(java.util.Map<String, Integer> now) {
        if (now != null) {
            now.forEach((code, n) -> {
                Integer was = keysSeen.put(code, n);
                if (was != null && n < was) {
                    keysSpent.merge(code, was - n, Integer::sum);
                }
            });
        }
        return java.util.Map.copyOf(keysSpent);
    }

    /** NPCs killed by name and the seconds of their fights (from the pick to the kill; {@code -1} unknown — not summed). */
    private final java.util.Map<String, Integer> killedBy = new java.util.TreeMap<>();
    private final java.util.Map<String, double[]> fightTime = new java.util.TreeMap<>();

    /** One NPC killed: the total and the kind's count, and its fight's length when known. */
    public synchronized void killed(String name, double fightSeconds) {
        kills.incrementAndGet();
        String n = name == null || name.isBlank() ? "?" : name;
        killedBy.merge(n, 1, Integer::sum);
        if (fightSeconds > 0) {
            double[] t = fightTime.computeIfAbsent(n, k -> new double[2]);
            t[0] += fightSeconds;
            t[1]++;
        }
    }

    /** NPCs killed this session: name → count. */
    public synchronized java.util.Map<String, Integer> killedBy() {
        return java.util.Map.copyOf(killedBy);
    }

    /** The average fight with each kind killed, seconds (only kinds with known lengths). */
    public synchronized java.util.Map<String, Double> fightSeconds() {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        fightTime.forEach((k, t) -> out.put(k, t[0] / t[1]));
        return out;
    }

    /**
     * Galaxy gates by name: {@code "<gate>|placed"}, {@code "|done"}, {@code "|seconds"} (the runs' length summed),
     * {@code "|deaths"}, {@code "|lives"} (bought) → count.
     */
    private final java.util.Map<String, Integer> gates = new java.util.TreeMap<>();

    /** Adds {@code n} to one of a gate's counters ({@link #gates()}). */
    public synchronized void gate(String name, String what, int n) {
        if (n > 0) {
            gates.merge((name == null || name.isBlank() ? "?" : name) + "|" + what, n, Integer::sum);
        }
    }

    /** The gates' counters this session ({@code "<gate>|<what>"} → count). */
    public synchronized java.util.Map<String, Integer> gates() {
        return java.util.Map.copyOf(gates);
    }

    /** Ammunition counts as last seen (item id → count) and how much of each went this session. */
    private final java.util.Map<String, Double> ammoSeen = new java.util.HashMap<>();
    private final java.util.Map<String, Double> ammoSpent = new java.util.TreeMap<>();
    /**
     * A drop bigger than this between two looks is no shooting (35 lasers fire 35 rounds a second; the bot looks ~10
     * times a second) but the item bar sent anew — not counted.
     */
    static final double AMMO_STEP_MAX = 1_000;

    /**
     * The ammunition spent this session brought up to date with the item bar's counts ({@code ammunition_*} items): a
     * count lower than the last one seen is that many spent, a higher one (picked up, bought) spends nothing.
     */
    public synchronized void ammoSeen(java.util.Map<String, Double> counts) {
        counts.forEach((id, n) -> {
            if (!id.startsWith("ammunition_")) {
                return;
            }
            Double was = ammoSeen.put(id, n);
            if (was != null && n < was && was - n <= AMMO_STEP_MAX) {
                ammoSpent.merge(id, was - n, Double::sum);
            }
        });
    }

    /** The ammunition spent this session: item id → rounds. */
    public synchronized java.util.Map<String, Double> ammoSpent() {
        return java.util.Map.copyOf(ammoSpent);
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
     * The players in sight who attacked or killed the ship this session (any company, {@code Offenders}): the behemoth's
     * wait keeps off them too (an offender may be absolutely anyone).
     */
    public final java.util.concurrent.atomic.AtomicReference<java.util.Set<Integer>> offenders =
            new java.util.concurrent.atomic.AtomicReference<>(java.util.Set.of());

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
        java.util.Map<String, Integer> killedBefore = base.killedBy();
        killedBy().forEach((k, v) -> {
            int n = v - killedBefore.getOrDefault(k, 0);
            if (n > 0) {
                d.killedBy.put(k, n);
            }
        });
        java.util.Map<String, Integer> gatesBefore = base.gates();
        gates().forEach((k, v) -> {
            int n = v - gatesBefore.getOrDefault(k, 0);
            if (n > 0) {
                d.gates.put(k, n);
            }
        });
        synchronized (this) {
            java.util.Map<String, double[]> before;
            synchronized (base) {
                before = new java.util.HashMap<>(base.fightTime);
            }
            fightTime.forEach((k, t) -> {
                double[] b = before.getOrDefault(k, new double[2]);
                if (t[1] - b[1] > 0) {
                    d.fightTime.put(k, new double[] {t[0] - b[0], t[1] - b[1]});
                }
            });
        }
        java.util.Map<String, Double> spentBefore = base.ammoSpent();
        ammoSpent().forEach((k, v) -> {
            double n = v - spentBefore.getOrDefault(k, 0.0);
            if (n > 0) {
                d.ammoSpent.put(k, n);
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
