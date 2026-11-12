package orbit.bot.run;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import orbit.world.QuestTexts;
import orbit.world.Quests;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One visit to the quest giver of the own home base, as the user did it with the client (ru1 2026-11-12, plan 5б.9):
 * to the nearest own map with a quest giver (x-1, x-4, x-5, x-8) and to within reach of the giver (a map asset of type 34; the server switches it on at
 * about 400 and sends its quest list itself), a click on it ({@code MapAssetActivationRequest}), the window
 * ({@code QuestGiverApproachedRequest}, {@code QuestSettingsRequest}), the conditions of each offered quest of a wanted
 * type ({@code QuestInfoRequest}), «Принять» for those the bot can do ({@code QuestAcceptRequest}, one by one, while
 * there are slots), and the window closed ({@code QuestGiverWindowClosedRequest}). Pauses as in the recording.
 */
public final class QuestGiverVisit {

    private static final Logger log = LoggerFactory.getLogger(QuestGiverVisit.class);

    static final int GIVER_TYPE = 34;
    /** The server switched the giver on at 397 and off at 427 (recording): stop this close. */
    static final double NEAR = 250;
    /** No answer from the server this long: the step is given up. */
    static final long ANSWER_MS = 8_000;
    /** Waited at the giver this long for the server to switch it on: closer, then give up. */
    static final long SWITCH_ON_MS = 20_000;

    enum Step { TO_BASE, FIND, TO_GIVER, WAIT_ON, CLICKED, OPENED, READING, ACCEPTING, CLOSING, DONE, FAILED }

    private final BotContext ctx;
    private final Set<Integer> types;
    private final Predicate<Quests.Quest> doable;
    /** Quests the user marked «Первым»: read and taken whatever their type. */
    private final Set<Integer> always;
    private Step step = Step.TO_BASE;
    private final List<Integer> bases = new ArrayList<>();
    private int base;
    private int giver;
    private long at;
    private boolean started;
    private final Deque<Integer> toRead = new ArrayDeque<>();
    private int reading;
    private final Deque<Integer> toTake = new ArrayDeque<>();
    private int taking;
    private int missionSlots;
    private int eventSlots;
    private final List<Integer> taken = new ArrayList<>();
    private String why = "";

    /**
     * @param types  quest types to take ({@link Quests#MISSION}, {@link Quests#DAILY}…)
     * @param doable whether the bot can do a quest (by its conditions)
     */
    public QuestGiverVisit(BotContext ctx, Set<Integer> types, Predicate<Quests.Quest> doable) {
        this(ctx, types, doable, Set.of());
    }

    public QuestGiverVisit(BotContext ctx, Set<Integer> types, Predicate<Quests.Quest> doable, Set<Integer> always) {
        this.ctx = ctx;
        this.types = Set.copyOf(types);
        this.doable = doable;
        this.always = Set.copyOf(always);
    }

    public Step step() {
        return step;
    }

    public boolean done() {
        return step == Step.DONE || step == Step.FAILED;
    }

    public boolean failed() {
        return step == Step.FAILED;
    }

    /** Quests taken on this visit. */
    public List<Integer> taken() {
        return List.copyOf(taken);
    }

    public String status() {
        return switch (step) {
            case TO_BASE -> "Квесты: лечу к квестодателю на " + ctx.maps().info(base).name();
            case FIND, TO_GIVER, WAIT_ON -> "Квесты: к квестодателю";
            case CLICKED, OPENED, READING, ACCEPTING, CLOSING -> "Квесты: у квестодателя";
            case DONE -> "Квесты: взято " + taken.size();
            case FAILED -> "Квесты: " + why;
        };
    }

