package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import orbit.bot.run.BotContext;
import orbit.world.QuestTitles;
import orbit.world.Quests;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The passes' free buttons, as a player clicks them in their windows (plan 5б.9 step 4; the user's choices
 * 2026-11-12): Season Pass «Забрать всё» when a reached level's reward is unclaimed on a track the account has; a free
 * reroll of a season mission the bot cannot do; Battle Pass «Забрать» with the bought pass; Galaxy Trek's sector rewards;
 * the diminishing window's «Принять» for a quest the bot can do. Never rerolled: a mission that counts other missions
 * or objectives ({@link QuestTargets#grows}) — it grows by itself. Never: uridium (level purchases, paid rerolls), Galaxy
 * Trek searches (they spend FTL keys — the user: never), the Battle Pass key exchange (the user chooses himself).
 * Settings ({@code modules.quests}): {@code season}, {@code seasonReroll}, {@code battlepass}, {@code trek},
 * {@code diminishing} — on by default.
 */
final class PassCare {

    private static final Logger log = LoggerFactory.getLogger(PassCare.class);

    static final long RETRY_MS = 60_000;

    private final BotContext ctx;
    private long nextAt;
    private long seasonAt = Long.MIN_VALUE / 2;
    private long battlePassAt = Long.MIN_VALUE / 2;
    private final Map<Integer, Long> trekAt = new HashMap<>();
    private final Map<Integer, Long> rerollAt = new HashMap<>();
    private final Map<Integer, Long> diminishingAt = new HashMap<>();

    PassCare(BotContext ctx) {
        this.ctx = ctx;
    }

    /** One free click at most, a human moment after the last one. */
    void tick(WorldSnapshot w, JsonNode settings, Predicate<Quests.Quest> doable) {
        tick(w, settings, doable, true);
    }

    /**
     * @param picks false: no rerolls and no diminishing quests (quests alongside another module: what it moves is not
     *              known) — only the rewards are collected
     */
    void tick(WorldSnapshot w, JsonNode settings, Predicate<Quests.Quest> doable, boolean picks) {
        if (settings == null) {
            settings = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        long now = ctx.now();
        if (now < nextAt) {
            return;
        }
        Quests.Passes p = w.quests().passes();
        if (on(settings, "season") && p.seasonClaimable() > 0 && ms(now - seasonAt) > RETRY_MS) {
            log.info("Season Pass: забираю награды уровней ({})", p.seasonClaimable());
            ctx.actions().seasonClaimAll();
            seasonAt = now;
            done(now);
            return;
        }
        if (picks && on(settings, "seasonReroll")) {
            for (Quests.Quest q : w.quests().active().values()) {
                // «N Season Pass missions», «N objectives»: they grow as the rest is done (the user, 2026-11-19)
                if (!Quests.SEASON.equals(q.source()) || doable.test(q) || QuestTargets.growing(q)) {
                    continue;
                }
                boolean daily = q.is(Quests.DAILY);
                boolean weekly = q.is(Quests.WEEKLY);
                if (!(daily && p.dailyLeft() > 0 || weekly && p.weeklyLeft() > 0)) {
                    continue;
                }
                if (ms(now - rerollAt.getOrDefault(q.id(), Long.MIN_VALUE / 2)) < 5 * RETRY_MS) {
                    continue;
                }
                log.info("Season Pass: миссию {} бот не умеет — бесплатно перевыбираю (осталось {})",
                        QuestTitles.label(q.id()), daily ? p.dailyLeft() : p.weeklyLeft());
                ctx.actions().seasonFreeReroll(q.id());
                rerollAt.put(q.id(), now);
                done(now);
                return;
            }
        }
        if (on(settings, "battlepass") && p.bpPurchased() && !p.bpToCollect().isEmpty()
                && ms(now - battlePassAt) > RETRY_MS) {
            log.info("Battle Pass: забираю награды ({})", p.bpToCollect().size());
            ctx.actions().battlePassCollect();
            battlePassAt = now;
            done(now);
            return;
        }
        if (on(settings, "trek")) {
            for (int[] s : p.trekSectors()) {
                if (s[2] == 1 && s[3] == 0 && ms(now - trekAt.getOrDefault(s[0], Long.MIN_VALUE / 2)) > RETRY_MS) {
                    log.info("Galaxy Trek: забираю награду за сектор ({}/{})", s[1], s[0]);
                    ctx.actions().trekSectorReward(s[0]);
                    trekAt.put(s[0], now);
                    done(now);
                    return;
                }
            }
        }
        Quests.Quest d = p.diminishing();
        if (picks && on(settings, "diminishing") && d != null && p.diminishingStatus() == Quests.ACCEPTABLE
                && ms(now - diminishingAt.getOrDefault(d.id(), Long.MIN_VALUE / 2)) > RETRY_MS) {
            if (doable.test(d)) {
                log.info("Убывающий квест {}: принимаю", QuestTitles.label(d.id()));
                ctx.actions().diminishingAccept(d.id());
            } else {
                log.info("Убывающий квест {}: бот его не умеет — не принимаю", QuestTitles.label(d.id()));
            }
            diminishingAt.put(d.id(), now);
            done(now);
        }
    }

    private void done(long now) {
        nextAt = now + ctx.pause(1_500, 4_000);
    }

    private static boolean on(JsonNode settings, String key) {
        return settings.path(key).asBoolean(true);
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
