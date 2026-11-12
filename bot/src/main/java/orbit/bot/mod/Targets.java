package orbit.bot.mod;

import java.util.Comparator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;

/** NPC choice by the config's NPC list: who may be attacked and who comes first. */
public final class Targets {

    /** Distance kept when an NPC has no rule. */
    /**
     * An NPC with no radius of its own (0 in the config's table) and none DarkBot recommends
     * ({@link orbit.world.maps.NpcRadius}): the user's DarkBot profiles give such NPCs 560.
     */
    public static final double DEFAULT_RADIUS = 560;

    private Targets() {
    }

    /** The rule of a ship on a map, or {@code null}. */
    public static BotConfig.NpcRule rule(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        BotConfig.NpcRule r = c.npc(s.name());
        return r != null && r.appliesOn(mapId) ? r : null;
    }

    /** Allowed to attack now: listed with "бить", visible, and free unless the rule ignores ownership. */
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
     * Another player's NPC: the server greyed it for us ({@code LSH}), or a player who is not in our group is shooting
     * at it. Only the first shooter (and his group) gets the kill's reward, so such an NPC is not ours — unless its
     * rule says to fight it anyway (the Cubikon, group NPCs: damage counts there).
     */
    public static boolean taken(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship s) {
        BotConfig.NpcRule r = rule(c, s, w.hero().mapId());
        if (r != null && (r.has(BotConfig.NpcFlag.IGNORE_OWNERSHIP) || r.has(BotConfig.NpcFlag.IGNORE_ATTACKED))) {
            return false;
        }
        if (s.lockedByOther()) {
            return true;
        }
        java.util.Set<Integer> group = new java.util.HashSet<>();
        w.group().members().forEach(m -> group.add(m.id()));
        int hero = w.hero().id();
        return w.ships().values().stream().anyMatch(p -> p.kind() == WorldSnapshot.ShipKind.PLAYER && p.id() != hero
                && !group.contains(p.id()) && p.attackTargetId() == s.id());
    }

    public static double radius(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        BotConfig.NpcRule r = rule(c, s, mapId);
        if (r != null && r.radius() > 0) {
            return r.radius(); // the user's own
        }
        double recommended = orbit.world.maps.NpcRadius.bundled().of(s.name());
        return recommended > 0 ? recommended : DEFAULT_RADIUS;
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
                .filter(s -> !skip.test(s))
                .filter(s -> s.distanceTo(h.x(), h.y()) <= maxDistance)
                .min(Comparator.<WorldSnapshot.Ship>comparingInt(s -> rule(c, s, h.mapId()).priority())
                        .thenComparingInt(s -> crowd(w, s))
                        .thenComparingDouble(s -> s.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    /** Around a target this close, other NPCs that attack on sight or shoot the ship make a crowd. */
    static final double CROWD = 1_200;

    /**
     * How many NPCs that attack on sight (seen so) or shoot the ship now stand by {@code s}: of two targets alike the
     * one alone first (docs/FIGHT-MOVEMENT.md Д4).
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
