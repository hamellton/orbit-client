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
 * What an account earned today over all its runs (the session's card starts from nothing at each start of the
 * bot), kept in {@code local/today-<account>.json} and written every {@link #SAVE_MS}. Each look adds what every
 * counter gained since the last look; a counter that went down was started anew and all of it is new. The counters
 * come from two places that start anew at different times — the world's rewards run on over the account's whole stay
 * in the program, the bot's own counters start from nothing at each login — so each counter is followed on its own
 * (a new login must not count the stay's rewards again). At midnight the day starts anew.
 */
public final class DailyTotals {

    static final long SAVE_MS = 30_000;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, DailyTotals> ACCOUNTS = new ConcurrentHashMap<>();

    private final Path file;
    private final ZoneId zone;
    private LocalDate day;
    /** Today's gains so far. */
    private HourlyReport.Totals today = HourlyReport.Totals.NONE;
    /** The counters at the last look in this program ({@code NONE} before the first: the program's start from nothing). */
    private HourlyReport.Totals last = HourlyReport.Totals.NONE;
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

    /** The counters now (the world's rewards and the running session's own); saved now and then. */
    public synchronized void update(HourlyReport.Totals now, long nowMillis) {
        LocalDate date = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(nowMillis), zone);
        if (!date.equals(day)) {
            day = date; // a new day: only what comes from here
            today = HourlyReport.Totals.NONE;
        }
        today = plus(today, gain(last, now));
        last = now;
        if (nowMillis - savedAt >= SAVE_MS) {
            savedAt = nowMillis;
            save();
        }
    }

    /** What each counter gained from {@code a} to {@code b}; a counter that went down started anew: all of it. */
    static HourlyReport.Totals gain(HourlyReport.Totals a, HourlyReport.Totals b) {
        // honour may fall for real (the server's negative notices): it starts anew only with the world's other rewards
        boolean worldAnew = b.uridium() < a.uridium() || b.credits() < a.credits() || b.experience() < a.experience();
        return new HourlyReport.Totals(d(a.uridium(), b.uridium()), d(a.credits(), b.credits()),
                d(a.experience(), b.experience()), worldAnew ? b.honor() : b.honor() - a.honor(), (int) d(a.kills(), b.kills()),
                (int) d(a.cubes(), b.cubes()), (int) d(a.repairs(), b.repairs()), (int) d(a.deaths(), b.deaths()),
                ints(a.picked(), b.picked()), doubles(a.items(), b.items()), d(a.spentUridium(), b.spentUridium()),
                d(a.spentCredits(), b.spentCredits()), doubles(a.ammo(), b.ammo()), ints(a.killed(), b.killed()),
                ints(a.gates(), b.gates()));
    }

    private static double d(double before, double now) {
        return now >= before ? now - before : now;
    }

    private static Map<String, Integer> ints(Map<String, Integer> a, Map<String, Integer> b) {
        Map<String, Integer> out = new TreeMap<>();
        b.forEach((k, v) -> {
            int n = (int) d(a.getOrDefault(k, 0), v);
            if (n > 0) {
                out.put(k, n);
            }
        });
        return out;
    }

    private static Map<String, Double> doubles(Map<String, Double> a, Map<String, Double> b) {
        Map<String, Double> out = new TreeMap<>();
        b.forEach((k, v) -> {
            double n = d(a.getOrDefault(k, 0.0), v);
            if (n > 0) {
                out.put(k, n);
            }
        });
        return out;
    }

    /** Two gains summed. */
    static HourlyReport.Totals plus(HourlyReport.Totals a, HourlyReport.Totals b) {
        Map<String, Integer> picked = new TreeMap<>(a.picked());
        b.picked().forEach((k, v) -> picked.merge(k, v, Integer::sum));
        Map<String, Double> items = new TreeMap<>(a.items());
        b.items().forEach((k, v) -> items.merge(k, v, Double::sum));
        Map<String, Double> ammo = new TreeMap<>(a.ammo());
        b.ammo().forEach((k, v) -> ammo.merge(k, v, Double::sum));
        Map<String, Integer> killed = new TreeMap<>(a.killed());
        b.killed().forEach((k, v) -> killed.merge(k, v, Integer::sum));
        Map<String, Integer> gates = new TreeMap<>(a.gates());
        b.gates().forEach((k, v) -> gates.merge(k, v, Integer::sum));
        return new HourlyReport.Totals(a.uridium() + b.uridium(), a.credits() + b.credits(),
                a.experience() + b.experience(), a.honor() + b.honor(), a.kills() + b.kills(), a.cubes() + b.cubes(),
                a.repairs() + b.repairs(), a.deaths() + b.deaths(), picked, items,
                a.spentUridium() + b.spentUridium(), a.spentCredits() + b.spentCredits(), ammo, killed, gates);
    }

    /** Today's totals over every run. */
    public synchronized HourlyReport.Totals today() {
        return today;
    }

    /** Lines for the window's card. */
    public List<String> lines() {
        HourlyReport.Totals t = today();
        List<String> out = new ArrayList<>();
        out.add("Уридий: " + SessionSummary.num(t.uridium()) + "   кредиты: " + SessionSummary.num(t.credits()));
        out.add("Опыт: " + SessionSummary.num(t.experience()) + "   честь: " + SessionSummary.num(t.honor()));
        String spent = HourlyReport.spent(t.spentUridium(), t.spentCredits());
        if (spent != null) {
            out.add("Потрачено: " + spent);
        }
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
            if (root.has("today")) {
                today = totals(root.path("today"));
            } else {
                today = fromRuns(root.path("runs"));
            }
        } catch (IOException e) {
            // started anew
        }
    }

    /**
     * Today from the earlier file of runs (each run its counters at the day's start and at the end). A run started
     * by a new login in the same program began from nothing, while the world's rewards had run on: they are counted
     * from where the program's run before it ended, not from nothing (it counted the stay's rewards twice).
     */
    static HourlyReport.Totals fromRuns(JsonNode runs) {
        HourlyReport.Totals sum = HourlyReport.Totals.NONE;
        Map<String, HourlyReport.Totals> lastOfProgram = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : runs.properties()) {
            String program = e.getKey().contains("-") ? e.getKey().substring(0, e.getKey().indexOf('-')) : e.getKey();
            HourlyReport.Totals from = totals(e.getValue().path("from"));
            HourlyReport.Totals to = totals(e.getValue().path("to"));
            HourlyReport.Totals before = lastOfProgram.get(program);
            if (before != null && isNone(from)) {
                from = new HourlyReport.Totals(before.uridium(), before.credits(), before.experience(), before.honor(),
                        0, 0, 0, 0, Map.of(), before.items(), before.spentUridium(), before.spentCredits(), Map.of(),
                        Map.of(), Map.of());
            }
            sum = plus(sum, gain(from, to));
            lastOfProgram.put(program, to);
        }
        return sum;
    }

    private static boolean isNone(HourlyReport.Totals t) {
        return t.uridium() == 0 && t.credits() == 0 && t.experience() == 0 && t.kills() == 0 && t.items().isEmpty();
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        root.put("day", day.toString());
        root.set("today", node(today));
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
        n.put("spentUridium", t.spentUridium());
        n.put("spentCredits", t.spentCredits());
        ObjectNode picked = n.putObject("picked");
        t.picked().forEach(picked::put);
        ObjectNode items = n.putObject("items");
        t.items().forEach(items::put);
        ObjectNode ammo = n.putObject("ammo");
        t.ammo().forEach(ammo::put);
        ObjectNode killed = n.putObject("killed");
        t.killed().forEach(killed::put);
        ObjectNode gates = n.putObject("gates");
        t.gates().forEach(gates::put);
        return n;
    }

    private static HourlyReport.Totals totals(JsonNode n) {
        Map<String, Integer> picked = new TreeMap<>();
        n.path("picked").properties().forEach(e ->
                picked.merge(orbit.config.LootNames.renamed(e.getKey()), e.getValue().asInt(), Integer::sum));
        Map<String, Double> items = new TreeMap<>();
        n.path("items").properties().forEach(e -> items.put(e.getKey(), e.getValue().asDouble()));
        Map<String, Double> ammo = new TreeMap<>();
        n.path("ammo").properties().forEach(e -> ammo.put(e.getKey(), e.getValue().asDouble()));
        Map<String, Integer> killed = new TreeMap<>();
        n.path("killed").properties().forEach(e -> killed.put(e.getKey(), e.getValue().asInt()));
        Map<String, Integer> gates = new TreeMap<>();
        n.path("gates").properties().forEach(e -> gates.put(e.getKey(), e.getValue().asInt()));
        return new HourlyReport.Totals(n.path("uridium").asDouble(), n.path("credits").asDouble(),
                n.path("experience").asDouble(), n.path("honor").asDouble(), n.path("kills").asInt(),
                n.path("cubes").asInt(), n.path("repairs").asInt(), n.path("deaths").asInt(), picked, items,
                n.path("spentUridium").asDouble(), n.path("spentCredits").asDouble(), ammo, killed, gates);
    }
}
