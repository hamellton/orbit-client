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
            SiteHangars.ListInfo list = SiteHangars.list(site.listJson());
            int moved = 0;
            for (int other : list.hangars().keySet()) {
                if (other == list.activeHangar()) {
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
            SiteHangars.HangarInfo active = SiteHangars.hangar(site.hangarJson(list.activeHangar()), list.activeHangar());
            var moves = orbit.bot.level.FitPlanner.plan(active, list.activeHangar(), list.slots(), plan);
            LOG.info("вне игры: раскладка на «{}» (ангар {}): {} перекладок", list.activeShip(), list.activeHangar(), moves.size());
            return moved + apply(site, list.activeHangar(), moves);
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
                site.move(hangarId, java.util.List.of(m.itemId()), m.config(), m.toShip(), "ship", m.slotset());
                LOG.info("раскладка: {}", m.describe());
                done++;
            } catch (java.io.IOException | RuntimeException e) {
                LOG.warn("раскладка: не вышло — {} ({})", m.describe(), e.getMessage());
            }
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
    private static void buy(AuthClient auth, String origin, Advisor.Purchase p) throws InterruptedException {
        String tab = orbit.auth.Shop.tabOf(p.item());
        if (tab == null) {
            LOG.warn("магазин: не знаю вкладку для {}", p.item());
            return;
        }
        try {
            Thread.sleep(GAP_MS);
            var offers = new orbit.auth.Shop(auth.http(), origin).byLoot(tab);
            var item = offers.get(p.item());
            if (item == null) {
                LOG.warn("магазин: {} на вкладке {} нет (товаров {})", p.item(), tab, offers.size());
                return;
            }
            String cur = item.uridium() ? "уридия" : "кредитов";
            if ((long) item.price() != p.price() || item.uridium() != p.uridium()) {
                LOG.warn("магазин: цена {} — {} {}, в плане {} {} — план устарел, не покупаю", p.item(), (long) item.price(), cur,
                        p.price(), p.uridium() ? "уридия" : "кредитов");
                return;
            }
            int amount = item.maxAmount() > 0 ? Math.min(p.amount(), item.maxAmount()) : p.amount();
            Thread.sleep(GAP_MS);
            String answer = new orbit.auth.Shop(auth.http(), origin).purchase(item, amount, tab);
            if (orbit.auth.Shop.refused(answer)) {
                LOG.warn("магазин: {} × {} не куплено — сайт: {}", amount, p.item(), answer);
            } else {
                LOG.info("магазин: куплено {} × {} за {} {} — сайт: {}", amount, p.item(), (long) item.price() * amount, cur,
                        answer);
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("магазин не прочитан ({})", e.getMessage());
        }
    }

    /**
     * The auction's bids the plan would place (docs/LEVELING.md «Аукцион»): the hourly page and the daily list read, the
     * plan logged — nothing is bid.
     */
    private static void auction(AuthClient auth, String origin, AccountState state, LevelPlan plan) throws InterruptedException {
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
                    orbit.bot.level.BidPlanner.plan(state, plan, board).describe());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("аукцион не прочитан ({})", e.getMessage());
        }
    }

    /** One look: the site's list and active hangar, the game's level and money, the advice. True when the shape was logged. */
    private static boolean round(AuthClient auth, GameConnection conn, WorldFeed feed, String account, String cfg,
                                 LevelPlan plan, boolean logShape, Supplier<String> status, Meter meter,
                                 java.util.function.Consumer<String> switchConfig, java.util.function.IntConsumer requestShip,
                                 Runnable refresh, Runnable requestRefit, boolean first) throws InterruptedException {
        var session = conn.session();
        if (session == null || session.host() == null) {
            LOG.info("советчик: нет игровой сессии — пропускаю");
            return false;
        }
        orbit.auth.Hangars site = new orbit.auth.Hangars(auth.http(), session.host());
        SiteHangars.ListInfo list;
        SiteHangars.HangarInfo hangar = null;
        try {
            list = SiteHangars.list(site.listJson());
            if (list.activeHangar() != 0) {
                Thread.sleep(GAP_MS);
                hangar = SiteHangars.hangar(site.hangarJson(list.activeHangar()), list.activeHangar());
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
        LAST_STATE.put(account, state);
        Advisor.Advice advice = Advisor.advise(state, plan);
        LOG.info("снимок [{}, конфиг «{}», план v{}]: {}; премиум {}", account, cfg, plan.version(), state.describe(),
                snap == null || snap.hero() == null ? "?" : snap.hero().premium() ? "да" : "нет");
        LOG.info("совет: выполнено {}; {}", advice.done(), advice.text());
        if (advice.purchase() != null) {
            LOG.info("покупаю по плану: {} × {} = {} {} ({})", advice.purchase().amount(), advice.purchase().item(),
                    advice.purchase().total(), advice.purchase().uridium() ? "уридия" : "кредитов", advice.goal().id());
            buy(auth, session.host(), advice.purchase());
        }
        if (advice.other() != null) {
            LOG.info("покупаю в другой валюте: {} × {} = {} {}", advice.other().amount(), advice.other().item(),
                    advice.other().total(), advice.other().uridium() ? "уридия" : "кредитов");
            buy(auth, session.host(), advice.other());
        }
        if (hangar != null) {
            // a better ship owned (won on the auction, bought): its gear moved over and the bot sent there (user's consent
            // 2026-11-25); the fitting is done there after the login
            String best = plan.bestShip(list.ownedShips());
            int bestHangar = best == null ? 0 : list.hangarOf(best);
            int nowRank = plan.ships().indexOf(list.activeShip());
            boolean better = best != null && bestHangar != 0 && bestHangar != list.activeHangar()
                    && (nowRank < 0 || plan.ships().indexOf(best) < nowRank);
            if (better) {
                // the gear is moved over out of the game, between the logout and the login (OrbitMain → fitOffline): in the
                // game the site refuses gear changes away from the base station (live 2026-11-25)
                LOG.info("пересадка: лучший корабль «{}» в ангаре {}, сейчас «{}» в {} — пересаживаюсь, снаряжение — вне игры",
                        best, bestHangar, list.activeShip(), list.activeHangar());
                requestShip.accept(bestHangar);
                return false;
            }
            var moves = orbit.bot.level.FitPlanner.plan(hangar, list.activeHangar(), list.slots(), plan);
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
            } else if (apply(site, list.activeHangar(), moves, () -> atHomeStation(feed)) > 0) {
                PENDING_SINCE.remove(account);
                LOG.info("раскладка: перезахожу, чтобы игра увидела снаряжение");
                refresh.run();
            }
        }
        String moduleStatus = status.get();
        boolean idle = moduleStatus != null && moduleStatus.contains("нечего делать");
        String mapName = snap == null || snap.hero() == null ? null
                : orbit.world.maps.MapCatalog.bundled().info(snap.hero().mapId()).name();
        var rates = meter.rates();
        var decision = orbit.bot.level.Strategy.decide(state, plan, advice, faction, mapName, idle, rates);
        LOG.info("стратег: {} «{}» — {}; модуль: «{}»; карта {}; заработок в час: {}",
                decision.mode(), decision.config(), decision.reason(), moduleStatus, mapName, meter.describe());
        switchTo(account, decision, cfg, switchConfig);
        String start = startConfig(faction);
        if (start != null && !cfg.startsWith(start.substring(0, start.lastIndexOf(' ') + 1))) {
            LOG.info("фракция {}: конфиг другой фракции — нужен «{}», сейчас «{}»", faction, start, cfg);
        }
        auction(auth, session.host(), state, plan);
        launcherRockets(auth, session.host(), state, plan, feed);
        skylab(auth, session.host(), state, plan, advice, feed);
        if (logShape && hangar != null) {
            LOG.info("поля getHangar по конфигам (без значений): {}", hangar.shape());
            return true;
        }
        return false;
    }
}
