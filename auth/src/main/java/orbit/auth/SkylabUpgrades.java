package orbit.auth;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Skylab's upgrades as the page shows and starts them (docs/SKYLAB-LEVELING.md, recon 2026-11-25 on int1): each module's
 * level ({@code module_infobox_<module>_footer … level_icon}), the next level's price in its upgrade box (rows URI, credits,
 * time, the three raw ores; the «urgent» column with uridium and the «standard» one), an upgrade running
 * ({@code progress_timer_<module>}, as the transport's), the page's token ({@code var rid}); the robots of the collectors
 * («актив. N /12»). An upgrade is the page's own link {@code subaction=upgrade|instantUpgrade&construction=<module>}; a robot
 * — {@code POST ajax/skylab.php command=buySkylabRobot type=1} (250 credits; type 2 — 50 uridium).
 */
public final class SkylabUpgrades {

    public static final List<String> MODULES = List.of("baseModule", "solarModule", "storageModule", "prometiumCollector",
            "enduriumCollector", "terbiumCollector", "xenoModule", "prometidRefinery", "duraniumRefinery", "promeriumRefinery",
            "sepromRefinery");
    public static final List<String> COLLECTORS = List.of("prometiumCollector", "enduriumCollector", "terbiumCollector");

    /** The next level's price: standard (credits, ores, minutes) and the urgent one's extra uridium. */
    public record Price(long credits, long prometium, long endurium, long terbium, int minutes, long instantUridium) {
    }

    /**
     * The page as the upgrades need it.
     *
     * @param levels  module → level
     * @param prices  module → the next level's price (none: at the top or no upgrade box)
     * @param running modules with an upgrade going on
     * @param robots  collector → active robots
     * @param token   the page's reload token
     */
    public record Page(Map<String, Integer> levels, Map<String, Price> prices, java.util.Set<String> running,
                       Map<String, Integer> robots, String token) {
    }

    private final HttpSession http;
    private final String origin;

    public SkylabUpgrades(HttpSession http, String origin) {
        this.http = http;
        this.origin = origin;
    }

    static final String PAGE = "/indexInternal.es?action=internalSkylab";

    public Page read() throws IOException {
        HttpSession.Response r = http.get(origin + PAGE, origin + "/indexInternal.es?action=internalStart");
        if (r.status() != 200) {
            throw new IOException("Skylab: HTTP " + r.status());
        }
        Page p = parse(r.body());
        if (p.levels().isEmpty()) {
            throw new IOException("Skylab: на странице нет модулей");
        }
        return p;
    }

    /** Starts the next level of {@code module} as the page's link does; the page after it, read. */
    public Page upgrade(String module, boolean instant, String token) throws IOException {
        String url = origin + PAGE + "&subaction=" + (instant ? "instantUpgrade" : "upgrade") + "&construction=" + module
                + "&reloadToken=" + token;
        HttpSession.Response r = http.get(url, origin + PAGE);
        if (r.status() != 200) {
            throw new IOException("Skylab: апгрейд — HTTP " + r.status());
        }
        return parse(r.body());
    }

    /** Buys one robot for a collector for credits ({@code type=1}); the site's answer (JSON, {@code result: OK}). */
    public String buyRobot(String collector) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("command", "buySkylabRobot");
        form.put("type", "1");
        form.put("construction", collector);
        HttpSession.Response r = http.postForm(origin + "/ajax/skylab.php", form, origin + PAGE);
        String body = r.body() == null ? "" : r.body().strip();
        return orbit.secrets.SecretMask.mask(body.length() > 300 ? body.substring(0, 300) : body);
    }

    private static final Pattern ROW = Pattern.compile(
            "<td class=\"firstRow\">([^<]*)</td>\\s*<td class=\"secondRow\">([^<]*)</td>\\s*<td>.*?</td>\\s*"
                    + "<td class=\"thirdRow[^\"]*\">([^<]*)</td>", Pattern.DOTALL);

    public static Page parse(String html) {
        String s = html == null ? "" : html;
        Map<String, Integer> levels = new TreeMap<>();
        Map<String, Price> prices = new TreeMap<>();
        java.util.Set<String> running = new java.util.TreeSet<>();
        Map<String, Integer> robots = new TreeMap<>();
        for (String m : MODULES) {
            Matcher l = Pattern.compile("module_infobox_" + m + "_footer.*?level_icon\">\\s*(\\d+)", Pattern.DOTALL).matcher(s);
            if (l.find()) {
                levels.put(m, Integer.parseInt(l.group(1)));
            }
            if (s.contains("progress_timer_" + m)) {
                running.add(m);
            }
            int at = s.indexOf("module_infobox_" + m + "_upgrade_large");
            if (at >= 0) {
                String box = s.substring(at, Math.min(s.length(), at + 5_000));
                int end = box.indexOf("_resourceWarning_large");
                if (end > 0) {
                    box = box.substring(0, end);
                }
                Map<String, String[]> rows = new LinkedHashMap<>();
                Matcher r = ROW.matcher(box);
                while (r.find()) {
                    rows.put(r.group(1).strip(), new String[] {r.group(2).strip(), r.group(3).strip()});
                }
                if (rows.containsKey("кредитов") || rows.containsKey("credits")) {
                    String[] cr = rows.getOrDefault("кредитов", rows.get("credits"));
                    String[] uri = rows.getOrDefault("URI", new String[] {"0", "0"});
                    String[] time = rows.getOrDefault("Время", rows.getOrDefault("Time", new String[] {"0", "0:00"}));
                    prices.put(m, new Price(num(cr[1]), num(ore(rows, "Прометиум", "Prometium")),
                            num(ore(rows, "Эндуриум", "Endurium")), num(ore(rows, "Тербиум", "Terbium")), minutes(time[1]),
                            num(uri[0])));
                }
            }
        }
        for (String c : COLLECTORS) {
            Matcher a = Pattern.compile(c + "_skylabActiveRobots\"[^>]*>\\s*(\\d+)").matcher(s);
            if (a.find()) {
                robots.put(c, Integer.parseInt(a.group(1)));
            }
        }
        Matcher t = Pattern.compile("var\\s+rid\\s*=\\s*'([0-9a-fA-F]+)'").matcher(s);
        return new Page(levels, prices, running, robots, t.find() ? t.group(1) : "");
    }

    private static String ore(Map<String, String[]> rows, String ru, String en) {
        String[] v = rows.getOrDefault(ru, rows.get(en));
        return v == null ? "0" : v[1];
    }

    /** {@code 16,352} or {@code 16.352} → 16352. */
    static long num(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9]", "");
        return d.isEmpty() ? 0 : Long.parseLong(d);
    }

    /** {@code 2:18} → 138 minutes; {@code 1:0} → 60. */
    static int minutes(String s) {
        String[] p = (s == null ? "" : s.strip()).split(":");
        try {
            return p.length == 2 ? Integer.parseInt(p[0].strip()) * 60 + Integer.parseInt(p[1].strip()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
