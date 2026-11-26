package orbit.bot.mod;

import java.util.Comparator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;

/** NPC choice by the config's NPC list: who may be attacked and who comes first. */
public final class Targets {

    /** Distance kept when an NPC has no rule. */
    /**
     * An NPC with no radius of its own (0 in the config's table) and none DarkBot recommends
     * ({@link orbit.world.maps.NpcRadius}): the owner's DarkBot profiles give such NPCs 560.
     */
    public static final double DEFAULT_RADIUS = 560;

    private Targets() {
    }

    /** The rule of a ship on a map, or {@code null}. */
    public static BotConfig.NpcRule rule(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        BotConfig.NpcRule r = c.npc(s.name());
        return r != null && r.appliesOn(mapId) ? r : null;
    }

    /** Allowed to attack now: listed with the attack flag, visible, and free unless the rule ignores ownership. */
    public static boolean attackable(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        if (!s.npc() || s.cloaked()) {
            return false;
        }
        BotConfig.NpcRule r = rule(c, s, mapId);
        if (r == null || !r.kill()) {
            return false;
        }
        return !s.lockedByOther() || r.has(BotConfig.NpcFlag.IGNORE_OWNERSHIP) || r.has(BotConfig.NpcFlag.IGNORE_ATTACKED);
    }

    /**
     * Another player's NPC: the server greyed it for us ({@code LSH}). Only the first shooter (and his group) gets the
     * kill's reward, so such an NPC is not ours — unless its rule says to fight it anyway (the Cubikon, group NPCs:
     * damage counts there).
     */
    public static boolean taken(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship s) {
        BotConfig.NpcRule r = rule(c, s, w.hero().mapId());
        if (r != null && (r.has(BotConfig.NpcFlag.IGNORE_OWNERSHIP) || r.has(BotConfig.NpcFlag.IGNORE_ATTACKED))) {
            return false;
        }
        // the server's own word only (LSH greys it, USH frees it again): an NPC hurt and left by somebody is free
        // again, and a player shooting it got no claim unless the server greys it
        return s.lockedByOther();
    }

    /**
     * As DarkBot's {@code NpcInfo.radius}: the config's own, else the recommended one; nothing learned. The fights once
     * learned a radius per kind (farther while the target hit the ship) and took Invoke XVI to 740, beyond the lasers'
     * reach (2026-11-20 on gb2: 6–12 s without a shot), as Protegit 630 → 720 in a gate before (2026-11-15).
     */
    public static double radius(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        return baseRadius(c, s, mapId);
    }

    /** The radius a kind is fought at, for the config window: {@code own} when the list sets one, else the recommended
     * one, else {@link #DEFAULT_RADIUS}. */
    public static double shownRadius(String name, double own) {
        double recommended = orbit.world.maps.NpcRadius.bundled().of(name);
        return own > 0 ? own : recommended > 0 ? recommended : DEFAULT_RADIUS;
    }

    /** The radius with nothing learned: the config's own, else DarkBot's recommended, else {@link #DEFAULT_RADIUS}. */
    static double baseRadius(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        BotConfig.NpcRule r = rule(c, s, mapId);
        if (r != null && r.radius() > 0) {
            return r.radius();
        }
        double recommended = orbit.world.maps.NpcRadius.bundled().of(s.name());
        return recommended > 0 ? recommended : DEFAULT_RADIUS;
    }

    /**
     * A train by the NPC: {@code players} or more players who are not of the ship's group within {@code radius} of it
     * (AstraBot «avoidTrain»: a group farming it — it would be theirs, and they would turn on the ship).
     */
    public static boolean train(WorldSnapshot w, WorldSnapshot.Ship s, int players, int radius) {
        java.util.Set<Integer> group = new java.util.HashSet<>();
        w.group().members().forEach(m -> group.add(m.id()));
        int hero = w.hero().id();
        long n = w.ships().values().stream().filter(p -> p.kind() == WorldSnapshot.ShipKind.PLAYER && p.id() != hero
                && !group.contains(p.id()) && p.distanceTo(s.x(), s.y()) <= radius).count();
        return n >= players;
    }

    /** The best target within {@code maxDistance}: lowest priority value, then nearest. */
    public static WorldSnapshot.Ship best(BotConfig c, WorldSnapshot w, double maxDistance) {
        return best(c, w, maxDistance, s -> false);
    }

