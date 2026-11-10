package orbit.bot.mod;

import java.util.Comparator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;

/** NPC choice by the config's NPC list: who may be attacked and who comes first. */
public final class Targets {

    /** Distance kept when an NPC has no rule. */
    public static final double DEFAULT_RADIUS = 550;

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

    public static double radius(BotConfig c, WorldSnapshot.Ship s, int mapId) {
        BotConfig.NpcRule r = rule(c, s, mapId);
        return r != null && r.radius() > 0 ? r.radius() : DEFAULT_RADIUS;
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
                .filter(s -> !skip.test(s))
                .filter(s -> s.distanceTo(h.x(), h.y()) <= maxDistance)
                .min(Comparator.<WorldSnapshot.Ship>comparingInt(s -> rule(c, s, h.mapId()).priority())
                        .thenComparingDouble(s -> s.distanceTo(h.x(), h.y())))
                .orElse(null);
    }
}
