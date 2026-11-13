package orbit.app;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One line an hour on the clock («Сводка 14:00–15:00 · в игре 58 мин · уридий +1 200 · …»): what the hour
 * brought, so 40 hours read as 40 lines and the hour where something went wrong stands out. The first hour, when
 * shorter than {@link #MIN_FIRST_MINUTES}, is joined to the next one.
 */
public final class HourlyReport {

    private static final Logger log = LoggerFactory.getLogger(HourlyReport.class);
    static final int MIN_FIRST_MINUTES = 15;

    /** The counters an hour is told by (rewards, the bot's own counts). */
    public record Totals(double uridium, double credits, double experience, double honor, int kills, int cubes,
                         int repairs, int deaths, Map<String, Integer> picked, Map<String, Double> items) {

        public static final Totals NONE = new Totals(0, 0, 0, 0, 0, 0, 0, 0, Map.of(), Map.of());

        static Totals of(WorldFeed feed) {
            var r = feed.snapshot().rewards();
            orbit.bot.run.Stats s = feed.stats();
            return new Totals(r.uridium(), r.credits(), r.experience(), r.honor(),
                    s == null ? 0 : s.kills.get(), s == null ? 0 : s.cubesKilled.get(),
                    s == null ? 0 : s.repairs.get(), s == null ? 0 : s.deaths.get(),
                    s == null ? Map.of() : s.pickedSoFar(), Map.copyOf(r.items()));
        }
    }

    private final Supplier<Totals> totals;
    private final ZoneId zone;
    private Totals base;
    private long from;
    private long until;
    private long onlineMs;
    private long lastTick;

    /** @param startMillis now (epoch ms) */
    public HourlyReport(Supplier<Totals> totals, long startMillis, ZoneId zone) {
        this.totals = totals;
        this.zone = zone;
        this.base = totals.get();
        this.from = startMillis;
        this.lastTick = startMillis;
        long next = nextHour(startMillis);
        this.until = next - startMillis < MIN_FIRST_MINUTES * 60_000L ? next + 3_600_000L : next;
    }

    public static HourlyReport of(WorldFeed feed) {
        return new HourlyReport(() -> Totals.of(feed), System.currentTimeMillis(), ZoneId.systemDefault());
    }

    /**
     * Called often (each second): counts the time in game; past the hour, logs the line and starts the next.
     *
     * @return the line logged, or {@code null}
     */
    public String tick(long nowMillis, boolean online) {
        if (online) {
            onlineMs += Math.max(0, Math.min(nowMillis - lastTick, 60_000));
        }
        lastTick = nowMillis;
        if (nowMillis < until) {
            return null;
        }
        Totals now = totals.get();
        String text = text(from, until, base, now, onlineMs);
        log.info(text);
        base = now;
        from = until;
        until = nextHour(nowMillis);
        onlineMs = 0;
        return text;
    }

    private long nextHour(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone).truncatedTo(ChronoUnit.HOURS)
                .plusHours(1);
        return t.atZone(zone).toInstant().toEpochMilli();
    }

    String text(long fromMillis, long untilMillis, Totals a, Totals b, long online) {
        List<String> parts = new ArrayList<>();
        parts.add("Сводка " + clock(fromMillis) + "–" + clock(untilMillis));
        long minutes = Math.round(online / 60_000.0);
        long span = Math.round((untilMillis - fromMillis) / 60_000.0);
        if (minutes == 0) {
            parts.add("не в игре");
            return String.join(" · ", parts);
        }
        parts.add(minutes < span - 1 ? "в игре " + minutes + " из " + span + " мин"
                : span >= 59 ? "в игре весь час" : "в игре все " + span + " мин");
        add(parts, "уридий", diff(a.uridium(), b.uridium()));
        add(parts, "кредиты", diff(a.credits(), b.credits()));
        add(parts, "опыт", diff(a.experience(), b.experience()));
        add(parts, "честь", diff(a.honor(), b.honor()));
        int kills = (int) diff(a.kills(), b.kills());
        if (kills > 0) {
            parts.add("NPC " + kills);
        }
        int cubes = (int) diff(a.cubes(), b.cubes());
        if (cubes > 0) {
            parts.add("кубов " + cubes);
        }
        Map<String, Integer> picked = new LinkedHashMap<>();
        b.picked().forEach((name, n) -> {
            int d = (int) diff(a.picked().getOrDefault(name, 0), n);
            if (d > 0) {
                picked.put(name, d);
            }
        });
        if (!picked.isEmpty()) {
            List<String> p = new ArrayList<>();
            picked.forEach((name, n) -> p.add(name + " " + SessionSummary.num(n)));
            parts.add("подобрано: " + String.join(", ", p));
        }
        // resources gained in the hold, apart from the boxes picked up (the same ore under two names was unclear)
        List<String> hold = new ArrayList<>();
        b.items().forEach((id, v) -> {
            double d = diff(a.items().getOrDefault(id, 0.0), v);
            String res = resource(id);
            if (res != null && d > 0) {
                hold.add(res + " +" + SessionSummary.num(d));
            } else if (res == null) {
                add(parts, SessionSummary.itemName(id), d);
            }
        });
        if (!hold.isEmpty()) {
            parts.add("в трюме: " + String.join(", ", hold));
        }
        parts.add("ремонтов " + (int) diff(a.repairs(), b.repairs()));
        int deaths = (int) diff(a.deaths(), b.deaths());
        parts.add(deaths > 0 ? "смертей " + deaths + " ⚠" : "смертей 0");
        return String.join(" · ", parts);
    }

    /** A resource's name for {@code resource_collectable_scrapium} and the like, {@code null} for other items. */
    private static String resource(String id) {
        for (String p : new String[] {"resource_collectable_", "resource_"}) {
            if (id.startsWith(p)) {
                String n = id.substring(p.length());
                String ru = orbit.config.LootNames.known(n);
                return ru != null ? ru : SessionSummary.itemName(id);
            }
        }
        return null;
    }

    /** A counter that went down was started anew (a new session): all of it is this hour's. */
    private static double diff(double before, double now) {
        return now >= before ? now - before : now;
    }

    private static void add(List<String> parts, String name, double v) {
        if (v > 0) {
            parts.add(name + " +" + SessionSummary.num(v));
        }
    }

    private String clock(long millis) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), zone);
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }
}