    /** As {@link #best(BotConfig, WorldSnapshot, double)}, leaving out the ships {@code skip} says. */
    public static WorldSnapshot.Ship best(BotConfig c, WorldSnapshot w, double maxDistance,
                                          java.util.function.Predicate<WorldSnapshot.Ship> skip) {
        WorldSnapshot.Hero h = w.hero();
        return w.ships().values().stream()
                .filter(s -> attackable(c, s, h.mapId()))
                .filter(s -> !taken(c, w, s))
                // another player is shooting it (DarkBot's isAttacked), or another account of this program fights it:
                // not ours (the server greys it only after the first shots) — unless its rule fights it anyway
                .filter(s -> ownerIgnored(c, w, s) || !shotByOther(w, s) && !TargetClaims.claimedByOther(h.mapId(), s.id()))
                .filter(s -> !passiveIdle(c, w, s))
                .filter(s -> !skip.test(s))
                .filter(s -> s.distanceTo(h.x(), h.y()) <= maxDistance)
                .min(Comparator.<WorldSnapshot.Ship>comparingInt(s -> rule(c, s, h.mapId()).priority())
                        .thenComparingInt(s -> crowd(w, s))
                        .thenComparingDouble(s -> s.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    /**
     * DarkBot's {@code PASSIVE} ({@code LootModule.shouldKill}): such an NPC is not taken while it leaves the ship
     * alone — only once it shoots it. A fight already begun goes on (the attacker keeps its target).
     */
    public static boolean passiveIdle(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship s) {
        BotConfig.NpcRule r = rule(c, s, w.hero().mapId());
        if (r == null || !r.has(BotConfig.NpcFlag.PASSIVE)) {
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        return s.attackTargetId() != h.id() && !h.attackers().contains(s.id());
    }

    /**
     * A player not of the ship's group, or such a player's pet, is shooting the NPC ({@code AttackLaserRunCommand} with it
     * as the target, till the {@code AttackAbortLaserCommand}).
     */
    public static boolean shotByOther(WorldSnapshot w, WorldSnapshot.Ship npc) {
        WorldSnapshot.Hero h = w.hero();
        java.util.Set<Integer> group = new java.util.HashSet<>();
        if (w.group() != null) {
            w.group().members().forEach(m -> group.add(m.id()));
        }
        for (WorldSnapshot.Ship o : w.ships().values()) {
            if (o.id() == h.id() || o.id() == h.petId() || o.attackTargetId() != npc.id()) {
                continue;
            }
            if (o.kind() == WorldSnapshot.ShipKind.PLAYER && !group.contains(o.id())
                    || o.kind() == WorldSnapshot.ShipKind.PET && o.ownerId() != h.id() && !group.contains(o.ownerId())) {
                return true;
            }
        }
        return false;
    }

    /** The NPC's rule fights it whoever hit first or attacks it ({@code IGNORE_OWNERSHIP}, {@code IGNORE_ATTACKED}). */
    public static boolean ownerIgnored(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship s) {
        BotConfig.NpcRule r = rule(c, s, w.hero().mapId());
        return r != null && (r.has(BotConfig.NpcFlag.IGNORE_OWNERSHIP) || r.has(BotConfig.NpcFlag.IGNORE_ATTACKED));
    }

    /** A player this close to a target, and nearer to it than the ship, gets it first. */
    static final double NEARER = 700;

    /** Another player (not of the group) is within {@link #NEARER} of the NPC and nearer to it than the ship. */
    static boolean someoneNearer(WorldSnapshot w, WorldSnapshot.Ship s) {
        WorldSnapshot.Hero h = w.hero();
        double mine = s.distanceTo(h.x(), h.y());
        if (mine < NEARER) {
            return false;
        }
        java.util.Set<Integer> group = new java.util.HashSet<>();
        w.group().members().forEach(m -> group.add(m.id()));
        for (WorldSnapshot.Ship p : w.ships().values()) {
            if (p.kind() == WorldSnapshot.ShipKind.PLAYER && p.id() != h.id() && !group.contains(p.id())) {
                double d = Math.hypot(p.x() - s.x(), p.y() - s.y());
                if (d < NEARER && d < mine) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Around a target this close, other NPCs that attack on sight or shoot the ship make a crowd. */
    static final double CROWD = 1_200;

    /**
     * How many NPCs that attack on sight (seen so) or shoot the ship now stand by {@code s}: of two targets alike the
     * one alone first (docs/FIGHT-MOVEMENT.md item D4).
     */
    static int crowd(WorldSnapshot w, WorldSnapshot.Ship s) {
        int n = 0;
        int hero = w.hero().id();
        for (WorldSnapshot.Ship o : w.ships().values()) {
            if (o.npc() && o.id() != s.id() && Math.hypot(o.x() - s.x(), o.y() - s.y()) < CROWD
                    && (o.attackTargetId() == hero || Threats.aggressive(o.name()))) {
                n++;
            }
        }
        return n;
    }
}
