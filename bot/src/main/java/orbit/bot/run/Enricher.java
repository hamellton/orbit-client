package orbit.bot.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The lab's enrichment kept up from the hold (lasers, rockets, drives and shields enriched by the bot, set in the
 * config): an item below its threshold gets ore from the hold ({@code LabUpdateItemRequest}, the client's
 * own request) — the ore it has now while some is left (no switching of a running enrichment), else the config's.
 *
 * <p>Settings {@code modules.enrich}: {@code enabled}, {@code items.<laser|rockets|drive|shield>} =
 * {@code {on, ore, below, load}} — {@code below} in the lab's units (the card's numbers, 10 an ore unit), {@code load}
 * in ore units a time — {@code keepInCargo} (ore units left in the hold), {@code maxPerHour}.
 */
public final class Enricher {

    private static final Logger log = LoggerFactory.getLogger(Enricher.class);

    /** The config's keys of the lab items, by their number. */
    public static final String[] ITEMS = {"laser", "rockets", "drive", "shield"};
    static final String[] ITEM_NAMES = {"лазеры", "ракеты", "двигатели", "щиты"};

    /** The ores the lab takes, the config's names → {@code OreTypeModule} numbers. */
    public static final Map<String, Integer> ORES = Map.of("PROMETID", 4, "DURANIUM", 5, "PROMERIUM", 6, "SEPROM", 7);

    /** After a request: the next only after the lab's answer, or this long. */
    static final long ANSWER_MS = 10_000;
    /** The lab asked again when its state is this old (the app asks it every 5 min). */
    static final long STALE_MS = 6 * 60_000;

    private final BotContext ctx;
    private final Deque<Long> sent = new ArrayDeque<>();
    private long sentAt = Long.MIN_VALUE / 2;
    private long askedAt = Long.MIN_VALUE / 2;
    private final Map<Integer, Long> missing = new LinkedHashMap<>();
    /**
     * What was said and not changed since: every message once (live 2026-11-25: two items short of ore took turns and each
     * line was said 600 times a minute); cleared when a request is sent.
     */
    private final java.util.Set<String> told = new java.util.HashSet<>();

    public Enricher(BotContext ctx) {
        this.ctx = ctx;
    }

    private JsonNode settings() {
        JsonNode s = ctx.config().get().modules().get("enrich");
        return s == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : s;
    }

    /**
     * An item the enrichment settings {@code e} turn on is below its threshold in the lab ({@code lab} {@code null} or not
     * read yet: below). The cargo's keeping and picking of ore for promerium go by it (the leveling configs).
     */
    public static boolean low(JsonNode e, WorldSnapshot.Lab lab) {
        if (lab == null || lab.items().isEmpty() && lab.nanos() == 0) {
            return true;
        }
        for (int item = 0; item < ITEMS.length; item++) {
            JsonNode it = e.path("items").path(ITEMS[item]);
            if (!it.path("on").asBoolean(false)) {
                continue;
            }
            WorldSnapshot.Enrichment en = lab.items().get(item);
            if ((en == null ? 0 : en.left()) < it.path("below").asLong(50_000)) {
                return true;
            }
        }
        return false;
    }

    public boolean enabled() {
        return settings().path("enabled").asBoolean(false);
    }

    /**
     * The ores the enrichment wants and the hold lacks (ore number → ore units), for the Skylab's transport; empty when
     * nothing is wanted.
     */
    public Map<Integer, Long> missing() {
        return Map.copyOf(missing);
    }

