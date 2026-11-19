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
 * EVENT, MISSION — the user's choice 2026-11-12); {@code do} — condition kinds to do (KILL, DAMAGE, SALVAGE, BOX, ORE,
 * SELL, MAP; all by default); {@code revisitMinutes} — how often to look at the giver again (360).
 */
public final class QuestModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(QuestModule.class);

    static final long DEFAULT_REVISIT_MIN = 360;
    /** Type order of the work: what resets soonest first. */
    static final List<Integer> ORDER = List.of(Quests.DAILY, Quests.WEEKLY, Quests.EVENT, Quests.CHALLENGE, Quests.MISSION);
    static final String BONUS_BOX = "BONUS_BOX";
    /** On the work's map this long without one of its NPCs in sight: try another map (the table can be wrong). */
    static final long NOT_THERE_MS = 10 * 60_000;
    /** Bosses are easier elsewhere than on 4-5 (the user, 2026-11-12): that map counts as this many jumps more. */
    static final int BOSS_4_5_EXTRA_HOPS = 4;
    /**
     * Another company's map (N-x with N its company) counts as this many jumps more: its players and stations
     * shoot at the ship (live 2026-11-12 on ru1: EIC sent to MMO's 1-3 for Devolarium, which also live on 2-3).
     */
    static final int ENEMY_MAP_EXTRA_HOPS = 8;

    enum Kind { KILL, DAMAGE, SALVAGE, BOX, ORE, SELL, MAP, REFINE, SKYLAB }

    /** {@code Work.ore} of a resource's salvage («собрать Prismatium» — from the NPCs that carry it). */
    static final int LOOT = -3;

    /**
     * Which NPCs carry a resource a quest asks to collect ({@code COLLECT_LOOT}, «resource_collectable_hybrid-alloy»):
     * darkorbitwiki «Elite Missions (Level 22+)», Tips and Tricks (2026-11-17) — Prismatium from all Kristallin and
     * Kristallon, Scrap from Saimon and Mordon, Mucosum from Lordakia, Lordakium and Sibelonit, Plasmide from Sibelon and
     * Devolarium; Hybrid Alloy from the Blighted Gygerthrall (the user, 2026-11-17), called up by fighting Blighted
     * Kristallon ({@link #CALLED_BY}). Names as {@link
     * QuestTargets#norm}; Uber ones are left out (4-5), the plain ones and bosses do. Where they live — {@link NpcMaps}.
     */
    static final Map<String, Set<String>> LOOT_NPCS = Map.of(
            "prismatium", Set.of("kristallin", "kristallon", "bosskristallin", "bosskristallon"),
            "scrap", Set.of("saimon", "mordon", "bosssaimon", "bossmordon"),
            "mucosum", Set.of("lordakia", "lordakium", "sibelonit", "bosslordakia", "bosslordakium", "bosssibelonit"),
            "plasmid", Set.of("sibelon", "devolarium", "bosssibelon", "bossdevolarium"),
            "hybrid", Set.of("blightedgygerthrall"),
            // the Mimesis event's Byte Remnant (darkorbitwiki «Mimesis Mutiny»: Hexor 5, Raging 10, Cloning 25, Terror
            // 35, Reflector 50 a kill; Cloned and Kamikaze none) — the game writes the names with digits
            "byte", Set.of("hexorm1mesis", "ragingmimes1s", "cloningmim3sis", "terrormime5is", "reflectormimesi5"));

    /**
     * Refined ore from raw ore in the hold ({@code OreTypeModule}: ore → {ingredient → per unit}), DarkBot's AutoRefine:
     * prometid = 20 prometium + 10 endurium, duranium = 20 terbium + 10 endurium, promerium = 10 prometid + 10 duranium +
     * 1 xenomit.
     */
    static final Map<Integer, Map<Integer, Integer>> RECIPES = Map.of(
            4, Map.of(0, 20, 1, 10),
            5, Map.of(2, 20, 1, 10),
            6, Map.of(4, 10, 5, 10, 3, 1));

    /** The cargo a killed NPC leaves ({@code FROM_SHIP}), what a «salvage» condition counts. */
    static final String CARGO = "FROM_SHIP";

    private final BotContext ctx;
    private final NpcMaps npcMaps;
    private final OreMaps oreMaps;
    private QuestGiverVisit visit;
    private long visitedAt = Long.MIN_VALUE / 2;
    private boolean visitedOnce;
    private boolean giverLaterTold;
    private int activeSeen = -1;
    private Work work;
    private KillModule worker;
    private String status = "Квесты";
    private String told = "";
    /** Map → conditions whose NPCs were not found there this session. */
    private final Map<Integer, Set<Integer>> notThere = new java.util.HashMap<>();
    /** The passes' free buttons (Season Pass, Battle Pass, Galaxy Trek, the diminishing window). */
    private final PassCare passes;
    private long onMapSince;
    private boolean seenWanted;
    /** The pet's enemy locator on the work's NPC since then; its points before belong to another target. */
    private int locatorFor;
    private long locatorSince;
    private int[] emptyLead;
    private long emptyLeadAt;
    private boolean leadTold;
    /** A fight was on: the locator's next point is waited for (its last one is where the NPC just died). */
    private boolean fought;
    /** A locator point is fresh this long; reached with no NPC in sight it is left alone this long. */
    static final long LEAD_FRESH_MS = 15_000;
    static final long LEAD_EMPTY_MS = 60_000;
    /** This close to the locator's point the NPC would be in sight. */
    static final double LEAD_REACHED = 900;

    /**
     * One condition being done: {@code names} are the NPCs to kill or the collectables to pick ({@code BONUS_BOX},
     * {@code ore_5}); {@code ore} the ore type of a sale (-2 any).
     */
    record Work(int quest, int condition, Kind kind, int map, Set<String> names, double x, double y, int ore,
                Set<String> want, int npc) {

        Work(int quest, int condition, Kind kind, int map, Set<String> names, double x, double y, int ore) {
            this(quest, condition, kind, map, names, x, y, ore, Set.of(), 0);
        }

        Work(int quest, int condition, Kind kind, int map, Set<String> names, double x, double y, int ore,
             Set<String> want) {
            this(quest, condition, kind, map, names, x, y, ore, want, 0);
        }
    }

    public QuestModule(BotContext ctx) {
        this(ctx, NpcMaps.shared(), OreMaps.shared());
    }

    public QuestModule(BotContext ctx, NpcMaps npcMaps, OreMaps oreMaps) {
        this.ctx = ctx;
        this.npcMaps = npcMaps;
        this.oreMaps = oreMaps;
        this.passes = new PassCare(ctx);
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

    /** The quest and condition worked on now ({quest, condition}), {@code null} none — the «Квесты» card marks them. */
    public int[] doing() {
        Work wk = work;
        return wk == null ? null : new int[] {wk.quest(), wk.condition()};
    }

    /** «Теория преобразования» by its title (the user, 2026-11-17: the number alone said nothing), else the number. */
    static String named(int quest) {
        String t = orbit.world.QuestTitles.of(quest);
        return t == null ? String.valueOf(quest) : "«" + t + "»";
    }

    @Override
    public void pause() {
        if (worker != null) {
            worker.pause();
        }
    }

    /** The work's NPC for the pet's enemy locator (the «Квесты» tab's checkbox), on the work's map only. */
    @Override
    public int petLocator() {
        if (!locatorOn() || visit != null || work == null || work.npc() <= 0
                || !(work.kind() == Kind.KILL || work.kind() == Kind.DAMAGE || work.kind() == Kind.SALVAGE)
                || ctx.world().get().hero().mapId() != work.map()) {
            return 0;
        }
        return work.npc();
    }

    boolean locatorOn() {
        return settings().path("locator").asBoolean(false);
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
        cancelMarked(q, now);
        if (!busy()) {
            passes.tick(w, settings(), this::doable); // not in the middle of a fight
        }
        Work next = choose(w);
        boolean finishedOne = activeSeen >= 0 && q.active().size() < activeSeen;
        activeSeen = q.active().size();
        long revisit = (long) setting("revisitMinutes", DEFAULT_REVISIT_MIN) * 60_000_000_000L;
        boolean due = !visitedOnce || finishedOne || now - visitedAt > revisit || next == null && now - visitedAt > revisit / 6;
        // on a map left only one way (5-2 → 5-3, back by the whole loop) its quests first, the giver after
        // (the user, 2026-11-12: «нельзя прыгать на 5-3, потом нужно делать целый облет»)
        if (due && next != null && next.map() == w.hero().mapId()
                && orbit.bot.run.SafePlaces.hardToLeave(w, ctx.maps())) {
            if (!giverLaterTold) {
                log.info("Квесты: к квестодателю позже — с {} обратно только кругом, сначала доделаю квесты здесь",
                        ctx.maps().info(w.hero().mapId()).name());
                giverLaterTold = true;
            }
            due = false;
        } else if (!due) {
            giverLaterTold = false;
        }
        if (due && !busy()) {
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
                log.info("Квест {}: начинаю — {} {}/{}, {} на {}{}", orbit.world.QuestTitles.label(next.quest()), QuestTexts.condition(c), (long) c.current(),
                        (long) c.target(), what(next.kind()), ctx.maps().info(next.map()).name(),
                        // the server's fields of a cargo condition (faction, ship type, NPC, loot type, ore): which
                        // NPCs carry what is known nowhere else (2026-11-17)
                        next.kind() == Kind.SALVAGE ? " (условие сервера: " + c.matches() + ")" : "");
            }
            if (worker != null) {
                worker.pause();
            }
            worker = null;
            work = next;
            onMapSince = 0;
            seenWanted = false;
            cargoFrom = -1;
        }
        Quests.Condition c = condition(q.active().get(work.quest()), work.condition());
        String progress = QuestTexts.condition(c) + (c.target() > 0 ? " " + (long) c.current() + "/" + (long) c.target() : "");
        if (work.kind() != Kind.SKYLAB && !ctx.stats().questOres.get().isEmpty()) {
            ctx.stats().questOres.set(Map.of());
        }
        checkWay(w, c, now);
        switch (work.kind()) {
            case KILL, DAMAGE, SALVAGE, BOX, ORE -> {
                if (notFound(w, now) || noCount(w, c)) {
                    return;
                }
                if (followLocator(w, now, progress)) {
                    return;
                }
                if (worker == null) {
                    boolean kill = work.kind() == Kind.KILL || work.kind() == Kind.DAMAGE || work.kind() == Kind.SALVAGE;
                    worker = new KillModule(questContext(work), kill, true);
                }
                worker.tick();
                status = "Квест " + named(work.quest()) + ": " + progress + (worker.status().isEmpty() ? "" : " · " + worker.status());
            }
            case SELL -> {
                // the runner's cargo care sells (cargoPlan); here the ship waits by the work
                ctx.nav().tick();
                status = "Квест " + named(work.quest()) + ": " + progress + " · продаю";
            }
            case REFINE -> {
                long n = canRefine(w, work.ore());
                if (n > 0 && now - refinedAt > 3_000_000_000L) {
                    log.info("Квест {}: перерабатываю в трюме {} {}", named(work.quest()), n,
                            QuestTexts.ore(oldOre(work.ore())));
                    ctx.actions().labRefine(work.ore(), n);
                    refinedAt = now;
                    if (work.ore() == QuestTargets.oreType(c.match(0))) {
                        wayCheck = new double[] {c.id(), c.current(), -1};
                        wayCheckAt = now;
                    }
                }
                ctx.nav().tick();
                status = "Квест " + named(work.quest()) + ": " + progress + " · перерабатываю в трюме";
            }
            case SKYLAB -> {
                long left = Math.max(0, (long) (c.target() - c.current()));
                long room = Math.max(0, w.cargoFree());
                ctx.stats().questOres.set(left > 0 && room > 0 ? Map.of(work.ore(), Math.min(left, room)) : Map.of());
                double aboardNow = w.inventory().ores().getOrDefault(work.ore(), 0.0);
                if (wayCheck == null || (int) wayCheck[0] != c.id()) {
                    wayCheck = new double[] {c.id(), c.current(), aboardNow};
                    wayCheckAt = 0; // armed when the ore lands
                } else if (wayCheckAt == 0 && aboardNow > wayCheck[2]) {
                    wayCheckAt = now;
                }
                ctx.nav().tick();
                status = "Квест " + named(work.quest()) + ": " + progress + " · жду руду со Скайлаба";
            }
            case MAP -> {
                if (!Double.isNaN(work.x()) && ctx.nav().idle()
                        && Math.hypot(w.hero().x() - work.x(), w.hero().y() - work.y()) > 150) {
                    ctx.nav().moveTo(work.x(), work.y(), false);
                }
                ctx.nav().tick();
                status = "Квест " + named(work.quest()) + ": " + progress;
            }
            default -> {
            }
        }
    }

    /** A refining or Skylab ore a while ago and the counter where it was: that way does not count for the condition. */
    private void checkWay(WorldSnapshot w, Quests.Condition c, long now) {
        if (wayCheck == null || wayCheckAt == 0 || now - wayCheckAt < WAY_CHECK_NS) {
            return;
        }
        double[] k = wayCheck;
        wayCheck = null;
        if ((int) k[0] != c.id() || c.current() > k[1]) {
            return;
        }
        boolean skylab = k[2] >= 0;
        (skylab ? skylabNoCount : refineNoCount).add(c.id());
        log.warn("Квест {}: {} не засчитывается ({}) — {}", named(work.quest()),
                skylab ? "руда со Скайлаба" : "переработка в трюме", QuestTexts.condition(c),
                skylab ? "перерабатываю сам" : "везу со Скайлаба, если включён");
        work = null;
        worker = null;
    }

    /** The quest's old ore number of an {@code OreTypeModule} one (for the ore's Russian name). */
    static int oldOre(int ore) {
        for (int old = 0; old <= 42; old++) {
            if (QuestTargets.oreType(old) == ore) {
                return old;
            }
        }
        return -1;
    }

    /**
     * The pet's enemy locator on the work's NPC ({@link #petLocator}) shows where one is while none is in sight: fly
     * there (as the cube module does with the Cubikon). Reached with none in sight — that point is left alone a while.
     */
    private boolean followLocator(WorldSnapshot w, long now, String progress) {
        int target = petLocator();
        if (target != locatorFor) {
            locatorFor = target;
            locatorSince = w.nanos();
            emptyLead = null;
            leadTold = false;
        }
        if (target == 0) {
            if (locatorOn() && work.npc() > 0 && !ctx.config().get().pet().enabled()) {
                tell("Квесты: пеленгатор включён, но питомец в конфиге выключен — NPC квеста ищу без него");
            }
            return false;
        }
        if (busy()) {
            fought = true;
            return false;
        }
        if (fought) {
            // live 2026-11-12 on 4-5: a tenth of a second after each kill the old point was «reached, nothing there»
            fought = false;
            locatorSince = w.nanos();
            leadTold = false;
            return false;
        }
        WorldSnapshot.Pet pet = w.pet();
        boolean inSight = w.ships().values().stream()
                .anyMatch(sh -> sh.npc() && work.want().contains(QuestTargets.norm(sh.name())));
        if (inSight || !pet.active() || pet.gear() != orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR
                || !pet.locatorWithin(w.nanos(), LEAD_FRESH_MS) || pet.locatorNanos() <= locatorSince) {
            leadTold = false;
            return false;
        }
        int[] lead = {pet.locatorX(), pet.locatorY()};
        if (emptyLead != null && now - emptyLeadAt < LEAD_EMPTY_MS * 1_000_000
                && Math.hypot(lead[0] - emptyLead[0], lead[1] - emptyLead[1]) < LEAD_REACHED) {
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        double dist = Math.hypot(lead[0] - h.x(), lead[1] - h.y());
        if (dist < LEAD_REACHED) {
            log.info("Пеленгатор: долетел до {}/{}, но {} там нет — ищу дальше сам", lead[0] / 100, lead[1] / 100,
                    String.join(", ", work.names()));
            emptyLead = lead;
            emptyLeadAt = now;
            leadTold = false;
            return false;
        }
        if (!leadTold) {
            leadTold = true;
            log.info("Пеленгатор нашёл {} на {}/{}, в {} от нас — лечу", QuestTexts.npc(work.npc()), lead[0] / 100,
                    lead[1] / 100, String.format("%,d", (int) dist).replace(',', ' '));
        }
        ctx.ship().want(ctx.config().get().general().travel());
        if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - lead[0], ctx.nav().destination().y() - lead[1]) > 300) {
            ctx.nav().moveTo(lead[0], lead[1], false);
        }
        ctx.nav().tick();
        status = "Квест " + named(work.quest()) + ": " + progress + " · лечу по пеленгатору";
        return true;
    }

    /**
     * Cargo picked up on the work's map and the condition not moving: its NPCs there carry other ore (live 2026-11-17 on
     * gb2: Streuner's cargo on 3-1, «Сборщик дураниума» 0/100 after dozens) — that map is left out, the next one tried.
     */
    private boolean noCount(WorldSnapshot w, Quests.Condition c) {
        // a resource's NPCs come from the wiki's table: no guessing at other maps (the user, 2026-11-17: «лишнее, потом
        // баг может быть»)
        if (work.kind() != Kind.SALVAGE || work.ore() == LOOT || w.hero().mapId() != work.map()) {
            cargoFrom = -1;
            return false;
        }
        long picked = ctx.stats().boxes.get();
        if (cargoFrom < 0 || c.current() != cargoAt) {
            cargoFrom = picked;
            cargoAt = c.current();
            return false;
        }
        if (picked - cargoFrom < CARGO_TRIES) {
            return false;
        }
        log.warn("Квест {}: на {} подобрал {} грузов, а «{}» не растёт ({}/{}) — здесь в грузе не то, пробую другую карту",
                work.quest(), ctx.maps().info(work.map()).name(), picked - cargoFrom, QuestTexts.condition(c),
                (long) c.current(), (long) c.target());
        notThere.computeIfAbsent(work.map(), k -> new TreeSet<>()).add(work.condition());
        cargoFrom = -1;
        if (worker != null) {
            worker.pause();
        }
        worker = null;
        work = null;
        return true;
    }

    /** Cargo boxes picked with no progress before a map counts as the wrong one. */
    public static final int CARGO_TRIES = 10;
    private long cargoFrom = -1;
    private double cargoAt;

    /** Killing on the work's map with none of its NPCs seen for a while: that map is left out, the next one tried. */
    private boolean notFound(WorldSnapshot w, long now) {
        if (work.want().isEmpty() || w.hero().mapId() != work.map()) {
            onMapSince = 0;
            return false;
        }
        if (onMapSince == 0) {
            onMapSince = now;
        }
        if (w.ships().values().stream().anyMatch(sh -> sh.npc() && work.want().contains(QuestTargets.norm(sh.name())))) {
            seenWanted = true;
        }
        if (seenWanted || now - onMapSince < NOT_THERE_MS * 1_000_000) {
            return false;
        }
        log.warn("Квест {}: на {} за {} мин не видно {} — пробую другую карту", work.quest(),
                ctx.maps().info(work.map()).name(), NOT_THERE_MS / 60_000, String.join(", ", work.names()));
        notThere.computeIfAbsent(work.map(), k -> new TreeSet<>()).add(work.condition());
        if (worker != null) {
            worker.pause();
        }
        worker = null;
        work = null;
        return true;
    }

    /** Quests marked «Отменить»: cancelled (the quest window's button, anywhere), one at a time, again if still there. */
    private final Map<Integer, Long> cancelAskedAt = new java.util.HashMap<>();
    private long nextCancelAt;

    private void cancelMarked(Quests q, long now) {
        if (now < nextCancelAt) {
            return;
        }
        for (int id : ids("cancel")) {
            if (!q.active().containsKey(id)) {
                continue;
            }
            Long asked = cancelAskedAt.get(id);
            if (asked != null && now - asked < 30_000_000_000L) {
                continue;
            }
            log.info("Квест {} отменяю — отмечен «Отменить»", id);
            ctx.actions().questCancel(id);
            cancelAskedAt.put(id, now);
            nextCancelAt = now + ctx.pause(2_000, 4_000);
            return;
        }
    }

    /** Quest numbers of a setting list ({@code first}, {@code skip}, {@code cancel}). */
    List<Integer> ids(String key) {
        List<Integer> out = new java.util.ArrayList<>();
        settings().path(key).forEach(n -> {
            if (n.canConvertToInt()) {
                out.add(n.asInt());
            }
        });
        return out;
    }

    /** Neither taken nor worked on: marked «Не делать» or «Отменить». */
    private boolean left(int questId) {
        return ids("skip").contains(questId) || ids("cancel").contains(questId);
    }

    private static String what(Kind k) {
        return switch (k) {
            case KILL, DAMAGE -> "бью";
            case SALVAGE -> "бью и подбираю груз";
            case BOX, ORE -> "собираю";
            case SELL -> "продаю";
            case MAP -> "лечу";
            case REFINE -> "перерабатываю в трюме";
            case SKYLAB -> "везу со Скайлаба";
        };
    }

    private void startVisit(String why) {
        log.info("Квесты: к квестодателю — {}", why);
        if (worker != null) {
            worker.pause();
        }
        worker = null;
        work = null;
        visit = new QuestGiverVisit(ctx, types(), this::doable, java.util.Set.copyOf(ids("first")))
                .withGiverMaps(orbit.world.maps.GiverMaps.shared().all());
        told = "";
    }

    /** The next condition to work on: by type order, then the quest's id; the first open condition the bot can do. */
    Work choose(WorldSnapshot w) {
        // «Первым» first, in the user's order; then by type, then by number; «Не делать»/«Отменить» never
        List<Integer> first = ids("first");
        List<Quests.Quest> quests = w.quests().active().values().stream()
                .filter(x -> !left(x.id()))
                .sorted(Comparator.<Quests.Quest>comparingInt(x -> first.contains(x.id()) ? first.indexOf(x.id()) : first.size())
                        .thenComparingInt(QuestModule::rank).thenComparingInt(Quests.Quest::id)).toList();
        for (Quests.Quest quest : quests) {
            for (Quests.Condition c : quest.leaves()) {
                if (c.completed() || QuestTargets.passive(c)) {
                    continue;
                }
                Work wk = work(quest, c, w);
                if (wk != null) {
                    return wk;
                }
                if (c.mandatory()) {
                    break; // the quest's open condition the bot cannot do: the quest waits
                }
            }
        }
        return null;
    }

    private Work work(Quests.Quest quest, Quests.Condition c, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        Set<String> doing = kinds();
        if (QuestTargets.killing(c) && doing.contains("KILL") || QuestTargets.damaging(c) && doing.contains("DAMAGE")) {
            Map<Integer, Set<String>> where = new LinkedHashMap<>(QuestTargets.where(c, npcMaps));
            Set<String> want = QuestTargets.wanted(c);
            // the quest says where the kills count (a sub-condition «on these maps»): only there — and there even if
            // the bot never saw them on it, the quest says they live there
            List<Integer> on = c.onMaps();
            if (!on.isEmpty()) {
                where.keySet().retainAll(on);
                if (where.isEmpty()) {
                    on.forEach(m -> where.put(m, Set.of()));
                }
            }
            where.keySet().removeIf(m -> notThere.getOrDefault(m, Set.of()).contains(c.id()));
            boolean bosses = !want.isEmpty() && want.stream().allMatch(n -> n.startsWith("boss"));
            int map45 = ctx.maps().byName("4-5") == null ? -1 : ctx.maps().byName("4-5").id();
            Integer best = on.isEmpty() ? pirateMap(want, where.keySet(), h) : null;
            if (best == null) {
                best = nearest(where.keySet(), h, m -> bosses && m == map45 ? BOSS_4_5_EXTRA_HOPS : 0);
            }
            Kind k = QuestTargets.killing(c) ? Kind.KILL : Kind.DAMAGE;
            List<Integer> ids = QuestTargets.npcIds(c);
            return best == null ? null : new Work(quest.id(), c.id(), k, best, Set.copyOf(where.get(best)), Double.NaN,
                    Double.NaN, -1, want, ids.isEmpty() ? 0 : ids.getFirst());
        }
        if (c.type() == Quests.SALVAGE && doing.contains("SALVAGE")) {
            return salvageWork(quest, c, w);
        }
        if (c.type() == Quests.COLLECT_BONUS_BOX && doing.contains("BOX")) {
            int map = ctx.config().get().general().workingMap();
            return new Work(quest.id(), c.id(), Kind.BOX, map > 0 ? map : h.mapId(), Set.of(BONUS_BOX), Double.NaN,
                    Double.NaN, -1);
        }
        if (c.type() == Quests.COLLECT_LOOT && doing.contains("SALVAGE")) {
            return lootWork(quest, c, w);
        }
        if (c.type() == Quests.COLLECT && doing.contains("ORE")) {
            int ore = QuestTargets.oreType(c.match(0));
            return RECIPES.containsKey(ore) ? refinedWork(quest, c, ore, ore, w) : oreWork(quest, c, ore, w);
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

    /**
     * «Salvage»: the cargo of killed NPCs ({@code SalvageCondition}: faction, ship type, NPC number, loot type, ore).
     * The NPC of the condition when it names one; else the NPCs of the quest's other kill conditions (310023: Sibelon
     * and Saimon, then 100 of their cargo); else the config's own NPCs on its working map.
     */
    private Work salvageWork(Quests.Quest quest, Quests.Condition c, WorldSnapshot w) {
        List<Quests.Condition> sources = new java.util.ArrayList<>();
        if (c.match(2) > 0) {
            sources.add(new Quests.Condition(c.id(), Quests.KILL_NPC, List.of(String.valueOf(c.match(2))), c.target(),
                    c.current(), c.active(), c.completed(), c.mandatory(), List.of()));
        } else {
            quest.leaves().stream().filter(o -> QuestTargets.killing(o) || QuestTargets.damaging(o)).forEach(sources::add);
        }
        // the nearest map of any of them (live 2026-11-12 on ru1: 310023's first kill condition, Sibelon, sent the ship to
        // 2-4 though its Saimon live on 2-3 too)
        Map<Integer, Set<String>> where = new LinkedHashMap<>();
        Map<Integer, Set<String>> wantOn = new LinkedHashMap<>();
        Map<Integer, Integer> npcOn = new LinkedHashMap<>();
        for (Quests.Condition src : sources) {
            List<Integer> ids = QuestTargets.npcIds(src);
            QuestTargets.where(src, npcMaps).forEach((m, names) -> {
                if (notThere.getOrDefault(m, Set.of()).contains(c.id())) {
                    return;
                }
                where.computeIfAbsent(m, k -> new TreeSet<>()).addAll(names);
                wantOn.computeIfAbsent(m, k -> new TreeSet<>()).addAll(QuestTargets.wanted(src));
                if (!ids.isEmpty()) {
                    npcOn.putIfAbsent(m, ids.getFirst());
                }
            });
        }
        Integer best = nearest(where.keySet(), w.hero());
        if (best != null) {
            return new Work(quest.id(), c.id(), Kind.SALVAGE, best, Set.copyOf(where.get(best)), Double.NaN, Double.NaN,
                    -1, Set.copyOf(wantOn.get(best)), npcOn.getOrDefault(best, 0));
        }
        if (!sources.isEmpty()) {
            return null;
        }
        // no NPC named anywhere (300011 «Сборщик дураниума»): the plain NPCs of the company's own maps, the nearest —
        // the config's list and working map sent a VRU ship to MMO's 1-1 and killed nothing there (live 2026-11-17)
        Map<Integer, Set<String>> home = ownMapNpcs(c, w.hero().factionId());
        Integer near = nearest(home.keySet(), w.hero());
        if (near != null) {
            Set<String> names = home.get(near);
            Set<String> want = new TreeSet<>();
            names.forEach(n -> want.add(QuestTargets.norm(n)));
            return new Work(quest.id(), c.id(), Kind.SALVAGE, near, Set.copyOf(names), Double.NaN, Double.NaN, -1,
                    Set.copyOf(want), 0);
        }
        BotConfig cfg = ctx.config().get();
        Set<String> own = new TreeSet<>();
        cfg.npcs().forEach((n, r) -> {
            if (r.kill()) {
                own.add(n);
            }
        });
        int map = cfg.general().workingMap();
        return own.isEmpty() ? null : new Work(quest.id(), c.id(), Kind.SALVAGE, map > 0 ? map : w.hero().mapId(),
                Set.copyOf(own), Double.NaN, Double.NaN, -1);
    }

    /**
     * The company's own maps (1-x for MMO, 2-x EIC, 3-x VRU) with the plain NPCs known there — no Boss or Uber ones, they
     * are the hard ones and the cargo counts the same; maps already found without them are left out.
     */
    Map<Integer, Set<String>> ownMapNpcs(Quests.Condition c, int faction) {
        Map<Integer, Set<String>> out = new LinkedHashMap<>();
        if (faction < 1 || faction > 3) {
            return out;
        }
        for (int m : npcMaps.maps()) {
            String n = ctx.maps().info(m).name();
            if (n.length() < 3 || n.charAt(0) - '0' != faction || n.charAt(1) != '-'
                    || notThere.getOrDefault(m, Set.of()).contains(c.id())) {
                continue;
            }
            Set<String> plain = new TreeSet<>();
            for (String npc : npcMaps.on(m)) {
                String k = QuestTargets.norm(npc);
                if (!k.startsWith("boss") && !k.startsWith("uber")) {
                    plain.add(npc);
                }
            }
            if (!plain.isEmpty()) {
                out.put(m, plain);
            }
        }
        return out;
    }

    /** The pirates' names (letters only, lower case), the «Uber» ones too. */
    static final Set<String> PIRATES = Set.of("saboteur", "interceptor", "barracuda", "annihilator", "battleray",
            "ubersaboteur", "uberinterceptor", "uberbarracuda", "uberannihilator", "uberbattleray");

    /**
     * Pirate NPCs with no map in the quest: 5-2 (the user, 2026-11-12: more chance of a group there, the quest closes
     * sooner), when they live there and it can be reached.
     */
    private Integer pirateMap(Set<String> want, Set<Integer> maps, WorldSnapshot.Hero h) {
        if (want.isEmpty() || !PIRATES.containsAll(want)) {
            return null;
        }
        orbit.world.maps.MapCatalog.MapInfo m52 = ctx.maps().byName("5-2");
        if (m52 == null || !maps.contains(m52.id())) {
            return null;
        }
        return nearest(List.of(m52.id()), h);
    }

    /** Collect an ore: where it lies in sight, else the nearest map where it was seen. */
    /**
     * «Собрать resource_collectable_…»: the NPCs that carry it ({@link #LOOT_NPCS}) killed and their cargo picked, on the
     * nearest map they live on ({@link NpcMaps}; the quest's own maps if it binds them).
     */
    private Work lootWork(Quests.Quest quest, Quests.Condition c, WorldSnapshot w) {
        String id = c.matches().isEmpty() ? "" : c.matches().getFirst().toLowerCase(java.util.Locale.ROOT);
        Set<String> want = LOOT_NPCS.entrySet().stream().filter(e -> id.contains(e.getKey())).map(Map.Entry::getValue)
                .findFirst().orElse(null);
        if (want == null) {
            return null;
        }
        Map<Integer, Set<String>> where = new LinkedHashMap<>();
        for (int m : npcMaps.maps()) {
            Set<String> names = new TreeSet<>();
            npcMaps.on(m).stream().filter(n -> want.contains(QuestTargets.norm(n))).forEach(names::add);
            if (!names.isEmpty()) {
                where.put(m, names);
            }
        }
        List<Integer> on = c.onMaps();
        if (!on.isEmpty()) {
            where.keySet().retainAll(on);
        }
        where.keySet().removeIf(m -> notThere.getOrDefault(m, Set.of()).contains(c.id()));
        Integer best = nearest(where.keySet(), w.hero());
        return best == null ? null : new Work(quest.id(), c.id(), Kind.SALVAGE, best, Set.copyOf(where.get(best)),
                Double.NaN, Double.NaN, LOOT, want, 0);
    }

    /**
     * Refined ore a quest wants collected (prometid, duranium, promerium): refined in the hold from what is aboard; else
     * brought from the Skylab (its transport on); else the raw ore it is made of collected first (the user, 2026-11-17:
     * «покупки нет, есть только скайлаб или переработка — любое должно подходить»). A way the counter does not move on
     * is dropped for that condition. The hold nearly full with the refined ore: it is sold to make room (counted already).
     */
    private Work refinedWork(Quests.Quest quest, Quests.Condition c, int wanted, int ore, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        boolean refineOk = !refineNoCount.contains(c.id());
        if (refineOk && canRefine(w, ore) > 0) {
            return new Work(quest.id(), c.id(), Kind.REFINE, h.mapId(), Set.of(), Double.NaN, Double.NaN, ore);
        }
        if (ore == wanted) {
            long free = w.cargoFree();
            long max = h.cargoMax();
            if (aboard(w, ore) && max > 0 && free < Math.max(100, max / 20)) {
                return new Work(quest.id(), c.id(), Kind.SELL, 0, Set.of(), Double.NaN, Double.NaN, ore);
            }
            if (!skylabNoCount.contains(c.id()) && skylabOn()) {
                return new Work(quest.id(), c.id(), Kind.SKYLAB, h.mapId(), Set.of(), Double.NaN, Double.NaN, ore);
            }
        }
        if (!refineOk) {
            return null;
        }
        // the ingredient furthest from one unit's share: refined itself — the same way down; raw — collected
        Map<Integer, Integer> recipe = RECIPES.get(ore);
        int need = recipe.entrySet().stream().min(java.util.Comparator.comparingDouble(
                e -> w.inventory().ores().getOrDefault(e.getKey(), 0.0) / e.getValue())).orElseThrow().getKey();
        return RECIPES.containsKey(need) ? refinedWork(quest, c, wanted, need, w) : oreWork(quest, c, need, w);
    }

    /** Units of a refined ore the hold's raw ores make now. */
    static long canRefine(WorldSnapshot w, int ore) {
        Map<Integer, Integer> recipe = RECIPES.get(ore);
        if (recipe == null) {
            return 0;
        }
        long n = Long.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : recipe.entrySet()) {
            n = Math.min(n, (long) (w.inventory().ores().getOrDefault(e.getKey(), 0.0) / e.getValue()));
        }
        return n == Long.MAX_VALUE ? 0 : n;
    }

    private boolean skylabOn() {
        return ctx.config().get().moduleSettings("skylab").path("enabled").asBoolean(false);
    }

    /** Conditions whose counter did not move on a refining in the hold, or on ore brought from the Skylab. */
    private final Set<Integer> refineNoCount = new java.util.HashSet<>();
    private final Set<Integer> skylabNoCount = new java.util.HashSet<>();
    /** The last refining or Skylab ore of the work: {condition, the counter before, the ore aboard before}, when. */
    private double[] wayCheck;
    private long wayCheckAt;
    private long refinedAt = Long.MIN_VALUE / 2;
    static final long WAY_CHECK_NS = 20_000_000_000L;

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
        // «Endurium 30 000 on enemy x-4» (the user, 2026-11-17): only where the quest counts it — there even when the
        // bot never saw that ore on it
        List<Integer> on = c.onMaps();
        if (!on.isEmpty()) {
            maps.retainAll(on);
            if (maps.isEmpty()) {
                maps.addAll(on);
            }
        }
        maps.removeIf(m -> notThere.getOrDefault(m, Set.of()).contains(c.id()));
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

    private Integer nearest(java.util.Collection<Integer> maps, WorldSnapshot.Hero h) {
        return nearest(maps, h, m -> 0);
    }

    /** The map fewest jumps away (plus its extra), the one the ship is on first; {@code null} when none can be reached. */
    private Integer nearest(java.util.Collection<Integer> maps, WorldSnapshot.Hero h,
                            java.util.function.IntUnaryOperator extra) {
        Integer best = null;
        int bestHops = Integer.MAX_VALUE;
        for (int m : maps) {
            if (orbit.config.GateCatalog.instanced(m)) {
                continue; // a gate's or the Mimesis escort's map: no way there but its own portal (Medic, Hardy… live there)
            }
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
            hops += extra.applyAsInt(m) + (enemyMap(m, h.factionId()) ? ENEMY_MAP_EXTRA_HOPS : 0);
            if (hops < bestHops) {
                best = m;
                bestHops = hops;
            }
        }
        return best;
    }

    /** A map of another company: its name starts with that company's number (1-3 is MMO's, 2-3 EIC's, 3-3 VRU's). */
    private boolean enemyMap(int map, int faction) {
        String n = ctx.maps().info(map).name();
        if (faction <= 0 || n.length() < 2 || n.charAt(1) != '-' && !n.endsWith("BL")) {
            return false;
        }
        int company = n.charAt(0) - '0';
        return company >= 1 && company <= 3 && company != faction;
    }

    /** A quest the bot can do: each of its open required conditions is one it does, or a limit it keeps. */
    public boolean doable(Quests.Quest quest) {
        if (left(quest.id())) {
            return false;
        }
        WorldSnapshot w = ctx.world().get();
        int work = 0;
        for (Quests.Condition c : quest.leaves()) {
            if (c.completed() || QuestTargets.passive(c)) {
                continue;
            }
            if (work(quest, c, w) == null) {
                if (c.mandatory()) {
                    return false;
                }
                continue;
            }
            work++;
        }
        // something the bot really does (live 2026-11-12 on ru1: a quest of R-Zone maps, its only condition not
        // required, was taken though no portal leads there)
        return work > 0;
    }

    /** The config for the work: only its NPCs or collectables listed, the working map the work's map. */
    private BotContext questContext(Work wk) {
        return new BotContext(ctx.world(), () -> questConfig(ctx.config().get(), withSeen(wk), side(wk), boxesOpen(wk)),
                ctx.actions(), ctx.nav(),
                ctx.traveler(), ctx.maps(), ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(),
                ctx.ships());
    }

    /**
     * The box a resource drops in, when not the plain cargo ({@link #CARGO}): the Gygerthrall's alloy comes in its own
     * box (live 2026-11-17 on gb2: none picked while only the cargo was on; AstraBot's QUESTS profile collects it too).
     */
    static final Map<String, String> LOOT_BOXES = Map.of("hybrid", "HYBRID_ALLOY_BOX");

    /**
     * NPCs that call up others when fought ({@link QuestTargets#norm} names): Blighted Kristallon brings the Blighted
     * Gygerthrall, which carry the Hybrid Alloy (the user, 2026-11-17: «чтобы вторые были, нужно бить Blighted
     * Kristallon»).
     */
    static final Map<String, String> CALLED_BY = Map.of("blightedgygerthrall", "blightedkristallon");
    /** The priority of a caller: fought only when none of what it calls is in sight. */
    static final int CALLER_PRIORITY = 10;

    /**
     * The other open conditions of the work's quest fought alongside on the work's map — 122011: while the Blighted
     * Kristallon are killed the Gygerthrall they call up are killed too, and their alloy picked — and the callers of
     * what is wanted, after it. Names as the map's NPC table and the sight write them.
     */
    private Map<String, Integer> side(Work wk) {
        WorldSnapshot w = ctx.world().get();
        Quests.Quest quest = w.quests().active().get(wk.quest());
        if (quest == null || !(wk.kind() == Kind.KILL || wk.kind() == Kind.DAMAGE || wk.kind() == Kind.SALVAGE)) {
            return Map.of();
        }
        Map<String, Integer> norms = new LinkedHashMap<>();
        for (Quests.Condition o : quest.leaves()) {
            if (o.completed() || o.id() == wk.condition()) {
                continue;
            }
            if (QuestTargets.killing(o) || QuestTargets.damaging(o)) {
                QuestTargets.wanted(o).forEach(n -> norms.putIfAbsent(n, 0));
            } else if (o.type() == Quests.COLLECT_LOOT && !o.matches().isEmpty()) {
                String id = o.matches().getFirst().toLowerCase(java.util.Locale.ROOT);
                LOOT_NPCS.forEach((k, v) -> {
                    if (id.contains(k)) {
                        v.forEach(n -> norms.putIfAbsent(n, 0));
                    }
                });
            }
        }
        Set<String> wanted = new TreeSet<>(wk.want());
        wanted.addAll(norms.keySet());
        for (String n : wanted) {
            String caller = CALLED_BY.get(n);
            if (caller != null && !wk.want().contains(caller)) {
                norms.putIfAbsent(caller, CALLER_PRIORITY);
            }
        }
        wk.want().forEach(norms::remove);
        if (norms.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        java.util.function.Consumer<String> add = name -> {
            Integer p = norms.get(QuestTargets.norm(name));
            if (p != null) {
                out.putIfAbsent(name, p);
            }
        };
        npcMaps.on(wk.map()).forEach(add);
        w.ships().values().stream().filter(sh -> sh.npc() && sh.name() != null).map(WorldSnapshot.Ship::name).forEach(add);
        return out;
    }

    /** The boxes the work's quest's open cargo and resource conditions want picked ({@link #LOOT_BOXES}, else cargo). */
    private Set<String> boxesOpen(Work wk) {
        Quests.Quest quest = ctx.world().get().quests().active().get(wk.quest());
        Set<String> out = new TreeSet<>();
        if (quest == null) {
            return out;
        }
        for (Quests.Condition o : quest.leaves()) {
            if (o.completed()) {
                continue;
            }
            if (o.type() == Quests.SALVAGE) {
                out.add(CARGO);
            } else if (o.type() == Quests.COLLECT_LOOT && !o.matches().isEmpty()) {
                String id = o.matches().getFirst().toLowerCase(java.util.Locale.ROOT);
                LOOT_NPCS.keySet().stream().filter(id::contains).findFirst()
                        .ifPresent(k -> out.add(LOOT_BOXES.getOrDefault(k, CARGO)));
            }
        }
        return out;
    }

    /** The work's NPCs as they are named in sight, too: the table may write a name a little otherwise. */
    private Work withSeen(Work wk) {
        if (wk.want().isEmpty()) {
            return wk;
        }
        Set<String> names = new TreeSet<>(wk.names());
        for (WorldSnapshot.Ship sh : ctx.world().get().ships().values()) {
            if (sh.npc() && sh.name() != null && wk.want().contains(QuestTargets.norm(sh.name()))) {
                names.add(sh.name());
            }
        }
        return names.size() == wk.names().size() ? wk : new Work(wk.quest(), wk.condition(), wk.kind(), wk.map(),
                Set.copyOf(names), wk.x(), wk.y(), wk.ore(), wk.want(), wk.npc());
    }

    static BotConfig questConfig(BotConfig c, Work wk) {
        return questConfig(c, wk, Map.of(), wk.kind() == Kind.SALVAGE ? Set.of(CARGO) : Set.of());
    }

    /**
     * @param side  more NPCs to fight with their priority (lower first): the quest's other open conditions' NPCs and the
     *              ones that call them up ({@link #side})
     * @param boxes the boxes the quest's open cargo and resource conditions want: picked whatever the work is
     */
    static BotConfig questConfig(BotConfig c, Work wk, Map<String, Integer> side, Set<String> boxes) {
        BotConfig out = c;
        if (wk.kind() == Kind.KILL || wk.kind() == Kind.DAMAGE || wk.kind() == Kind.SALVAGE) {
            Map<String, BotConfig.NpcRule> npcs = new LinkedHashMap<>();
            Map<String, Integer> all = new LinkedHashMap<>();
            wk.names().forEach(n -> all.put(n, 0));
            side.forEach(all::putIfAbsent);
            all.forEach((n, prio) -> {
                BotConfig.NpcRule own = c.npc(n);
                int p = prio == 0 && own != null ? own.priority() : prio;
                npcs.put(n, own != null ? new BotConfig.NpcRule(own.radius(), p, true, own.flags(), Set.of(),
                        own.ammo(), own.sab(), own.rocket(), own.launcher())
                        : new BotConfig.NpcRule(0, p, true, Set.of(), Set.of(), null)); // 0: the default radius
            });
            out = out.withNpcs(npcs);
            if (!boxes.isEmpty()) {
                // their cargo or resource boxes too: what the conditions count
                BotConfig.Collect k = c.collect();
                Map<String, BotConfig.BoxRule> rules = new LinkedHashMap<>(k.boxes());
                for (String b : boxes) {
                    BotConfig.BoxRule own = k.boxes().get(b);
                    rules.put(b, new BotConfig.BoxRule(true, own != null ? own.waitMs() : 0, own != null ? own.priority() : 0));
                }
                out = out.withCollect(new BotConfig.Collect(rules, k.radius(), k.stayAwayFromEnemies(), k.ignoreContested(),
                        k.cargo(), k.cargoFullAt(), k.sellOres()));
            }
        } else if (wk.kind() == Kind.BOX || wk.kind() == Kind.ORE) {
            BotConfig.Collect k = c.collect();
            Map<String, BotConfig.BoxRule> picks = new LinkedHashMap<>();
            for (String n : wk.names()) {
                BotConfig.BoxRule own = k.boxes().get(n);
                picks.put(n, new BotConfig.BoxRule(true, own != null ? own.waitMs() : n.startsWith("ore_") ? 800 : 0,
                        own != null ? own.priority() : 0));
            }
            out = out.withNpcs(Map.of()).withCollect(new BotConfig.Collect(picks, k.radius(), k.stayAwayFromEnemies(),
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
        for (String k : strings("do", List.of("KILL", "DAMAGE", "SALVAGE", "BOX", "ORE", "SELL", "MAP"))) {
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
