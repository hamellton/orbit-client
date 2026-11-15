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
    private volatile long failedUntil = Long.MIN_VALUE;
    private long lackingSince;
    private volatile String told = "";
    static final long LACK_HOLD_NS = 60_000_000_000L;

    private void tellOnce(String s) {
        if (!s.equals(told)) {
            told = s;
            log.warn(s);
        }
    }
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
        if (now < failedUntil) {
            return; // the site refused a moment ago (no uridium for the quick one, a limit…): not again at once
        }
        Map<Integer, Long> lacking = s.path("forEnrich").asBoolean(true) ? enrichMissing.get() : Map.of();
        // the want must hold a minute: the ore of a transport may be landing in the hold this very second (live
        // 2026-11-15 on GE1: «нет seprom» 2 s before the 100 came, a second transport asked and refused)
        if (lacking.isEmpty()) {
            lackingSince = 0;
        } else if (lackingSince == 0) {
            lackingSince = now;
        }
        long every = Math.max(1, s.path("everyMinutes").asLong(20)) * 60_000_000_000L;
        boolean due = checkedAt == Long.MIN_VALUE || now - checkedAt >= every;
        // the enrichment lacks an ore: soon, but not more often than every 2 min
        boolean wanted = !lacking.isEmpty() && now - lackingSince >= LACK_HOLD_NS
                && (checkedAt == Long.MIN_VALUE || now - checkedAt >= LACK_EVERY_NS);
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
        long cargoMax = ctx.world().get().hero().cargoMax();
        int fastMinFree = Math.max(0, Math.min(100, s.path("fastMinFreePercent").asInt(50)));
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
                long total = 0;
                for (Map.Entry<String, Long> e : want.entrySet()) {
                    long there = Math.max(0, st.ores().getOrDefault(e.getKey(), 0L) - keep);
                    if (there < e.getValue()) {
                        // a warning, not a refusal: what there is goes (the user, 2026-11-16)
                        tellOnce("Скайлаб: на складе " + e.getKey() + " " + there + " из нужных " + e.getValue()
                                + " — везу сколько есть");
                    }
                    long n = Math.min(e.getValue(), there);
                    total += n;
                    n = Math.min(n, free);
                    if (n > 0) {
                        send.put(e.getKey(), n);
                        free -= n;
                    }
                }
                long sending = send.values().stream().mapToLong(Long::longValue).sum();
                if (room >= 0 && total > room) {
                    // the paid one only into a hold at least half free (the user, 2026-11-16: «без премиума у Цитадели
                    // 8 000 трюма… хотя бы 50 % трюма свободно, если размер большой»), else not in small paid trips
                    if (fast && cargoMax > 0 && room * 100 < (long) fastMinFree * cargoMax) {
                        tellOnce("Скайлаб: быстрый транспорт — в трюме свободно " + room + " из " + cargoMax + " (меньше "
                                + fastMinFree + " %), нужно " + total + " — жду, пока освободится");
                        return;
                    }
                    tellOnce("Скайлаб: в трюме свободно " + room + " из нужных " + total + " — везу " + sending);
                }
                if (send.isEmpty()) {
                    tellOnce(room == 0 ? "Скайлаб: в трюме нет места — жду"
                            : "Скайлаб: везти нечего — на складе " + st.ores() + ", нужно " + want);
                    return;
                }
                a.transport(send, fast);
                told = "";
                log.info("Скайлаб: отправил в трюм {}{}", send, fast ? " (быстро)" : "");
            } catch (java.io.IOException | RuntimeException e) {
                // refused or failed: the next try after the check's interval, told once (no uridium for the quick one
                // would else be asked every 2 min)
                failedUntil = ctx.now() + Math.max(1, s.path("everyMinutes").asLong(20)) * 60_000_000_000L;
                tellOnce("Скайлаб: не отправить — " + e.getMessage() + (fast ? " (быстрый: хватает ли уридия?)" : "")
                        + "; снова через " + s.path("everyMinutes").asLong(20) + " мин");
            } finally {
                busy = false;
            }
        });
    }
}
