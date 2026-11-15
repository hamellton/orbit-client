package orbit.auth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The account's Skylab as the website shows it ({@code indexInternal.es?action=internalSkylab}) — read only. The page's
 * marks are those DarkBot's plugins read (KekkaReloaded's SkylabBuilder, DmPlugin's SkylabSender), checked against a
 * saved page of a gb2 account on 2026-11-15; the ore stock is read from {@code view_<ore>_stock}, the plugins' pattern
 * cut the numbers short there.
 */
public final class Skylab {

    /** A module: its page name ({@code baseModule}, {@code sepromRefinery}…), level, whether it runs. */
    public record Module(String name, int level, boolean active) {
    }

    /** An ore on the Skylab: how much lies there and how much the storage holds. */
    public record Ore(String name, long amount, long capacity) {
    }

    /**
     * @param transporting the transport module is on its way (no new transport till it is back)
     * @param robots       the collectors' robots waiting to be placed, by module
     */
    public record Info(List<Module> modules, List<Ore> ores, boolean transporting, Map<String, Integer> robots,
                       long readMillis) {

        public Module module(String name) {
            return modules.stream().filter(m -> m.name().equals(name)).findFirst().orElse(null);
        }

        public Ore ore(String name) {
            return ores.stream().filter(o -> o.name().equals(name)).findFirst().orElse(null);
        }
    }

    /** The modules' order on the page, for the display. */
    public static final List<String> MODULES = List.of("baseModule", "solarModule", "storageModule", "transportModule",
            "xenoModule", "prometiumCollector", "enduriumCollector", "terbiumCollector", "prometidRefinery",
            "duraniumRefinery", "promeriumRefinery", "sepromRefinery");

    private static final Pattern LEVEL = Pattern.compile(
            "Skylab\\.showModule\\('(\\w+)'\\);\"\">[\\s\\S]+?level skylab_font_level(_inactive)?\">(\\d+)");
    private static final Pattern STOCK = Pattern.compile(
            "id=\"view_(\\w+)_stock\">\\s*<span[^>]*>[^<]*</span><br\\s*/?>\\s*([\\d,.\\s]+?)\\s*</div>");
    private static final String MAXIMAL = "id=\"view_%s_maximal\">[\\s\\S]*?maximal_number[^\"]*\">\\s*([\\d,.\\s]+?)\\s*<";
    private static final Pattern ROBOTS = Pattern.compile("(\\w+)_skylabPendingRobots.>(\\d+)<");

    private final HttpSession http;
    private final String origin;

    public Skylab(HttpSession http, String origin) {
        this.http = http;
        this.origin = origin;
    }

    /** One GET of the page, parsed. */
    public Info read() throws IOException {
        HttpSession.Response r = http.get(origin + "/indexInternal.es?action=internalSkylab",
                origin + "/indexInternal.es?action=internalStart");
        if (r.status() != 200) {
            throw new IOException("Skylab: HTTP " + r.status());
        }
        Info info = parse(r.body(), System.currentTimeMillis());
        if (info.modules().isEmpty() && info.ores().isEmpty()) {
            throw new IOException("Skylab: на странице нет модулей (не та страница или сайт поменял разметку)");
        }
        return info;
    }

    /** The page's modules, ores, transport and robots; whatever is missing is left out. */
    public static Info parse(String html, long readMillis) {
        Map<String, Module> modules = new LinkedHashMap<>();
        Matcher m = LEVEL.matcher(html);
        while (m.find()) {
            modules.putIfAbsent(m.group(1), new Module(m.group(1), Integer.parseInt(m.group(3)), m.group(2) == null));
        }
        List<Module> ordered = new ArrayList<>();
        for (String name : MODULES) {
            if (modules.containsKey(name)) {
                ordered.add(modules.remove(name));
            }
        }
        ordered.addAll(modules.values());
        List<Ore> ores = new ArrayList<>();
        Matcher s = STOCK.matcher(html);
        while (s.find()) {
            String ore = s.group(1);
            Matcher max = Pattern.compile(String.format(MAXIMAL, Pattern.quote(ore))).matcher(html);
            ores.add(new Ore(ore, number(s.group(2)), max.find() ? number(max.group(1)) : -1));
        }
        Map<String, Integer> robots = new LinkedHashMap<>();
        Matcher r = ROBOTS.matcher(html);
        while (r.find()) {
            robots.put(r.group(1), Integer.parseInt(r.group(2)));
        }
        return new Info(List.copyOf(ordered), List.copyOf(ores), html.contains("progress_timer_transportModule"),
                Map.copyOf(robots), readMillis);
    }

    /** {@code 16,732,917} → 16732917 (the site's thousands' separators, whatever they are). */
    static long number(String s) {
        String digits = s.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? -1 : Long.parseLong(digits);
    }
}
