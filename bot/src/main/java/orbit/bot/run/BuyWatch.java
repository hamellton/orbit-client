package orbit.bot.run;

import java.util.HashSet;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The config's buy list against what is left. The purchase itself is not wired yet (it needs one
 * recorded purchase in the shop); until then the bot says once per item when it is time to buy.
 */
final class BuyWatch {

    private static final Logger log = LoggerFactory.getLogger(BuyWatch.class);

    private final BotContext ctx;
    private final Set<String> told = new HashSet<>();
    private long nextCheck;

    BuyWatch(BotContext ctx) {
        this.ctx = ctx;
    }

    void tick() {
        long now = ctx.now();
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + 10_000_000_000L;
        BotConfig.Buy b = ctx.config().get().buy();
        if (!b.enabled()) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        for (BotConfig.BuyRule r : b.items()) {
            WorldSnapshot.Item item = w.items().get(r.item());
            double left = item == null ? 0 : item.counter();
            if (left < r.below()) {
                if (told.add(r.item())) {
                    log.warn("Пора докупить {}: осталось {}, по конфигу купить {} (покупка пока не подключена)",
                            ItemCatalog.label(r.item()), (long) left, r.amount());
                }
            } else {
                told.remove(r.item());
            }
        }
    }
}
