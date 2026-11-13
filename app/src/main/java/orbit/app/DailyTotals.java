package orbit.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What an account earned today over all its runs (the user, 2026-11-13: the session's card starts from nothing at
 * each start of the bot). Each run keeps its own totals in {@code local/today-<account>.json}, written every
 * {@link #SAVE_MS}; today is their sum. A run's counters going down (a new login in the same program) starts a new
 * run; at midnight the day starts anew, the running one counting from there.
 */
public final class DailyTotals {

    static final long SAVE_MS = 30_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, DailyTotals> ACCOUNTS = new ConcurrentHashMap<>();

    private final Path file;
    private final ZoneId zone;
    private final String runPrefix = Long.toString(System.currentTimeMillis(), 36);
    private LocalDate day;
    /** run → {totals at the day's start (or the run's), totals now}. */
    private final Map<String, HourlyReport.Totals[]> runs = new LinkedHashMap<>();
    private String run;
    private int runIndex;
    private long savedAt;

    DailyTotals(Path file, ZoneId zone, LocalDate today) {
        this.file = file;
        this.zone = zone;
        this.day = today;
        load();
    }

    /** The account's totals of today (kept on disk). */
    public static DailyTotals of(String account) {
        return ACCOUNTS.computeIfAbsent(account == null ? "" : account, a -> new DailyTotals(
                Path.of("local", "today-" + (a.isEmpty() ? "default" : a.replaceAll("[^\\w.@-]", "_")) + ".json"),
                ZoneId.systemDefault(), LocalDate.now()));
    }

    /** The running session's totals now; saved now and then. */
    public synchronized void update(HourlyReport.Totals now, long nowMillis) {
        LocalDate today = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone);
        if (!today.equals(day)) {
            // a new day: the earlier runs were yesterday's; the running one counts from here
            day = today;
            runs.keySet().removeIf(k -> !k.equals(run));
            HourlyReport.Totals[] mine = runs.get(run);
            if (mine != null) {
                mine[0] = mine[1];
            }
        }
        HourlyReport.Totals[] mine = run == null ? null : runs.get(run);
        if (mine == null || wentDown(mine[1], now)) {
            run = runPrefix + "-" + (++runIndex);
            mine = new HourlyReport.Totals[] {HourlyReport.Totals.NONE, now};
            runs.put(run, mine);
        }
        mine[1] = now;
        if (nowMillis - savedAt >= SAVE_MS) {
            savedAt = nowMillis;
            save();
        }
    }

    private static boolean wentDown(HourlyReport.Totals before, HourlyReport.Totals now) {
        return now.kills() < before.kills() || now.uridium() < before.uridium() || now.credits() < before.credits()
                || now.experience() < before.experience();
    }

    /** Today's totals: every run's gain summed. */
    public synchronized HourlyReport.Totals today() {
        double uri = 0;
        double cr = 0;
        double xp = 0;
        double hon = 0;
        int kills = 0;
        int cubes = 0;
        int repairs = 0;
        int deaths = 0;
        Map<String, Integer> picked = new TreeMap<>();
        Map<String, Double> items = new TreeMap<>();
        for (HourlyReport.Totals[] r : runs.values()) {
            HourlyReport.Totals a = r[0];
            HourlyReport.Totals b = r[1];
            uri += b.uridium() - a.uridium();
            cr += b.credits() - a.credits();
            xp += b.experience() - a.experience();
            hon += b.honor() - a.honor();
            kills += b.kills() - a.kills();
            cubes += b.cubes() - a.cubes();
            repairs += b.repairs() - a.repairs();
            deaths += b.deaths() - a.deaths();
            b.picked().forEach((k, v) -> picked.merge(k, v - a.picked().getOrDefault(k, 0), Integer::sum));
            b.items().forEach((k, v) -> items.merge(k, v - a.items().getOrDefault(k, 0.0), Double::sum));
        }
        picked.values().removeIf(v -> v <= 0);
        items.values().removeIf(v -> v <= 0);
        return new HourlyReport.Totals(uri, cr, xp, hon, kills, cubes, repairs, deaths, picked, items);
    }

    /** Lines for the window's card. */
    public List<String> lines() {
        HourlyReport.Totals t = today();
        List<String> out = new ArrayList<>();
        out.add("Уридий: " + SessionSummary.num(t.uridium()) + "   кредиты: " + SessionSummary.num(t.credits()));
        out.add("Опыт: " + SessionSummary.num(t.experience()) + "   честь: " + SessionSummary.num(t.honor()));
        out.add("Убито NPC " + t.kills() + (t.cubes() > 0 ? ", кубов " + t.cubes() : "")
                + "   ремонтов " + t.repairs() + "   смертей " + t.deaths());
        if (!t.picked().isEmpty()) {
            List<String> parts = new ArrayList<>();
            t.picked().forEach((n, v) -> parts.add(n + " " + v));
            out.add("Подобрано: " + String.join(", ", parts));
        }
        t.items().forEach((id, v) -> {
            String res = SessionSummary.resource(id);
            out.add((res != null ? "В трюме " + res : SessionSummary.itemName(id)) + ": " + SessionSummary.num(v));
        });
        return out;
    }

    private void load() {
        try {
            if (file == null || !Files.exists(file)) {
                return;
            }
            JsonNode root = JSON.readTree(file.toFile());
            if (!day.toString().equals(root.path("day").asText())) {
                return; // another day's
            }
            root.path("runs").properties().forEach(e -> runs.put(e.getKey(), new HourlyReport.Totals[] {
                    totals(e.getValue().path("from")), totals(e.getValue().path("to"))}));
        } catch (IOException e) {
            // started anew
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("day", day.toString());
        ObjectNode rs = root.putObject("runs");
        runs.forEach((k, r) -> {
            ObjectNode n = rs.putObject(k);
            n.set("from", node(r[0]));
            n.set("to", node(r[1]));
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // kept in memory
        }
    }

    /** Written at once (the account stops). */
    public void flush() {
        save();
    }

    private static ObjectNode node(HourlyReport.Totals t) {
        ObjectNode n = JSON.createObjectNode();
        n.put("uridium", t.uridium());
        n.put("credits", t.credits());
        n.put("experience", t.experience());
        n.put("honor", t.honor());
        n.put("kills", t.kills());
        n.put("cubes", t.cubes());
        n.put("repairs", t.repairs());
        n.put("deaths", t.deaths());
        ObjectNode picked = n.putObject("picked");
        t.picked().forEach(picked::put);
        ObjectNode items = n.putObject("items");
        t.items().forEach(items::put);
        return n;
    }

    private static HourlyReport.Totals totals(JsonNode n) {
        Map<String, Integer> picked = new TreeMap<>();
        n.path("picked").properties().forEach(e -> picked.put(e.getKey(), e.getValue().asInt()));
        Map<String, Double> items = new TreeMap<>();
        n.path("items").properties().forEach(e -> items.put(e.getKey(), e.getValue().asDouble()));
        return new HourlyReport.Totals(n.path("uridium").asDouble(), n.path("credits").asDouble(),
                n.path("experience").asDouble(), n.path("honor").asDouble(), n.path("kills").asInt(),
                n.path("cubes").asInt(), n.path("repairs").asInt(), n.path("deaths").asInt(), picked, items);
    }
}
