package orbit.bot.mod;

import orbit.bot.run.BotContext;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attacks one ship the way the player does in the recordings: click it, choose the ammunition (the
 * config's, SAB while the own shield is low), the attack starts with the ammunition key 0.8–1.5 s
 * later; keeps the NPC's radius from the config.
 */
public final class Attacker {

    private static final Logger log = LoggerFactory.getLogger(Attacker.class);

    private final BotContext ctx;
    private int targetId;
    private boolean shotAt;
    private long selectAt;
    private long pressAt;
    private long pressedAt = Long.MIN_VALUE / 2;
    private long ammoAt = Long.MIN_VALUE / 2;
    private long nextApproach;
    private int kills;

    public Attacker(BotContext ctx) {
        this.ctx = ctx;
    }

    public int targetId() {
        return targetId;
    }

    public int kills() {
        return kills;
    }

    public void target(WorldSnapshot.Ship s) {
        if (s.id() == targetId) {
            return;
        }
        targetId = s.id();
        shotAt = false;
        selectAt = ctx.now() + ctx.pause(300, 900);
        pressAt = 0;
        WorldSnapshot.Hero h = ctx.world().get().hero();
        log.info("Цель: {} в {}", s.name(), (int) s.distanceTo(h.x(), h.y()));
    }

    public void clear() {
        targetId = 0;
        shotAt = false;
    }

    /**
     * @param move whether to keep the distance (false while the module moves the ship, e.g. onto a box)
     * @return false when there is no target (none set, or it is gone)
     */
    public boolean tick(boolean move) {
        if (targetId == 0) {
            return false;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship t = w.ships().get(targetId);
        if (t == null) {
            if (shotAt) {
                kills++;
                log.info("Цель уничтожена (всего {})", kills);
            }
            clear();
            return false;
        }
        BotConfig c = ctx.config().get();
        long now = ctx.now();
        double radius = Targets.radius(c, t, h.mapId());
        double dist = t.distanceTo(h.x(), h.y());
        ctx.ship().want(c.general().offensive());
        if (move && dist > radius + 60 && now >= nextApproach) {
            double k = (dist - radius) / dist;
            ctx.nav().moveTo(h.x() + (t.x() - h.x()) * k, h.y() + (t.y() - h.y()) * k, false);
            nextApproach = now + ctx.pause(700, 1_200);
        }
        if (move) {
            ctx.nav().tick();
        }
        if (h.attackTargetId() == targetId) {
            shotAt = true;
        }
        if (h.selectedId() != targetId) {
            if (now >= selectAt) {
                ctx.actions().select(t);
                selectAt = now + ctx.pause(1_500, 2_500);
                pressAt = 0;
            }
            return true;
        }
        String ammo = ammo(c, w, t);
        WorldSnapshot.Item wanted = ammo == null ? null : w.items().get(ammo);
        if (wanted != null && !wanted.selected() && wanted.counter() > 0 && now - ammoAt > 1_500_000_000L) {
            if (pressAt == 0) {
                pressAt = now + ctx.pause(800, 1_500);
            }
            if (now >= pressAt) {
                ctx.actions().useItem(ammo); // with a target selected this also starts the attack
                ammoAt = now;
                pressedAt = now;
                pressAt = 0;
            }
            return true;
        }
        if (h.attackTargetId() == targetId) {
            return true;
        }
        if (pressAt == 0) {
            pressAt = now + ctx.pause(800, 1_500);
        }
        if (now >= pressAt && dist <= radius + 200 && now - pressedAt > 3_000_000_000L) {
            WorldSnapshot.Item laser = w.selected("lasers");
            if (laser != null && laser.counter() > 0) {
                ctx.actions().useItem(laser.id());
            } else {
                ctx.actions().attackLaser(t);
            }
            pressedAt = now;
            pressAt = 0;
        }
        return true;
    }

    /** SAB while the own shield is low and the target has shield to drain; else the NPC's or the default ammo. */
    static String ammo(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t) {
        BotConfig.Loot l = c.loot();
        double shield = w.hero().health().shieldPercent();
        if (l.sab() != null && shield >= 0 && shield < l.sabBelow() && t.health().shield() >= l.sabNpcShieldMin()
                && t.health().shield() > 0) {
            return l.sab();
        }
        BotConfig.NpcRule r = Targets.rule(c, t, w.hero().mapId());
        return r != null && r.ammo() != null ? r.ammo() : l.ammo();
    }
}
