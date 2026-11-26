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
 * QUESTS (the Quests module, plan 5b.9): takes quests at the home base's quest giver ({@link QuestGiverVisit}) and
 * does them one condition at a time with the bot's own parts:
 * <ul>
 *   <li>kill or damage NPCs — the kill module on the nearest map where those NPCs were seen, only them on its list
 *       (the config's other settings stay: ammunition, safety, boxes);</li>
 *   <li>bonus boxes — the collector on the config's working map (or where the ship is), only {@code BONUS_BOX};</li>
 *   <li>ore — the collector on the nearest map where that ore was seen ({@link OreMaps}), only that ore;</li>
 *   <li>sell ore — the sale ({@code CargoCare}, the config's way: base or pet) of that ore even with room in the hold;
 *       none aboard — it is collected first;</li>
 *   <li>a map or a point — a flight there;</li>
 *   <li>cargo boxes ({@code CARGO}) — as the salvage: NPCs killed and their cargo picked;</li>
 *   <li>built recipes — crafted beside the other work ({@link QuestCrafts}); conditions that count other quests grow
 *       by themselves.</li>
 * </ul>
 * Daily quests first, then weekly, event, challenge, missions. A quest done — back to the giver for the next one (a
 * chain); quests the bot cannot do are not taken, and a taken one it cannot do waits.
 *
 * <p>Settings ({@code modules.quests}, the Quests tab): {@code types} — quest types to take (default DAILY, WEEKLY,
 * EVENT, MISSION — user's choice, 2026-11-12); {@code do} — condition kinds to do (KILL, DAMAGE, SALVAGE, BOX, ORE,
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
    /** Bosses are easier elsewhere than on 4-5: that map counts as this many jumps more. */
    static final int BOSS_4_5_EXTRA_HOPS = 4;
    /**
     * Another company's map (N-x with N its company) counts as this many jumps more: its players and stations
     * shoot at the ship (live 2026-11-12 on ru1: EIC sent to MMO's 1-3 for Devolarium, which also live on 2-3).
     */
    static final int ENEMY_MAP_EXTRA_HOPS = 8;

    enum Kind { KILL, DAMAGE, SALVAGE, BOX, ORE, SELL, MAP, REFINE, SKYLAB, GIVER, SLOTBAR, GATE }

    /** {@code Work.ore} of a resource's salvage ("collect Prismatium" — from the NPCs that carry it). */
    static final int LOOT = -3;

    /**
     * Which NPCs carry a resource a quest asks to collect ({@code COLLECT_LOOT}, «resource_collectable_hybrid-alloy»):
     * darkorbitwiki «Elite Missions (Level 22+)», Tips and Tricks (2026-11-17) — Prismatium from all Kristallin and
     * Kristallon, Scrap from Saimon and Mordon, Mucosum from Lordakia, Lordakium and Sibelonit, Plasmide from Sibelon and
     * Devolarium; Hybrid Alloy from the Blighted Gygerthrall (2026-11-17), called up by fighting Blighted
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
    /**
     * Every cargo box a «pick up cargo boxes» condition may count: the plain one and the events' (DarkBot's DmPlugin picks
     * FROM_SHIP, CANDY_CARGO, TURKEY_CARGO; DarkBot's collector counts PROSPEROUS_CARGO as cargo; the client draws these
     * four — and a Perseus cargo whose type name was never seen). {@code FROM_SHIP_BLOCKED} is another's: not to be picked.
     */
    static final Set<String> CARGO_BOXES = Set.of(CARGO, "CANDY_CARGO", "TURKEY_CARGO", "PROSPEROUS_CARGO");

    private final BotContext ctx;
    private final NpcMaps npcMaps;
    private final OreMaps oreMaps;
    private QuestGiverVisit visit;
    private long visitedAt = Long.MIN_VALUE / 2;
    /** A «back to the giver» condition of one quest sends the ship there at most this often. */
    static final long GIVER_GAP_NS = 3 * 60_000_000_000L;
    /** The quest a «back to the giver» visit was last started for, and when. */
    private int giverFor = -1;
    private long giverAt;
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
        if (visit != null || work == null) {
            return 0;
        }
        if (work.map() == STARTER_GATE_MAP && ctx.world().get().hero().mapId() != STARTER_GATE_MAP) {
            // out of the gate (the server took the ship back to x-1 when it was over): the gate's work is done, never a
            // way back in (live 2026-11-26 02:07: the learnt portal led the runner back towards «Devolarium Attack»)
            return 0;
        }
        if (ctx.world().get().hero().mapId() == STARTER_GATE_MAP && kinds().contains("STARTERGATE")) {
            // in the starter gate: the work there is chosen anew, no travel back to x-1 meanwhile (live 2026-11-26: the
            // jump went through, the runner found no path from «Devolarium Attack» to 1-1 and stopped the bot)
            return STARTER_GATE_MAP;
        }
        return work.map();
    }

    @Override
    public boolean busy() {
        return worker != null && worker.busy();
    }

    /** The quest and condition worked on now ({quest, condition}), {@code null} none — the Quests card marks them. */
    public int[] doing() {
        Work wk = work;
        return wk == null ? null : new int[] {wk.quest(), wk.condition()};
    }

    /** A quest by its title (the number alone says nothing), else the number. */
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

    /** The work's NPC for the pet's enemy locator (the Quests tab's checkbox), on the work's map only. */
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
        notePlayerDeath(w, now);
        if (w.hero().mapId() == STARTER_GATE_MAP && kinds().contains("STARTERGATE")) {
            inStarterGate(w, now);
            return;
        }
        if (gateFight != null) {
            gateFight = null; // out of the gate
            work = null;
            worker = null;
        }
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
        if (due && visitedOnce && next != null && settings().path("finishFirst").asBoolean(false) && blocking(w) > 0) {
            // «finishFirst» (the leveling quests configs, a user's choice 2026-11-25): no new quests while a taken mission
            // or starter quest the bot can do is not finished — long ones and the passes' tasks aside
            due = false;
        }
        // on a map left only one way (5-2 → 5-3, back by the whole loop) its quests first, the giver after
        // (a jump to 5-3 means a whole loop back)
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
                        (long) c.target(), what(next.kind()), next.map() > 0 ? ctx.maps().info(next.map()).name() : "станции",
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
        if (stuck(c, now)) {
            return;
        }
        String progress = QuestTexts.condition(c) + (c.target() > 0 ? " " + orbit.world.Num.of(c.current(), c.target()) : "");
        if (work.kind() != Kind.SKYLAB && !ctx.stats().questOres.get().isEmpty()) {
            ctx.stats().questOres.set(Map.of());
        }
        checkWay(w, c, now);
        switch (work.kind()) {
            case KILL, DAMAGE, SALVAGE, BOX, ORE -> {
                if (notFound(w, now) || noCount(w, c) || killsNotCounted(c)) {
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
            case SLOTBAR -> {
                slotBar(w, c, now);
                ctx.nav().tick();
                status = "Квест " + named(work.quest()) + ": " + progress + " · кладу на панель";
            }
            case GIVER -> {
                // at once the first time; again for the same quest only after a while (a visit did not count it: an
                // earlier condition still open) — a visit for other reasons does not hold it back (live 2026-11-25: the
                // ship stood still for minutes after taking the quests)
                int quest = work.quest();
                status = "Квест " + named(quest) + ": " + progress + " · к квестодателю";
                if (visit == null && (giverFor != quest || now - giverAt > GIVER_GAP_NS)) {
                    giverFor = quest;
                    giverAt = now;
                    startVisit("квест " + named(quest) + " просит вернуться к квестодателю"); // clears the work
                }
            }
            case GATE -> {
                if (w.hero().mapId() == STARTER_GATE_MAP) {
                    work = null; // in the gate: chosen anew (its NPCs)
                    worker = null;
                    return;
                }
                // what the map shows, once per change: to learn how the starter gate is entered
                String seen = w.portals().values().stream().map(p -> p.id() + ":" + p.designId() + "@" + p.x() + "," + p.y())
                        .sorted().toList() + " активные " + w.activatable().keySet();
                if (!seen.equals(gateSeen)) {
                    gateSeen = seen;
                    log.info("Квест {}: стартовые врата — порталы на карте {}: {}", named(work.quest()),
                            ctx.maps().info(w.hero().mapId()).name(), seen);
                }
                if (Double.isNaN(work.x())) {
                    if (gateSince == 0) {
                        gateSince = now;
                    } else if (now - gateSince > GATE_WAIT_NS) {
                        log.warn("Квест {}: портала стартовых врат на карте нет — откладываю квест", named(work.quest()));
                        setAside.add(work.quest());
                        gateSince = 0;
                        work = null;
                        return;
                    }
                    ctx.nav().tick();
                    status = "Квест " + named(work.quest()) + ": " + progress + " · жду портал стартовых врат";
                    return;
                }
                gateSince = 0;
                double d = Math.hypot(w.hero().x() - work.x(), w.hero().y() - work.y());
                if (d > 150 && ctx.nav().idle()) {
                    ctx.nav().moveTo(work.x(), work.y(), false);
                }
                if (d < 400 && now - gateJumpAt > 5_000_000_000L) {
                    if (gatePresses >= GATE_PRESSES) {
                        log.warn("Квест {}: портал стартовых врат не пускает после {} нажатий — откладываю квест",
                                named(work.quest()), gatePresses);
                        setAside.add(work.quest());
                        gatePresses = 0;
                        work = null;
                        return;
                    }
                    gatePresses++;
                    gateJumpAt = now;
                    log.info("Квест {}: прыгаю в стартовые врата", named(work.quest()));
                    ctx.actions().jump();
                }
                ctx.nav().tick();
                status = "Квест " + named(work.quest()) + ": " + progress + " · лечу в стартовые врата";
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
     * gb2: Streuner's cargo on 3-1, the duranium collector quest 0/100 after dozens) — that map is left out, the next
     * one tried.
     */
    private boolean noCount(WorldSnapshot w, Quests.Condition c) {
        // a resource's NPCs come from the wiki's table: no guessing at other maps (needless, and a source of bugs)
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

    /**
     * «killNoCount» (the leveling configs): a kill condition that does not grow after {@link #KILL_TRIES} kills is set
     * aside for the session, its server fields logged — live 2026-11-25: 106008 «Saimon on 1-3» stood at 0/12 after 23
     * Saimon killed on 1-3 while another quest's same condition counted them; something hidden is asked.
     */
    private boolean killsNotCounted(Quests.Condition c) {
        if (work.kind() != Kind.KILL || !settings().path("killNoCount").asBoolean(false)) {
            killFrom = -1;
            return false;
        }
        // kills of the NPC the condition wants, not every kill (live 2026-11-26: st2 set 108004 aside after 15 kills of
        // others while its rare Boss Mordon had not come; st4 finished the same quest)
        int kills = wantedKills(work);
        // the server counts a kill for one quest only (live 2026-11-25: five Saimon went to 107005 while 107002 waited) —
        // another quest growing means the kills go there for now: no verdict on this one
        double others = othersProgress(work.quest());
        if (killFrom < 0 || c.current() != killAt || killQuest != work.quest() || others != killOthers) {
            killFrom = kills;
            killAt = c.current();
            killQuest = work.quest();
            killOthers = others;
            return false;
        }
        if (kills - killFrom < KILL_TRIES) {
            return false;
        }
        Quests.Quest quest = ctx.world().get().quests().active().get(work.quest());
        log.warn("Квест {}: убито {}, а «{}» не растёт ({}/{}) — откладываю до конца сессии; условия сервера: {}",
                named(work.quest()), kills - killFrom, QuestTexts.condition(c), (long) c.current(), (long) c.target(),
                quest == null ? "?" : quest.all().stream().map(x -> x.type() + ":" + x.matches()
                        + (x.mandatory() ? "" : "(необяз.)") + (x.active() ? "" : "(неакт.)")).toList());
        setAside.add(work.quest());
        killFrom = -1;
        if (worker != null) {
            worker.pause();
        }
        worker = null;
        work = null;
        return true;
    }

    private int stuckQuest;
    private int stuckCondition;
    private double stuckAt;
    private long stuckSince;
    private double stuckOthers;

    /**
     * {@code stuckMinutes} (the leveling quests configs; 0 = off, the default): a condition worked on that long with no
     * progress is set aside for the session — not cancelled — so that under «finishFirst» it does not hold back new quests
     * (live 2026-11-26: 108002 «collect 50 duranium» on 1-1/1-2, 0/50 for over ten minutes, terbium hardly there, and the
     * giver gave nothing new meanwhile).
     */
    private boolean stuck(Quests.Condition c, long now) {
        int minutes = settings().path("stuckMinutes").asInt(0);
        if (minutes <= 0 || work == null || ids("first").contains(work.quest())) {
            return false; // the quests marked First (the starter chain up to its premium) are never set aside
        }
        // another quest growing meanwhile is the same work counted there (the server credits a kill to one quest: live
        // 2026-11-26 st2's Boss Saimon went to 101001, 106002 stayed 0/2) — no verdict, the time starts again
        double others = othersProgress(work.quest());
        if (stuckQuest != work.quest() || stuckCondition != c.id() || stuckAt != c.current() || stuckOthers != others) {
            stuckQuest = work.quest();
            stuckCondition = c.id();
            stuckAt = c.current();
            stuckOthers = others;
            stuckSince = now;
            return false;
        }
        if (now - stuckSince < minutes * 60_000_000_000L) {
            return false;
        }
        log.warn("Квест {}: «{}» не сдвинулось за {} мин ({}/{}) — откладываю до конца сессии, новые квесты он не держит",
                named(work.quest()), QuestTexts.condition(c), minutes, (long) c.current(), (long) c.target());
        setAside.add(work.quest());
        stuckQuest = 0;
        if (worker != null) {
            worker.pause();
        }
        worker = null;
        work = null;
        return true;
    }

    /** Work left above which a quest does not hold back the next visit to the giver (101001: ~75 kills left). */
    static final double FINISH_FIRST_MAX = 60;

    /**
     * Taken quests that hold back new ones under {@code finishFirst}: missions and starter quests (not the passes' tasks, not
     * dailies) the bot can do, not set aside, with no more than {@link #FINISH_FIRST_MAX} left to do.
     */
    int blocking(WorldSnapshot w) {
        int n = 0;
        for (Quests.Quest q : w.quests().active().values()) {
            if (!(q.is(Quests.MISSION) || q.is(Quests.STARTER)) || setAside.contains(q.id()) || left(q.id())) {
                continue;
            }
            if (leftToDo(q) > FINISH_FIRST_MAX || !doable(q)) {
                continue;
            }
            n++;
        }
        return n;
    }

    /** The progress of every other active quest's conditions summed (a kill counted there shows here). */
    private double othersProgress(int quest) {
        double sum = 0;
        for (Quests.Quest q : ctx.world().get().quests().active().values()) {
            // missions and starter quests only: the passes' long tasks (700037 «pick 1 500 cargo boxes») grow beside any
            // work and kept the no-progress clock from ever running out (live 2026-11-26, st1 on 108002)
            if (q.id() == quest || !(q.is(Quests.MISSION) || q.is(Quests.STARTER))) {
                continue;
            }
            for (Quests.Condition x : q.leaves()) {
                if (fedByWork(x)) {
                    sum += x.current();
                }
            }
        }
        return sum;
    }

    /**
     * A condition the work itself feeds — kills, damage, ores, cargo, boxes — not what grows anyway while the ship flies
     * and shoots (live 2026-11-26: 700036 «fly 15 000 000» grew all the time, so the no-progress clock never ran out).
     */
    static boolean fedByWork(Quests.Condition x) {
        return QuestTargets.killing(x) || QuestTargets.damaging(x) || x.type() == Quests.COLLECT
                || x.type() == Quests.COLLECT_LOOT || x.type() == Quests.SALVAGE || x.type() == Quests.CARGO
                || x.type() == Quests.COLLECT_BONUS_BOX;
    }

    private double killOthers;

    /** The session's kills of the work's NPCs (by name, as the quest wants them; all kills when it names none). */
    private int wantedKills(Work wk) {
        Set<String> want = wk.want().isEmpty() ? Set.of() : wk.want();
        if (want.isEmpty()) {
            return ctx.stats().kills.get();
        }
        int n = 0;
        for (var e : ctx.stats().killedBy().entrySet()) {
            if (want.contains(QuestTargets.norm(e.getKey()))) {
                n += e.getValue();
            }
        }
        return n;
    }

    /** Kills of a wanted NPC with no progress before a kill condition is set aside. */
    static final int KILL_TRIES = 15;
    private long killFrom = -1;
    private double killAt;
    private int killQuest;
    /** Quests set aside for this session (their kills did not count). */
    private final Set<Integer> setAside = new java.util.HashSet<>();

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

    /** Quests marked Cancel: cancelled (the quest window's button, anywhere), one at a time, again if still there. */
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

    /** Neither taken nor worked on: marked Skip or Cancel. */
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
            case GIVER -> "лечу к квестодателю";
            case SLOTBAR -> "кладу на панель";
            case GATE -> "лечу в стартовые врата";
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
        // marked First go first, in the list's order; then by type, then by number; Skip/Cancel never
        List<Integer> first = ids("first");
        List<Quests.Quest> quests = w.quests().active().values().stream()
                .filter(x -> !left(x.id()) && !setAside.contains(x.id()))
                .sorted(Comparator.<Quests.Quest>comparingInt(x -> first.contains(x.id()) ? first.indexOf(x.id()) : first.size())
                        // «starterFirst» (the leveling configs): the tutorial chain before all else — its rewards are the
                        // first equipment; off by default
                        .thenComparingInt(x -> starterFirst() && x.is(Quests.STARTER) ? 0 : 1)
                        .thenComparingInt(this::typeRank)
                        // «highLevelFirst» (the leveling configs): of one type, the quest of the highest level first
                        .thenComparingInt(x -> highLevelFirst() ? -minLevel(w, x.id()) : 0)
                        // «easyFirst» (the leveling configs): of one type, the quest with the least left to do first
                        .thenComparingDouble(x -> easyFirst() ? leftToDo(x) : 0)
                        .thenComparingInt(Quests.Quest::id)).toList();
        for (Quests.Quest quest : quests) {
            for (Quests.Condition c : quest.leaves()) {
                if (c.completed() || QuestTargets.passive(c) || background(c)) {
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
        Work wk = workAnywhere(quest, c, w);
        // «ownMapsOnly» (the leveling configs): a new ship only on its company's maps — another company's map has its
        // stations and players shooting (live 2026-11-25 on int1: an MMO Liberator of level 1 sent to EIC's 2-2). The
        // companies' 1-x, 2-x, 3-x are alike: an NPC known only on another's x-N lives on the own x-N too (Aider Streuner,
        // seen on 2-2 by other accounts, was on 1-1 and 1-2 as well)
        if (wk != null && ownMapsOnly() && wk.map() > 0 && !ownMap(wk.map(), w.hero().factionId())) {
            int mirror = ownTwin(wk.map(), w.hero().factionId());
            if (mirror > 0) {
                wk = new Work(wk.quest(), wk.condition(), wk.kind(), mirror, wk.names(), wk.x(), wk.y(), wk.ore(), wk.want(),
                        wk.npc());
            }
        }
        if (wk != null && ownMapsOnly() && wk.map() > 0 && (!ownMap(wk.map(), w.hero().factionId())
                || orbit.bot.nav.Traveler.closed(wk.map(), w.hero().level()))) {
            return null; // also a map a portal would not let this level into
        }
        return wk;
    }

    /** A map of the hero's own company ({@code 1-x} for MMO, {@code 2-x} EIC, {@code 3-x} VRU). */
    private boolean ownMap(int map, int faction) {
        String name = ctx.maps().info(map).name();
        return name != null && name.matches("\\d-\\d+") && name.charAt(0) - '0' == faction;
    }

    /** The own company's map of the same number ({@code 2-2} → {@code 1-2} for MMO); {@code -1} none (4-x, BL, gates). */
    private int ownTwin(int map, int faction) {
        String name = ctx.maps().info(map).name();
        if (faction < 1 || faction > 3 || name == null || !name.matches("[123]-\\d+")) {
            return -1;
        }
        var twin = ctx.maps().byName(faction + name.substring(1));
        return twin == null ? -1 : twin.id();
    }

    /** When the item was last put on the bar for a «slot bar» condition (not again sooner than {@link #SLOTBAR_GAP_NS}). */
    private long slotBarAt = Long.MIN_VALUE / 2;
    static final long SLOTBAR_GAP_NS = 30_000_000_000L;

    /**
     * «Put an item in the slot bar»: the item ({@code [bar, slot, item]} or {@code [bar, item]}) dragged onto the bar's slot
     * the condition names, else its first empty slot, else its last one — as a player would; again after a while if the
     * condition did not count it.
     */
    private void slotBar(WorldSnapshot w, Quests.Condition c, long now) {
        if (now - slotBarAt < SLOTBAR_GAP_NS) {
            return;
        }
        List<String> m = c.matches();
        String bar = m.get(0);
        String item = m.size() > 2 ? m.get(2) : m.get(1);
        int slot = m.size() > 2 ? c.match(1) : -1;
        Map<Integer, String> slots = w.slotBars().getOrDefault(bar, Map.of());
        if (slot <= 0) {
            slot = emptySlot(slots);
        }
        slotBarAt = now;
        log.info("Квест {}: кладу {} на панель {} в ячейку {} (на панели: {})", named(work.quest()), item, bar, slot, slots);
        ctx.actions().slotBarPut(item, bar, slot);
    }

    /**
     * The first empty slot of a bar of {@link #BAR_SLOTS}: the server lists only the filled ones (live 2026-11-25: slots 1, 2,
     * 3, 9, 10 sent, 4–8 empty and absent), so a slot it does not list is empty; all full — the last.
     */
    static int emptySlot(Map<Integer, String> slots) {
        for (int i = 1; i <= BAR_SLOTS; i++) {
            String item = slots.get(i);
            if (item == null || item.isBlank()) {
                return i;
            }
        }
        return BAR_SLOTS;
    }

    static final int BAR_SLOTS = 10;

    /** {@code ownMapsOnly}: quests worked on the own company's maps only ({@code false} by default). */
    boolean ownMapsOnly() {
        return settings().path("ownMapsOnly").asBoolean(false);
    }

    private Work workAnywhere(Quests.Quest quest, Quests.Condition c, WorldSnapshot w) {
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
            avoidDanger(where.keySet(), now());
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
        if ((c.type() == Quests.SALVAGE || c.type() == Quests.CARGO) && doing.contains("SALVAGE")) {
            // «pick up cargo boxes» (Season Pass 700037, 1 500 of them) — the cargo of killed NPCs, as AstraBot's and
            // DmPlugin's season modules do it
            return salvageWork(quest, c, w);
        }
        if (c.type() == Quests.COLLECT_BONUS_BOX && doing.contains("BOX")) {
            int map = ctx.config().get().general().workingMap();
            return new Work(quest.id(), c.id(), Kind.BOX, map > 0 ? map : h.mapId(), Set.of(BONUS_BOX), Double.NaN,
                    Double.NaN, -1);
        }
        if (c.type() == Quests.COLLECT_LOOT && doing.contains("ORE") && oreAsLoot()) {
            // an ore named as loot («resource_ore_prometium», the starter quest 20003, live 2026-11-25 on int1): collected as
            // ore — only with «oreAsLoot» (the leveling configs)
            int ore = lootOre(c);
            if (ore >= 0) {
                return RECIPES.containsKey(ore) ? refinedWork(quest, c, ore, ore, w) : oreWork(quest, c, ore, w);
            }
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
            // «sellAtOnce» (the leveling configs): what is left to sell gathered first, then sold in one trip (live
            // 2026-11-26 on int1: 1 prometium aboard for «sell 10» — picked and sold one by one, the second sale taken for
            // a failed one as the next piece landed, five minutes lost each time)
            double left = c.target() - c.current();
            if (ore >= 0 && sellAtOnce() && doing.contains("ORE") && w.cargoFree() > 0
                    && w.inventory().ores().getOrDefault(ore, 0.0) < left) {
                return oreWork(quest, c, ore, w);
            }
            if (aboard(w, ore)) {
                return new Work(quest.id(), c.id(), Kind.SELL, 0, Set.of(), Double.NaN, Double.NaN, ore);
            }
            // nothing of it aboard: collect it first (any ore: the cheapest common one, prometium)
            return doing.contains("ORE") ? oreWork(quest, c, ore >= 0 ? ore : 0, w) : null;
        }
        if (c.type() == Quests.VISIT_QUEST_GIVER && doing.contains("GIVER")) {
            // «back to the quest giver» (the starter chain 20000+, live 2026-11-25 on int1: 20000 counted once the ship
            // reached the giver) — only with GIVER listed in «do» (the leveling configs)
            return new Work(quest.id(), c.id(), Kind.GIVER, h.mapId(), Set.of(), Double.NaN, Double.NaN, -1);
        }
        if (c.type() == Quests.PUT_ITEM_IN_SLOT_BAR && doing.contains("SLOTBAR") && c.matches().size() >= 2) {
            // «put an item in the slot bar» (the starter quest 20006: [standardSlotBar, ammunition_laser_mcb-50]) — only
            // with SLOTBAR listed in «do» (the leveling configs)
            return new Work(quest.id(), c.id(), Kind.SLOTBAR, h.mapId(), Set.of(), Double.NaN, Double.NaN, -1);
        }
        if (c.type() == Quests.FINISH_STARTER_GATE && doing.contains("STARTERGATE") && h.factionId() >= 1
                && h.factionId() <= 3) {
            // «finish the starter gate» (the starter quest 20012, 14 days of premium; live 2026-11-26 on int1): the map
            // «Devolarium Attack» (223) — there every NPC in sight is fought; else the company's x-1, where a portal the
            // of type 34 is the gate's (flown to and jumped in, Kind.GATE) — only with STARTERGATE in «do»
            if (h.mapId() == STARTER_GATE_MAP) {
                Set<String> npcs = new java.util.TreeSet<>();
                w.ships().values().stream().filter(WorldSnapshot.Ship::npc).forEach(x -> npcs.add(x.name()));
                return new Work(quest.id(), c.id(), Kind.KILL, h.mapId(), npcs.isEmpty() ? Set.of("Devolarium") : npcs,
                        Double.NaN, Double.NaN, -1);
            }
            int home = HOME_MAPS[h.factionId()];
            WorldSnapshot.Portal gate = h.mapId() == home ? starterGatePortal(w) : null;
            return new Work(quest.id(), c.id(), Kind.GATE, home, Set.of(), gate == null ? Double.NaN : gate.x(),
                    gate == null ? Double.NaN : gate.y(), -1);
        }
        if (c.type() == Quests.VISIT_MAP_ASSET && doing.contains("VISITASSET") && h.factionId() >= 1 && h.factionId() <= 3) {
            // «visit a map object» (the starter quest 20008, live 2026-11-26 on int1: 75:[48] = the home station's hangar):
            // the company's x-1, there the object of that type, else the station — only with VISITASSET listed in «do»
            int home = HOME_MAPS[h.factionId()];
            if (h.mapId() != home) {
                return new Work(quest.id(), c.id(), Kind.MAP, home, Set.of(), Double.NaN, Double.NaN, -1);
            }
            int type = (int) c.match(0);
            WorldSnapshot.Asset a = w.assets().values().stream().filter(x -> x.type() == type).findFirst()
                    .or(() -> w.assets().values().stream().filter(x -> STATION_ASSETS.contains(x.type()))
                            .min(java.util.Comparator.comparingDouble(x -> Math.hypot(x.x() - h.x(), x.y() - h.y()))))
                    .orElse(null);
            return a == null ? null : new Work(quest.id(), c.id(), Kind.MAP, home, Set.of(), a.x(), a.y(), -1);
        }
        if (c.type() == Quests.VISIT_MAP_TYPE && doing.contains("VISITMAPS")) {
            // «visit maps» (the starter quest 20006, live 2026-11-25 on int1): the nearest of the maps it names — only
            // with VISITMAPS listed in «do» (the leveling configs)
            List<Integer> maps = new java.util.ArrayList<>(QuestTargets.mapsOf(c));
            if (maps.size() > 1 && c.current() > 0) {
                maps.remove(Integer.valueOf(h.mapId())); // one of them done here: on to another
            }
            Integer best = nearest(maps, h);
            return best == null ? null : new Work(quest.id(), c.id(), Kind.MAP, best, Set.of(), Double.NaN, Double.NaN, -1);
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
        // no NPC named anywhere (300011, the duranium collector): the plain NPCs of the company's own maps, nearest —
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
     * Pirate NPCs with no map in the quest: 5-2 (more chance of a group there, the quest closes
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
     * Collecting resource_collectable_…: the NPCs that carry it ({@link #LOOT_NPCS}) killed and their cargo picked, on
     * the nearest map they live on ({@link NpcMaps}; the quest's own maps if it binds them).
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
        avoidDanger(where.keySet(), now());
        Integer best = nearest(where.keySet(), w.hero());
        return best == null ? null : new Work(quest.id(), c.id(), Kind.SALVAGE, best, Set.copyOf(where.get(best)),
                Double.NaN, Double.NaN, LOOT, want, 0);
    }

    /**
     * Refined ore a quest wants collected (prometid, duranium, promerium): refined in the hold from what is aboard; else
     * brought from the Skylab (its transport on); else the raw ore it is made of collected first (there is no purchase,
     * only the Skylab or refining, and any of them must do). A way the counter does not move on
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
        // «Endurium 30 000 on enemy x-4» (2026-11-17): only where the quest counts it — there even when the
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

    /** The starter gate's map («Devolarium Attack» in the client's maps-config). */
    static final int STARTER_GATE_MAP = 223;
    /** The starter gate portal's type (its design id). */
    static final int STARTER_GATE_PORTAL = 34;
    /** Jump presses at the starter gate's portal with no jump: then the quest is set aside. */
    static final int GATE_PRESSES = 6;
    private int gatePresses;
    /** Waiting on x-1 for the starter gate's portal: longer and the quest is set aside. */
    static final long GATE_WAIT_NS = 3 * 60_000_000_000L;
    private long gateSince;
    private long gateJumpAt;
    private String gateSeen = "";

    /**
     * The starter gate's portal: of type {@link #STARTER_GATE_PORTAL} (DarkBot's StarManager: «Devolarium Attack» is reached
     * only by portal type 34 from the home maps; live 2026-11-26 on 1-1 at 12500/8500 among the galaxy gates' portals).
     */
    private WorldSnapshot.Portal starterGatePortal(WorldSnapshot w) {
        return w.portals().values().stream().filter(p -> p.designId() == STARTER_GATE_PORTAL).findFirst().orElse(null);
    }

    /** No NPC in sight in the starter gate this long: it is over, out by its portal. */
    static final long GATE_EMPTY_NS = 20_000_000_000L;
    private long gateEmptySince;
    /** The gate's fight: every NPC name seen in it so far. */
    private volatile Work gateFight;

    /**
     * In the starter gate («Devolarium Attack», the leveling quests configs): nothing leads from it to another map, so no
     * work elsewhere and no quest giver (live 2026-11-26: the runner found no path to 1-1 and stopped the bot, twice). Every
     * NPC in sight is fought; none for {@link #GATE_EMPTY_NS} — to the map's portal and jump (out).
     */
    private void inStarterGate(WorldSnapshot w, long now) {
        visit = null;
        Set<String> npcs = new java.util.TreeSet<>();
        w.ships().values().stream().filter(x -> x.npc() && x.name() != null).forEach(x -> npcs.add(x.name()));
        if (!npcs.isEmpty()) {
            gateEmptySince = 0;
            // one fighter for the whole gate; the NPCs it may fight grow with each wave (live 2026-11-26: a new fighter for
            // every new name broke off fights with the target at 1-5 %)
            Set<String> all = new java.util.TreeSet<>(gateFight == null ? Set.of() : gateFight.names());
            boolean grew = all.addAll(npcs);
            if (gateFight == null || grew) {
                gateFight = new Work(0, 0, Kind.KILL, STARTER_GATE_MAP, Set.copyOf(all), Double.NaN, Double.NaN, -1);
                log.info("Стартовые врата: бью {}", npcs);
            }
            work = gateFight;
            if (worker == null) {
                worker = new KillModule(new BotContext(ctx.world(), () -> questConfig(ctx.config().get(), gateFight,
                        Map.of(), Set.of()), ctx.actions(), ctx.nav(), ctx.traveler(), ctx.maps(), ctx.random(), ctx.clock(),
                        ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(), ctx.ships()), true, true);
            }
            worker.tick();
            status = "Стартовые врата: бой · " + worker.status();
            return;
        }
        if (gateEmptySince == 0) {
            gateEmptySince = now;
        }
        if (worker != null) {
            worker.pause();
            worker = null;
        }
        work = new Work(0, 0, Kind.GATE, STARTER_GATE_MAP, Set.of(), Double.NaN, Double.NaN, -1);
        if (now - gateEmptySince < GATE_EMPTY_NS) {
            ctx.nav().tick();
            status = "Стартовые врата: жду NPC";
            return;
        }
        WorldSnapshot.Portal out = w.portals().values().stream()
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - w.hero().x(), p.y() - w.hero().y())))
                .orElse(null);
        if (out == null) {
            ctx.nav().tick();
            status = "Стартовые врата: NPC нет, портала нет — жду";
            if (now - gateEmptySince > 6 * GATE_EMPTY_NS) {
                tell("Стартовые врата: NPC нет, портала выхода не видно");
            }
            return;
        }
        double d = Math.hypot(w.hero().x() - out.x(), w.hero().y() - out.y());
        if (d > 150 && ctx.nav().idle()) {
            ctx.nav().moveTo(out.x(), out.y(), false);
        }
        if (d < 400 && now - gateJumpAt > 5_000_000_000L) {
            gateJumpAt = now;
            log.info("Стартовые врата: NPC нет — выхожу через портал ({}/{})", out.x() / 100, out.y() / 100);
            ctx.actions().jump();
        }
        ctx.nav().tick();
        status = "Стартовые врата: выхожу";
    }

    /** Maps where a player killed the ship → until when work there is avoided ({@code playerDeathAvoidMinutes}). */
    // shared by the accounts of one program (one company's maps): a player camping 1-3 killed st2 and a minute later st3
    // (live 2026-11-26 07:29–07:30)
    // wall-clock millis, kept in local/danger-maps.properties so a restart does not forget them (07:36: right after a
    // restart st2 flew back to 1-3 and the same player killed it again)
    private static final Map<Integer, Long> dangerUntil = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.nio.file.Path DANGER_FILE = java.nio.file.Path.of("local", "danger-maps.properties");
    private static volatile boolean dangerLoaded;

    private static synchronized void loadDanger() {
        if (dangerLoaded) {
            return;
        }
        dangerLoaded = true;
        try {
            if (java.nio.file.Files.exists(DANGER_FILE)) {
                java.util.Properties p = new java.util.Properties();
                try (var in = java.nio.file.Files.newInputStream(DANGER_FILE)) {
                    p.load(in);
                }
                p.forEach((k, v) -> dangerUntil.put(Integer.parseInt(k.toString()), Long.parseLong(v.toString())));
            }
        } catch (Exception e) {
            // no marks, no harm
        }
    }

    private static synchronized void saveDanger() {
        try {
            java.util.Properties p = new java.util.Properties();
            dangerUntil.forEach((k, v) -> p.setProperty(String.valueOf(k), String.valueOf(v)));
            java.nio.file.Files.createDirectories(DANGER_FILE.getParent());
            try (var out = java.nio.file.Files.newOutputStream(DANGER_FILE)) {
                p.store(out, "maps where a ship died: until (epoch ms)");
            }
        } catch (Exception e) {
            // told nowhere: the marks live on in memory
        }
    }
    private int aliveMap;
    private int playerDeaths = -1;

    private long now() {
        return ctx.now();
    }

    /**
     * {@code playerDeathAvoidMinutes} (the leveling quests configs; 0 = off, the default): the map where the ship died (a
     * player's kill mostly) is left out of the work's maps that long while another map has the same work (live 2026-11-26 on int1: st1 was
     * killed by players twice in three minutes on 1-4, and the portal revive put it back there).
     */
    private void notePlayerDeath(WorldSnapshot w, long now) {
        int minutes = settings().path("playerDeathAvoidMinutes").asInt(0);
        // the session's deaths (not killedBy: those are the NPCs the ship killed — live 2026-11-26 04:27 it fired on a kill)
        int deaths = ctx.stats().deaths.get();
        // the map of the death is the last one this module saw the ship alive on before it: checked before taking the
        // current map (live 2026-11-26: st5 died on 1-3, fled through 2-3 after the revive, and 2-3 was taken)
        if (playerDeaths >= 0 && deaths > playerDeaths && minutes > 0 && aliveMap > 0) {
            loadDanger();
            dangerUntil.put(aliveMap, System.currentTimeMillis() + minutes * 60_000L);
            saveDanger();
            log.warn("Квесты: на {} корабль погиб — {} мин беру работу на других картах, где она есть",
                    ctx.maps().info(aliveMap).name(), minutes);
            work = null;
            worker = null;
        }
        playerDeaths = deaths;
        if (w.hero().alive() && w.hero().mapId() > 0) {
            aliveMap = w.hero().mapId();
        }
    }

    /**
     * Leaves out the maps a ship died on lately; when only such maps have the work, none — the quest waits and others are
     * done meanwhile (07:36: 108004 wants Boss Mordon on 1-3 only, where a player was camping).
     */
    private void avoidDanger(Set<Integer> maps, long now) {
        if (settings().path("playerDeathAvoidMinutes").asInt(0) <= 0) {
            return;
        }
        loadDanger();
        long wall = System.currentTimeMillis();
        dangerUntil.values().removeIf(t -> t < wall);
        if (!dangerUntil.isEmpty()) {
            maps.removeIf(dangerUntil::containsKey);
        }
    }

    /** The companies' x-1 by faction (1 MMO 1-1, 2 EIC 2-1, 3 VRU 3-1). */
    static final int[] HOME_MAPS = {0, 1, 5, 9};
    /** The home station's parts ({@code AssetTypeModule}: 46 company HQ, 48 hangar, 50 ore trade, 53 instant repair). */
    static final Set<Integer> STATION_ASSETS = Set.of(46, 48, 50, 53);

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

    /**
     * Done beside the work, not as one: conditions that grow by themselves ({@link QuestTargets#grows}) and crafting
     * ({@link QuestCrafts}, built and collected by {@code CraftWatch} while the ship works on).
     */
    static boolean background(Quests.Condition c) {
        return QuestTargets.grows(c) || c.type() == Quests.COLLECT_BUILT_RECIPE;
    }

    /** A quest the bot can do: each of its open required conditions is one it does, or a limit it keeps. */
    public boolean doable(Quests.Quest quest) {
        if (left(quest.id())) {
            return false;
        }
        WorldSnapshot w = ctx.world().get();
        int work = 0;
        for (Quests.Condition c : quest.leaves()) {
            if (c.completed() || QuestTargets.passive(c) || QuestTargets.grows(c)) {
                continue;
            }
            if (c.type() == Quests.COLLECT_BUILT_RECIPE) {
                // crafted beside the other work (CraftWatch); none can be built now — the bot cannot do it
                if (QuestCrafts.possible(ctx.config().get(), w, c)) {
                    work++;
                } else if (c.mandatory()) {
                    return false;
                }
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
     * Gygerthrall, which carry the Hybrid Alloy (the second ones come only while Blighted Kristallon is fought).
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
            if (o.type() == Quests.CARGO) {
                out.addAll(CARGO_BOXES);
            } else if (o.type() == Quests.SALVAGE) {
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
                out = out.withCollect(k.withBoxes(rules));
            }
        } else if (wk.kind() == Kind.BOX || wk.kind() == Kind.ORE) {
            BotConfig.Collect k = c.collect();
            Map<String, BotConfig.BoxRule> picks = new LinkedHashMap<>();
            for (String n : wk.names()) {
                BotConfig.BoxRule own = k.boxes().get(n);
                picks.put(n, new BotConfig.BoxRule(true, own != null ? own.waitMs() : n.startsWith("ore_") ? 800 : 0,
                        own != null ? own.priority() : 0));
            }
            out = out.withNpcs(Map.of()).withCollect(k.withBoxes(picks));
        }
        BotConfig.General g = c.general();
        com.fasterxml.jackson.databind.JsonNode enrich = out.modules().get("enrich");
        if (wk.kind() == Kind.SALVAGE && enrich != null && enrich.path("cargoWhenLow").asBoolean(false)) {
            // a quest that counts cargo picks it whatever the drives need
            out = out.withModuleSettings("enrich", ((com.fasterxml.jackson.databind.node.ObjectNode) enrich.deepCopy())
                    .put("cargoWhenLow", false));
        }
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

    /** The quest types to take ({@code types}); the chosen four by default. */
    Set<Integer> types() {
        Set<Integer> out = new TreeSet<>();
        for (String t : strings("types", List.of("DAILY", "WEEKLY", "EVENT", "MISSION"))) {
            switch (t.toUpperCase(java.util.Locale.ROOT)) {
                case "MISSION" -> out.add(Quests.MISSION);
                case "STARTER" -> out.add(Quests.STARTER); // the tutorial (docs/LEVELING.md «Квесты»), off unless listed
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

    /** The work order of a quest's type: {@code typeOrder} of the config when it has one, else {@link #ORDER}. */
    private int typeRank(Quests.Quest q) {
        List<String> names = strings("typeOrder", List.of());
        if (names.isEmpty()) {
            return rank(q);
        }
        for (int i = 0; i < names.size(); i++) {
            Integer t = TYPE_NAMES.get(names.get(i).toUpperCase(java.util.Locale.ROOT));
            if (t != null && q.is(t)) {
                return i;
            }
        }
        return names.size();
    }

    static final Map<String, Integer> TYPE_NAMES = Map.of("STARTER", Quests.STARTER, "MISSION", Quests.MISSION,
            "DAILY", Quests.DAILY, "WEEKLY", Quests.WEEKLY, "EVENT", Quests.EVENT, "CHALLENGE", Quests.CHALLENGE);

    /** What a quest still asks: the open amounts of its required conditions (kills, boxes…) summed. */
    static double leftToDo(Quests.Quest q) {
        double n = 0;
        for (Quests.Condition c : q.leaves()) {
            if (!c.completed() && c.mandatory() && c.target() > 0) {
                n += Math.max(0, c.target() - c.current());
            }
        }
        return n;
    }

    /** The ore types ({@code OreTypeModule}) by the loot id's name: {@code resource_ore_<name>}. */
    static final Map<String, Integer> ORE_LOOT = Map.of("prometium", 0, "endurium", 1, "terbium", 2, "xenomit", 3,
            "prometid", 4, "duranium", 5, "promerium", 6, "seprom", 7, "palladium", 8);

    /** The ore a «collect loot» condition names ({@code resource_ore_prometium} → 0); {@code -1} not an ore. */
    static int lootOre(Quests.Condition c) {
        String id = c.matches().isEmpty() ? "" : c.matches().getFirst().toLowerCase(java.util.Locale.ROOT).strip();
        return id.startsWith("resource_ore_") ? ORE_LOOT.getOrDefault(id.substring("resource_ore_".length()), -1) : -1;
    }

    /** {@code oreAsLoot}: an ore named as loot is collected as ore ({@code false} by default). */
    boolean oreAsLoot() {
        return settings().path("oreAsLoot").asBoolean(false);
    }

    /** {@code highLevelFirst}: of one type, the quest of the highest level first ({@code false} by default). */
    boolean highLevelFirst() {
        return settings().path("highLevelFirst").asBoolean(false);
    }

    /** A quest's level: the giver's offer of it, else the quest list's ({@code QuestCatalog}), else 0. */
    static int minLevel(WorldSnapshot w, int questId) {
        for (Quests.Offer o : w.quests().offers()) {
            if (o.questId() == questId) {
                return o.minLevel();
            }
        }
        var e = orbit.world.QuestCatalog.shared().all().get(questId);
        return e == null ? 0 : e.minLevel();
    }

    /** {@code easyFirst}: of one type, the quest with the least left first ({@code false} by default). */
    /** {@code sellAtOnce}: a «sell ore» condition's whole amount gathered before the sale ({@code false} by default). */
    boolean sellAtOnce() {
        return settings().path("sellAtOnce").asBoolean(false);
    }

    boolean easyFirst() {
        return settings().path("easyFirst").asBoolean(false);
    }

    /** {@code starterFirst}: the starter quests before every other ({@code false} by default). */
    boolean starterFirst() {
        return settings().path("starterFirst").asBoolean(false);
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
