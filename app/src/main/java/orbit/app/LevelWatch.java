package orbit.app;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import orbit.auth.AuthClient;
import orbit.bot.level.AccountState;
import orbit.bot.level.Advisor;
import orbit.bot.level.LevelPlan;
import orbit.bot.level.SiteHangars;
import orbit.config.ConfigStore;
import orbit.game.GameConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The leveling mode's advisor in the running app (docs/LEVELING.md): while the account plays a config of the folder
 * {@link ConfigStore#LEVELING_FOLDER}, it reads the account from the website and the game now and then and writes the
 * state, the goal and what it would buy to the leveling log ({@code orbit.level} → {@code logs/leveling-<account>.log},
 * never the window). It buys, fits and switches nothing.
 */
final class LevelWatch {

    /** The leveling log: its own file per account, kept out of the window and the journal (logback-app.xml). */
    static final Logger LOG = LoggerFactory.getLogger("orbit.level");

    static final long FIRST_MS = 90_000;
    static final long EVERY_MS = 30 * 60_000L;
    /** Between two calls of the site's inventory: it refuses one right after another (live 2026-11-14). */
    static final long GAP_MS = 4_000;

    private LevelWatch() {
    }

    /** Starts the advisor's thread for this game connection; it ends with the game thread. */
    static void start(AuthClient auth, GameConnection conn, WorldFeed feed, String account, Supplier<String> config,
                      BooleanSupplier alive, Supplier<String> status, java.util.function.IntConsumer requestShip,
                      Runnable refresh, Runnable requestRefit, java.util.function.Consumer<String> switchConfig) {
        java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        AuctionWatch.start(auth, conn, feed, account, config, alive); // the bids, only on leveling configs too
        Thread.ofVirtual().name("leveling").start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            java.util.Random random = new java.util.Random();
            boolean shapeLogged = false;
            boolean first = true;
            Meter meter = new Meter();
            try {
                Thread.sleep(FIRST_MS + random.nextInt(60_000));
                LevelPlan plan = LevelPlan.bundled();
                while (alive.getAsBoolean()) {
                    String cfg = config.get();
                    if (cfg != null && ConfigStore.LEVELING_FOLDER.equals(ConfigStore.openDefault().folderOf(cfg))) {
                        meter.sample(feed, cfg);
                        shapeLogged |= round(auth, conn, feed, account, cfg, plan, !shapeLogged, status, meter, switchConfig,
                                requestShip, refresh, requestRefit, first);
                        first = false;
                    }
                    // gear waiting for the base station: the next look sooner, to fit it by a re-login if still waiting
                    Thread.sleep(PENDING_SINCE.containsKey(account) ? PENDING_LOOK_MS + random.nextInt(30_000)
                            : EVERY_MS + random.nextInt(120_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.error("советчик остановлен: {}", e.toString(), e);
            }
        });
    }

    /**
     * Earning per hour by the kind of config played (quests or farm), from the session's rewards (earned only, spending
     * left out): each sample adds the earning since the last one to the kind the account played then.
     */
    static final class Meter {

        private long at;
        private double credits = Double.NaN;
        private double uridium;
        private String kind;
        private final double[] hours = new double[2];
        private final double[] earnedCredits = new double[2];
        private final double[] earnedUridium = new double[2];
        /** Less than this measured — no rate yet. */
        static final double MIN_HOURS = 0.5;

        void sample(WorldFeed feed, String cfg) {
            var r = feed.sessionRewards();
            long now = System.currentTimeMillis();
            String k = cfg.endsWith(" Quests") ? "q" : cfg.matches(".* x-\\d$") ? "f" : null;
            if (r != null && !Double.isNaN(credits) && kind != null && kind.equals(k) && r.credits() >= credits) {
                int i = "q".equals(kind) ? 0 : 1;
                hours[i] += (now - at) / 3_600_000.0;
                earnedCredits[i] += r.credits() - credits;
                earnedUridium[i] += Math.max(0, r.uridium() - uridium);
            }
            at = now;
            credits = r == null ? Double.NaN : r.credits();
            uridium = r == null ? 0 : r.uridium();
            kind = k;
        }

        orbit.bot.level.Strategy.Rates rates() {
            return new orbit.bot.level.Strategy.Rates(rate(earnedCredits, 0), rate(earnedCredits, 1), rate(earnedUridium, 0),
                    rate(earnedUridium, 1));
        }

        private double rate(double[] earned, int i) {
            return hours[i] < MIN_HOURS ? Double.NaN : earned[i] / hours[i];
        }

        String describe() {
            return String.format(java.util.Locale.ROOT, "квесты %.1f ч — %s кр/ч, %s ури/ч; фарм %.1f ч — %s кр/ч, %s ури/ч",
                    hours[0], fmt(rate(earnedCredits, 0)), fmt(rate(earnedUridium, 0)), hours[1], fmt(rate(earnedCredits, 1)),
                    fmt(rate(earnedUridium, 1)));
        }

        private static String fmt(double v) {
            return Double.isNaN(v) ? "?" : String.valueOf(Math.round(v));
        }
    }

    /**
     * Out of the game (between a logout and the next login): every other hangar's lasers, generators and extras taken off,
     * then the active ship fitted by the plan — so a ship never enters the game empty (live 2026-11-25: the Bastion logged
     * in bare and flew to fight; in the game the site refuses gear changes away from the base station). The moves done.
     */
    static int fitOffline(orbit.auth.Hangars site, String account) throws InterruptedException {
        LevelPlan plan = LevelPlan.bundled();
        try {
            var listJson = site.listJson();
            SiteHangars.ListInfo list = SiteHangars.list(listJson);
            int moved = 0;
            orbit.bot.level.PalladiumKit.Kit kit = orbit.bot.level.PalladiumKit.bundled();
            int kept = kit == null ? 0 : list.hangarOf(kit.ship());
            for (int other : list.hangars().keySet()) {
                // the Nostromo keeps its own kit (palladium stage): never stripped for the main ship; on the stage the
                // main ship keeps its gear too (the Nostromo flies with its own)
                if (other == list.activeHangar() || other == kept || PalladiumStage.holds(account)) {
                    continue;
                }
                Thread.sleep(GAP_MS);
                SiteHangars.HangarInfo h = SiteHangars.hangar(site.hangarJson(other), other);
                if (h.fittedIds().values().stream().anyMatch(m -> m.values().stream().anyMatch(l -> !l.isEmpty()))) {
                    LOG.info("вне игры: снимаю снаряжение с ангара {} («{}»)", other, list.hangars().get(other));
                    moveOut(site, other, h);
                    moved++;
                }
            }
            Thread.sleep(GAP_MS);
            var activeJson = site.hangarJson(list.activeHangar());
            SiteHangars.HangarInfo active = SiteHangars.hangar(activeJson, list.activeHangar());
            // the Nostromo's moves out of the game (in it the site changes no hangar away from the base station): first,
            // so what it gives back is in the inventory for the main ship's fitting right below
            if (kept != 0 && kept != list.activeHangar()) {
                moved += nostromoMoves(site, listJson, list, active, kept, kit, false, "вне игры: ");
                Thread.sleep(GAP_MS);
                activeJson = site.hangarJson(list.activeHangar());
                active = SiteHangars.hangar(activeJson, list.activeHangar());
            }
            var moves = new java.util.ArrayList<>(orbit.bot.level.FitPlanner.plan(active, list.activeHangar(),
                    SiteHangars.withExtender(list.slots(), active), plan));
            moves.addAll(orbit.bot.level.FitPlanner.drones(active, SiteHangars.droneView(activeJson, list.activeHangar()),
                    list.activeHangar(), plan));
            LOG.info("вне игры: раскладка на «{}» (ангар {}): {} перекладок", list.activeShip(), list.activeHangar(), moves.size());
            return moved + fitMain(site, list, plan, moves, () -> true);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("вне игры: раскладка не вышла ({})", e.getMessage());
            return 0;
        }
    }

    /** Every laser, generator and extra of a hangar taken off to the inventory, config by config (before a ship change). */
    private static void moveOut(orbit.auth.Hangars site, int hangarId, SiteHangars.HangarInfo hangar) throws InterruptedException {
        for (var c : hangar.fittedIds().entrySet()) {
            for (String set : java.util.List.of("lasers", "generators", "extras")) {
                java.util.List<String> ids = c.getValue().getOrDefault(set, java.util.List.of());
                if (ids.isEmpty()) {
                    continue;
                }
                Thread.sleep(GAP_MS);
                try {
                    site.move(hangarId, ids, c.getKey(), false, "ship", set);
                    LOG.info("пересадка: снял из конфига {} «{}»: {} шт. ({})", c.getKey(), set, ids.size(), ids);
                } catch (java.io.IOException | RuntimeException e) {
                    LOG.warn("пересадка: из конфига {} «{}» не снять ({})", c.getKey(), set, e.getMessage());
                }
            }
        }
    }

    /** Within this of an own HQ station the site lets the gear change in the game. */
    static final double STATION_RANGE = 1_500;

    /** The ship by its company's HQ station on this map (the base). */
    static boolean atHomeStation(WorldFeed feed) {
        var w = feed.snapshot();
        if (w == null || w.hero() == null) {
            return false;
        }
        return w.assets().values().stream().anyMatch(a -> a.name() != null && a.name().contains("HQ")
                && a.factionId() == w.hero().factionId()
                && Math.hypot(a.x() - w.hero().x(), a.y() - w.hero().y()) <= STATION_RANGE);
    }

    /** The fitting's moves done on the site, one by one with a person's pause; the number that went through. */
    private static int apply(orbit.auth.Hangars site, int hangarId, java.util.List<orbit.bot.level.FitPlanner.Move> moves)
            throws InterruptedException {
        return apply(site, hangarId, moves, () -> true);
    }

    /**
     * As above, stopped once {@code still} is false (in the game: the ship left the base station — live 2026-11-25: the last
     * four moves were refused after it flew off, the rest is fitted at the next re-login).
     */
    private static int apply(orbit.auth.Hangars site, int hangarId, java.util.List<orbit.bot.level.FitPlanner.Move> moves,
                             BooleanSupplier still) throws InterruptedException {
        int done = 0;
        for (var m : moves) {
            Thread.sleep(GAP_MS);
            if (!still.getAsBoolean()) {
                LOG.info("раскладка: корабль ушёл от станции базы — остальные {} перекладок позже", moves.size() - done);
                break;
            }
            try {
                if (m.droneId() != null) {
                    site.moveDrone(hangarId, java.util.List.of(m.itemId()), m.config(), m.toShip(), m.droneId());
                } else {
                    site.move(hangarId, java.util.List.of(m.itemId()), m.config(), m.toShip(), "ship", m.slotset());
                }
                LOG.info("раскладка: {}", m.describe());
                done++;
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("раскладка: не вышло — {} ({})", m.describe(), e.getMessage());
            }
        }
        return done;
    }

    /**
     * The main ship's moves; with an extender changed among them the hangar is read again and what is left fitted at once:
     * the old extender taken off drops the places, and the site puts the extras over them in the inventory (live 2026-11-26
     * on st2: SLE-03 off for the SLE-04 — AROL-X, RLLB-X, G3X-CRGO-X went to the inventory till the next round).
     */
    private static int fitMain(orbit.auth.Hangars site, SiteHangars.ListInfo list, LevelPlan plan,
                               java.util.List<orbit.bot.level.FitPlanner.Move> moves, BooleanSupplier still)
            throws InterruptedException {
        int done = apply(site, list.activeHangar(), moves, still);
        boolean extender = moves.stream().anyMatch(m -> m.droneId() == null && "extras".equals(m.slotset())
                && "sle".equals(orbit.bot.level.FitPlanner.kind(m.loot())));
        if (!extender || !still.getAsBoolean()) {
            return done;
        }
        try {
            Thread.sleep(GAP_MS);
            var json = site.hangarJson(list.activeHangar());
            SiteHangars.HangarInfo view = SiteHangars.hangar(json, list.activeHangar());
            var again = new java.util.ArrayList<>(orbit.bot.level.FitPlanner.plan(view, list.activeHangar(),
                    SiteHangars.withExtender(list.slots(), view), plan));
            again.addAll(orbit.bot.level.FitPlanner.drones(view, SiteHangars.droneView(json, list.activeHangar()),
                    list.activeHangar(), plan));
            if (!again.isEmpty()) {
                LOG.info("раскладка: расширитель сменён — ещё {} перекладок (то, что сайт выкинул в инвентарь)", again.size());
                done += apply(site, list.activeHangar(), again, still);
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("раскладка: после смены расширителя ангар не прочитан ({})", e.getMessage());
        }
        return done;
    }

    /** Gear moves waiting for the base station since when, per account. */
    private static final java.util.Map<String, Long> PENDING_SINCE = new java.util.concurrent.ConcurrentHashMap<>();
    static final long REFIT_AFTER_MS = 10 * 60_000L;
    static final long PENDING_LOOK_MS = 10 * 60_000L;

    /** The last state the advisor read, per account: the auction's bids use it (with the game's money of the moment). */
    private static final java.util.Map<String, AccountState> LAST_STATE = new java.util.concurrent.ConcurrentHashMap<>();

    static AccountState lastState(String account) {
        return LAST_STATE.get(account);
    }

    /** No second switch sooner than this after the last one: a choice gets time to work and be measured. */
    static final long SWITCH_GAP_MS = 30 * 60_000L;
    /** The last switch per account. */
    private static final java.util.Map<String, Long> SWITCHED_AT = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The strategist's choice applied (with the user's consent, 2026-11-25): the header's live switch, only from a config of
     * the leveling folder to another one there that exists, not sooner than {@link #SWITCH_GAP_MS} after the last switch.
     */
    private static void switchTo(String account, orbit.bot.level.Strategy.Decision d, String cfg,
                                 java.util.function.Consumer<String> switchConfig) {
        String to = d.config();
        if (to == null || to.equals(cfg)) {
            return;
        }
        ConfigStore store = ConfigStore.openDefault();
        if (!ConfigStore.LEVELING_FOLDER.equals(store.folderOf(cfg)) || !store.exists(to)
                || !ConfigStore.LEVELING_FOLDER.equals(store.folderOf(to))) {
            LOG.warn("стратег: «{}» → «{}» не переключаю (не оба конфига из папки {})", cfg, to, ConfigStore.LEVELING_FOLDER);
            return;
        }
        long now = System.currentTimeMillis();
        long last = SWITCHED_AT.getOrDefault(account, 0L);
        if (now - last < SWITCH_GAP_MS) {
            LOG.info("стратег: «{}» → «{}» — позже, с прошлой смены {} мин", cfg, to, (now - last) / 60_000);
            return;
        }
        SWITCHED_AT.put(account, now);
        LOG.info("стратег: переключаю «{}» → «{}» ({})", cfg, to, d.reason());
        switchConfig.accept(to);
    }

    static final String ECO = "ammunition_rocketlauncher_eco-10";
    /** ECO-10 bought when fewer than this are left, this many at a time (1 500 credits each). */
    static final long ECO_BELOW = 50;
    static final int ECO_AMOUNT = 100;

    /**
     * Rockets for a launcher on the ship (a user's choice, 2026-11-25): with a launcher fitted and fewer than {@link #ECO_BELOW}
     * ECO-10 left, {@link #ECO_AMOUNT} are bought in the shop from the credits above the plan's reserve (ammunition, like the
     * lasers' — not held back for the equipment). The launcher CPU fires them; the config selects ECO-10.
     */
    /** The game session the CPUs were last switched on in, per account (each login is a new one). */
    private static final java.util.Map<String, Object> CPUS_ON = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * The rocket CPUs (AROL-X auto rockets, RLLB-X the launcher's auto load) are switched on as the client's «CPUs» item
     * section does it: the item of category {@code cpus} activated ({@code MenuActionRequest}) when it is not selected — a
     * user's choice 2026-11-26 (bought but never on; the item text: «activated on the map in the CPUs section»). The CPUs the
     * game lists are told once per game session.
     */
    static final String X = "drone_formation_f-3d-x";
    static final String MOTH = "drone_formation_f-09-mo";

    /** The ship's formation and the leveling formations owned, told every round (to see the formation X take). */
    private static void formations(GameConnection conn, WorldFeed feed, String account) {
        var w = feed.snapshot();
        if (w == null || w.hero() == null) {
            return;
        }
        java.util.List<String> owned = new java.util.ArrayList<>();
        for (String f : java.util.List.of(X, MOTH)) {
            var item = w.items().get(f);
            if (item != null && item.available()) {
                owned.add(f + (item.selected() ? " (выбрана)" : ""));
            }
        }
        LOG.info("формации: на корабле №{}; есть: {}", w.hero().formation(), owned.isEmpty() ? "нет" : owned);
    }

    private static String state(orbit.world.WorldSnapshot w, String id) {
        var i = w.items().get(id);
        return i == null ? "нет в игре" : (i.selected() ? "вкл" : "выкл") + (i.available() ? "" : " (недоступен)");
    }

    private static void cpus(GameConnection conn, WorldFeed feed, String account) {
        var w = feed.snapshot();
        Object session = conn.session();
        if (w == null || w.hero() == null || session == null) {
            return;
        }
        var cpus = w.items().values().stream().filter(i -> "cpus".equals(i.category())).toList();
        if (session != CPUS_ON.get(account)) {
            CPUS_ON.put(account, session);
            LOG.info("процессоры в игре: {}", cpus.stream()
                    .map(i -> i.id() + (i.selected() ? " (вкл)" : " (выкл)") + (i.available() ? "" : " (недоступен)")).toList());
        }
        // every round: the rocket CPUs, the launcher's load and its rockets — to see where the launcher stops
        var launcher = w.items().values().stream().filter(i -> i.id().startsWith("equipment_weapon_rocketlauncher_"))
                .findFirst().orElse(null);
        var eco = w.items().get(ECO);
        LOG.info("ракетомёт: AROL-X {}, RLLB-X {}; {}; ECO-10 {}", state(w, "equipment_extra_cpu_arol-x"),
                state(w, "equipment_extra_cpu_rllb-x"),
                launcher == null ? "ракетомёта в игре нет" : launcher.id() + " заряд " + (long) launcher.counter() + "/"
                        + (long) launcher.maxCounter() + (launcher.available() ? "" : " (недоступен)"),
                eco == null ? "нет" : (long) eco.counter() + " шт." + (eco.selected() ? ", выбраны" : ", не выбраны"));
        for (var i : cpus) {
            if ((i.id().contains("arol") || i.id().contains("rllb")) && !i.selected() && i.available()) {
                orbit.protocol.gen.MenuActionRequest m = new orbit.protocol.gen.MenuActionRequest();
                m.actionType = (short) orbit.protocol.gen.MenuActionRequest.ACTIONTYPE_ACTIVATE;
                m.sourceType = (short) (w.slotItems().contains(i.id()) ? orbit.protocol.gen.MenuActionRequest.SOURCETYPE_SLOT_BAR
                        : orbit.protocol.gen.MenuActionRequest.SOURCETYPE_ITEM_BAR);
                m.menuItemId = i.id();
                conn.send(m);
                LOG.info("процессоры: включаю {}", i.id());
            }
        }
    }


    private static void launcherRockets(AuthClient auth, String origin, AccountState state, LevelPlan plan, WorldFeed feed)
            throws InterruptedException {
        boolean launcher = state.fitted().values().stream().anyMatch(c -> c.getOrDefault("heavy_guns", java.util.List.of())
                .stream().anyMatch(i -> i.contains("rocketlauncher")));
        var w = feed.snapshot();
        if (!launcher || w == null || w.hero() == null) {
            return;
        }
        var item = w.items().get(ECO);
        long have = item == null ? 0 : (long) item.counter();
        if (have >= ECO_BELOW) {
            return;
        }
        try {
            Thread.sleep(GAP_MS);
            var offers = new orbit.auth.Shop(auth.http(), origin).byLoot("Ammo");
            var offer = offers.get(ECO);
            if (offer == null) {
                LOG.warn("ракеты: ECO-10 в магазине нет");
                return;
            }
            long cost = (long) offer.price() * ECO_AMOUNT;
            long free = (long) w.hero().credits() - plan.reserveCredits();
            if (free < cost) {
                LOG.info("ракеты: ECO-10 {} шт., на {} нужно {} кредитов, свободно {}", have, ECO_AMOUNT, cost, Math.max(0, free));
                return;
            }
            Thread.sleep(GAP_MS);
            String answer = new orbit.auth.Shop(auth.http(), origin).purchase(offer, ECO_AMOUNT, "Ammo");
            LOG.info("ракеты: ECO-10 было {}, {} {} за {} кредитов — сайт: {}", have,
                    orbit.auth.Shop.refused(answer) ? "не куплено" : "куплено", ECO_AMOUNT, cost, answer);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("ракеты: не вышло ({})", e.getMessage());
        }
    }

    /** Robots per collector the site allows. */
    static final int ROBOTS_MAX = 12;
    static final long ROBOT_PRICE = 250;

    /**
     * The Skylab by the plan (docs/SKYLAB-LEVELING.md, the user's choices 2026-11-25): the next upgrade of the stages A–D from
     * the credits left after the reserve and the current equipment purchase, urgent only for promerium and seprom with more
     * than 800 000 uridium left; then robots for the collectors (250 credits, up to 12 each). Everything to the leveling log.
     */
    private static void skylab(AuthClient auth, String origin, AccountState state, LevelPlan plan, Advisor.Advice advice,
                               WorldFeed feed) throws InterruptedException {
        try {
            Thread.sleep(GAP_MS);
            var info = new orbit.auth.Skylab(auth.http(), origin).read();
            Thread.sleep(GAP_MS);
            var site = new orbit.auth.SkylabUpgrades(auth.http(), origin);
            var page = site.read();
            java.util.Map<String, Long> storage = new java.util.HashMap<>();
            info.ores().forEach(o -> storage.put(o.name(), o.amount()));
            java.util.Map<String, orbit.bot.level.SkylabPlanner.Price> prices = new java.util.HashMap<>();
            page.prices().forEach((m, p) -> prices.put(m, new orbit.bot.level.SkylabPlanner.Price(p.credits(), p.prometium(),
                    p.endurium(), p.terbium(), p.minutes(), p.instantUridium())));
            var sky = new orbit.bot.level.SkylabPlanner.State(page.levels(), prices, page.running(), storage);
            long equipment = advice.goal() != null && advice.goal().buy() != null && !advice.goal().buy().uridium()
                    && advice.missing() > 0 ? advice.goal().buy().price() : 0;
            // the game's credits now: a purchase earlier in this round is already off
            var now = feed.snapshot();
            long credits = now != null && now.hero() != null ? (long) now.hero().credits() : state.credits();
            long uridium = now != null && now.hero() != null ? (long) now.hero().uridium() : state.uridium();
            long free = credits - plan.reserveCredits() - equipment;
            java.util.List<orbit.bot.level.SkylabPlanner.Stage> stages;
            try (var in = LevelPlan.class.getResourceAsStream(LevelPlan.RESOURCE)) {
                stages = orbit.bot.level.SkylabPlanner.stages(orbit.config.Json.MAPPER.readTree(in));
            }
            var d = orbit.bot.level.SkylabPlanner.next(sky, stages, free, uridium);
            LOG.info("Скайлаб: уровни {}; идёт {}; склад {}; свободно кредитов {} (после резерва {} и снаряжения {}) — {}",
                    page.levels(), page.running(), storage, free, plan.reserveCredits(), equipment, d.reason());
            if (d.module() != null) {
                Thread.sleep(GAP_MS);
                var after = site.upgrade(d.module(), d.instant(), page.token());
                boolean started = after.running().contains(d.module())
                        || after.levels().getOrDefault(d.module(), 0) > page.levels().getOrDefault(d.module(), 0);
                LOG.info("Скайлаб: улучшение «{}» {} — {}", d.module(), d.instant() ? "срочно" : "обычно",
                        started ? "началось" : "не видно на странице (идёт: " + after.running() + ", уровни " + after.levels() + ")");
                if (started) {
                    free -= prices.get(d.module()).credits();
                }
            }
            // robots for the collectors: 250 credits each, from what is left after the reserve and the equipment
            for (String c : orbit.auth.SkylabUpgrades.COLLECTORS) {
                int have = page.robots().getOrDefault(c, 0);
                while (have < ROBOTS_MAX && free >= ROBOT_PRICE) {
                    Thread.sleep(GAP_MS / 2);
                    String answer = site.buyRobot(c);
                    boolean ok = answer.contains("\"OK\"");
                    LOG.info("Скайлаб: робот на «{}» за {} кредитов — {}", c, ROBOT_PRICE, ok ? "куплен" : "не куплен: " + answer);
                    if (!ok) {
                        break;
                    }
                    have++;
                    free -= ROBOT_PRICE;
                }
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("Скайлаб: не вышло ({})", e.getMessage());
        }
    }

    /** The leveling folder's start config for a faction (1 MMO, 2 EIC, 3 VRU): its quests; {@code null} unknown. */
    static String startConfig(int faction) {
        return switch (faction) {
            case 1 -> "Leveling MMO Quests";
            case 2 -> "Leveling EIC Quests";
            case 3 -> "Leveling VRU Quests";
            default -> null;
        };
    }

    /**
     * A purchase of the plan (with the user's consent, 2026-11-25): the shop tab read, the item found by loot id, its price
     * set against the plan's (a different one — no purchase: the plan is out of date), then bought — at most the shop's
     * limit at once — and the shop's answer logged. The item lands in the inventory; the fitting puts it on.
     */
    /**
     * @param freeCredits credits above the plan's reserve: a drone dearer than the plan is still bought within them — each
     *                    next drone costs more (live 2026-11-26: the second Flax 200 000 against 100 000 in the plan)
     */
    static final String PET = "pet_pet10";

    /** A P.E.T. is bought only when the game said the account has none (a second one is 50 000 uridium lost). */
    private static boolean petUnknown(Advisor.Purchase p, orbit.world.WorldSnapshot snap) {
        return PET.equals(p.item()) && (snap == null || snap.pet() == null || snap.pet().owned());
    }

    /** The Nostromo kit's bids per account, for the auction's planner (renewed every round). */
    private static final java.util.Map<String, java.util.List<LevelPlan.Goal>> KIT_GOALS =
            new java.util.concurrent.ConcurrentHashMap<>();

    static java.util.List<LevelPlan.Goal> kitGoals(String account) {
        return KIT_GOALS.getOrDefault(account, java.util.List.of());
    }

    /**
     * The palladium stage's Nostromo (docs/LEVELING.md «План v2», ПД1–ПД3): its own kit — the second extra of each kind
     * won on the auction, fitted into its hangar with shields in its drones' places, the engines bought and fitted after
     * the P.E.T. It stays out of the main ship's way: never the active ship here, never stripped for it.
     */
    /** @return the Nostromo gave the main ship's extras back now (the main ship's fitting reads its hangar again) */
    private static boolean nostromo(AuthClient auth, String origin, orbit.auth.Hangars site,
                                 com.fasterxml.jackson.databind.JsonNode listJson, SiteHangars.ListInfo list,
                                 SiteHangars.HangarInfo anyView, String account, AccountState state, LevelPlan plan,
                                 WorldFeed feed, java.util.function.Consumer<String> switchConfig, Runnable requestRefit)
            throws InterruptedException {
        orbit.bot.level.PalladiumKit.Kit kit = orbit.bot.level.PalladiumKit.bundled();
        if (kit == null) {
            return false;
        }
        PalladiumStage.resume(account, feed, h -> { }, switchConfig);
        if (PalladiumStage.phase(account) == PalladiumStage.Phase.RUN) {
            PalladiumStage.count(account, feed);
        }
        int nh = list.hangarOf(kit.ship());
        java.util.List<String> missing = orbit.bot.level.PalladiumKit.missingExtras(kit, anyView, nh != 0);
        KIT_GOALS.put(account, orbit.bot.level.PalladiumKit.auctionGoals(missing));
        if (nh == 0 || nh == list.activeHangar()) {
            return false; // not bought yet (ПД1), or flown now (the stage itself)
        }
        try {
            Thread.sleep(GAP_MS);
            var json = site.hangarJson(nh);
            SiteHangars.HangarInfo view = SiteHangars.hangar(json, nh);
            SiteHangars.DroneView drones = SiteHangars.droneView(json, nh);
            var slots = SiteHangars.withExtender(SiteHangars.slotsOf(listJson, kit.ship()), view);
            LevelPlan.Goal after = plan.goals().stream().filter(g -> kit.enginesAfter().equals(g.id())).findFirst().orElse(null);
            boolean engines = after != null && orbit.bot.level.Advisor.done(state, after);
            var moves = orbit.bot.level.PalladiumKit.moves(kit, view, drones, nh, slots, engines, mainKinds(anyView));
            // in the game the site changes a hangar only by the base station («Станция с ангаром…», live 2026-11-26 on
            // st2): there at once, else — waited as the main ship's moves — a re-login and the moves out of the game
            int done = 0;
            boolean gaveBack = false;
            if (!moves.isEmpty() && atHomeStation(feed)) {
                done = apply(site, nh, moves, () -> atHomeStation(feed));
                gaveBack = done > 0 && moves.stream().anyMatch(m -> !m.toShip() && m.droneId() == null);
                PENDING_SINCE.remove(account + ":n");
            } else if (!moves.isEmpty()) {
                long since = PENDING_SINCE.computeIfAbsent(account + ":n", k -> System.currentTimeMillis());
                // the main ship's own extras held by the Nostromo: back at once, not after the wait
                boolean giveBack = moves.stream().anyMatch(m -> !m.toShip() && m.droneId() == null);
                if (giveBack || System.currentTimeMillis() - since >= REFIT_AFTER_MS) {
                    PENDING_SINCE.remove(account + ":n");
                    LOG.info("Nostromo: {} перекладок ждут станцию — перезахожу, раскладка вне игры", moves.size());
                    requestRefit.run();
                }
            } else {
                PENDING_SINCE.remove(account + ":n");
            }
            int lacking = engines ? orbit.bot.level.PalladiumKit.enginesMissing(kit, view, nh, slots) : 0;
            boolean gearOn = collectorGear(site, json, nh, kit);
            LOG.info("Nostromo: ангар {}, слоты {}; с аукциона ещё нужно {}; перекладок {} (сделано {}); двигателей не хватает {}{}",
                    nh, slots, missing.isEmpty() ? "ничего" : missing, moves.size(), done, lacking,
                    engines ? "" : " (двигатели — после P.E.T.)");
            // ПД4–ПД6: everything ready — the stage begins (the travel to 5-2 and the two switches there)
            int vh = kit.park().stream().mapToInt(list::hangarOf).filter(x -> x != 0).findFirst().orElse(0);
            String parkShip = vh == 0 ? "" : list.hangars().get(vh);
            // both ships favourites on the site: the game's ship warp (ССД) offers only those
            favourite(site, listJson, list, nh, kit.ship());
            if (vh != 0) {
                favourite(site, listJson, list, vh, parkShip);
            }
            java.util.List<String> notYet = new java.util.ArrayList<>();
            if (!missing.isEmpty()) {
                notYet.add("комплект с аукциона");
            }
            if (!moves.isEmpty()) {
                notYet.add("раскладка Nostromo");
            }
            if (!engines || lacking > 0) {
                notYet.add("двигатели Nostromo");
            }
            if (!gearOn) {
                notYet.add("механизм сбора в P.E.T. Nostromo");
            }
            if (vh == 0) {
                notYet.add("Vengeance");
            }
            if (state.uridium() < kit.uridiumReserve()) {
                notYet.add("уридий на топливо " + state.uridium() + " из " + kit.uridiumReserve());
            }
            if (PalladiumStage.phase(account) == PalladiumStage.Phase.NONE) {
                if (notYet.isEmpty()) {
                    PalladiumStage.start(account, nh, vh, parkShip, feed, h -> { }, switchConfig);
                } else {
                    LOG.info("палладий: этап ещё не готов — нет: {}", String.join("; ", notYet));
                }
            }
            if (lacking > 0 && kit.enginePrice() > 0) {
                long free = Math.max(0, state.uridium() - plan.reserveUridium());
                int can = (int) Math.min(lacking, free / kit.enginePrice());
                if (can > 0) {
                    buy(auth, origin, new orbit.bot.level.Advisor.Purchase(kit.engines().getFirst(), can, kit.enginePrice(), true),
                            state.credits() - plan.reserveCredits());
                }
            }
            return gaveBack;
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("Nostromo: ангар не прочитан ({})", e.getMessage());
            return false;
        }
    }

    /**
     * The resource collector on the Nostromo's P.E.T. (ПД5): a gear of the kit's level (the 3rd), spare or already there,
     * fitted into the P.E.T.'s gears of its hangar in both configs ({@code move} with target {@code pet}, slotset
     * {@code gears}, as the inventory page does). The P.E.T.'s mode and fuel are the palladium config's.
     */
    /** The kinds of extras the main ship holds now ({@link orbit.bot.level.FitPlanner#kind}): the Nostromo minds them. */
    static java.util.Set<String> mainKinds(SiteHangars.HangarInfo main) {
        java.util.Set<String> kinds = new java.util.HashSet<>();
        main.fittedIds().values().forEach(sets -> sets.getOrDefault("extras", java.util.List.of()).forEach(id ->
                main.items().stream().filter(o -> o.id().equals(id)).findFirst()
                        .ifPresent(o -> kinds.add(orbit.bot.level.FitPlanner.kind(o.loot())))));
        return kinds;
    }

    /**
     * The Nostromo's moves done now (out of the game): what goes back to the main ship first, then its own kit; the
     * engines only when {@code engines} (in the round, after the P.E.T.). The moves that went through.
     */
    static int nostromoMoves(orbit.auth.Hangars site, com.fasterxml.jackson.databind.JsonNode listJson,
                             SiteHangars.ListInfo list, SiteHangars.HangarInfo main, int nh,
                             orbit.bot.level.PalladiumKit.Kit kit, boolean engines, String prefix) throws InterruptedException {
        try {
            Thread.sleep(GAP_MS);
            var json = site.hangarJson(nh);
            SiteHangars.HangarInfo view = SiteHangars.hangar(json, nh);
            var slots = SiteHangars.withExtender(SiteHangars.slotsOf(listJson, kit.ship()), view);
            var moves = orbit.bot.level.PalladiumKit.moves(kit, view, SiteHangars.droneView(json, nh), nh, slots, engines,
                    mainKinds(main));
            LOG.info("{}Nostromo (ангар {}): {} перекладок", prefix, nh, moves.size());
            return moves.isEmpty() ? 0 : apply(site, nh, moves);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("{}Nostromo: раскладка не вышла ({})", prefix, e.getMessage());
            return 0;
        }
    }

    /** Marks the ship a favourite on the site if it is not one yet (for the game's ship warp, ССД). */
    private static void favourite(orbit.auth.Hangars site, com.fasterxml.jackson.databind.JsonNode listJson,
                                  SiteHangars.ListInfo list, int hangarId, String ship) throws InterruptedException {
        if (SiteHangars.favourite(listJson, ship)) {
            return;
        }
        Thread.sleep(GAP_MS);
        try {
            site.setFavourite(list.activeHangar(), hangarId, true);
            LOG.info("избранное: {} (ангар {}) отмечен избранным — для ССД", ship, hangarId);
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("избранное: {} не отмечен ({})", ship, e.getMessage());
        }
    }

    /** @return the collector is on the Nostromo's P.E.T. in both configs (after the moves of this call) */
    private static boolean collectorGear(orbit.auth.Hangars site, com.fasterxml.jackson.databind.JsonNode json, int nh,
                                         orbit.bot.level.PalladiumKit.Kit kit) throws InterruptedException {
        if (kit.collector() == null || kit.collector().isEmpty()) {
            return true;
        }
        var all = SiteHangars.gears(json, kit.collector());
        if (!all.isEmpty()) {
            LOG.info("механизм сбора: {}", all.stream().map(g -> g.id() + " уровень " + g.level()
                    + (g.hangar() == 0 ? " (в запасе)" : " (ангар " + g.hangar() + ")")).toList());
        }
        var gear = all.stream().filter(g -> g.level() >= kit.collectorLevel() && (g.hangar() == 0 || g.hangar() == nh))
                .findFirst().orElse(null);
        if (gear == null) {
            return false;
        }
        var onPet = orbit.auth.Hangars.petItems(json, nh, "gears");
        boolean both = true;
        for (int config : new int[] {1, 2}) {
            if (onPet.getOrDefault(config, java.util.List.of()).contains(gear.id())) {
                continue;
            }
            both = false;
            Thread.sleep(GAP_MS);
            try {
                site.move(nh, java.util.List.of(gear.id()), config, true, "pet", "gears");
                LOG.info("механизм сбора: поставил {} в P.E.T. Nostromo, конфиг {}", gear.id(), config);
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("механизм сбора: в P.E.T. Nostromo, конфиг {} — не вышло ({})", config, e.getMessage());
            }
        }
        return both;
    }

    /** Drone places of an account (the game's limit). */
    static final int DRONE_PLACES = 8;

    /**
     * Iris in place of Flax (docs/LEVELING.md «План v2», the user's choice 2026-11-26): with all {@link #DRONE_PLACES} taken
     * and a Flax among them, the weakest Flax (lowest level) is sold, so the Iris won on the auction has a place — one at a
     * time: seven drones then, the next one only once an Iris came.
     */
    static void sellFlaxForIris(orbit.auth.Hangars site, int hangarId, SiteHangars.DroneView drones) throws InterruptedException {
        long flax = drones.drones().stream().filter(d -> "drone_flax".equals(d.loot())).count();
        long iris = drones.drones().stream().filter(d -> "drone_iris".equals(d.loot())).count();
        LOG.info("дроны: всего {}, Flax {}, Iris {}", drones.drones().size(), flax, iris);
        if (drones.drones().size() < DRONE_PLACES || flax == 0) {
            return;
        }
        SiteHangars.Drone weakest = drones.drones().stream().filter(d -> "drone_flax".equals(d.loot()))
                .min(java.util.Comparator.comparingInt(SiteHangars.Drone::level).thenComparing(SiteHangars.Drone::id))
                .orElseThrow();
        Thread.sleep(GAP_MS);
        try {
            site.sellDrone(hangarId, weakest.id());
            LOG.info("дроны: продал Flax {} (уровень {}) — место под Iris с аукциона", weakest.id(), weakest.level());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("дроны: Flax {} не продан ({})", weakest.id(), e.getMessage());
        }
    }

    /** The level of a P.E.T. gear the leveling buys (the resource collector: the 3rd, from P.E.T. 8). */
    static final int GEAR_LEVEL = 3;

    /** @return true when the site took the purchase */
    private static boolean buy(AuthClient auth, String origin, Advisor.Purchase p, long freeCredits) throws InterruptedException {
        String tab = orbit.auth.Shop.tabOf(p.item());
        if (tab == null) {
            LOG.warn("магазин: не знаю вкладку для {}", p.item());
            return false;
        }
        try {
            Thread.sleep(GAP_MS);
            var offers = new orbit.auth.Shop(auth.http(), origin).byLoot(tab);
            var item = offers.get(p.item());
            if (item == null) {
                LOG.warn("магазин: {} на вкладке {} нет (товаров {})", p.item(), tab, offers.size());
                return false;
            }
            String cur = item.uridium() ? "уридия" : "кредитов";
            // a P.E.T. gear has levels; the leveling buys the 3rd only (the 1st and 2nd are no use — the user's choice
            // 2026-11-26): the shop sells it once the P.E.T. allows ({@code maxLevel}, the 3rd from P.E.T. 8)
            int level = 0;
            if (!item.levelPrices().isEmpty()) {
                level = GEAR_LEVEL;
                if (item.maxLevel() < level) {
                    LOG.info("магазин: {} — в продаже только до уровня {} (нужен {}: P.E.T. ещё мал) — жду", p.item(),
                            item.maxLevel(), level);
                    return false;
                }
                Double levelPrice = item.levelPrices().get(level);
                if (levelPrice == null || levelPrice <= 0 || levelPrice > p.price()) {
                    LOG.warn("магазин: {} уровня {} — цена {}, в плане {} — не покупаю", p.item(), level, levelPrice, p.price());
                    return false;
                }
                Thread.sleep(GAP_MS);
                String answer = new orbit.auth.Shop(auth.http(), origin).purchase(item, 1, tab, level);
                if (orbit.auth.Shop.refused(answer)) {
                    LOG.warn("магазин: {} уровня {} не куплен — сайт: {}", p.item(), level, answer);
                    return false;
                }
                LOG.info("магазин: куплен {} уровня {} за {} {} — сайт: {}", p.item(), level, levelPrice.longValue(), cur, answer);
                return true;
            }
            // dearer than the plan (or another currency): the plan is out of date; cheaper is bought (live 2026-11-26: the
            // G3N-7900 was 1 900 uridium against the plan's 2 000 and none were bought all night)
            // a drone's price grows with each one owned (Flax 100 000, 190 000, … 800 000 for the fourth, live 2026-11-26):
            // dearer than the plan, one is bought while the free credits cover it — the next round sees the next price
            // … up to the goal's ceiling (Flax: 800 000, a user's choice 2026-11-26 — the fifth was 1 520 000)
            long ceiling = LevelPlan.bundled().maxPrice(p.item());
            boolean dearerDrone = p.item().startsWith("drone_") && !item.uridium() && !p.uridium()
                    && (long) item.price() > p.price() && (long) item.price() <= freeCredits
                    && (ceiling <= 0 || (long) item.price() <= ceiling);
            if (p.item().startsWith("drone_") && ceiling > 0 && (long) item.price() > ceiling) {
                LOG.info("магазин: {} — {} кредитов, выше потолка {} — не покупаю", p.item(), (long) item.price(), ceiling);
                return false;
            }
            if (dearerDrone) {
                LOG.info("магазин: {} подорожал — {} кредитов (в плане {}), свободно {} — покупаю один", p.item(),
                        (long) item.price(), p.price(), freeCredits);
            }
            if (!dearerDrone && ((long) item.price() > p.price() || item.uridium() != p.uridium())) {
                LOG.warn("магазин: цена {} — {} {}, в плане {} {} — план устарел, не покупаю", p.item(), (long) item.price(), cur,
                        p.price(), p.uridium() ? "уридия" : "кредитов");
                return false;
            }
            int amount = dearerDrone ? 1 : item.maxAmount() > 0 ? Math.min(p.amount(), item.maxAmount()) : p.amount();
            Thread.sleep(GAP_MS);
            String answer = new orbit.auth.Shop(auth.http(), origin).purchase(item, amount, tab);
            if (orbit.auth.Shop.refused(answer)) {
                LOG.warn("магазин: {} × {} не куплено — сайт: {}", amount, p.item(), answer);
            } else {
                LOG.info("магазин: куплено {} × {} за {} {} — сайт: {}", amount, p.item(), (long) item.price() * amount, cur,
                        answer);
                return true;
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("магазин не прочитан ({})", e.getMessage());
        }
        return false;
    }

    /**
     * The auction's bids the plan would place (docs/LEVELING.md «Аукцион»): the hourly page and the daily list read, the
     * plan logged — nothing is bid.
     */
    private static void auction(AuthClient auth, String origin, AccountState state, LevelPlan plan, String account)
            throws InterruptedException {
        try {
            Thread.sleep(GAP_MS);
            var page = auth.http().get(origin + "/indexInternal.es?action=internalAuction",
                    origin + "/indexInternal.es?action=internalStart");
            orbit.bot.level.AuctionBoard board = orbit.bot.level.AuctionBoard.parse(page.body());
            Thread.sleep(GAP_MS);
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            form.put("command", "getAuctionList");
            form.put("category", "day");
            form.put("list", "current");
            var day = auth.http().postForm(origin + "/ajax/auction.php", form, origin + "/indexInternal.es?action=internalAuction");
            String code = orbit.config.Json.MAPPER.readTree(day.body() == null ? "{}" : day.body()).path("code").asText("");
            board = board.with(orbit.bot.level.AuctionBoard.parse(code));
            LOG.info("аукцион: лотов {} (час {}, день {}), до конца часа {} с, дня {} с", board.lots().size(),
                    board.of("hour").size(), board.of("day").size(), board.secondsLeft("hour"), board.secondsLeft("day"));
            LOG.info("ставки по плану сейчас (ставит поток аукциона в последние минуты часа): {}",
                    orbit.bot.level.BidPlanner.plan(state, plan.withGoals(kitGoals(account)), board).describe());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("аукцион не прочитан ({})", e.getMessage());
        }
    }

    /** One look: the site's list and active hangar, the game's level and money, the advice. True when the shape was logged. */
    private static boolean round(AuthClient auth, GameConnection conn, WorldFeed feed, String account, String cfg,
                                 LevelPlan plan, boolean logShape, Supplier<String> status, Meter meter,
                                 java.util.function.Consumer<String> switchConfig, java.util.function.IntConsumer requestShip,
                                 Runnable refresh, Runnable requestRefit, boolean first) throws InterruptedException {
        if (HandPlay.active(account)) {
            LOG.info("советчик: играют руками в клиенте — пропускаю (без покупок и перезаходов)");
            return false;
        }
        var session = conn.session();
        if (session == null || session.host() == null) {
            LOG.info("советчик: нет игровой сессии — пропускаю");
            return false;
        }
        orbit.auth.Hangars site = new orbit.auth.Hangars(auth.http(), session.host());
        SiteHangars.ListInfo list;
        SiteHangars.HangarInfo hangar = null;
        SiteHangars.DroneView drones = null;
        com.fasterxml.jackson.databind.JsonNode listJson;
        try {
            listJson = site.listJson();
            list = SiteHangars.list(listJson);
            if (list.activeHangar() != 0) {
                Thread.sleep(GAP_MS);
                var hangarJson = site.hangarJson(list.activeHangar());
                hangar = SiteHangars.hangar(hangarJson, list.activeHangar());
                drones = SiteHangars.droneView(hangarJson, list.activeHangar());
                LOG.info("дроны на сайте: {}", SiteHangars.drones(hangarJson, list.activeHangar()));
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("советчик: сайт не прочитан ({}) — повторю в следующий раз", e.getMessage());
            return false;
        }
        int level = 0;
        long credits = -1;
        long uridium = -1;
        int faction = 0;
        var snap = feed.snapshot();
        if (snap != null && snap.hero() != null) {
            faction = snap.hero().factionId();
            level = snap.hero().level();
            credits = (long) snap.hero().credits();
            uridium = (long) snap.hero().uridium();
        }
        AccountState state = SiteHangars.state(list, hangar, level, credits, uridium);
        if (snap != null && snap.pet() != null && snap.pet().owned() && !state.spare().containsKey(PET)) {
            // the site lists no P.E.T. among the items: the game's word counts it as owned (never bought twice)
            java.util.Map<String, Integer> spare = new java.util.HashMap<>(state.spare());
            spare.put(PET, 1);
            state = new AccountState(state.level(), state.credits(), state.uridium(), state.activeHangar(),
                    state.activeShip(), state.ownedShips(), state.slots(), state.fitted(), spare, state.drones());
        }
        LAST_STATE.put(account, state);
        if (feed.stats() != null) {
            // the laser count for the laser ammunition bought by the ship's growth (BuyWatch, modules.buyScale)
            feed.stats().lasers = state.fitted().values().stream()
                    .mapToInt(c -> c.getOrDefault("lasers", java.util.List.of()).size()).max().orElse(0);
        }
        Advisor.Advice advice = Advisor.advise(state, plan);
        LOG.info("снимок [{}, конфиг «{}», план v{}]: {}; премиум {}", account, cfg, plan.version(), state.describe(),
                snap == null || snap.hero() == null ? "?" : snap.hero().premium() ? "да" : "нет");
        LOG.info("совет: выполнено {}; {}", advice.done(), advice.text());
        if (advice.purchase() != null) {
            LOG.info("покупаю по плану: {} × {} = {} {} ({})", advice.purchase().amount(), advice.purchase().item(),
                    advice.purchase().total(), advice.purchase().uridium() ? "уридия" : "кредитов", advice.goal().id());
            if (petUnknown(advice.purchase(), snap)) {
                LOG.info("покупка P.E.T.: игра ещё не сказала, есть ли он — не покупаю");
            } else {
                buy(auth, session.host(), advice.purchase(), state.credits() - plan.reserveCredits());
            }
        }
        if (advice.other() != null) {
            LOG.info("покупаю в другой валюте: {} × {} = {} {}", advice.other().amount(), advice.other().item(),
                    advice.other().total(), advice.other().uridium() ? "уридия" : "кредитов");
            if (petUnknown(advice.other(), snap)) {
                LOG.info("покупка P.E.T.: игра ещё не сказала, есть ли он — не покупаю");
            } else {
                buy(auth, session.host(), advice.other(), state.credits() - plan.reserveCredits());
            }
        }
        // the Nostromo first: what it gives back to the main ship is fitted on it right below, in this round
        if (hangar != null
                && nostromo(auth, session.host(), site, listJson, list, hangar, account, state, plan, feed, switchConfig, requestRefit)) {
            try {
                Thread.sleep(GAP_MS);
                var fresh = site.hangarJson(list.activeHangar());
                hangar = SiteHangars.hangar(fresh, list.activeHangar());
                drones = SiteHangars.droneView(fresh, list.activeHangar());
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("раскладка: ангар после Nostromo не прочитан ({})", e.getMessage());
            }
        }
        if (hangar != null) {
            // a better ship owned (won on the auction, bought): its gear moved over and the bot sent there (user's consent
            // 2026-11-25); the fitting is done there after the login
            String best = plan.bestShip(list.ownedShips());
            int bestHangar = best == null ? 0 : list.hangarOf(best);
            int nowRank = plan.ships().indexOf(list.activeShip());
            boolean better = best != null && bestHangar != 0 && bestHangar != list.activeHangar()
                    && (nowRank < 0 || plan.ships().indexOf(best) < nowRank);
            if (better && PalladiumStage.holds(account)) {
                LOG.info("пересадка: этап «Палладий» — остаюсь на «{}»", list.activeShip());
            } else if (better) {
                // the gear is moved over out of the game, between the logout and the login (OrbitMain → fitOffline): in the
                // game the site refuses gear changes away from the base station (live 2026-11-25)
                LOG.info("пересадка: лучший корабль «{}» в ангаре {}, сейчас «{}» в {} — пересаживаюсь, снаряжение — вне игры",
                        best, bestHangar, list.activeShip(), list.activeHangar());
                requestShip.accept(bestHangar);
                return false;
            }
            // the extras with the slot extender's places (the site lists the ship's own alone)
            var moves = new java.util.ArrayList<>(orbit.bot.level.FitPlanner.plan(hangar, list.activeHangar(),
                    SiteHangars.withExtender(list.slots(), hangar), plan));
            if (drones != null) {
                moves.addAll(orbit.bot.level.FitPlanner.drones(hangar, drones, list.activeHangar(), plan));
            }
            if (moves.isEmpty()) {
                PENDING_SINCE.remove(account);
                LOG.info("раскладка: всё на местах");
            } else if (!atHomeStation(feed)) {
                // in the game the site changes gear only by the base station (live 2026-11-25): half done is worse; waiting
                // longer than REFIT_AFTER_MS — a re-login, the gear fitted out of the game (user's choice, 2026-11-25)
                // the first look of a session: gear already waiting (bought, won, left from before) — the re-login at once
                long since = PENDING_SINCE.computeIfAbsent(account,
                        k -> System.currentTimeMillis() - (first ? REFIT_AFTER_MS : 0));
                long waited = System.currentTimeMillis() - since;
                if (waited >= REFIT_AFTER_MS) {
                    PENDING_SINCE.remove(account);
                    LOG.info("раскладка: {} перекладок ждут {} мин, база далеко — перезахожу, раскладка вне игры", moves.size(),
                            waited / 60_000);
                    requestRefit.run();
                } else {
                    LOG.info("раскладка: {} перекладок ждут, пока корабль у станции базы (перезаход через {} мин)", moves.size(),
                            (REFIT_AFTER_MS - waited) / 60_000 + 1);
                }
            } else if (fitMain(site, list, plan, moves, () -> atHomeStation(feed)) > 0) {
                PENDING_SINCE.remove(account);
                LOG.info("раскладка: перезахожу, чтобы игра увидела снаряжение");
                refresh.run();
            }
        }
        if (drones != null && list.activeHangar() != 0) {
            sellFlaxForIris(site, list.activeHangar(), drones);
        }
        String moduleStatus = status.get();
        boolean idle = moduleStatus != null && moduleStatus.contains("нечего делать");
        String mapName = snap == null || snap.hero() == null ? null
                : orbit.world.maps.MapCatalog.bundled().info(snap.hero().mapId()).name();
        var rates = meter.rates();
        var decision = orbit.bot.level.Strategy.decide(state, plan, advice, faction, mapName, idle, rates);
        LOG.info("стратег: {} «{}» — {}; модуль: «{}»; карта {}; заработок в час: {}",
                decision.mode(), decision.config(), decision.reason(), moduleStatus, mapName, meter.describe());
        if (PalladiumStage.holds(account)) {
            String own = orbit.bot.level.PalladiumKit.bundled().config();
            LOG.info("стратег: этап «Палладий» ({}) — конфиг не меняю", PalladiumStage.phase(account));
            if (PalladiumStage.phase(account) == PalladiumStage.Phase.RUN && !own.equals(cfg)) {
                switchConfig.accept(own);
            }
        } else {
            switchTo(account, decision, cfg, switchConfig);
        }
        String start = startConfig(faction);
        if (start != null && !cfg.startsWith(start.substring(0, start.lastIndexOf(' ') + 1))) {
            LOG.info("фракция {}: конфиг другой фракции — нужен «{}», сейчас «{}»", faction, start, cfg);
        }
        auction(auth, session.host(), state, plan, account);
        launcherRockets(auth, session.host(), state, plan, feed);
        cpus(conn, feed, account);
        formations(conn, feed, account);
        skylab(auth, session.host(), state, plan, advice, feed);
        if (logShape && hangar != null) {
            LOG.info("поля getHangar по конфигам (без значений): {}", hangar.shape());
            return true;
        }
        return false;
    }
}
