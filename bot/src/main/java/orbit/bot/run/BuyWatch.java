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
                    try {
                        buy(s, r, (long) left, credits, uridium, b);
                    } finally {
                        busy.set(false);
                    }
                });
            }
            return; // one purchase at a time
        }
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
