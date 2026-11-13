package orbit.bot.run;

import java.util.Comparator;
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
 *   <li>the user's orders from the map ({@link #order}) and the pause ({@link #setPaused});</li>
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

    /** An order from the user's map: fly to a point, or jump through the portal to {@code map}. */
    public record Order(Kind kind, double x, double y, int map) {

        public enum Kind { MOVE, JUMP }

        public static Order move(double x, double y) {
            return new Order(Kind.MOVE, x, y, 0);
        }

        public static Order jump(int map) {
            return new Order(Kind.JUMP, 0, 0, map);
        }
    }

    /** After an order is done the module waits this long before it takes the ship back (DarkBot does the same). */
    static final long HOLD_MS = 5_000;
    /** An order not done in this time is given up. */
    static final long ORDER_LIMIT_MS = 3 * 60_000L;

    private final java.util.concurrent.atomic.AtomicReference<Order> pending = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile boolean paused;
    private boolean pauseShown;
    private Order current;
    /** The website hangar in use ({@code 0} not known), and the one the module asked to switch to. */
    private volatile int activeHangar;
    private volatile int hangarRequest;
    private boolean logoutHere;
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
    private final Flee flee;
    private final GroupWatch groupWatch;
    private final PetCare pet;
    private final CargoCare cargo;
    private final orbit.bot.nav.CpuJumper jumper;
    private final BoxCheckSolver boxCheck;
    private final Offenders offenders;
    private int offendersSeen;
    private boolean avoiding;
    private BotModule module;
    private BotConfig.Module moduleKind;
    private State state = State.STARTING;
    private String status = "Вход в игру";
    private String stopReason;
    private int travelTarget;
    private long logoutAt;
    private long logoutPressedAt = Long.MIN_VALUE / 2;
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
        this.revive = new Revive(ctx);
        this.buy = new BuyWatch(ctx);
        this.flee = new Flee(ctx);
        this.groupWatch = new GroupWatch(ctx);
        this.pet = new PetCare(ctx);
        pet.setModuleLocator(() -> module == null ? 0 : module.petLocator());
        this.cargo = new CargoCare(ctx, pet);
        this.boxCheck = new BoxCheckSolver(ctx);
        this.offenders = new Offenders(ctx);
        ctx.nav().setAvoidedPlayers(offenders::circles);
        this.jumper = new orbit.bot.nav.CpuJumper(ctx.world(), ctx.actions(), ctx.maps(), ctx.clock(), ctx.random(),
                ctx.dataDir() == null ? null : ctx.dataDir().resolve("jump-uridium.json"));
        loadModule();
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
        module = Modules.create(moduleKind, ctx);
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

    /** The hangar the module asked for: the app switches to it after this session ({@code 0} none). */
    public int hangarRequest() {
        return hangarRequest;
    }

    /** Paused: the module rests, the ship does only what the user orders; any thread. */
    public void setPaused(boolean on) {
        paused = on;
    }

    public boolean paused() {
        return paused;
    }

    /** True when orders are taken now (in game, not logging out). */
    public boolean takesOrders() {
        return state != State.STOPPING && state != State.STOPPED && state != State.FAILED;
    }

    /** A stop waits this long at most for the fight to end. */
    static final long FREE_WAIT_NS = 5 * 60_000_000_000L;
    private String stopWhenFree;
    private long stopWhenFreeSince;
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
        log.info("Остановка: {} — сначала заканчиваю бой", reason);
    }

    /**
     * The fight of the stop's moment is over: its target killed or left (live 2026-11-13: the hero's attack flag
     * dropped for a moment with Boss Kristallon at 1 % and the ship left it), no NPC shooting the ship.
     */
    private boolean fightOver(WorldSnapshot w) {
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
        log.info("Остановка: {}", reason);
        if (module != null) {
            module.pause();
        }
        ctx.nav().stop();
        state = State.STOPPING;
        logoutAt = 0;
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
                fail("лимит смертей (" + revive.deaths() + ")");
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
        }
        buy.tick();
        groupWatch.tick();
        pet.tick();
        if (state == State.STOPPING) {
            if (pending.getAndSet(null) != null) {
                log.info("Выхожу из игры — приказ с карты не выполняю");
            }
            stopping(w);
            return;
        }
        if (manual(h)) {
            return;
        }
        if (flee.tick()) {
            if (module != null) {
                module.pause();
            }
            state = State.FLEEING;
            show("Бегу от " + flee.threat());
            return;
        }
        // the repair before keeping off others: getting away from an NPC must not stop the trip to the portal (live
        // 2026-11-13: 75 % → 23 % in 8 s while stepping out of reach, the repair never began, killed)
        if (safety.tick(module != null && module.busy())) {
            if (module != null) {
                module.pause();
            }
            state = State.REPAIRING;
            show("Ремонт, HP " + Safety.pct(h.health().hpPercent()) + "%");
            return;
        }
        if (offenders.active()) {
            if (offenders.sightings() != offendersSeen) {
                offendersSeen = offenders.sightings();
                if (module != null) {
                    module.pause(); // targets near him are left: the module chooses again away from him
                }
            }
            // a target nearly dead is finished first, while the hull allows (live 2026-11-13: Impulse II left at 7 %)
            String who = finishing(w) ? null : offenders.escape(w);
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
        int wantHangar = hangarRequest == 0 && !module.busy() ? module.hangarWanted(activeHangar, w) : 0;
        if (wantHangar != 0 && wantHangar != activeHangar) {
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

    /** The user's order and the pause; true while they hold the ship. */
    private boolean manual(WorldSnapshot.Hero h) {
        long now = ctx.now();
        Order o = pending.getAndSet(null);
        if (o != null) {
            if (module != null) {
                module.pause();
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
                holdUntil = now + HOLD_MS * 1_000_000;
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
                if (module != null) {
                    module.pause();
                }
                ctx.nav().halt(); // stop where it is: it would fly on to the module's last click
            }
            state = State.PAUSED;
            show("Пауза");
            return true;
        }
        if (pauseShown) {
            pauseShown = false;
            log.info("Пауза снята — продолжаю");
        }
        return false;
    }

    /** Below this share of its hull and shield the target is finished before stepping away from others. */
    static final double FINISH_BELOW = 0.3;
    /** …while the ship has at least this share of its hull. */
    static final double FINISH_HULL = 0.4;

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
        w.portals().values().forEach(x -> all.add(new int[] {x.x(), x.y()}));
        if (all.isEmpty()) {
            ctx.maps().portals(h.mapId()).forEach(x -> all.add(new int[] {x.x(), x.y()}));
        }
        // a base (own or shared) is as safe (the user, 2026-11-12: on 5-2 the shared base, not a portal)
        SafePlaces.bases(w).forEach(b -> all.add(new int[] {b.x(), b.y()}));
        return all.stream().min(Comparator.comparingDouble(x -> Math.hypot(x[0] - h.x(), x[1] - h.y()))).orElse(null);
    }

    /** Shot at by an NPC this long while logging out at a portal: jump through it. */
    static final long FIRED_AT_JUMP_NS = 3_000_000_000L;
    private long firedAtSince;
    private long stopJumpAt = Long.MIN_VALUE / 2;

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
    private void stopping(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (loggedOut.getAsBoolean()) {
            state = State.STOPPED;
            show("Вышел: " + stopReason);
            log.info("Вышел из игры");
            return;
        }
        // straight to the portal and out: the hull is not repaired first, no escape jump on the way
        safety.healOnly();
        int[] p = logoutHere ? null : logoutPortal(w);
        if (p != null && Math.hypot(p[0] - h.x(), p[1] - h.y()) > Safety.AT_PORTAL) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != p[0]) {
                ctx.nav().moveTo(p[0], p[1], true);
            }
            ctx.nav().tick();
            logoutAt = 0;
            show("К порталу или базе для выхода");
            return;
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
                return;
            }
        } else {
            firedAtSince = 0;
        }
        if (logoutAt == 0) {
            logoutAt = now + ctx.pause(1_000, 3_000);
        }
        if (now >= logoutAt && !h.moving() && now - logoutPressedAt > 30_000_000_000L) {
            log.info("Выход из игры");
            ctx.actions().logout();
            logoutPressedAt = now;
        }
        show("Выход из игры");
    }

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
