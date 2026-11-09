package orbit.bot.act;

import java.util.Comparator;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import orbit.bot.nav.Navigator;
import orbit.world.WorldSnapshot;
import orbit.world.WorldSnapshot.Ship;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kills NPCs on the current map and picks up the boxes they drop, the way the player does in the
 * recordings: click the ship, press the laser ammunition key 0.8–1.5 s later (the server starts the
 * attack), stay in range, fly onto the box and pick it up. Repairs with the repair robot when hurt
 * and nobody attacks. The basis of the KILL module (stage 5).
 *
 * <p>Call {@link #tick} about ten times a second; it owns the {@link Navigator} meanwhile.
 */
public final class Hunter {

    private static final Logger log = LoggerFactory.getLogger(Hunter.class);

    static final double KEEP_MAX = 520;
    static final double KEEP_AT = 380;
    static final double BOX_RANGE = 1_500;
    static final String REPAIR_BOT = "equipment_extra_repbot_rep";
    static final double REPAIR_BELOW = 0.9;

    public enum State { HUNTING, DONE }

    private final Supplier<WorldSnapshot> world;
    private final Actions actions;
    private final Navigator nav;
    private final Random random;
    private final LongSupplier clock;
    private final Predicate<Ship> wanted;
    private final int killsWanted;

    private State state = State.HUNTING;
    private int targetId;
    private long selectAt;
    private long pressAt;
    private long pressedAt;
    private long nextApproach;
    private String boxHash;
    private long collectAt;
    private long collectedAt;
    private long repairedAt = Long.MIN_VALUE / 2;
    private int kills;
    private int boxes;

    /**
     * @param wanted which NPCs to hunt (e.g. by name); free NPCs only (not shot at by other players)
     */
    public Hunter(Supplier<WorldSnapshot> world, Actions actions, Navigator nav, Random random, LongSupplier clock,
                  Predicate<Ship> wanted, int killsWanted) {
        this.world = world;
        this.actions = actions;
        this.nav = nav;
        this.random = random;
        this.clock = clock;
        this.wanted = wanted;
        this.killsWanted = killsWanted;
    }

    public State state() {
        return state;
    }

    public int kills() {
        return kills;
    }

    public int boxes() {
        return boxes;
    }

    public void tick() {
        if (state == State.DONE) {
            return;
        }
        WorldSnapshot w = world.get();
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0 || !h.alive()) {
            return;
        }
        long now = clock.getAsLong();
        repairIfHurt(w, now);
        if (targetId != 0 && !w.ships().containsKey(targetId)) {
            kills++;
            log.info("Цель уничтожена ({} из {})", kills, killsWanted);
            targetId = 0;
            nav.stop();
        }
        if (targetId == 0 && collectBoxes(w, now)) {
            return;
        }
        if (targetId == 0) {
            if (kills >= killsWanted) {
                state = State.DONE;
                return;
            }
            Ship next = w.ships().values().stream()
                    .filter(s -> s.kind() == WorldSnapshot.ShipKind.NPC && !s.lockedByOther() && !s.cloaked() && wanted.test(s))
                    .min(Comparator.comparingDouble(s -> s.distanceTo(h.x(), h.y()))).orElse(null);
            if (next == null) {
                return;
            }
            targetId = next.id();
            selectAt = now + ms(300, 900);
            pressAt = 0;
            pressedAt = 0;
            log.info("Цель: {} в {}", next.name(), (int) next.distanceTo(h.x(), h.y()));
        }
        Ship t = w.ships().get(targetId);
        if (t == null) {
            return;
        }
        if (t.lockedByOther() && h.attackTargetId() != targetId) {
            log.info("{} атакует другой игрок — ищу другую цель", t.name());
            targetId = 0;
            return;
        }
        double dist = t.distanceTo(h.x(), h.y());
        approach(h, t, dist, now);
        if (h.selectedId() != targetId) {
            if (now >= selectAt) {
                actions.select(t);
                selectAt = now + ms(1_500, 2_500); // click again if the server ignored it
                pressAt = 0;
            }
            return;
        }
        if (h.attackTargetId() == targetId) {
            return;
        }
        if (pressAt == 0) {
            pressAt = now + ms(800, 1_500);
        }
        if (now >= pressAt && dist <= KEEP_MAX + 100 && now - pressedAt > 3_000_000_000L) {
            WorldSnapshot.Item laser = w.selected("lasers");
            if (laser != null && laser.counter() > 0) {
                actions.useItem(laser.id());
            } else {
                actions.attackLaser(t);
            }
            pressedAt = now;
        }
    }

    private void approach(WorldSnapshot.Hero h, Ship t, double dist, long now) {
        if (dist <= KEEP_MAX || now < nextApproach) {
            return;
        }
        // to a point between us and the target, KEEP_AT short of it
        double k = (dist - KEEP_AT) / dist;
        nav.moveTo(h.x() + (t.x() - h.x()) * k, h.y() + (t.y() - h.y()) * k, false);
        nextApproach = now + ms(700, 1_200);
        nav.tick();
    }

    /** After a kill: fly onto the nearest box and pick it up. Returns true while busy with boxes. */
    private boolean collectBoxes(WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        if (boxHash != null && !w.collectables().containsKey(boxHash)) {
            boxes++;
            log.info("Ящик собран ({})", boxes);
            boxHash = null;
        }
        if (boxHash == null) {
            WorldSnapshot.Collectable box = w.collectables().values().stream()
                    .filter(c -> c.kind() == WorldSnapshot.CollectableKind.BOX)
                    .filter(c -> Math.hypot(c.x() - h.x(), c.y() - h.y()) <= BOX_RANGE)
                    .min(Comparator.comparingDouble(c -> Math.hypot(c.x() - h.x(), c.y() - h.y()))).orElse(null);
            if (box == null) {
                return false;
            }
            boxHash = box.hash();
            collectAt = 0;
            collectedAt = 0;
            nav.moveTo(box.x(), box.y(), true);
        }
        WorldSnapshot.Collectable box = w.collectables().get(boxHash);
        nav.tick();
        if (box != null && nav.idle() && !h.moving()) {
            if (collectAt == 0) {
                collectAt = now + ms(100, 300);
            }
            if (now >= collectAt && now - collectedAt > 3_000_000_000L) {
                actions.collect(box);
                collectedAt = now;
            }
        }
        return true;
    }

    private void repairIfHurt(WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        double hp = h.health().hpPercent();
        WorldSnapshot.Item bot = w.items().get(REPAIR_BOT);
        if (hp >= 0 && hp < REPAIR_BELOW && h.attackers().isEmpty() && bot != null && bot.available()
                && bot.remainingMs(now) == 0 && now - repairedAt > 10_000_000_000L) {
            log.info("Ремонт роботом (HP {}%)", (int) (hp * 100));
            actions.useItem(REPAIR_BOT);
            repairedAt = now;
        }
    }

    private long ms(long min, long max) {
        return (min + (long) (random.nextDouble() * (max - min))) * 1_000_000;
    }
}
