package orbit.bot.run;

import java.util.List;
import orbit.config.BotConfig;
import orbit.protocol.gen.KillScreenOptionTypeModule;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The kill screen: waits like a player reading it, then takes the repair the config asks for
 * ({@code BASE}, {@code PORTAL}, {@code SPOT}) whatever it costs if affordable, otherwise the base; a
 * single option is taken as it is. Counts deaths for {@code maxDeaths}.
 * <p>Paid revives in a row are limited ({@code paidRevives}): killed again within {@code paidReviveMinutes} of a
 * revive at the portal or on the spot (live 2026-11-11: a player kept killing the ship at the portal) counts as
 * «in a row»; past the limit the ship revives at the base once and the count starts again. The count is kept
 * in the account's data folder, so a restart does not reset it.
 */
public final class Revive {

    private static final Logger log = LoggerFactory.getLogger(Revive.class);

    private final BotContext ctx;
    private int deaths;
    private boolean dead;
    private long repairAt;
    private long repairedAt = Long.MIN_VALUE / 2;
    private long resumeAt;
    /** Paid revives in a row, and when the last one was (wall ms); kept in {@link #STREAK_FILE}. */
    private int streak;
    private long paidAtMs;
    private final long wallBase = System.currentTimeMillis();
    private final long nanoBase;

    static final String STREAK_FILE = "revive-streak.json";

    public Revive(BotContext ctx) {
        this.ctx = ctx;
        this.nanoBase = ctx.now();
        loadStreak();
    }

    /** Wall-clock ms on the bot's clock (a fake clock in tests). */
    private long wallMs() {
        return wallBase + (ctx.now() - nanoBase) / 1_000_000;
    }

    int streak() {
        return streak;
    }

    /** The same session goes on after a ship change: its deaths keep counting towards the limit. */
    void takeFrom(Revive previous) {
        deaths = previous.deaths;
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
            log.warn("Корабль уничтожен ({}) — смертей {}", death == null ? "?" : death.killer(), deaths);
            if (streak > 0 && wallMs() - paidAtMs > Math.max(1, s.paidReviveMinutes()) * 60_000L) {
                streak = 0; // lived long enough after the last paid revive: not «in a row»
                saveStreak();
            }
            if (death != null) {
                log.info("Варианты ремонта: {}", death.options().stream().map(Revive::describe).toList());
            }
        }
        if (death == null || now < repairAt || now - repairedAt < 15_000_000_000L) {
            return true;
        }
        if (limitReached()) {
            return true; // the runner stops the bot
        }
        String wanted = s.revive();
        if (!"BASE".equals(wanted) && s.paidRevives() > 0 && streak >= s.paidRevives()) {
            log.info("Убит {} раз подряд после платного возрождения — сейчас на базе, потом снова как в конфиге", streak);
            wanted = "BASE";
        }
        WorldSnapshot.RepairOption pick = choose(death, wanted);
        int faction = w.hero().factionId();
        if (pick == null) {
            log.warn("Подходящего ремонта нет — жду");
            repairedAt = now;
            return true;
        }
        if (faction == 0) {
            // logged in dead: no ship yet, so no faction — the client sends what it knows then, 0 as in its login
            log.info("Фракция ещё неизвестна (вошёл с уничтоженным кораблём) — ремонт без неё");
        }
        log.info("Ремонт: {}", describe(pick));
        ctx.repair().take(pick.type(), faction);
        repairedAt = now;
        if (base(pick)) {
            streak = 0;
        } else {
            streak++;
            paidAtMs = wallMs();
        }
        saveStreak();
        return true;
    }

    private java.nio.file.Path streakFile() {
        return ctx.dataDir() == null ? null : ctx.dataDir().resolve(STREAK_FILE);
    }

    private void loadStreak() {
        java.nio.file.Path f = streakFile();
        if (f == null || !java.nio.file.Files.exists(f)) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.toFile());
            streak = n.path("streak").asInt(0);
            paidAtMs = n.path("paidAt").asLong(0);
        } catch (java.io.IOException e) {
            log.warn("Не прочитать {}: {}", f, e.getMessage());
        }
    }

    private void saveStreak() {
        java.nio.file.Path f = streakFile();
        if (f == null) {
            return;
        }
        try {
            java.nio.file.Files.createDirectories(f.toAbsolutePath().getParent());
            java.nio.file.Files.writeString(f, "{\"streak\": " + streak + ", \"paidAt\": " + paidAtMs + "}\n");
        } catch (java.io.IOException e) {
            log.warn("Не записать {}: {}", f, e.getMessage());
        }
    }

    /**
     * The user's rule: the chosen place (base, portal, death spot) whatever it costs, if the game says
     * it is affordable; otherwise the base; a single option is taken as it is.
     */
    static WorldSnapshot.RepairOption choose(WorldSnapshot.Death death, String revive) {
        List<WorldSnapshot.RepairOption> all = death.options();
        if (all.size() == 1) {
            return all.getFirst();
        }
        int wanted = switch (revive == null ? "" : revive) {
            case "PORTAL" -> KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR;
            case "SPOT" -> KillScreenOptionTypeModule.OPTIONTYPE_AT_DEATHLOCATION_REPAIR;
            default -> -1;
        };
        for (WorldSnapshot.RepairOption o : all) {
            if (o.type() == wanted && o.affordable() && o.cooldownSeconds() == 0) {
                return o;
            }
        }
        // the base: the free basic repair first, then any affordable basic one
        for (WorldSnapshot.RepairOption o : all) {
            if (base(o) && free(o)) {
                return o;
            }
        }
        for (WorldSnapshot.RepairOption o : all) {
            if (base(o) && o.affordable() && o.cooldownSeconds() == 0) {
                return o;
            }
        }
        return death.freeRepair();
    }

    static boolean base(WorldSnapshot.RepairOption o) {
        return o.type() == KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR
                || o.type() == KillScreenOptionTypeModule.OPTIONTYPE_FREE_PHOENIX
                || o.type() == KillScreenOptionTypeModule.OPTIONTYPE_BASIC_FULL_REPAIR;
    }

    /** {@code на базе — бесплатно}, {@code у портала — 200}… for the log. */
    static String describe(WorldSnapshot.RepairOption o) {
        String place = switch (o.type()) {
            case KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR, KillScreenOptionTypeModule.OPTIONTYPE_FREE_PHOENIX,
                 KillScreenOptionTypeModule.OPTIONTYPE_BASIC_FULL_REPAIR -> "на базе";
            case KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR -> "у портала";
            case KillScreenOptionTypeModule.OPTIONTYPE_AT_DEATHLOCATION_REPAIR -> "на месте гибели";
            default -> "вариант " + o.type();
        };
        String price = o.price() <= 0 ? "бесплатно"
                : o.price() + (o.priceType() == orbit.protocol.gen.PriceModule.CURRENCYTYPE_URIDIUM ? " уридия" : " кредитов");
        return place + " — " + price + (o.affordable() ? "" : " (не хватает)")
                + (o.cooldownSeconds() > 0 ? " (через " + o.cooldownSeconds() + " с)" : "");
    }

    private static boolean free(WorldSnapshot.RepairOption o) {
        return o.affordable() && o.price() == 0 && o.cooldownSeconds() == 0;
    }
}
