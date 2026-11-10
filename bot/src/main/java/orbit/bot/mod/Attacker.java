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

    /** Farther than the radius by this much the target is being chased. */
    static final double CHASE_GAP = 300;

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
        BotConfig.NpcRule rule = Targets.rule(c, t, h.mapId());
        boolean follow = rule != null && rule.has(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW);
        boolean chasing = move && dist > radius + CHASE_GAP && (follow || c.general().runModeChase());
        ctx.ship().want(chasing ? c.general().run() : c.general().offensive());
        if (move && dist > radius + 60 && now >= nextApproach) {
            // following: aim at where it flies, not where it is
            double tx = follow && t.moving() ? t.targetX() : t.x();
            double ty = follow && t.moving() ? t.targetY() : t.y();
            double d = Math.max(1, Math.hypot(tx - h.x(), ty - h.y()));
            double k = Math.max(0, (d - radius) / d);
            ctx.nav().moveTo(h.x() + (tx - h.x()) * k, h.y() + (ty - h.y()) * k, false);
            nextApproach = now + (follow ? ctx.pause(400, 800) : ctx.pause(700, 1_200));
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

    /**
     * SAB on NPCs marked for it, while the own shield is low and the target has shield to drain; else the
     * NPC's own or the default ammunition.
     */
    static String ammo(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t) {
        BotConfig.Loot l = c.loot();
        BotConfig.NpcRule r = Targets.rule(c, t, w.hero().mapId());
        double shield = w.hero().health().shieldPercent();
        if (l.sab() != null && r != null && r.sab() && shield >= 0 && shield < l.sabBelow()
                && t.health().shield() >= l.sabNpcShieldMin() && t.health().shield() > 0) {
            return l.sab();
        }
        return r != null && r.ammo() != null ? r.ammo() : l.ammo();
    }
}