    /** @return true while the visit steers the ship */
    public boolean tick() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (!started) {
            started = true;
            bases.addAll(bases(h));
            if (bases.isEmpty()) {
                return fail("нет пути на свою базу");
            }
            base = bases.removeFirst();
            ctx.traveler().travel(base, Double.NaN, Double.NaN);
        }
        return switch (step) {
            case TO_BASE -> toBase(w, now);
            case FIND -> find(w, now);
            case TO_GIVER -> toGiver(w, now);
            case WAIT_ON -> waitOn(w, now);
            case CLICKED -> clicked(now);
            case OPENED -> opened(w, now);
            case READING -> reading(w, now);
            case ACCEPTING -> accepting(w, now);
            case CLOSING -> closing(now);
            case DONE, FAILED -> false;
        };
    }

    private boolean toBase(WorldSnapshot w, long now) {
        if (w.hero().mapId() != base) {
            if (ctx.traveler().state() == orbit.bot.nav.Traveler.State.FAILED) {
                return fail("нет пути на " + ctx.maps().info(base).name());
            }
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.traveler().tick();
            return true;
        }
        step = Step.FIND;
        at = now;
        return true;
    }

    private boolean find(WorldSnapshot w, long now) {
        WorldSnapshot.Asset g = w.assets().values().stream().filter(a -> a.type() == GIVER_TYPE).findFirst().orElse(null);
        if (g != null) {
            giver = g.id();
            log.info("Квестодатель {} на {} — лечу к нему", g.name(), ctx.maps().info(base).name());
            double a = ctx.random().nextDouble() * 2 * Math.PI;
            double r = 80 + ctx.random().nextDouble() * (NEAR - 100);
            ctx.nav().moveTo(g.x() + Math.cos(a) * r, g.y() + Math.sin(a) * r, false);
            step = Step.TO_GIVER;
            at = now;
            return true;
        }
        if (now - at > ANSWER_MS * 1_000_000) {
            if (!bases.isEmpty()) {
                base = bases.removeFirst();
                log.info("На {} нет квестодателя — лечу на {}", ctx.maps().info(w.hero().mapId()).name(),
                        ctx.maps().info(base).name());
                ctx.traveler().travel(base, Double.NaN, Double.NaN);
                step = Step.TO_BASE;
                return true;
            }
            return fail("квестодатель не найден");
        }
        return true;
    }

    private boolean toGiver(WorldSnapshot w, long now) {
        WorldSnapshot.Asset g = w.assets().get(giver);
        if (g == null) {
            step = Step.FIND;
            at = now;
            return true;
        }
        if (w.quests().givers().contains(giver) && !w.hero().moving()) {
            step = Step.WAIT_ON;
            at = now;
            return true;
        }
        if (ctx.nav().idle()) {
            step = Step.WAIT_ON;
            at = now;
            return true;
        }
        ctx.nav().tick();
        return true;
    }

    private boolean waitOn(WorldSnapshot w, long now) {
        if (w.quests().givers().contains(giver)) {
            // the user clicked 0.2 s to 45 s after it switched on; a short look before the click
            if (at > 0) {
                at = -(now + ctx.pause(700, 1_800)); // negative: the click's time
                return true;
            }
            if (now >= -at) {
                ctx.actions().activateAsset(giver);
                step = Step.CLICKED;
                at = now + ctx.pause(400, 650);
            }
            return true;
        }
        if (at < 0) {
            at = now; // switched off again before the click
        }
        if (now - at > SWITCH_ON_MS * 1_000_000) {
            return fail("квестодатель не включился");
        }
        if (now - at > SWITCH_ON_MS / 2 * 1_000_000 && ctx.nav().idle()) {
            WorldSnapshot.Asset g = w.assets().get(giver);
            if (g != null) {
                ctx.nav().moveTo(g.x() + 60, g.y() + 40, false); // closer
            }
        }
        ctx.nav().tick();
        return true;
    }

    private boolean clicked(long now) {
        if (now < at) {
            return true;
        }
        ctx.actions().questGiverApproached(giver);
        ctx.actions().questSettings();
        step = Step.OPENED;
        at = now + ctx.pause(1_000, 2_000);
        return true;
    }

    private boolean opened(WorldSnapshot w, long now) {
        if (now < at) {
            return true;
        }
        Quests q = w.quests();
        missionSlots = q.maxQuests();
        eventSlots = q.maxEventQuests();
        int level = w.hero().level();
        for (Quests.Offer o : q.offers()) {
            if (o.status() == Quests.ACCEPTABLE && (always.contains(o.questId()) || types.stream().anyMatch(o::is))
                    && (level <= 0 || o.minLevel() <= level) && slotFor(o)) {
                if (always.contains(o.questId())) {
                    toRead.addFirst(o.questId()); // «Первым»: read and taken before the rest
                } else {
                    toRead.add(o.questId());
                }
            }
        }
        log.info("Квестодатель: квестов {}, подходящих по типу {} (мест для миссий {}, для событий {})", q.offers().size(),
                toRead.size(), missionSlots, eventSlots);
        step = Step.READING;
        at = now;
        return true;
    }

    private boolean slotFor(Quests.Offer o) {
        if (o.is(Quests.MISSION) || o.is(Quests.CHALLENGE)) {
            return missionSlots > 0;
        }
        if (o.is(Quests.EVENT)) {
            return eventSlots > 0;
        }
        return true; // daily and weekly were taken with no mission slot left (recording)
    }

    private boolean reading(WorldSnapshot w, long now) {
        if (reading != 0) {
            Quests.Quest d = w.quests().details().get(reading);
            if (d == null) {
                if (now - at > ANSWER_MS * 1_000_000) {
                    log.warn("Квест {}: сервер не прислал условия", reading);
                    reading = 0;
                }
                return true;
            }
            boolean can = doable.test(d);
            log.info("Квест {} ({}): {} — {}", orbit.world.QuestTitles.label(d.id()), QuestTexts.type(d.types()), describe(d),
                    can ? "беру" : "не умею, пропускаю");
            if (can) {
                toTake.add(d.id());
            }
            reading = 0;
            at = now + ctx.pause(1_000, 3_500);
            return true;
        }
        if (now < at) {
            return true;
        }
        if (!toRead.isEmpty()) {
            reading = toRead.removeFirst();
            ctx.actions().questInfo(reading);
            at = now;
            return true;
        }
        step = Step.ACCEPTING;
        at = now + ctx.pause(1_500, 4_000);
        return true;
    }

    private boolean accepting(WorldSnapshot w, long now) {
        Quests q = w.quests();
        if (taking != 0) {
            if (q.active().containsKey(taking)) {
                taken.add(taking);
                Quests.Offer o = q.offers().stream().filter(x -> x.questId() == taking).findFirst().orElse(null);
                if (o != null && (o.is(Quests.MISSION) || o.is(Quests.CHALLENGE))) {
                    missionSlots--;
                } else if (o != null && o.is(Quests.EVENT)) {
                    eventSlots--;
                }
                taking = 0;
                at = now + ctx.pause(1_800, 3_000);
            } else if (now - at > ANSWER_MS * 1_000_000) {
                log.warn("Квест {} не взялся: сервер не ответил", taking);
                taking = 0;
            }
            return true;
        }
        if (now < at) {
            return true;
        }
        while (!toTake.isEmpty()) {
            int id = toTake.removeFirst();
            Quests.Offer o = q.offers().stream().filter(x -> x.questId() == id).findFirst().orElse(null);
            if (o == null || o.status() != Quests.ACCEPTABLE || !slotFor(o)) {
                // live 2026-11-12 on ru1: after three daily quests two more were left without a word
                log.info("Квест {} не беру: {}", id, o == null ? "его больше нет в списке"
                        : o.status() != Quests.ACCEPTABLE ? "сервер больше не даёт взять (" + status(o.status()) + ")"
                        : "нет свободного места");
                continue;
            }
            taking = id;
            ctx.actions().questAccept(id);
            at = now;
            return true;
        }
        step = Step.CLOSING;
        at = now + ctx.pause(1_500, 5_000);
        return true;
    }

    private static String status(int s) {
        return switch (s) {
            case Quests.NOT_ACCEPTABLE -> "нельзя взять — лимит или условие";
            case Quests.RUNNING -> "уже идёт";
            case Quests.COMPLETED -> "уже выполнен";
            default -> "статус " + s;
        };
    }

    private boolean closing(long now) {
        if (now < at) {
            return true;
        }
        ctx.actions().questGiverClosed(giver);
        log.info("Квестодатель: взято {}{}", taken.size(), taken.isEmpty() ? "" : " " + taken);
        step = Step.DONE;
        return false;
    }

    private boolean fail(String reason) {
        why = reason;
        step = Step.FAILED;
        log.warn("Квесты: {}", reason);
        return false;
    }

    static String describe(Quests.Quest q) {
        return String.join("; ", q.leaves().stream().map(c -> {
            String t = QuestTexts.condition(c);
            return c.target() > 0 && !QuestTexts.isTime(c) && c.type() != Quests.AVOID_DEATH ? t + " " + (long) c.target() : t;
        }).toList());
    }

    /**
     * The own company's maps with a quest giver (x-1, x-4, x-5, x-8 — the user, 2026-11-12) with a way there, nearest
     * first by jumps (live 2026-11-12 on ru1: from 1-3 the bot flew 6 jumps to 2-8 with 2-1 three away); the map the
     * ship is on comes first when a giver is in sight there. The next one is tried when one has no quest giver.
     */
    private List<Integer> bases(WorldSnapshot.Hero h) {
        List<int[]> found = new ArrayList<>(); // {map, jumps, order}
        List<String> names = List.of(h.factionId() + "-8", h.factionId() + "-1", h.factionId() + "-4", h.factionId() + "-5");
        boolean giverHere = ctx.world().get().assets().values().stream().anyMatch(a -> a.type() == GIVER_TYPE);
        if (giverHere) {
            found.add(new int[] {h.mapId(), -1, -1});
        }
        for (int i = 0; i < names.size(); i++) {
            MapCatalog.MapInfo m = ctx.maps().byName(names.get(i));
            if (m == null || giverHere && m.id() == h.mapId()) {
                continue;
            }
            int jumps;
            if (m.id() == h.mapId()) {
                jumps = 0;
            } else {
                List<?> route = orbit.bot.nav.Router.route(ctx.maps(), h.mapId(), h.x(), h.y(), m.id(), h.factionId(),
                        h.level() > 0 ? h.level() : Integer.MAX_VALUE);
                if (route.isEmpty()) {
                    continue;
                }
                jumps = route.size();
            }
            found.add(new int[] {m.id(), jumps, i});
        }
        found.sort(java.util.Comparator.<int[]>comparingInt(f -> f[1]).thenComparingInt(f -> f[2]));
        List<Integer> out = new ArrayList<>();
        found.forEach(f -> out.add(f[0]));
        return out;
    }
}