    /** One step: at most one request, then the lab's answer is awaited. */
    public void tick() {
        missing.clear();
        JsonNode s = settings();
        if (!s.path("enabled").asBoolean(false)) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        if (w.hero().id() == 0 || !w.hero().alive()) {
            return;
        }
        long now = ctx.now();
        WorldSnapshot.Lab lab = w.inventory().lab();
        if (lab.items().isEmpty() && lab.nanos() == 0 || ms(now - lab.nanos()) > STALE_MS) {
            if (ms(now - askedAt) > 30_000) {
                askedAt = now;
                ctx.actions().labInfo();
            }
            return;
        }
        if (lab.nanos() <= sentAt && ms(now - sentAt) < ANSWER_MS) {
            return; // the last request not answered yet
        }
        while (!sent.isEmpty() && ms(now - sent.peekFirst()) > 3_600_000) {
            sent.removeFirst();
        }
        int perHour = s.path("maxPerHour").asInt(30);
        long keep = Math.max(0, s.path("keepInCargo").asLong(0));
        for (int item : order(s)) {
            JsonNode it = s.path("items").path(ITEMS[item]);
            if (!it.path("on").asBoolean(false)) {
                continue;
            }
            WorldSnapshot.Enrichment e = lab.items().get(item);
            long left = e == null ? 0 : e.left();
            long below = it.path("below").asLong(50_000);
            if (left >= below) {
                continue;
            }
            Integer wanted = ORES.get(it.path("ore").asText("PROMERIUM").toUpperCase(java.util.Locale.ROOT));
            // a running enrichment is topped up with its own ore — another one is not mixed in
            int ore = e != null && e.left() > 0 ? e.ore() : wanted != null ? wanted : 6;
            if (it.has("ores")) {
                // a list of ores, best first (the leveling configs' lasers: promerium the drives do not need, else
                // prometid — a user's choice, 2026-11-25)
                int pick = choose(it, s, lab, w, keep, e);
                if (pick == -2) {
                    continue; // the running ore is left to run out: a better one waits in the hold
                }
                if (pick >= 0) {
                    ore = pick;
                }
            }
            long load = Math.max(1, it.path("load").asLong(10_000));
            long have = w.inventory().ore(ore) - keep;
            if (have <= 0) {
                missing.merge(ore, load, Long::sum);
                tellOnce("Обогащение: " + ITEM_NAMES[item] + " — " + orbit.world.Num.group(left) + ", а " + oreName(ore) + " в трюме нет");
                continue;
            }
            if (sent.size() >= perHour) {
                tellOnce("Обогащение: за час уже " + sent.size() + " раз — жду (настройка «не чаще»)");
                return;
            }
            long amount = Math.min(load, have);
            ctx.actions().labEnrich(item, ore, amount);
            sentAt = now;
            sent.addLast(now);
            told.clear();
            log.info("Обогащение: {} ← {} {} (было {})", ITEM_NAMES[item], oreName(ore), amount, left);
            return; // one at a time: the lab's answer first
        }
    }

    /** The items in the settings' {@code order} (keys, e.g. drive first), else laser, rockets, drive, shield. */
    static int[] order(JsonNode s) {
        if (!s.has("order")) {
            return new int[] {0, 1, 2, 3};
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        s.path("order").forEach(k -> {
            int i = java.util.Arrays.asList(ITEMS).indexOf(k.asText());
            if (i >= 0 && !out.contains(i)) {
                out.add(i);
            }
        });
        for (int i = 0; i < ITEMS.length; i++) {
            if (!out.contains(i)) {
                out.add(i);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The ore of an item with an {@code ores} list (best first): the first one aboard, promerium skipped while the drives
     * need it ({@code promeriumAfterDrive}). A running enrichment is not mixed: its own ore while it is the best one aboard;
     * a better one aboard — {@code -2}, the running one is not topped up and runs out first. {@code -1}: none aboard (the
     * running or the first ore is reported as missing).
     */
    static int choose(JsonNode it, JsonNode s, WorldSnapshot.Lab lab, WorldSnapshot w, long keep, WorldSnapshot.Enrichment e) {
        boolean driveFirst = it.path("promeriumAfterDrive").asBoolean(false) && driveLow(s, lab);
        Integer best = null;
        for (JsonNode n : it.path("ores")) {
            Integer o = ORES.get(n.asText().toUpperCase(java.util.Locale.ROOT));
            if (o == null || o == 6 && driveFirst) {
                continue;
            }
            if (w.inventory().ore(o) - keep > 0) {
                best = o;
                break;
            }
        }
        if (best == null) {
            return -1;
        }
        if (e != null && e.left() > 0 && e.ore() != best) {
            int runRank = rank(it, e.ore());
            int bestRank = rank(it, best);
            if (bestRank < runRank) {
                return -2; // better ore aboard: let the running one run out
            }
            return w.inventory().ore(e.ore()) - keep > 0 ? e.ore() : -2;
        }
        return best;
    }

    private static int rank(JsonNode it, int ore) {
        int i = 0;
        for (JsonNode n : it.path("ores")) {
            if (ORES.get(n.asText().toUpperCase(java.util.Locale.ROOT)) == ore) {
                return i;
            }
            i++;
        }
        return Integer.MAX_VALUE;
    }

    /** The drives are on and below their threshold. */
    static boolean driveLow(JsonNode s, WorldSnapshot.Lab lab) {
        JsonNode d = s.path("items").path("drive");
        if (!d.path("on").asBoolean(false)) {
            return false;
        }
        WorldSnapshot.Enrichment en = lab.items().get(2);
        return (en == null ? 0 : en.left()) < d.path("below").asLong(50_000);
    }

    private void tellOnce(String s) {
        if (told.add(s)) {
            log.info(s);
        }
    }

    static String oreName(int ore) {
        return switch (ore) {
            case 4 -> "Prometid";
            case 5 -> "Duranium";
            case 6 -> "Promerium";
            case 7 -> "Seprom";
            default -> "руда №" + ore;
        };
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
