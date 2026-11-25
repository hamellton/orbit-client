package orbit.bot.run;

import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import orbit.bot.nav.Traveler;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the config's module: in order of precedence each tick —
 * <ol>
 *   <li>wait until in game;</li>
 *   <li>the kill screen and the pause after reviving ({@link Revive});</li>
 *   <li>stopping: to the nearest portal, log out ({@link #stop});</li>
 *   <li>orders given on the map ({@link #order}) and the pause ({@link #setPaused});</li>
 *   <li>running from players ({@link Flee});</li>
 *   <li>safety: healing, repair at a portal, escape ({@link Safety});</li>
 *   <li>travel to the module's map;</li>
 *   <li>the module.</li>
 * </ol>
 * Call {@link #tick} about ten times a second from one thread.
 */
public final class BotRunner {

    private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

    public enum State { STARTING, DEAD, MANUAL, PAUSED, FLEEING, REPAIRING, TRAVELLING, WORKING, STOPPING, STOPPED, FAILED }

    /** An order given on the map: fly to a point, or jump through the portal to {@code map}. */
    public record Order(Kind kind, double x, double y, int map) {

        public enum Kind { MOVE, JUMP }

        public static Order move(double x, double y) {
            return new Order(Kind.MOVE, x, y, 0);
        }

        public static Order jump(int map) {
            return new Order(Kind.JUMP, 0, 0, map);
        }
    }

    /**
     * After an order is done the module waits this long before it takes the ship back — the account's setting
     * ({@code clickHoldMs} in {@code local/accounts.json}, 1 second by default; DarkBot waits 5 s).
     */
    static final long HOLD_MS = 1_000;
    private volatile long holdMs = HOLD_MS;

    /** The account's wait after a click on the map, ms. */
    public void setClickHold(long ms) {
        holdMs = Math.max(0, ms);
    }
    /** An order not done in this time is given up. */
    static final long ORDER_LIMIT_MS = 3 * 60_000L;

    private final java.util.concurrent.atomic.AtomicReference<Order> pending = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean paused;
    private boolean pauseShown;
    private Order current;
    /** The website hangar in use ({@code 0} not known), and the one the module asked to switch to. */
    private volatile int activeHangar;
    private volatile int hangarRequest;
    /** A ship asked for from outside the module (the leveling mode's move to a better ship): its hangar, 0 none. */
    private volatile int shipWanted;

    /**
     * Asks for the ship in {@code hangarId} (the leveling mode, docs/LEVELING.md «Пересадка»): once the module is not
     * busy, the bot logs out, the app switches the ship on the site and logs in again — the palladium's own way.
     */
    public void requestShip(int hangarId) {
        shipWanted = hangarId;
    }
    private boolean logoutHere;
    /** The start ship was looked at this session (once). */
    private boolean startChecked;
    /** Honor by the reward log and the pilot's total at the last tick ({@code NaN} before the first). */
    private double honorGained = Double.NaN;
    private double honorTotal = Double.NaN;
    /** Minutes out of the game the app keeps after this session, asked by a lost honor ({@code 0} none). */
    private volatile int honorBreak;
    /** The game's box check is up: everything stopped until it is gone. */
    private boolean inBoxCheck;
    private long orderAt;
    private long holdUntil;

    private final BotContext ctx;
    private final BooleanSupplier loggedOut;
    private final Consumer<String> statusSink;
    private final Safety safety;
    private final Revive revive;
    private final BuyWatch buy;
    private final CraftWatch craft;
    private final DispatchCare dispatch;
    private final orbit.bot.mod.QuestsAlongside alongside;
    private final CloakCare cloak;
    private final Flee flee;
    private final GroupWatch groupWatch;
    private final PetCare pet;
    private final Enricher enricher;
    /** Raw ore refined towards promerium for the drive enrichment (modules.enrich.refine; the leveling configs). */
    private final Refiner refiner;
    private final SkylabCare skylab;
    private final ServiceVisit service;
    private final CargoCare cargo;
    private final orbit.bot.nav.CpuJumper jumper;
    private final BoxCheckSolver boxCheck;
    private final Offenders offenders;
    private int offendersSeen;
    private boolean avoiding;
    private BotModule module;
    private boolean rushTold;

    /** The module at work, {@code null} before the first tick. */
    public BotModule module() {
        return module;
    }
    private BotConfig.Module moduleKind;
    private State state = State.STARTING;
    private String status = "Вход в игру";
    private String stopReason;
    private int travelTarget;
    /** Where the ship last stood still from, and since when (the idle nudge). */
    private int stillX = Integer.MIN_VALUE;
    private int stillY;
    private long stillSince;
    private long stillLimit; // nanos
    /** Standing this long without a move (random within, not a fixed beat) and the ship steps aside a little. */
    static final long STILL_MIN_MS = 150_000;
    static final long STILL_MAX_MS = 240_000;
    /** Moved less than this — still standing. */
    static final double STILL_RADIUS = 40;
    private long logoutAt;
    private long logoutPressedAt = Long.MIN_VALUE / 2;
    /** The game's logout countdown of the last press: 20 s, 5 s with premium. */
    private long logoutSeconds = 20;
    private String lastStatus = "";

    /**
     * @param loggedOut true once the server ended the session after a logout ({@code 0|l})
     * @param statusSink receives the status line when it changes (window, log)
     */
    public BotRunner(BotContext ctx, BooleanSupplier loggedOut, Consumer<String> statusSink) {
        this.ctx = ctx;
        this.loggedOut = loggedOut;
        this.statusSink = statusSink;
        this.safety = new Safety(ctx);
        safety.gateRepair(() -> module != null && module.repairTick());
        safety.earlyRepair(() -> module == null || module.earlyRepair());
        safety.gateTargetHp(() -> module != null ? module.targetHp() : -1);
        this.revive = new Revive(ctx);
        this.buy = new BuyWatch(ctx);
        this.craft = new CraftWatch(ctx);
        this.dispatch = new DispatchCare(ctx);
        this.alongside = new orbit.bot.mod.QuestsAlongside(ctx);
        this.cloak = new CloakCare(ctx);
        this.flee = new Flee(ctx);
        this.groupWatch = new GroupWatch(ctx);
        this.pet = new PetCare(ctx);
        this.enricher = new Enricher(ctx);
        this.refiner = new Refiner(ctx);
        this.skylab = new SkylabCare(ctx, enricher::missing);
        this.service = new ServiceVisit(ctx);
        pet.setModuleLocator(() -> module == null ? 0 : module.petLocator());
        pet.setModuleGear(() -> module == null ? -1 : module.petGear());
        pet.setModuleFight(() -> module == null ? 0 : module.fightTarget());
        pet.setModuleOut(() -> module == null ? null : module.petOut());
        this.cargo = new CargoCare(ctx, pet);
        this.boxCheck = new BoxCheckSolver(ctx);
        this.offenders = new Offenders(ctx);
        ctx.nav().setAvoidedPlayers(() -> {
            if (module != null && module.holdsGround()) {
                // to the behemoth or fighting it in the train mode: the train round it is no detour (live 2026-11-14 on
                // the VPS: the way to it went round the offenders' circles, the ship off to the far side)
                return module.avoidCircles();
            }
            List<double[]> own = module == null ? List.of() : module.avoidCircles();
            if (own.isEmpty()) {
                return offenders.circles();
            }
            List<double[]> all = new java.util.ArrayList<>(offenders.circles());
            all.addAll(own);
            return all;
        });
        this.jumper = new orbit.bot.nav.CpuJumper(ctx.world(), ctx.actions(), ctx.maps(), ctx.clock(), ctx.random(),
                ctx.dataDir() == null ? null : ctx.dataDir().resolve("jump-uridium.json"));
        loadModule();
    }

    /** A quiet moment for a planned new login: working (not dead, fleeing, repairing, travelling), the module calm. */
    public boolean calm() {
        BotModule m = module;
        return state == State.WORKING && !paused && !offline && current == null && m != null && m.calm();
    }

    public State state() {
        return state;
    }

    public String status() {
        return status;
    }

    public int deaths() {
        return revive.deaths();
    }

    /**
     * This runner goes on with {@code previous}'s session after a ship change (logout, the site's hangar switch,
     * login): the deaths towards the limit and the players who attacked the ship are kept.
     */
    public void carryOver(BotRunner previous) {
        revive.takeFrom(previous.revive);
        offenders.takeFrom(previous.offenders);
        service.takeFrom(previous.service);
    }

    /** Circles round the players who attacked the ship, kept clear now (the window's map); any thread. */
    public java.util.List<Offenders.Circle> avoidedPlayers() {
        return offenders.shown();
    }

    /**
     * The config changed (switched or edited): a different main module replaces the running one; the
     * same module drops its plans so the new settings apply at once.
     */
    public void configChanged() {
        // the same module on the same map goes on with its fight: it reads the config each tick (live 2026-11-13: a
        // saved config dropped an Impulse II being fought)
        int map = ctx.config().get().general().workingMap();
        if (module != null && (ctx.config().get().module() != moduleKind || map != workingMap)) {
            module.pause();
        }
        workingMap = map;
        if (ctx.config().get().module() != moduleKind) {
            ctx.nav().stop();
            loadModule();
        }
    }

    private void loadModule() {
        moduleKind = ctx.config().get().module();
        // the module sees the quests' extras when quests go alongside it (cargo boxes for a cargo condition)
        module = Modules.create(moduleKind, new BotContext(ctx.world(), () -> alongside.adjust(ctx.config().get()),
                ctx.actions(), ctx.nav(), ctx.traveler(), ctx.maps(), ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(),
                ctx.dataDir(), ctx.stats(), ctx.ships()));
        if (module != null && refresh != null) {
            module.useRefresh(refresh);
        }
        if (module == null) {
            log.error("Модуль {} пока не поддерживается", moduleKind);
        } else {
            log.info("Модуль: {}", moduleKind);
        }
    }

    /** The config's working map when it was last read. */
    private int workingMap = Integer.MIN_VALUE;

    private java.util.function.BooleanSupplier linkLost = () -> false;
    private boolean offline;

    /**
     * True while the connection is lost and being restored: the world stands still then, so the bot does
     * nothing until the ship is back in game (map changes are not a lost link).
     */
    public void setLinkLost(java.util.function.BooleanSupplier lost) {
        linkLost = lost;
    }

    /** The account's equipment on the site, for a new cloaking CPU (none = not fitted by the bot). */
    public void setHangar(HangarAccess hangar) {
        cloak.setHangar(hangar);
    }

    /** The account's Skylab on the website, for ore into the hold ({@code null}: none). */
    public void setSkylab(SkylabAccess access) {
        skylab.setAccess(access);
        service.setSkylab(access);
    }

    /** A new login of the game connection (the game reads a new CPU at a login). */
    public void setRefresh(Runnable refresh) {
        this.refresh = refresh;
        cloak.setRefresh(refresh);
        if (module != null) {
            module.useRefresh(refresh);
        }
    }

    private Runnable refresh;

    /** The account's shop for the buy list (none = the bot only says when to buy). */
    public void setShop(ShopAccess shop) {
        buy.setShop(shop);
    }

    /** An order from the map; any thread. The latest one replaces an earlier one not done yet. */
    public void order(Order o) {
        pending.set(o);
    }

    /** The website hangar in use, as the app read it; any thread. */
    public void setActiveHangar(int id) {
        activeHangar = id;
    }

    /** The config's death limit ({@code -1} or 0: none), for the map's stats. */
    public int maxDeaths() {
        return ctx.config().get().safety().maxDeaths();
    }

    /** The module running ({@code null} before the first tick). */
    public orbit.config.BotConfig.Module moduleKind() {
        return moduleKind;
    }

    /** The module must be free this long for a service visit (two logins and the Skylab's transport). */
    static final long SERVICE_FREE_MS = 10 * 60_000;

    /** The hangar the module asked for: the app switches to it after this session ({@code 0} none). */
    public int hangarRequest() {
        return hangarRequest;
    }

    /** Minutes of break after this session for honor lost ({@code 0} none). */
    public int honorBreak() {
        return honorBreak;
    }

    /**
     * Honor lost for a kill (DarkBot's {@code StatsManager.checkHonor}: lost in one go — logs out and stops; here a
     * kill with minus honor means a 15 min break): the game's reward log
     * ({@code 0|LM|ST|HON|<gained>|<total>}) with a minus, the pilot's total down with it — out of the game for
     * {@code honorBreakMinutes}. DarkBot reads the total from memory and counts only 100·2ⁿ as a kill's; the reward
     * line is the server's own word for it, any minus counts.
     */
    private void watchHonor(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0) {
            return;
        }
        double gained = w.rewards().honor();
        double total = h.honor();
        double lostGained = Double.isNaN(honorGained) ? 0 : honorGained - gained;
        double lostTotal = Double.isNaN(honorTotal) ? 0 : honorTotal - total;
        honorGained = gained;
        honorTotal = total;
        if (lostGained <= 0 || lostTotal <= 0 || honorBreak > 0) {
            return;
        }
        int minutes = ctx.config().get().safety().honorBreakMinutes();
        String lost = String.format("%,.0f", lostGained).replace(',', ' ');
        if (minutes <= 0) {
            log.warn("Минус {} чести — перерыв за это выключен, продолжаю", lost);
            return;
        }
        log.warn("Минус {} чести за убийство — выхожу из игры, перерыв {} мин", lost, minutes);
        honorBreak = minutes;
        stop("минус честь, перерыв " + minutes + " мин");
    }

    /** Paused: the module rests, the ship does only what it is ordered on the map; any thread. */
    public void setPaused(boolean on) {
        paused = on;
    }

    public boolean paused() {
        return paused;
    }

    private volatile boolean finished;

    /** The module's work is over ({@link BotModule#stopRequest}): the account is not logged in again. */
    public boolean finished() {
        return finished;
    }

    private volatile BotModule.Rest rest;

    /** The module's rest ({@link BotModule#restRequest}): out of the game till it is over, then the same plan again. */
    public BotModule.Rest restRequest() {
        return rest;
    }

    /** True when orders are taken now (in game, not logging out). */
    public boolean takesOrders() {
        return state != State.STOPPING && state != State.STOPPED && state != State.FAILED;
    }

    /** A stop waits this long at most for the fight to end. */
    static final long FREE_WAIT_NS = 5 * 60_000_000_000L;
    private String stopWhenFree;
    private long stopWhenFreeSince;
    /** When the stop was first asked for: the fights it waits for end within {@link #FREE_WAIT_NS} of it. */
    private long stopAskedAt;
    /** The target fought when the stop came: the stop waits for its end, not for the next one's. */
    private int stopWhenFreeTarget;

    /**
     * Finish once the ship is out of the fight: the target killed, nobody shooting the ship — at most
     * {@link #FREE_WAIT_NS} (live 2026-11-13 on 2-7: the time ran out with Boss Kristallon at 70 %, the ship stopped
     * fighting by it to log out and was destroyed).
     */
    public void stopWhenFree(String reason) {
        if (stopWhenFree != null || state == State.STOPPING || state == State.STOPPED) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        int target = module != null ? module.fightTarget() : 0;
        if (target == 0 && !inFight(w)) {
            stop(reason);
            return;
        }
        stopWhenFreeTarget = target;
        stopWhenFree = reason;
        stopWhenFreeSince = ctx.now();
        if (stopAskedAt == 0) {
            stopAskedAt = stopWhenFreeSince;
        }
        log.info("Остановка: {} — сначала заканчиваю бой", reason);
    }

    /**
     * The fight of the stop's moment is over: its target killed or left (live 2026-11-13: the hero's attack flag
     * dropped for a moment with Boss Kristallon at 1 % and the ship left it), no NPC shooting the ship.
     */
    private boolean fightOver(WorldSnapshot w) {
        if (ctx.now() < fightBackUntil) {
            return false;
        }
        int now = module != null ? module.fightTarget() : 0;
        if (stopWhenFreeTarget != 0 && now == stopWhenFreeTarget && w.ships().containsKey(stopWhenFreeTarget)) {
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        if (stopWhenFreeTarget == 0 && h.attackTargetId() != 0) {
            return false;
        }
        for (int id : h.attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.npc() && s.attackTargetId() == h.id()) {
                return false;
            }
        }
        return true;
    }

    /** The ship shoots something or an NPC shoots it. */
    private static boolean inFight(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        if (h.attackTargetId() != 0) {
            return true;
        }
        for (int id : h.attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.npc()) {
                return true;
            }
        }
        return false;
    }

    /** Finish: leave the fight, fly to the nearest portal and log out. */
    public void stop(String reason) {
        if (state == State.STOPPING || state == State.STOPPED) {
            return;
        }
        stopReason = reason;
        if (stopAskedAt == 0) {
            stopAskedAt = ctx.now();
        }
        log.info("Остановка: {}", reason);
        if (module != null) {
            module.pause();
        }
        ctx.nav().stop();
        state = State.STOPPING;
        logoutAt = 0;
    }

    /** The ammunition spent, from the item bar's counts (the Totals window's «Расход»). */
    private void countAmmo(WorldSnapshot w) {
        if (w.items().isEmpty()) {
            return;
        }
        java.util.Map<String, Double> counts = new java.util.HashMap<>();
        w.items().forEach((id, i) -> counts.put(id, i.counter()));
        ctx.stats().ammoSeen(counts);
    }

    public void tick() {
        if (state == State.STOPPED || state == State.FAILED) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0 && w.death() == null) {
            show("Вход в игру");
            return;
        }
        if (linkLost.getAsBoolean()) {
            if (!offline) {
                offline = true;
                log.warn("Связь с игрой потеряна — ничего не делаю, пока не переподключусь");
                if (module != null) {
                    module.pause();
                }
                ctx.nav().stop();
            }
            show("Нет связи, переподключаюсь");
            return;
        }
        if (offline) {
            offline = false;
            log.info("Связь восстановлена — продолжаю");
        }
        countAmmo(w);
        // the game's box check: drop everything and pick the boxes it asks for, as a player does with the client
        // (live 2026-11-12 on gb2: the bot kept flying at the circle's edge while the timer ran)
        if (w.boxCheck() != null) {
            if (!inBoxCheck) {
                inBoxCheck = true;
                log.warn("Проверка с ящиками: все действия брошены, собираю ящики");
                if (module != null) {
                    module.pause();
                }
                ctx.nav().stop();
                if (h.attackTargetId() != 0) {
                    ctx.actions().stopAttack(); // the module only forgets its target, the lasers would go on
                }
            }
            boxCheck.tick(w);
            show(w.boxCheck().text(w.nanos()));
            return;
        }
        if (inBoxCheck) {
            inBoxCheck = false;
            log.info("Проверки больше нет — продолжаю");
        }
        offenders.observe(w);
        watchHonor(w);
        if (w.death() != null && module != null && hangarRequest == 0 && state != State.STOPPING) {
            int want = module.hangarOnDeath(activeHangar, w);
            if (want != 0 && want != activeHangar) {
                // destroyed with the cargo aboard: the other ship instead of a revive (DmPlugin «sellOnDie»)
                log.info("Корабль уничтожен — меняю корабль (ангар {}) вместо возрождения", want);
                hangarRequest = want;
                stopReason = "смена корабля";
                state = State.STOPPED;
                show("Смена корабля");
                return;
            }
        }
        boolean reviving = revive.tick();
        if (reviving && state == State.STOPPING && w.death() == null && h.alive()) {
            reviving = false; // back in game while logging out: no pause after the revive, out at once
        }
        if (reviving) {
            if (module != null) {
                module.pause();
            }
            if (revive.limitReached()) {
                fail(revive.limitReason());
                return;
            }
            if (state != State.STOPPING) {
                state = State.DEAD;
            }
            show(w.death() != null ? "Уничтожен" : "Возрождение");
            return;
        }
        if (stopWhenFree != null && state != State.STOPPING && (fightOver(w) || ctx.now() - stopWhenFreeSince > FREE_WAIT_NS)) {
            String reason = stopWhenFree;
            stopWhenFree = null;
            stop(reason);
        }
        if (module != null) {
            module.observe();
            String done = state != State.STOPPING ? module.stopRequest() : null;
            if (done != null) {
                finished = true;
                stopWhenFree(done); // to a portal and out, as the stop button does once the fight is over
            }
            BotModule.Rest r = state != State.STOPPING && rest == null ? module.restRequest() : null;
            if (r != null) {
                rest = r;
                stopWhenFree(r.reason()); // out once the fight is over; the app logs in again when the rest is over
            }
        }
        buy.tick();
        craft.tick();
        dispatch.tick(); // the dispatch's retrievers (modules.dispatch)
        alongside.tick(module != null && module.busy()); // the passes' rewards beside another module (modules.quests.alongside)
        groupWatch.tick();
        pet.tick();
        if (state != State.STOPPING && service.phase() == ServiceVisit.Phase.IDLE) {
            refiner.tick(); // raw ore → promerium in the hold (modules.enrich.refine)
            enricher.tick(); // the lab kept up from the hold (modules.enrich)
            skylab.tick(); // ore from the Skylab into the hold (modules.skylab)
        }
        if (state == State.STOPPING) {
            if (pending.getAndSet(null) != null) {
                log.info("Выхожу из игры — приказ с карты не выполняю");
            }
            if (!stopping(w) && module != null) {
                // in a gate with no home portal in sight: the stage is played on till it shows (no logging out there);
                // the state stays STOPPING (live 2026-11-15: the fall-through into the usual loop dropped the stop, the
                // bot went back into the gate)
                if (!safety.tick(module.busy(), almostDead(w))) {
                    module.tick();
                }
            }
            return;
        }
        if (manual(h)) {
            return;
        }
        nudgeWhenStill(h);
        // a RUSH target in sight counts too: the trip to repair is dropped for it (never leave while the invoke lives)
        boolean rush = module != null && (module.rushing() || rushInSight(w));
        if (rush != rushTold) {
            rushTold = rush;
            log.info(rush ? "Напролом: без ремонта, бегства и обхода врагов, пока цель жива"
                    : "Напролом окончен: ремонт, бегство и обход врагов снова как в конфиге");
        }
        if (!rush && flee.tick()) {
            if (module != null) {
                module.pause();
            }
            state = State.FLEEING;
            show("Бегу от " + flee.threat());
            return;
        }
        // the repair before keeping off others: getting away from an NPC must not stop the trip to the portal (live
        // 2026-11-13: 75 % → 23 % in 8 s while stepping out of reach, the repair never began, killed)
        if (rush) {
            safety.healOnly(); // the heal abilities go on, the trip to repair waits for the target's death
        } else if (safety.tick(module != null && module.busy(), almostDead(w))) {
            if (module != null) {
                module.pause();
            }
            state = State.REPAIRING;
            show("Ремонт, HP " + Safety.pct(h.health().hpPercent()) + "%");
            return;
        }
        if (offenders.active() && !rush) {
            if (offenders.sightings() != offendersSeen) {
                offendersSeen = offenders.sightings();
                if (module != null) {
                    module.pause(); // targets near him are left: the module chooses again away from him
                }
            }
            // a target nearly dead is finished first, while the hull allows (live 2026-11-13: Impulse II left at 7 %)
            String who = finishing(w) || module != null && module.holdsGround() ? null
                    : offenders.escape(w, portalOnTheWay(h));
            if (who != null) {
                if (!avoiding && module != null) {
                    module.pause();
                }
                avoiding = true;
                state = State.WORKING;
                show("Ухожу от " + who);
                return;
            }
            avoiding = false;
        }
        if (module == null) {
            fail("модуль " + moduleKind + " не поддерживается");
            return;
        }
        // the service visit (modules.enrich.visit): at a calm moment onto the service ship — the Skylab's ore and the
        // enrichment there — and back; never in a gate (no logging out there) or under fire
        boolean calm = !module.busy() && module.freeFor(SERVICE_FREE_MS)
                && !orbit.config.GateCatalog.instanced(h.mapId()) && h.attackers().isEmpty();
        // the two-ship palladium changes ships itself: the visit rides on its «sold — back to collecting»
        boolean ownShipChanges = moduleKind == orbit.config.BotConfig.Module.PALLADIUM && ctx.ships().palladiumCollect() > 0;
        ServiceVisit.Step visit = service.tick(activeHangar, calm, !ownShipChanges);
        if (visit.switchTo() != 0 && activeHangar != 0 && visit.switchTo() != activeHangar && hangarRequest == 0) {
            log.info("Меняю корабль: ангар {} → {} (обслуживание: Скайлаб и обогащение)", activeHangar, visit.switchTo());
            hangarRequest = visit.switchTo();
            logoutHere = false;
            module.pause();
            stop("смена корабля (обслуживание)");
            return;
        }
        if (visit.busy()) {
            module.pause();
            ctx.nav().stop();
            state = State.WORKING;
            show(visit.status());
            return;
        }
        // the account's start ship (account settings «Запускать на»): once, as soon as the site's active ship is known;
        // the two-ship palladium's collect ship rules over it there (user's choice)
        int startShip = ctx.ships().startShip();
        if (!startChecked && startShip != 0 && activeHangar != 0 && hangarRequest == 0 && !ownShipChanges
                && service.phase() == ServiceVisit.Phase.IDLE) {
            startChecked = true;
            if (startShip != activeHangar) {
                log.info("Меняю корабль: ангар {} → {} (корабль запуска из настроек аккаунта)", activeHangar, startShip);
                hangarRequest = startShip;
                logoutHere = true; // just logged in: out where it stands
                module.pause();
                stop("смена корабля (корабль запуска)");
                return;
            }
        }
        if (shipWanted != 0 && hangarRequest == 0 && !module.busy() && service.phase() == ServiceVisit.Phase.IDLE
                && activeHangar != 0) {
            int to = shipWanted;
            shipWanted = 0;
            if (to != activeHangar) {
                log.info("Пересаживаюсь на другой корабль: ангар {} → {} (выход из игры, смена на сайте, вход)", activeHangar, to);
                hangarRequest = to;
                logoutHere = true;
                stop("смена корабля");
                return;
            }
        }
        int wantHangar = hangarRequest == 0 && !module.busy() && service.phase() == ServiceVisit.Phase.IDLE
                ? module.hangarWanted(activeHangar, w) : 0;
        if (wantHangar != 0 && wantHangar != activeHangar) {
            // only on the way back to collecting (after the sale), never with palladium in the hold
            if (!ownShipChanges || wantHangar == ctx.ships().palladiumCollect()) {
                wantHangar = service.divert(activeHangar, wantHangar);
            }
            log.info("Меняю корабль: ангар {} → {} (выход из игры, смена на сайте, вход)", activeHangar, wantHangar);
            hangarRequest = wantHangar;
            logoutHere = !module.hangarAtPortal();
            stop("смена корабля");
            return;
        }
        if (cargo.tick(module.busy(), module.cargoPlan(ctx.config().get()))) {
            module.pause();
            state = State.TRAVELLING;
            show("Трюм полон — продаю руду");
            return;
        }
        if (cloak.tick(module.busy(), module.freeFor(CloakCare.WINDOW_MS), module.cloakPlan(), module.petLasersOff())) {
            module.pause();
            state = State.TRAVELLING;
            show(cloak.status());
            return;
        }
        int map = module.workingMap();
        if (map != 0 && h.mapId() != map) {
            travel(map);
            return;
        }
        travelTarget = 0;
        state = State.WORKING;
        module.tick();
        show(module.status());
    }

    /**
     * A ship standing on one spot for minutes gets a server kick (suspected idle kick; live on the VPS: KIK about 5 min
     * after each login while it stood still on 2-8):
     * after {@link #STILL_MIN_MS}–{@link #STILL_MAX_MS} without moving, a short step aside (150–300); the module puts the
     * ship back on its spot if it wants it there.
     */
    private void nudgeWhenStill(WorldSnapshot.Hero h) {
        long now = ctx.now();
        if (stillX == Integer.MIN_VALUE || Math.hypot(h.x() - stillX, h.y() - stillY) > STILL_RADIUS) {
            stillX = h.x();
            stillY = h.y();
            stillSince = now;
            stillLimit = ctx.pause(STILL_MIN_MS, STILL_MAX_MS);
            return;
        }
        if (now - stillSince < stillLimit) { // nanos, as ctx.pause gives
            return;
        }
        double a = ctx.random().nextDouble() * 2 * Math.PI;
        double d = 150 + ctx.random().nextDouble() * 150;
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(h.mapId());
        double x = h.x() + Math.cos(a) * d;
        double y = h.y() + Math.sin(a) * d;
        if (m != null && m.width() > 0) {
            x = Math.max(100, Math.min(m.width() - 100, x));
            y = Math.max(100, Math.min(m.height() - 100, y));
        }
        log.info("Стою на месте {} мин — чуть сдвигаюсь, чтобы сервер не выкинул за бездействие",
                (now - stillSince) / 60_000_000_000L);
        ctx.nav().moveTo(x, y, true);
        ctx.nav().tick();
        stillSince = now; // counted again from here, wherever the step ends
        stillLimit = ctx.pause(STILL_MIN_MS, STILL_MAX_MS);
    }

    /**
     * Logging out on a Blacklight map: the first portal of the way to the company's x-8 (1-BL → 2-BL → 2-8 for the
     * second company) — not just the nearest, which may lead to the next BL map and back without end (live 2026-11-13
     * on the VPS).
     */
    private SafePlaces.Place wayHome(WorldSnapshot.Hero h) {
        int x8 = switch (h.factionId()) {
            case 1 -> 20;
            case 2 -> 24;
            case 3 -> 28;
            default -> 0;
        };
        if (x8 == 0) {
            return null;
        }
        try {
            java.util.List<orbit.world.maps.MapCatalog.Portal> route = orbit.bot.nav.Router.route(ctx.maps(), h.mapId(),
                    h.x(), h.y(), x8, h.factionId(), h.level() > 0 ? h.level() : Integer.MAX_VALUE);
            if (route.isEmpty() || ctx.stats().closedPortals.contains(SafePlaces.key(h.mapId(), route.getFirst()))) {
                return null;
            }
            var p = route.getFirst();
            return new SafePlaces.Place(p.x(), p.y(), "портал на " + p.toName(), p, true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The first portal of the way to the module's map when it is another one, else {@code null}. */
    private double[] portalOnTheWay(WorldSnapshot.Hero h) {
        int map = module == null ? 0 : module.workingMap();
        if (map == 0 || map == h.mapId()) {
            return null;
        }
        try {
            java.util.List<orbit.world.maps.MapCatalog.Portal> route = orbit.bot.nav.Router.route(ctx.maps(), h.mapId(), h.x(), h.y(),
                    map, h.factionId(), h.level() > 0 ? h.level() : Integer.MAX_VALUE);
            return route.isEmpty() ? null : new double[] {route.getFirst().x(), route.getFirst().y()};
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The user's order and the pause; true while they hold the ship. */
    private boolean manual(WorldSnapshot.Hero h) {
        long now = ctx.now();
        Order o = pending.getAndSet(null);
        if (o != null) {
            if (module != null && !paused) {
                module.pause(); // paused: the module's plan is kept for the moment the pause ends
            }
            current = o;
            orderAt = now;
            holdUntil = 0;
            travelTarget = 0;
            if (o.kind() == Order.Kind.MOVE) {
                log.info("По клику: лечу в {}/{}", (int) o.x() / 100, (int) o.y() / 100);
                ctx.nav().moveTo(o.x(), o.y(), true);
            } else {
                log.info("По клику: прыгаю на {}", ctx.maps().info(o.map()).name());
                ctx.traveler().travel(o.map(), Double.NaN, Double.NaN);
            }
        }
        if (current != null) {
            boolean done;
            if (current.kind() == Order.Kind.MOVE) {
                ctx.nav().tick();
                done = ctx.nav().idle();
            } else {
                ctx.traveler().tick();
                Traveler.State ts = ctx.traveler().state();
                if (ts == Traveler.State.FAILED) {
                    log.warn("По клику: прыжок не вышел — {}", ctx.traveler().failure());
                }
                done = ts != Traveler.State.TRAVELLING;
            }
            if (!done && now - orderAt > ORDER_LIMIT_MS * 1_000_000) {
                log.warn("По клику: не выполнено за {} мин — бросаю", ORDER_LIMIT_MS / 60_000);
                ctx.nav().stop();
                done = true;
            }
            if (!done) {
                ctx.ship().want(ctx.config().get().general().travel());
                state = State.MANUAL;
                show(current.kind() == Order.Kind.MOVE
                        ? "По клику: лечу в " + (int) current.x() / 100 + "/" + (int) current.y() / 100
                        : "По клику: прыжок на " + ctx.maps().info(current.map()).name());
                return true;
            }
            if (holdUntil == 0) {
                holdUntil = now + holdMs * 1_000_000;
            }
            if (paused || now < holdUntil) {
                state = paused ? State.PAUSED : State.MANUAL;
                show(paused ? "Пауза" : "По клику: на месте");
                return true;
            }
            current = null;
            holdUntil = 0;
        }
        if (paused) {
            if (!pauseShown) {
                pauseShown = true;
                log.info("Пауза: бот ждёт, корабль летает только по кликам на карте");
                // the module's plan is kept (the behaviour before the pause resumes right after it)
                ctx.nav().halt(); // stop where it is: it would fly on to the module's last click
            }
            state = State.PAUSED;
            show("Пауза");
            return true;
        }
        if (pauseShown) {
            pauseShown = false;
            log.info("Пауза снята — продолжаю, как было до паузы");
            if (module != null) {
                module.resumed();
            }
        }
        return false;
    }

    /** Below this share of its hull and shield the target is finished before stepping away from others. */
    static final double FINISH_BELOW = 0.3;
    /** …while the ship has at least this share of its hull. */
    static final double FINISH_HULL = 0.4;

    /** The target shot has less than this left: the early repair waits for its end. */
    static final double ALMOST_DEAD = 0.1;

    /** An NPC the config flags {@code RUSH} is in sight. */
    private boolean rushInSight(WorldSnapshot w) {
        BotConfig c = ctx.config().get();
        if (!c.anyNpc(BotConfig.NpcFlag.RUSH)) {
            return false;
        }
        int map = w.hero().mapId();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc()) {
                BotConfig.NpcRule r = orbit.bot.mod.Targets.rule(c, s, map);
                if (r != null && r.kill() && r.has(BotConfig.NpcFlag.RUSH)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean almostDead(WorldSnapshot w) {
        WorldSnapshot.Ship t = w.ships().get(w.hero().attackTargetId());
        if (t == null || !t.npc() || !t.health().known()) {
            return false;
        }
        double max = t.health().hpMax() + Math.max(0, t.health().shieldMax());
        return max > 0 && (t.health().hp() + Math.max(0, t.health().shield())) / max < ALMOST_DEAD;
    }

    private static boolean finishing(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship t = w.ships().get(h.attackTargetId());
        if (t == null || !t.npc() || !t.health().known() || h.health().hpPercent() < FINISH_HULL) {
            return false;
        }
        double max = t.health().hpMax() + Math.max(0, t.health().shieldMax());
        return max > 0 && (t.health().hp() + Math.max(0, t.health().shield())) / max < FINISH_BELOW;
    }

    private void travel(int map) {
        int budget = module.jumpUridiumPerDay();
        if (budget >= 0 && travelTarget != map && jumper.worthTrying(map) && portalHops(map) >= CPU_FROM_HOPS) {
            // the jump CPU first (a voucher, or uridium within the day's budget); portals when it does not work.
            // It works at a portal or in our station only: fly to the nearest portal first.
            WorldSnapshot w = ctx.world().get();
            if (!jumper.running() && !orbit.bot.nav.CpuJumper.placeForJump(w, ctx.maps())) {
                orbit.world.maps.MapCatalog.Portal p = orbit.bot.nav.CpuJumper.nearestPortal(w, ctx.maps());
                if (p != null) {
                    module.pause();
                    if (ctx.nav().idle() || ctx.nav().destination().x() != p.x() || ctx.nav().destination().y() != p.y()) {
                        ctx.nav().moveTo(p.x(), p.y(), true);
                    }
                    ctx.ship().want(ctx.config().get().general().travel());
                    ctx.nav().tick();
                    state = State.TRAVELLING;
                    show("К порталу для прыжка CPU на " + ctx.maps().info(map).name());
                    return;
                }
            }
            orbit.bot.nav.CpuJumper.Result r = jumper.tick(map, budget);
            if (r == orbit.bot.nav.CpuJumper.Result.RUNNING) {
                module.pause();
                ctx.nav().stop();
                state = State.TRAVELLING;
                show("Прыжок CPU на " + ctx.maps().info(map).name());
                return;
            }
            if (r == orbit.bot.nav.CpuJumper.Result.DONE) {
                return;
            }
        }
        Traveler t = ctx.traveler();
        if (travelTarget != map || t.state() != Traveler.State.TRAVELLING) {
            if (travelTarget == map && t.state() == Traveler.State.FAILED) {
                WorldSnapshot.Hero hero = ctx.world().get().hero();
                if (module instanceof orbit.bot.mod.QuestModule && orbit.bot.nav.Traveler.closed(map, hero.level())) {
                    // a quest's map closed to this level: the quests choose other work, the bot goes on (2026-11-25)
                    log.warn("Квесты: {} закрыта для уровня {} — беру другую работу", ctx.maps().info(map).name(), hero.level());
                    travelTarget = 0;
                    return;
                }
                fail("нет пути на " + ctx.maps().info(map).name());
                return;
            }
            module.pause();
            travelTarget = map;
            log.info("Лечу на рабочую карту {}", ctx.maps().info(map).name());
            t.travel(map, Double.NaN, Double.NaN);
        }
        state = State.TRAVELLING;
        ctx.ship().want(ctx.config().get().general().travel());
        module.travelling();
        t.tick();
        show("Лечу на " + ctx.maps().info(map).name());
    }

    /**
     * The jump CPU only for a way of this many portals or more: one portal is about as quick and costs nothing
     * (live 2026-11-11: from 1-8 a voucher was spent on 1-6, one portal away).
     */
    static final int CPU_FROM_HOPS = 2;

    /** Portals on the way to {@code map} (a big number when there is no way by portals). */
    int portalHops(int map) {
        WorldSnapshot.Hero h = ctx.world().get().hero();
        int hops = orbit.bot.nav.Router.route(ctx.maps(), h.mapId(), h.x(), h.y(), map, h.factionId(),
                h.level() > 0 ? h.level() : Integer.MAX_VALUE).size();
        return hops == 0 ? Integer.MAX_VALUE : hops;
    }

    /**
     * The nearest portal of any faction to log out by: only being near one counts, not where it leads (live
     * 2026-11-11 on 5-3: the own faction's portals were far left, other portals near). The server's portals first
     * (it sends all, the other factions' hidden on the minimap), else the map's known ones.
     */
    private int[] logoutPortal(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        java.util.List<int[]> all = new java.util.ArrayList<>();
        // not the galaxy gates' portals: the server sends them on x-1 too (seven on 3-1, company 0, unknown to the map's
        // facts), they lead nowhere without a built gate — the ship went to one to log out (live 2026-11-17); a
        // company's portal the facts lack still counts (5-3's to 5-2, 2026-11-11)
        w.portals().values().stream()
                .filter(x -> orbit.world.maps.PortalTypes.gate(x.designId()) == null)
                .filter(x -> x.factionId() != 0 || ctx.maps().portalNear(h.mapId(), x.x(), x.y(), 400) != null)
                .forEach(x -> all.add(new int[] {x.x(), x.y()}));
        if (all.isEmpty()) {
            ctx.maps().portals(h.mapId()).forEach(x -> all.add(new int[] {x.x(), x.y()}));
        }
        // a base (own or shared) is as safe (on 5-2 the shared base, not a portal)
        SafePlaces.bases(w).forEach(b -> all.add(new int[] {b.x(), b.y()}));
        return all.stream().min(Comparator.comparingDouble(x -> Math.hypot(x[0] - h.x(), x[1] - h.y()))).orElse(null);
    }

    /** Shot at by an NPC this long while logging out at a portal: jump through it. */
    static final long FIRED_AT_JUMP_NS = 3_000_000_000L;
    private long firedAtSince;
    /** A player shot the ship this recently: no logout pressed (the server cancels it under fire). */
    private long playerFireAt = Long.MIN_VALUE / 2;
    static final long PLAYER_QUIET_NS = 10_000_000_000L;

    /** A player (not an NPC) shoots the ship now. */
    private static boolean underPlayerFire(WorldSnapshot w) {
        for (int id : w.hero().attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.kind() == WorldSnapshot.ShipKind.PLAYER) {
                return true;
            }
        }
        return false;
    }
    private long stopJumpAt = Long.MIN_VALUE / 2;

    /** Fighting back before a logout: this long at least before the fight may count as over. */
    static final long FIGHT_BACK_NS = 15_000_000_000L;
    private long fightBackUntil;

    /** The first NPC shooting the ship, 0 none. */
    private static int npcFiring(WorldSnapshot w) {
        for (int id : w.hero().attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.npc() && s.attackTargetId() == w.hero().id()) {
                return id;
            }
        }
        return 0;
    }

    private static boolean underNpcFire(WorldSnapshot w) {
        for (int id : w.hero().attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.npc() && s.attackTargetId() == w.hero().id()) {
                return true;
            }
        }
        return false;
    }

    /** To the nearest portal (out of the crowd), wait a moment, press logout, keep still until the server ends it. */
    /** @return false when the bot plays on meanwhile (a galaxy gate with no home portal in sight). */
    private boolean stopping(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (loggedOut.getAsBoolean()) {
            state = State.STOPPED;
            show("Вышел: " + stopReason);
            log.info("Вышел из игры");
            return true;
        }
        // straight to the portal and out: the hull is not repaired first, no escape jump on the way
        safety.healOnly();
        // on a Blacklight map no logging out at its portal (NPCs reach it): through it first, out on the other side
        if (!logoutHere && !orbit.config.GateCatalog.instanced(h.mapId()) && orbit.bot.nav.Router.blacklight(h.mapId())) {
            SafePlaces.Place out = wayHome(h);
            if (out == null) {
                out = SafePlaces.outOfBlacklight(w, ctx.maps(), ctx.stats().closedPortals);
            }
            if (out == null) {
                out = SafePlaces.nearest(w, ctx.maps(), ctx.stats().closedPortals, ctx.config().get().safety().oneWayJumps());
            }
            if (out != null && out.portal() != null && out.jumpable()) {
                if (out.distance(h) > out.reach()) {
                    if (ctx.nav().idle() || ctx.nav().destination().x() != out.x()) {
                        ctx.nav().moveTo(out.x(), out.y(), true);
                    }
                    ctx.nav().tick();
                    show("К порталу на " + out.portal().toName() + " — выйду там");
                } else if (w.jumpingTo() == 0 && now - stopJumpAt > 15_000_000_000L) {
                    log.info("Выхожу из игры с BL-карты — сначала прыгаю на {}", out.portal().toName());
                    ctx.nav().stop();
                    ctx.actions().jump();
                    stopJumpAt = now;
                }
                logoutAt = 0;
                return true;
            }
        }
        // in a galaxy gate the game lets nobody log out (jump into the portal to x-1 and log out there, at the base):
        // through the gate's home portal (type 1), out on x-1; the gate is kept
        boolean inGate = orbit.config.GateCatalog.instanced(h.mapId());
        if (inGate) {
            WorldSnapshot.Portal home = w.portals().values().stream()
                    .filter(g -> g.designId() == orbit.config.GateCatalog.HOME_PORTAL)
                    .min(java.util.Comparator.comparingDouble(g -> Math.hypot(g.x() - h.x(), g.y() - h.y()))).orElse(null);
            if (home != null) {
                double d = Math.hypot(home.x() - h.x(), home.y() - h.y());
                if (d > 300 || !w.activatable().containsKey(home.id())) {
                    if (ctx.nav().idle() || ctx.nav().destination().x() != home.x()) {
                        ctx.nav().moveTo(home.x(), home.y(), true);
                    }
                    ctx.nav().tick();
                } else if (w.jumpingTo() == 0 && now - stopJumpAt > 10_000_000_000L) {
                    log.info("Выхожу из игры во вратах — сначала прыгаю домой на x-1, выйду там");
                    ctx.nav().stop();
                    ctx.actions().jump();
                    stopJumpAt = now;
                }
                show("Во вратах: к порталу домой — выйду на x-1");
                logoutAt = 0;
                return true;
            }
            if (w.jumpingTo() != 0 || now - stopJumpAt < 15_000_000_000L) {
                show("Во вратах: прыгаю домой — выйду на x-1");
                logoutAt = 0;
                return true; // the jump home under way: the old map's portals are gone already
            }
            if (!noHomeTold) {
                noHomeTold = true;
                log.info("Во вратах портала домой не видно (выйти здесь нельзя) — довожу этап, выйду через него");
            }
            logoutAt = 0;
            return false;
        }
        // DarkBot's DisconnectModule: the logout only once the escape is over — a player shooting the ship (a battle map:
        // the server cancels the logout under fire) — away from him, through a portal when at one,
        // and the logout only after PLAYER_QUIET_NS without a shot (it used to stand still at the portal pressing it)
        if (underPlayerFire(w)) {
            playerFireAt = now;
        }
        if (now - playerFireAt < PLAYER_QUIET_NS) {
            logoutAt = 0;
            logoutPressedAt = Long.MIN_VALUE / 2;
            if (flee.tick()) {
                show("Выход из игры: бегу от " + flee.threat());
                return true;
            }
            SafePlaces.Place place = SafePlaces.nearest(w, ctx.maps(), ctx.stats().closedPortals,
                    ctx.config().get().safety().oneWayJumps());
            if (place != null && place.portal() != null && place.jumpable() && place.distance(h) <= place.reach()) {
                if (w.jumpingTo() == 0 && now - stopJumpAt > 10_000_000_000L) {
                    log.warn("Выхожу из игры, но по мне стреляет игрок — прыгаю на {}, выйду там", place.portal().toName());
                    ctx.nav().stop();
                    ctx.actions().jump();
                    stopJumpAt = now;
                }
                show("Выход из игры: по мне стреляет игрок — прыгаю");
                return true;
            }
            if (place != null && place.portal() != null) {
                if (ctx.nav().idle() || ctx.nav().destination().x() != place.x()) {
                    ctx.nav().moveTo(place.x(), place.y(), true);
                }
                ctx.nav().tick();
                show("Выход из игры: по мне стреляет игрок — к порталу на " + place.portal().toName());
                return true;
            }
            show("Выход из игры: по мне стреляет игрок — жду, пока перестанут");
            return true;
        }
        int[] p = logoutHere || inGate ? null : logoutPortal(w);
        if (p != null && Math.hypot(p[0] - h.x(), p[1] - h.y()) > Safety.AT_PORTAL) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != p[0]) {
                ctx.nav().moveTo(p[0], p[1], true);
            }
            ctx.nav().tick();
            logoutAt = 0;
            show("К порталу или базе для выхода");
            return true;
        }
        ctx.nav().stop();
        if (underNpcFire(w)) {
            if (firedAtSince == 0) {
                firedAtSince = now;
            }
            // standing still to log out by an NPC that shoots: through the portal, out on the other side
            SafePlaces.Place place = SafePlaces.nearest(w, ctx.maps(), ctx.stats().closedPortals,
                    ctx.config().get().safety().oneWayJumps());
            if (now - firedAtSince > FIRED_AT_JUMP_NS && place != null && place.portal() != null && place.jumpable()
                    && place.distance(h) <= place.reach() && w.jumpingTo() == 0 && now - stopJumpAt > 15_000_000_000L) {
                log.warn("Выхожу из игры, но меня бьют у портала — прыгаю на {}, выйду там", place.portal().toName());
                ctx.actions().jump();
                stopJumpAt = now;
                firedAtSince = 0;
                logoutAt = 0;
                return true;
            }
            // nowhere to jump: standing still to log out under fire is death (live 2026-11-13 on 3-BL: an Impulse
            // II pack, 82 % → 0 in 17 s) — back to the fight, out once nobody shoots, as long as the wait allows
            if (now - firedAtSince > FIRED_AT_JUMP_NS && module != null && hangarRequest == 0
                    && now - stopAskedAt < FREE_WAIT_NS) {
                log.warn("Выхожу из игры, но меня бьют — сначала отбиваюсь");
                String reason = stopReason;
                state = State.WORKING;
                firedAtSince = 0;
                logoutAt = 0;
                stopWhenFree = reason;
                stopWhenFreeSince = stopAskedAt;
                // the NPC that shoots is the fight to finish, and the module gets time to take it on: the next tick
                // found the ship not shooting yet and called the fight over — logout, fighting back, logout… for a
                // minute and a half, never shooting back (live 2026-11-17 on 3-1, Streuners)
                stopWhenFreeTarget = npcFiring(w);
                fightBackUntil = now + FIGHT_BACK_NS;
                return true;
            }
        } else {
            firedAtSince = 0;
        }
        if (logoutAt == 0) {
            logoutAt = now + ctx.react(1_000, 3_000);
        }
        // pressed again once its countdown is over and the ship is still in (the server cancels it under fire or when
        // the ship moves; DarkBot waits 25 s) — not 30 s every time
        if (now >= logoutAt && !h.moving() && now - logoutPressedAt > (logoutSeconds + 8) * 1_000_000_000L) {
            // the game's countdown: 20 s, 5 s with premium
            logoutSeconds = h.premium() ? 5 : 20;
            log.info("Выход из игры ({} с)", logoutSeconds);
            ctx.actions().logout();
            logoutPressedAt = now;
        }
        long gone = (now - logoutPressedAt) / 1_000_000_000L;
        show(gone <= logoutSeconds + 10 ? "Выход из игры через " + Math.max(0, logoutSeconds - gone) + " с" : "Выход из игры");
        return true;
    }

    private boolean noHomeTold;

    private void fail(String why) {
        log.error("Бот остановлен: {}", why);
        state = State.FAILED;
        stopReason = why;
        ctx.nav().stop();
        show("Остановлен: " + why);
    }

    private void show(String s) {
        status = s;
        if (!s.equals(lastStatus)) {
            lastStatus = s;
            statusSink.accept(s);
        }
    }
}
