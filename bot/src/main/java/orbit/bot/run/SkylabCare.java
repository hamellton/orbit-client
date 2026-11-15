package orbit.bot.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ore from the Skylab into the hold by its transport module (DarkBot's DmPlugin SkylabSender; the user, 2026-11-15: «бот сам
 * присылал со Скайлаба»): every {@code everyMinutes} — or as soon as the enrichment lacks an ore, with {@code forEnrich} —
 * the page is read; no new transport while one is on its way; the amounts are the config's, no more than the hold's room
 * and what the storage holds above {@code keepOnSkylab}.
 *
 * <p>Settings {@code modules.skylab}: {@code enabled}, {@code ores} = {name: amount} ({@code promerium}, {@code seprom},
 * {@code prometid}, {@code duranium}…), {@code everyMinutes}, {@code forEnrich}, {@code keepOnSkylab}, {@code fast}.
 * The website is asked on a thread of its own: the bot's loop never waits for it.
 */
public final class SkylabCare {

    private static final Logger log = LoggerFactory.getLogger(SkylabCare.class);

    /** {@code OreTypeModule} numbers → the website's ore names. */
    static final Map<Integer, String> NAMES = Map.of(0, "prometium", 1, "endurium", 2, "terbium", 3, "xenomit",
            4, "prometid", 5, "duranium", 6, "promerium", 7, "seprom");

    private final BotContext ctx;
    private final Supplier<Map<Integer, Long>> enrichMissing;
    private volatile SkylabAccess access;
    private volatile boolean busy;
    private long checkedAt = Long.MIN_VALUE;
    static final long LACK_EVERY_NS = 2 * 60_000_000_000L;

    public SkylabCare(BotContext ctx, Supplier<Map<Integer, Long>> enrichMissing) {
        this.ctx = ctx;
        this.enrichMissing = enrichMissing;
    }

    public void setAccess(SkylabAccess access) {
        this.access = access;
    }

    private JsonNode settings() {
        JsonNode s = ctx.config().get().modules().get("skylab");
        return s == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : s;
    }

    /** One step: starts a check on its own thread when it is due. */
    public void tick() {
        JsonNode s = settings();
        SkylabAccess a = access;
        if (!s.path("enabled").asBoolean(false) || a == null || busy) {
            return;
        }
        long now = ctx.now();
        Map<Integer, Long> lacking = s.path("forEnrich").asBoolean(true) ? enrichMissing.get() : Map.of();
        long every = Math.max(1, s.path("everyMinutes").asLong(20)) * 60_000_000_000L;
        boolean due = checkedAt == Long.MIN_VALUE || now - checkedAt >= every;
        // the enrichment lacks an ore: at once, but not more often than every 2 min
        boolean wanted = !lacking.isEmpty() && (checkedAt == Long.MIN_VALUE || now - checkedAt >= LACK_EVERY_NS);
        if (!due && !wanted) {
            return;
        }
        checkedAt = now;
        Map<String, Long> want = new LinkedHashMap<>();
        s.path("ores").fields().forEachRemaining(e -> {
            if (e.getValue().asLong(0) > 0) {
                want.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue().asLong());
            }
        });
        lacking.forEach((ore, n) -> {
            String name = NAMES.get(ore);
            if (name != null) {
                want.merge(name, n, Math::max);
            }
        });
        if (want.isEmpty()) {
            return;
        }
        long room = ctx.world().get().cargoFree();
        long keep = Math.max(0, s.path("keepOnSkylab").asLong(0));
        boolean fast = s.path("fast").asBoolean(false);
        busy = true;
        java.util.Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap(); // the account's log and journal
        Thread.ofVirtual().name("skylab-send").start(() -> {
            if (mdc != null) {
                org.slf4j.MDC.setContextMap(mdc);
            }
            try {
                SkylabAccess.Stock st = a.stock();
                if (st.transporting()) {
                    log.info("Скайлаб: транспорт ещё в пути — жду");
                    return;
                }
                Map<String, Long> send = new LinkedHashMap<>();
                long free = room < 0 ? Long.MAX_VALUE : room;
                for (Map.Entry<String, Long> e : want.entrySet()) {
                    long there = Math.max(0, st.ores().getOrDefault(e.getKey(), 0L) - keep);
                    long n = Math.min(Math.min(e.getValue(), there), free);
                    if (n > 0) {
                        send.put(e.getKey(), n);
                        free -= n;
                    }
                }
                if (send.isEmpty()) {
                    log.info("Скайлаб: везти нечего (на складе {}, в трюме свободно {})", st.ores(), room);
                    return;
                }
                a.transport(send, fast);
                log.info("Скайлаб: отправил в трюм {}{}", send, fast ? " (быстро)" : "");
            } catch (java.io.IOException | RuntimeException e) {
                log.warn("Скайлаб: не отправить — {}", e.getMessage());
            } finally {
                busy = false;
            }
        });
    }
}
