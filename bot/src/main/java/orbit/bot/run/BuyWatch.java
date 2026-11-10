package orbit.bot.run;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buys what the config's buy list asks for when it runs low — only those items, never below the money
 * to keep, at most the shop's limit per purchase, and one item at a time, not more than once every
 * {@link #RETRY_MS} per item (the counter may take a moment to come back from the game). The website
 * requests run on their own thread, so the fight never waits for the shop.
 */
final class BuyWatch {

    private static final Logger log = LoggerFactory.getLogger(BuyWatch.class);

    static final long CHECK_MS = 10_000;
    static final long RETRY_MS = 10 * 60_000;

    private final BotContext ctx;
    private volatile ShopAccess shop;
    private final Set<String> told = new HashSet<>();
    private final Map<String, Long> triedAt = new HashMap<>();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shop");
        t.setDaemon(true);
        return t;
    });
    private long nextCheck;
    /** No purchase before the game has listed the items: right after a login nothing is known yet. */
    static final long SETTLE_MS = 30_000;

    /** The account's log context, carried into the shop thread. */
    private final java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();

    BuyWatch(BotContext ctx) {
        this.ctx = ctx;
        this.nextCheck = ctx.now() + SETTLE_MS * 1_000_000;
    }

    void setShop(ShopAccess s) {
        shop = s;
    }

    void tick() {
        long now = ctx.now();
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_MS * 1_000_000;
        BotConfig.Buy b = ctx.config().get().buy();
        if (!b.enabled()) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        if (w.hero().id() == 0 || w.items().isEmpty()) {
            return; // not in game or the item bar not received yet
        }
        for (BotConfig.BuyRule r : b.items()) {
            WorldSnapshot.Item item = w.items().get(r.item());
            if (item == null) {
                // unknown is not zero: never buy what the game has not counted
                if (told.add("?" + r.item())) {
                    log.warn("{}: игра не прислала количество — не покупаю", ItemCatalog.label(r.item()));
                }
                continue;
            }
            double left = item.counter();
            if (left >= r.below()) {
                told.remove(r.item());
                continue;
            }
            if (shop == null) {
                if (told.add(r.item())) {
                    log.warn("Пора докупить {}: осталось {}, по конфигу купить {} (магазин недоступен)",
                            ItemCatalog.label(r.item()), (long) left, r.amount());
                }
                continue;
            }
            Long last = triedAt.get(r.item());
            if (last != null && now - last < RETRY_MS * 1_000_000) {
                continue;
            }
            if (busy.compareAndSet(false, true)) {
                triedAt.put(r.item(), now);
                double credits = w.hero().credits();
                double uridium = w.hero().uridium();
                ShopAccess s = shop;
                worker.execute(() -> {
                    if (logContext != null) {
                        org.slf4j.MDC.setContextMap(logContext);
                    }
                    try {
                        buy(s, r, (long) left, credits, uridium, b);
                    } finally {
                        busy.set(false);
                    }
                });
            }
            return; // one purchase at a time
        }
        boosters(b, w, now);
    }

    /**
     * The config's boosters: one whose time left is under {@code boosterMinutes} (or that has run out) is
     * bought again, for uridium, never below the uridium to keep. Nothing before the game has listed the
     * boosters: unknown is not "none left".
     */
    private void boosters(BotConfig.Buy b, WorldSnapshot w, long now) {
        if (b.boosters().isEmpty()) {
            return;
        }
        if (!w.boosters().known()) {
            if (told.add("?boosters")) {
                log.info("Бустеры: игра ещё не прислала, какие активны — жду");
            }
            return;
        }
        for (String code : b.boosters()) {
            WorldSnapshot.Booster have = w.boosters().active().get(ItemCatalog.boosterLootId(code));
            long left = have == null ? 0 : Math.max(0, have.secondsLeftAt(now));
            if (left > 0 && left >= b.boosterMinutes() * 60L) {
                told.remove("b:" + code);
                continue;
            }
            if (shop == null) {
                if (told.add("b:" + code)) {
                    log.warn("Пора купить бустер {} (магазин недоступен)", code);
                }
                continue;
            }
            Long last = triedAt.get("b:" + code);
            if (last != null && now - last < RETRY_MS * 1_000_000) {
                continue;
            }
            if (busy.compareAndSet(false, true)) {
                triedAt.put("b:" + code, now);
                double uridium = w.hero().uridium();
                ShopAccess s = shop;
                worker.execute(() -> {
                    if (logContext != null) {
                        org.slf4j.MDC.setContextMap(logContext);
                    }
                    try {
                        buyBooster(s, code, left, uridium, b);
                    } finally {
                        busy.set(false);
                    }
                });
            }
            return; // one purchase at a time
        }
    }

    private void buyBooster(ShopAccess s, String code, long left, double uridium, BotConfig.Buy b) {
        try {
            ShopAccess.Offer o = s.boosterOffers().get(code);
            if (o == null || o.price() <= 0) {
                log.warn("Бустер {}: в магазине такого нет — не покупаю", code);
                return;
            }
            if (!o.uridium()) {
                log.warn("Бустер {}: продаётся не за уридий — не покупаю", code);
                return;
            }
            if (uridium <= 0) {
                log.warn("Бустер {}: баланс уридия неизвестен — не покупаю", code);
                return;
            }
            if (uridium - o.price() < b.keepUridium()) {
                log.warn("Бустер {}: не хватает уридия (есть {}, запас {}, цена {})", code, (long) uridium,
                        (long) b.keepUridium(), (long) o.price());
                return;
            }
            log.info("Покупаю бустер {} за {} уридия (осталось {})", code, (long) o.price(), clock(left));
            String answer = s.buy(o.item(), 1);
            log.info("Магазин: {}", answer);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Бустер {}: покупка не удалась — {}", code, e.getMessage());
        }
    }

    static String clock(long seconds) {
        return seconds <= 0 ? "0" : String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private void buy(ShopAccess s, BotConfig.BuyRule r, long left, double credits, double uridium, BotConfig.Buy b) {
        String name = ItemCatalog.label(r.item());
        try {
            ShopAccess.Offer o = s.offers().get(r.item());
            if (o == null || o.price() < 0) {
                log.warn("{}: в магазине такого нет — не покупаю", name);
                return;
            }
            int amount = r.amount();
            if (o.maxAmount() > 0) {
                amount = Math.min(amount, o.maxAmount());
            }
            double balance = o.uridium() ? uridium : credits;
            double keep = o.uridium() ? b.keepUridium() : b.keepCredits();
            String currency = o.uridium() ? "уридия" : "кредитов";
            if (balance <= 0) {
                log.warn("{}: баланс {} неизвестен — не покупаю", name, currency);
                return;
            }
            double spendable = balance - keep;
            if (o.price() > 0 && Math.ceil(o.price() * amount) > spendable) {
                amount = (int) Math.floor(spendable / o.price());
            }
            if (amount <= 0) {
                log.warn("{}: не хватает {} (есть {}, запас {}, цена {} за шт.)", name, currency, (long) balance,
                        (long) keep, o.price());
                return;
            }
            long cost = (long) Math.ceil(o.price() * amount);
            log.info("Покупаю {} ×{} за {} {} (осталось {})", name, amount, cost, currency, left);
            String answer = s.buy(r.item(), amount);
            log.info("Магазин: {}", answer);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("{}: покупка не удалась — {}", name, e.getMessage());
        }
    }
}
