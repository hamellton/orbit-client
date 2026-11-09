package orbit.bot.run;

import java.util.Comparator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the ship alive between deaths (the SAFETY part of a config):
 * <ul>
 *   <li>heals on the go: the repair robot when HP is below {@code heal.hpBelow} and nobody shoots;</li>
 *   <li>below {@code repairHpMin} (attacked or fighting) or {@code repairHpNoNpc} (quiet) leaves the fight in
 *       the repair configuration, flies to the nearest portal and repairs there up to {@code repairHpMax}
 *       (and {@code repairToShield});</li>
 *   <li>at the portal, still shot at and nearly dead: jumps through; the runner brings the ship back
 *       after the repair.</li>
 * </ul>
 */
public final class Safety {

    private static final Logger log = LoggerFactory.getLogger(Safety.class);

    static final String REPAIR_BOT = "equipment_extra_repbot_rep";
    /** Jump away below this HP when shot at by the portal. */
    static final double ESCAPE_HP = 0.25;
    static final double AT_PORTAL = 300;
    static final long REPAIR_BOT_GAP_MS = 10_000;

    private final BotContext ctx;
    private boolean repairing;
    private int repairMap;
    private long healedAt = Long.MIN_VALUE / 2;
    private long jumpAt;
    private long jumpedAt = Long.MIN_VALUE / 2;

    public Safety(BotContext ctx) {
        this.ctx = ctx;
    }

    public boolean repairing() {
        return repairing;
    }

    /**
     * @param fighting the module has a target
     * @return true while safety steers the ship (the module must not act)
     */
    public boolean tick(boolean fighting) {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        BotConfig.Safety s = ctx.config().get().safety();
        long now = ctx.now();
        double hp = h.health().hpPercent();
        if (hp < 0) {
            return false;
        }
        boolean attacked = !h.attackers().isEmpty();
        heal(w, s, now, hp, attacked);
        if (!repairing) {
            double limit = attacked || fighting ? s.repairHpMin() : s.repairHpNoNpc();
            if (hp < limit) {
                repairing = true;
                repairMap = h.mapId();
                ctx.nav().stop();
                log.info("HP {}% — ухожу на ремонт", pct(hp));
            } else {
                return false;
            }
        }
        double shield = h.health().shieldPercent();
        if (hp >= s.repairHpMax() && (shield < 0 || shield >= s.repairToShield() || h.health().shieldMax() <= 0)) {
            repairing = false;
            log.info("Отремонтирован: HP {}%", pct(hp));
            return false;
        }
        ctx.ship().want(s.repairMode());
        MapCatalog.Portal portal = nearestPortal(h);
        if (portal == null) {
            return true; // no portal known (gate, instance): stay and heal
        }
        double dist = Math.hypot(portal.x() - h.x(), portal.y() - h.y());
        if (dist > AT_PORTAL) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != portal.x()) {
                ctx.nav().moveTo(portal.x(), portal.y(), true);
            }
            ctx.nav().tick();
            return true;
        }
        if (attacked && hp < ESCAPE_HP && w.jumpingTo() == 0 && now - jumpedAt > 10_000_000_000L) {
            if (jumpAt == 0) {
                jumpAt = now + ctx.pause(200, 700);
            } else if (now >= jumpAt) {
                log.warn("На портале под огнём, HP {}% — прыгаю", pct(hp));
                ctx.actions().jump();
                jumpedAt = now;
                jumpAt = 0;
            }
        }
        return true;
    }

    /** The map to come back to after a repair elsewhere (0 if not repairing). */
    public int repairMap() {
        return repairing ? repairMap : 0;
    }

    private void heal(WorldSnapshot w, BotConfig.Safety s, long now, double hp, boolean attacked) {
        double below = repairing ? s.repairHpMax() : s.heal().hpBelow();
        if (below < 0 || hp >= below || attacked || now - healedAt < REPAIR_BOT_GAP_MS * 1_000_000) {
            return;
        }
        WorldSnapshot.Item bot = w.items().get(REPAIR_BOT);
        if (bot != null && bot.available() && bot.remainingMs(now) == 0) {
            log.info("Ремонт роботом (HP {}%)", pct(hp));
            ctx.actions().useItem(REPAIR_BOT);
            healedAt = now;
        }
    }

    private MapCatalog.Portal nearestPortal(WorldSnapshot.Hero h) {
        return ctx.maps().portals(h.mapId()).stream()
                .filter(p -> p.faction() == 0 || p.faction() == h.factionId())
                .min(Comparator.comparingDouble(p -> Math.hypot(p.x() - h.x(), p.y() - h.y()))).orElse(null);
    }

    static int pct(double v) {
        return (int) Math.round(v * 100);
    }
}
