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
import orbit.bot.run.QuestGiverVisit;
import orbit.config.BotConfig;
import orbit.world.QuestTexts;
import orbit.world.Quests;
import orbit.world.WorldSnapshot;
import orbit.world.maps.NpcMaps;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * QUESTS («Квесты», plan 5б.9): takes quests at the home base's quest giver ({@link QuestGiverVisit}) and does them
 * one condition at a time with the bot's own parts — a kill condition is the kill module on the map where those NPCs
 * live with only them on its list (the config's other settings stay: ammunition, safety, boxes); a map condition is
 * a flight there. Daily quests first, then weekly, event, missions. A quest done — back to the giver for the next one
 * (a chain); quests the bot cannot do are not taken, and taken ones it cannot do are left alone.
 *
 * <p>Settings ({@code modules.quests}): {@code types} — which quest types to take (default mission, daily, weekly,
 * event — the user's choice 2026-11-12); {@code revisitMinutes} — how often to look at the giver again (360).
 */
public final class QuestModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(QuestModule.class);

    static final long DEFAULT_REVISIT_MIN = 360;
    /** Type order of the work: what resets soonest first. */
    static final List<Integer> ORDER = List.of(Quests.DAILY, Quests.WEEKLY, Quests.EVENT, Quests.CHALLENGE, Quests.MISSION);

    private final BotContext ctx;
    private final NpcMaps npcMaps;
    private QuestGiverVisit visit;
    private long visitedAt = Long.MIN_VALUE / 2;
    private boolean visitedOnce;
    private int doneSeen = -1;
    private Work work;
    private KillModule kill;
    private String status = "Квесты";
    private String told = "";

    /** One condition being done. */
    record Work(int quest, int condition, int map, Set<String> npcs, double x, double y) {
    }

    public QuestModule(BotContext ctx) {
        this(ctx, NpcMaps.shared());
    }

    public QuestModule(BotContext ctx, NpcMaps npcMaps) {
        this.ctx = ctx;
        this.npcMaps = npcMaps;
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
        return kill != null && kill.busy();
    }

    @Override
    public void pause() {
        if (kill != null) {
            kill.pause();
        }
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
        boolean finishedOne = doneSeen >= 0 && q.active().size() < doneSeen;
        doneSeen = q.active().size();
        long revisit = (long) setting("revisitMinutes", DEFAULT_REVISIT_MIN) * 60_000_000_000L;
        if (!visitedOnce || finishedOne || now - visitedAt > revisit
                || next == null && now - visitedAt > revisit / 6) {
            if (!(kill != null && kill.busy())) {
                visitedOnce = true;
                startVisit(finishedOne ? "квест сдан — за следующим" : next == null ? "нет квестов, которые бот умеет"
                        : "пора посмотреть новые квесты");
                return;
            }
        }
        if (next == null) {
            work = null;
            kill = null;
            status = "Квесты: нечего делать — жду";
            tell("Квесты: среди взятых нет тех, что бот умеет — снова к квестодателю через "
                    + revisit / 6 / 60_000_000_000L + " мин");
            return;
        }
        if (work == null || work.quest() != next.quest() || work.condition() != next.condition() || work.map() != next.map()) {
            work = next;
            kill = null;
            Quests.Quest quest = q.active().get(next.quest());
            Quests.Condition c = condition(quest, next.condition());
            log.info("Квест {}: {} {}/{} — на {}", next.quest(), QuestTexts.condition(c), (long) c.current(),
                    (long) c.target(), ctx.maps().info(next.map()).name());
        }
        Quests.Quest quest = q.active().get(work.quest());
        Quests.Condition c = condition(quest, work.condition());
        if (!work.npcs().isEmpty()) {
            if (kill == null) {
                kill = new KillModule(questContext(work), true, true);
            }
            kill.tick();
            status = "Квест " + work.quest() + ": " + QuestTexts.condition(c) + " " + (long) c.current() + "/"
                    + (long) c.target() + (kill.status().isEmpty() ? "" : " · " + kill.status());
            return;
        }
        // a map to be on (or a point on it): the runner flies there; then the point
        if (!Double.isNaN(work.x()) && ctx.nav().idle()
                && Math.hypot(w.hero().x() - work.x(), w.hero().y() - work.y()) > 150) {
            ctx.nav().moveTo(work.x(), work.y(), false);
        }
        ctx.nav().tick();
        status = "Квест " + work.quest() + ": " + QuestTexts.condition(c);
    }

    private void startVisit(String why) {
        log.info("Квесты: к квестодателю — {}", why);
        if (kill != null) {
            kill.pause();
        }
        kill = null;
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
                Work wk = work(quest, c, w.hero());
                if (wk != null) {
                    return wk;
                }
                break; // the quest's open condition the bot cannot do: the quest waits
            }
        }
        return null;
    }

    private Work work(Quests.Quest quest, Quests.Condition c, WorldSnapshot.Hero h) {
        if (QuestTargets.killing(c)) {
            Map<Integer, Set<String>> where = QuestTargets.where(c, npcMaps);
            Integer best = nearest(where.keySet(), h);
            return best == null ? null : new Work(quest.id(), c.id(), best, Set.copyOf(where.get(best)), Double.NaN, Double.NaN);
        }
        if (QuestTargets.travelling(c)) {
            Integer best = nearest(QuestTargets.mapsOf(c), h);
            if (best == null) {
                return null;
            }
            boolean point = c.type() == Quests.COORDINATES;
            return new Work(quest.id(), c.id(), best, Set.of(), point ? c.match(0) : Double.NaN, point ? c.match(1) : Double.NaN);
        }
        return null;
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

    /** A quest the bot can do: each of its open required conditions is a kill of known NPCs, a map, or a limit. */
    public boolean doable(Quests.Quest quest) {
        WorldSnapshot.Hero h = ctx.world().get().hero();
        for (Quests.Condition c : quest.leaves()) {
            if (c.completed() || !c.mandatory() || QuestTargets.passive(c)) {
                continue;
            }
            if (work(quest, c, h) == null) {
                return false;
            }
        }
        return !quest.leaves().isEmpty();
    }

    /** The config for the kill: its NPC list is only the quest's NPCs, the working map the quest's map. */
    private BotContext questContext(Work wk) {
        return new BotContext(ctx.world(), () -> questConfig(ctx.config().get(), wk), ctx.actions(), ctx.nav(),
                ctx.traveler(), ctx.maps(), ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(),
                ctx.ships());
    }

    static BotConfig questConfig(BotConfig c, Work wk) {
        Map<String, BotConfig.NpcRule> npcs = new LinkedHashMap<>();
        for (String n : wk.npcs()) {
            BotConfig.NpcRule own = c.npc(n);
            npcs.put(n, own != null ? new BotConfig.NpcRule(own.radius(), own.priority(), true, own.flags(), Set.of(),
                    own.ammo(), own.sab(), own.rocket(), own.launcher())
                    : new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null));
        }
        BotConfig.General g = c.general();
        return c.withNpcs(npcs).withGeneral(new BotConfig.General(wk.map(), g.offensive(), g.roam(), g.run(),
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

    /** The quest types to take, from the settings ({@code types}: «MISSION», «DAILY»…); the user's four by default. */
    Set<Integer> types() {
        JsonNode t = settings().path("types");
        Set<Integer> out = new TreeSet<>();
        if (t.isArray() && !t.isEmpty()) {
            t.forEach(n -> {
                switch (n.asText().toUpperCase(java.util.Locale.ROOT)) {
                    case "MISSION" -> out.add(Quests.MISSION);
                    case "DAILY" -> out.add(Quests.DAILY);
                    case "WEEKLY" -> out.add(Quests.WEEKLY);
                    case "EVENT" -> out.add(Quests.EVENT);
                    case "CHALLENGE" -> out.add(Quests.CHALLENGE);
                    default -> {
                    }
                }
            });
            return out;
        }
        return Set.of(Quests.MISSION, Quests.DAILY, Quests.WEEKLY, Quests.EVENT);
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
