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
                      BooleanSupplier alive, Supplier<String> status, java.util.function.Consumer<String> switchConfig) {
        java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("leveling").start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            java.util.Random random = new java.util.Random();
            boolean shapeLogged = false;
            Meter meter = new Meter();
            try {
                Thread.sleep(FIRST_MS + random.nextInt(60_000));
                LevelPlan plan = LevelPlan.bundled();
                while (alive.getAsBoolean()) {
                    String cfg = config.get();
                    if (cfg != null && ConfigStore.LEVELING_FOLDER.equals(ConfigStore.openDefault().folderOf(cfg))) {
                        meter.sample(feed, cfg);
                        shapeLogged |= round(auth, conn, feed, account, cfg, plan, !shapeLogged, status, meter, switchConfig);
                    }
                    Thread.sleep(EVERY_MS + random.nextInt(120_000));
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

    /** The leveling folder's start config for a faction (1 MMO, 2 EIC, 3 VRU); {@code null} unknown. */
    static String startConfig(int faction) {
        return switch (faction) {
            case 1 -> "Leveling MMO x-1";
            case 2 -> "Leveling EIC x-1";
            case 3 -> "Leveling VRU x-1";
            default -> null;
        };
    }

    /**
     * The shop's own offer for a purchase the plan would make: its tab read, the item found by loot id, its price set
     * against the plan's, and the exact request logged ({@code POST ajax/shop.php}, {@code Shop.purchaseForm}) — not sent.
     */
    private static void shopCheck(AuthClient auth, String origin, Advisor.Purchase p) throws InterruptedException {
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
                LOG.warn("магазин: цена {} — {} {}, в плане {} {} — план устарел", p.item(), (long) item.price(), cur, p.price(),
                        p.uridium() ? "уридия" : "кредитов");
            }
            int amount = item.maxAmount() > 0 ? Math.min(p.amount(), item.maxAmount()) : p.amount();
            LOG.info("магазин: отправил бы POST ajax/shop.php {} (вкладка {}, цена {} {}, за раз не больше {})",
                    orbit.auth.Shop.purchaseForm(item, amount), tab, (long) item.price(), cur,
                    item.maxAmount() > 0 ? item.maxAmount() : "без лимита");
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
            LOG.info("ставки (режим советчика — не ставлю): {}", orbit.bot.level.BidPlanner.plan(state, plan, board).describe());
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("аукцион не прочитан ({})", e.getMessage());
        }
    }

    /** One look: the site's list and active hangar, the game's level and money, the advice. True when the shape was logged. */
    private static boolean round(AuthClient auth, GameConnection conn, WorldFeed feed, String account, String cfg,
                                 LevelPlan plan, boolean logShape, Supplier<String> status, Meter meter,
                                 java.util.function.Consumer<String> switchConfig) throws InterruptedException {
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
        Advisor.Advice advice = Advisor.advise(state, plan);
        LOG.info("снимок [{}, конфиг «{}», план v{}]: {}", account, cfg, plan.version(), state.describe());
        LOG.info("совет: выполнено {}; {}", advice.done(), advice.text());
        if (advice.purchase() != null) {
            LOG.info("купил бы (режим советчика — не покупаю): {} × {} = {} {}", advice.purchase().amount(),
                    advice.purchase().item(), advice.purchase().total(), advice.purchase().uridium() ? "уридия" : "кредитов");
            shopCheck(auth, session.host(), advice.purchase());
        }
        if (hangar != null) {
            var moves = orbit.bot.level.FitPlanner.plan(hangar, list.activeHangar(), list.slots(), plan);
            LOG.info("раскладка (режим советчика — не перекладываю): {}", moves.isEmpty() ? "всё на местах"
                    : String.join("; ", moves.stream().map(orbit.bot.level.FitPlanner.Move::describe).toList()));
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
        if (start != null && !start.equals(cfg)) {
            LOG.info("фракция {}: стартовый конфиг — «{}», сейчас «{}»", faction, start, cfg);
        }
        auction(auth, session.host(), state, plan);
        if (logShape && hangar != null) {
            LOG.info("поля getHangar по конфигам (без значений): {}", hangar.shape());
            return true;
        }
        return false;
    }
}
