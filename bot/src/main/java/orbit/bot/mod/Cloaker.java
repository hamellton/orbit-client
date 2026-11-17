package orbit.bot.mod;

import orbit.bot.run.BotContext;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A CL04K cloak kept on while the ship does not fight, as the behemoth module's waiting cloak (CARNAGE's BLCycle useCloak):
 * the biggest CPU there is, not more often than {@link #GAP_NS}; a use that brings no cloak twice in a row (the charge
 * spent, as after a death) — a rest of {@link #REST_NS}. Our own attack takes the cloak off by itself (the user,
 * 2026-11-17: invokes under the enemy trains — «невиды, пока не бьёт инвоков»).
 */
final class Cloaker {

    private static final Logger log = LoggerFactory.getLogger(Cloaker.class);

    static final long GAP_NS = 60_000_000_000L;
    static final long ANSWER_NS = 5_000_000_000L;
    static final int MISSES = 2;
    static final long REST_NS = 5 * 60_000_000_000L;

    private final BotContext ctx;
    private long askedAt = Long.MIN_VALUE / 2;
    private long restUntil = Long.MIN_VALUE / 2;
    private double counterAtUse = -1;
    private int misses;
    private boolean noneTold;

    Cloaker(BotContext ctx) {
        this.ctx = ctx;
    }

    /** One step; {@code fighting}: the ship shoots or is about to — no cloak then (the charge would be lost). */
    void tick(boolean fighting) {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (h.id() == 0 || !h.alive()) {
            return;
        }
        if (h.cloaked()) {
            misses = 0;
            counterAtUse = -1;
            return;
        }
        if (counterAtUse >= 0 && now - askedAt > ANSWER_NS) {
            counterAtUse = -1;
            if (++misses >= MISSES) {
                misses = 0;
                restUntil = now + REST_NS;
                log.warn("Маскировка {} раза подряд не включилась (заряды уходят) — не включаю {} мин", MISSES,
                        REST_NS / 60_000_000_000L);
            }
        }
        if (fighting || now < restUntil || now - askedAt < GAP_NS) {
            return;
        }
        for (String id : orbit.config.ItemCatalog.CLOAKS) {
            WorldSnapshot.Item i = w.items().get(id);
            if (i != null && i.available() && !i.blocked() && i.counter() > 0 && i.remainingMs(now) == 0) {
                log.info("Маскировка: {} (осталось {})", orbit.config.ItemCatalog.label(id), (long) i.counter() - 1);
                ctx.actions().useItem(id);
                askedAt = now;
                counterAtUse = i.counter();
                return;
            }
        }
        askedAt = now;
        if (!noneTold) {
            noneTold = true;
            log.info("Маскировки нет (CL04K) — работаю без неё; купить можно во вкладке «Покупки»");
        }
    }
}
