package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.bot.run.CargoPlan;
import orbit.bot.run.QuestGiverVisit;
import orbit.config.BotConfig;
import orbit.world.QuestTexts;
import orbit.world.Quests;
import orbit.world.WorldSnapshot;
import orbit.world.maps.NpcMaps;
import orbit.world.maps.OreMaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QUESTS («Квесты», plan 5б.9): takes quests at the home base's quest giver ({@link QuestGiverVisit}) and does them
 * one condition at a time with the bot's own parts:
 * <ul>
 *   <li>kill or damage NPCs — the kill module on the nearest map where those NPCs were seen, only them on its list
 *       (the config's other settings stay: ammunition, safety, boxes);</li>
 *   <li>bonus boxes — the collector on the config's working map (or where the ship is), only {@code BONUS_BOX};</li>
 *   <li>ore — the collector on the nearest map where that ore was seen ({@link OreMaps}), only that ore;</li>
 *   <li>sell ore — the sale ({@code CargoCare}, the config's way: base or pet) of that ore even with room in the hold;
 *       none aboard — it is collected first;</li>
 *   <li>a map or a point — a flight there.</li>
 * </ul>
 * Daily quests first, then weekly, event, challenge, missions. A quest done — back to the giver for the next one (a
 * chain); quests the bot cannot do are not taken, and a taken one it cannot do waits.
 *
 * <p>Settings ({@code modules.quests}, the «Квесты» tab): {@code types} — quest types to take (default DAILY, WEEKLY,
 * EVENT, MISSION — the user's choice 2026-11-12); {@code do} — condition kinds to do (KILL, DAMAGE, BOX, ORE, SELL,
 * MAP; all by default); {@code revisitMinutes} — how often to look at the giver again (360).
 */
public final class QuestModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(QuestModule.class);

    static final long DEFAULT_REVISIT_MIN = 360;
    /** Type order of the work: what resets soonest first. */
    static final List<Integer> ORDER = List.of(Quests.DAILY, Quests.WEEKLY, Quests.EVENT, Quests.CHALLENGE, Quests.MISSION);
    static final String BONUS_BOX = "BONUS_BOX";

    enum Kind { KILL, DAMAGE, BOX, ORE, SELL, MAP }

    private final BotContext ctx;
    private final NpcMaps npcMaps;
    private final OreMaps oreMaps;
    private QuestGiverVisit visit;
    private long visitedAt = Long.MIN_VALUE / 2;
    private boolean visitedOnce;
    private int activeSeen = -1;
    private Work work;
    private KillModule worker;
    private String status = "Квесты";
    private String told = "";

    /**
     * One condition being done: {@code names} are the NPCs to kill or the collectables to pick ({@code BONUS_BOX},
     * {@code ore_5}); {@code ore} the ore type of a sale (-2 any).
     */
    record Work(int quest, int condition, Kind kind, int map, Set<String> names, double x, double y, int ore) {
    }

    public QuestModule(BotContext ctx) {
        this(ctx, NpcMaps.shared(), OreMaps.shared());
    }

    public QuestModule(BotContext ctx, NpcMaps npcMaps, OreMaps oreMaps) {
        this.ctx = ctx;
        this.npcMaps = npcMaps;
        this.oreMaps = oreMaps;
    }

    /** Tests: as if the giver was just visited. */
    public void visited(long at) {
        visitedOnce = true;
        visitedAt = at;
    }

    @Override
    public String status() {
        return visit != null ? visit.status() : status;
    }

    @Override
    public int workingMap() {
        return visit != null || work == null ? 0 : work.map();
    }

    @Override
    public boolean busy() {
        return worker != null && worker.busy();
    }

    @Override
    public void pause() {
        if (worker != null) {
            worker.pause();
        }
    }

    /** A sale condition with that ore aboard: sell it now, room in the hold or not. */
    @Override
    public CargoPlan cargoPlan(BotConfig c) {
        if (visit != null || work == null || work.kind() != Kind.SELL || !aboard(ctx.world().get(), work.ore())) {
            return null;
        }
        String mode = BotConfig.Collect.CARGO_PET.equals(c.collect().cargo()) ? BotConfig.Collect.CARGO_PET
                : BotConfig.Collect.CARGO_BASE;
        List<Integer> ores = work.ore() >= 0 ? List.of(work.ore())
                : c.collect().sellOres() != null && !c.collect().sellOres().isEmpty() ? c.collect().sellOres()
                : List.of(0, 1, 2, 4, 5);
        return new CargoPlan(mode, 0, ores, true);
    }

    @Override
    public void tick() {
        WorldSnapshot w = ctx.world().get();
        Quests q = w.quests();
        long now = ctx.now();
        if (visit != null) {
            if (visit.tick()) {
                return;
            }
            visit = null;
            visitedAt = now;
        }
        Work next = choose(w);
        boolean finishedOne = activeSeen >= 0 && q.active().size() < activeSeen;
        activeSeen = q.active().size();
        long revisit = (long) setting("revisitMinutes", DEFAULT_REVISIT_MIN) * 60_000_000_000L;
        if ((!visitedOnce || finishedOne || now - visitedAt > revisit || next == null && now - visitedAt > revisit / 6)
                && !busy()) {
            visitedOnce = true;
            startVisit(finishedOne ? "квест сдан — за следующим" : next == null ? "нет квестов, которые бот умеет"
                    : "пора посмотреть новые квесты");
            return;
        }
        if (next == null) {
            work = null;
            worker = null;
            status = "Квесты: нечего делать — жду";
            tell("Квесты: среди взятых нет тех, что бот умеет — снова к квестодателю через "
                    + revisit / 6 / 60_000_000_000L + " мин");
            return;
        }
        if (!next.equals(work)) {
            if (work == null || work.quest() != next.quest() || work.condition() != next.condition()
                    || work.kind() != next.kind() || work.map() != next.map()) {
                Quests.Condition c = condition(q.active().get(next.quest()), next.condition());
                log.info("Квест {}: {} {}/{} — {} на {}", next.quest(), QuestTexts.condition(c), (long) c.current(),
                        (long) c.target(), what(next.kind()), ctx.maps().info(next.map()).name());
            }
            if (worker != null) {
                worker.pause();
            }
            worker = null;
            work = next;
        }
        Quests.Condition c = condition(q.active().get(work.quest()), work.condition());
        String progress = QuestTexts.condition(c) + (c.target() > 0 ? " " + (long) c.current() + "/" + (long) c.target() : "");
        switch (work.kind()) {
            case KILL, DAMAGE, BOX, ORE -> {
                if (worker == null) {
                    boolean kill = work.kind() == Kind.KILL || work.kind() == Kind.DAMAGE;
                    worker = new KillModule(questContext(work), kill, true);
                }
                worker.tick();
                status = "Квест " + work.quest() + ": " + progress + (worker.status().isEmpty() ? "" : " · " + worker.status());
            }
            case SELL -> {
                // the runner's cargo care sells (cargoPlan); here the ship waits by the work
                ctx.nav().tick();
                status = "Квест " + work.quest() + ": " + progress + " · продаю";
            }
            case MAP -> {
                if (!Double.isNaN(work.x()) && ctx.nav().idle()
                        && Math.hypot(w.hero().x() - work.x(), w.hero().y() - work.y()) > 150) {
                    ctx.nav().moveTo(work.x(), work.y(), false);
                }
                ctx.nav().tick();
                status = "Квест " + work.quest() + ": " + progress;
            }
            default -> {
            }
        }
    }

    private static String what(Kind k) {
        return switch (k) {
            case KILL, DAMAGE -> "бью";
            case BOX, ORE -> "собираю";
            case SELL -> "продаю";
            case MAP -> "лечу";
        };
    }

    private void startVisit(String why) {
        log.info("Квесты: к квестодателю — {}", why);
        if (worker != null) {
            worker.pause();
        }
        worker = null;
        work = null;
        visit = new QuestGiverVisit(ctx, types(), this::doable);
        told = "";
    }

    /** The next condition to work on: by type order, then the quest's id; the first open condition the bot can do. */
    Work choose(WorldSnapshot w) {
        List<Quests.Quest> quests = w.quests().active().values().stream()
                .sorted(Comparator.comparingInt(QuestModule::rank).thenComparingInt(Quests.Quest::id)).toList();
        for (Quests.Quest quest : quests) {
            for (Quests.Condition c : quest.leaves()) {
                if (c.completed() || !c.mandatory() || QuestTargets.passive(c)) {
                    continue;
                }
                Work wk = work(quest, c, w);
                if (wk != null) {
                    return wk;
                }
                break; // the quest's open condition the bot cannot do: the quest waits
            }
        }
        return null;
    }

    private Work work(Quests.Quest quest, Quests.Condition c, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        Set<String> doing = kinds();
        if (QuestTargets.killing(c) && doing.contains("KILL") || QuestTargets.damaging(c) && doing.contains("DAMAGE")) {
            Map<Integer, Set<String>> where = QuestTargets.where(c, npcMaps);
            Integer best = nearest(where.keySet(), h);
            Kind k = QuestTargets.killing(c) ? Kind.KILL : Kind.DAMAGE;
            return best == null ? null : new Work(quest.id(), c.id(), k, best, Set.copyOf(where.get(best)), Double.NaN,
                    Double.NaN, -1);
        }
        if (c.type() == Quests.COLLECT_BONUS_BOX && doing.contains("BOX")) {
            int map = ctx.config().get().general().workingMap();
            return new Work(quest.id(), c.id(), Kind.BOX, map > 0 ? map : h.mapId(), Set.of(BONUS_BOX), Double.NaN,
                    Double.NaN, -1);
        }
        if (c.type() == Quests.COLLECT && doing.contains("ORE")) {
            return oreWork(quest, c, QuestTargets.oreType(c.match(0)), w);
        }
        if (c.type() == Quests.SELL_ORE && doing.contains("SELL")) {
            int ore = QuestTargets.oreType(c.match(0));
            if (ore == -1) {
                return null;
            }
            if (aboard(w, ore)) {
                return new Work(quest.id(), c.id(), Kind.SELL, 0, Set.of(), Double.NaN, Double.NaN, ore);
            }
            // nothing of it aboard: collect it first (any ore: the cheapest common one, prometium)
            return doing.contains("ORE") ? oreWork(quest, c, ore >= 0 ? ore : 0, w) : null;
        }
        if (QuestTargets.travelling(c) && doing.contains("MAP")) {
            Integer best = nearest(QuestTargets.mapsOf(c), h);
            if (best == null) {
                return null;
            }
            boolean point = c.type() == Quests.COORDINATES;
            return new Work(quest.id(), c.id(), Kind.MAP, best, Set.of(), point ? c.match(0) : Double.NaN,
                    point ? c.match(1) : Double.NaN, -1);
        }
        return null;
    }

    /** Collect an ore: where it lies in sight, else the nearest map where it was seen. */
    private Work oreWork(Quests.Quest quest, Quests.Condition c, int ore, WorldSnapshot w) {
        if (ore < 0) {
            return null;
        }
        boolean here = w.collectables().values().stream()
                .anyMatch(b -> b.kind() == WorldSnapshot.CollectableKind.ORE && b.oreType() == ore);
        Set<Integer> maps = new TreeSet<>(oreMaps.mapsWith(ore));
        if (here) {
            maps.add(w.hero().mapId());
        }
        Integer best = nearest(maps, w.hero());
        return best == null ? null : new Work(quest.id(), c.id(), Kind.ORE, best, Set.of("ore_" + ore), Double.NaN,
                Double.NaN, ore);
    }

    private static boolean aboard(WorldSnapshot w, int ore) {
        Map<Integer, Double> ores = w.inventory().ores();
        if (ore >= 0) {
            return ores.getOrDefault(ore, 0.0) >= 1;
        }
        return ores.entrySet().stream().anyMatch(e -> e.getKey() != WorldSnapshot.Inventory.XENOMIT && e.getValue() >= 1);
    }

    /** The map fewest jumps away (the one the ship is on first); {@code null} when none can be reached. */
    private Integer nearest(java.util.Collection<Integer> maps, WorldSnapshot.Hero h) {
        Integer best = null;
        int bestHops = Integer.MAX_VALUE;
        for (int m : maps) {
            int hops;
            if (m == h.mapId()) {
                hops = 0;
            } else {
                List<?> route = orbit.bot.nav.Router.route(ctx.maps(), h.mapId(), h.x(), h.y(), m, h.factionId(),
                        h.level() > 0 ? h.level() : Integer.MAX_VALUE);
                if (route.isEmpty()) {
                    continue;
                }
                hops = route.size();
            }
            if (hops < bestHops) {
                best = m;
                bestHops = hops;
            }
        }
        return best;
    }

    /** A quest the bot can do: each of its open required conditions is one it does, or a limit it keeps. */
    public boolean doable(Quests.Quest quest) {
        WorldSnapshot w = ctx.world().get();
        for (Quests.Condition c : quest.leaves()) {
            if (c.completed() || !c.mandatory() || QuestTargets.passive(c)) {
                continue;
            }
            if (work(quest, c, w) == null) {
                return false;
            }
        }
        return !quest.leaves().isEmpty();
    }

    /** The config for the work: only its NPCs or collectables listed, the working map the work's map. */
    private BotContext questContext(Work wk) {
        return new BotContext(ctx.world(), () -> questConfig(ctx.config().get(), wk), ctx.actions(), ctx.nav(),
                ctx.traveler(), ctx.maps(), ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(),
                ctx.ships());
    }

    static BotConfig questConfig(BotConfig c, Work wk) {
        BotConfig out = c;
        if (wk.kind() == Kind.KILL || wk.kind() == Kind.DAMAGE) {
            Map<String, BotConfig.NpcRule> npcs = new LinkedHashMap<>();
            for (String n : wk.names()) {
                BotConfig.NpcRule own = c.npc(n);
                npcs.put(n, own != null ? new BotConfig.NpcRule(own.radius(), own.priority(), true, own.flags(), Set.of(),
                        own.ammo(), own.sab(), own.rocket(), own.launcher())
                        : new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null));
            }
            out = out.withNpcs(npcs);
        } else if (wk.kind() == Kind.BOX || wk.kind() == Kind.ORE) {
            BotConfig.Collect k = c.collect();
            Map<String, BotConfig.BoxRule> boxes = new LinkedHashMap<>();
            for (String n : wk.names()) {
                BotConfig.BoxRule own = k.boxes().get(n);
                boxes.put(n, new BotConfig.BoxRule(true, own != null ? own.waitMs() : n.startsWith("ore_") ? 800 : 0,
                        own != null ? own.priority() : 0));
            }
            out = out.withNpcs(Map.of()).withCollect(new BotConfig.Collect(boxes, k.radius(), k.stayAwayFromEnemies(),
                    k.ignoreContested(), k.cargo(), k.cargoFullAt(), k.sellOres()));
        }
        BotConfig.General g = c.general();
        return out.withGeneral(new BotConfig.General(wk.map(), g.offensive(), g.roam(), g.run(),
                g.runFromEnemies(), g.runFromEnemiesInSight(), g.rememberEnemiesSec(), g.roamSequential(), false,
                g.runModeTravel(), g.runModeChase(), g.avoidMines(), g.baseRadius()));
    }

    private static Quests.Condition condition(Quests.Quest quest, int id) {
        return quest.all().stream().filter(c -> c.id() == id).findFirst().orElseThrow();
    }

    private static int rank(Quests.Quest q) {
        for (int i = 0; i < ORDER.size(); i++) {
            if (q.is(ORDER.get(i))) {
                return i;
            }
        }
        return ORDER.size();
    }

    /** The quest types to take ({@code types}); the user's four by default. */
    Set<Integer> types() {
        Set<Integer> out = new TreeSet<>();
        for (String t : strings("types", List.of("DAILY", "WEEKLY", "EVENT", "MISSION"))) {
            switch (t.toUpperCase(java.util.Locale.ROOT)) {
                case "MISSION" -> out.add(Quests.MISSION);
                case "DAILY" -> out.add(Quests.DAILY);
                case "WEEKLY" -> out.add(Quests.WEEKLY);
                case "EVENT" -> out.add(Quests.EVENT);
                case "CHALLENGE" -> out.add(Quests.CHALLENGE);
                default -> {
                }
            }
        }
        return out;
    }

    /** The condition kinds to do ({@code do}); all by default. */
    Set<String> kinds() {
        Set<String> out = new TreeSet<>();
        for (String k : strings("do", List.of("KILL", "DAMAGE", "BOX", "ORE", "SELL", "MAP"))) {
            out.add(k.toUpperCase(java.util.Locale.ROOT));
        }
        return out;
    }

    private List<String> strings(String key, List<String> def) {
        JsonNode t = settings().path(key);
        if (!t.isArray()) {
            return def;
        }
        List<String> out = new java.util.ArrayList<>();
        t.forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode settings() {
        JsonNode s = ctx.config().get().modules().get(BotConfig.Module.QUESTS.key());
        return s == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : s;
    }

    private double setting(String key, double def) {
        JsonNode v = settings().path(key);
        return v.isNumber() ? v.asDouble() : def;
    }

    private void tell(String text) {
        if (!text.equals(told)) {
            told = text;
            log.info(text);
        }
    }
}
