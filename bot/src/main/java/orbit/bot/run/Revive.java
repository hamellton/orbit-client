package orbit.bot.run;

import orbit.config.BotConfig;
import orbit.protocol.gen.KillScreenOptionTypeModule;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kill screen: waits like a player reading it, then takes the repair the config asks for
 * ({@code BASE}, {@code PORTAL}, {@code SPOT}) if it is free, otherwise the free basic one. Paid
 * repairs are never taken. Counts deaths for {@code maxDeaths}.
 */
public final class Revive {

    private static final Logger log = LoggerFactory.getLogger(Revive.class);

    private final BotContext ctx;
    private int deaths;
    private boolean dead;
    private long repairAt;
    private long repairedAt = Long.MIN_VALUE / 2;
    private long resumeAt;

    public Revive(BotContext ctx) {
        this.ctx = ctx;
    }

    public int deaths() {
        return deaths;
    }

    /** Stop after this many deaths (config {@code maxDeaths} &gt; 0). */
    public boolean limitReached() {
        int max = ctx.config().get().safety().maxDeaths();
        return max > 0 && deaths >= max;
    }

    /** @return true while dead or in the pause after reviving */
    public boolean tick() {
        WorldSnapshot w = ctx.world().get();
        long now = ctx.now();
        BotConfig.Safety s = ctx.config().get().safety();
        WorldSnapshot.Death death = w.death();
        if (death == null && w.hero().alive()) {
            if (dead) {
                dead = false;
                resumeAt = now + Math.max(0, s.waitAfterReviveSec()) * 1_000_000_000L + ctx.pause(0, 3_000);
                log.info("Снова в игре");
            }
            return now < resumeAt;
        }
        if (!dead) {
            dead = true;
            deaths++;
            ctx.stats().deaths.incrementAndGet();
            ctx.nav().stop();
            repairAt = now + Math.max(0, s.waitBeforeReviveSec()) * 1_000_000_000L + ctx.pause(2_000, 6_000);
            log.warn("Корабль уничтожен ({}){} — смертей {}", death == null ? "?" : death.killer(), "", deaths);
        }
        if (death == null || now < repairAt || now - repairedAt < 15_000_000_000L) {
            return true;
        }
        if (limitReached()) {
            return true; // the runner stops the bot
        }
        WorldSnapshot.RepairOption pick = choose(death, s.revive());
        int faction = w.hero().factionId();
        if (pick == null) {
            log.warn("Бесплатного ремонта нет — жду");
            repairedAt = now;
            return true;
        }
        if (faction == 0) {
            log.warn("Фракция неизвестна — не могу выбрать ремонт");
            repairedAt = now;
            return true;
        }
        log.info("Ремонт: {}", pick.button());
        ctx.repair().take(pick.type(), faction);
        repairedAt = now;
        return true;
    }

    static WorldSnapshot.RepairOption choose(WorldSnapshot.Death death, String revive) {
        int wanted = switch (revive == null ? "" : revive) {
            case "PORTAL" -> KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR;
            case "SPOT" -> KillScreenOptionTypeModule.OPTIONTYPE_AT_DEATHLOCATION_REPAIR;
            default -> KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR;
        };
        for (WorldSnapshot.RepairOption o : death.options()) {
            if (o.type() == wanted && free(o)) {
                return o;
            }
        }
        return death.freeRepair();
    }

    private static boolean free(WorldSnapshot.RepairOption o) {
        return o.affordable() && o.price() == 0 && o.cooldownSeconds() == 0;
    }
}
