package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.protocol.gen.ShipInitializationCommand;
import org.junit.jupiter.api.Test;

class BuyWatchTest {

    /** A shop that records purchases. */
    static final class FakeShop implements ShopAccess {
        final List<String> bought = new ArrayList<>();

        @Override
        public Map<String, Offer> offers() {
            return Map.of(
                    "ammunition_laser_lcb-10", new Offer("ammunition_laser_lcb-10", 10, false, 30_000),
                    "ammunition_laser_mcb-25", new Offer("ammunition_laser_mcb-25", 25, false, 100_000),
                    "ammunition_rocket_plt-2026", new Offer("ammunition_rocket_plt-2026", 2, true, 10_000));
        }

        @Override
        public synchronized String buy(String item, int amount) {
            bought.add(item + " x" + amount);
            return "{\"isError\":0}";
        }
    }

    static Sim sim(BotConfig.Buy buy, double credits, double uridium) {
        Sim sim = new Sim(BotConfig.empty().withBuy(buy), 26, 7_500, 3_900);
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 26;
        si.x = 7_500;
        si.y = 3_900;
        si.credits = credits;
        si.uridium = uridium;
        sim.world.apply(si, sim.now);
        sim.menu("ammunition_laser_lcb-10", "ammunition_rocket_plt-2026"); // LCB-10 and PLT-2026 at 10 000 each
        return sim;
    }

    static void runFor(Sim sim, BuyWatch w, long ms) throws InterruptedException {
        for (long t = 0; t < ms; t += 100) {
            w.tick();
            sim.now += 100 * Sim.MS;
            Thread.sleep(1); // the shop thread
        }
        Thread.sleep(200);
    }

    @Test
    void buysOnlyListedItemsWithinTheShopLimitAndKeepsTheMoney() throws Exception {
        BotConfig.Buy buy = new BotConfig.Buy(true, List.of(
                new BotConfig.BuyRule("ammunition_laser_lcb-10", 20_000, 50_000),    // 10 000 left: buy
                new BotConfig.BuyRule("ammunition_rocket_plt-2026", 5_000, 1_000)),  // 10 000 left: enough
                100_000, 0);
        Sim sim = sim(buy, 400_000, 1_000);
        FakeShop shop = new FakeShop();
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 30_000);
        // 50 000 wanted, 30 000 at most per purchase, 300 000 credits to spend at 10 each = 30 000
        assertEquals(List.of("ammunition_laser_lcb-10 x30000"), shop.bought);
    }

    @Test
    void neverSpendsTheMoneyToKeep() throws Exception {
        BotConfig.Buy buy = new BotConfig.Buy(true, List.of(new BotConfig.BuyRule("ammunition_laser_lcb-10", 20_000, 50_000)),
                400_000, 0);
        Sim sim = sim(buy, 400_000, 0);
        FakeShop shop = new FakeShop();
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 30_000);
        assertTrue(shop.bought.isEmpty(), shop.bought.toString());
    }

    @Test
    void doesNothingWhenSwitchedOff() throws Exception {
        BotConfig.Buy buy = new BotConfig.Buy(false, List.of(new BotConfig.BuyRule("ammunition_laser_lcb-10", 20_000, 50_000)),
                0, 0);
        Sim sim = sim(buy, 400_000, 0);
        FakeShop shop = new FakeShop();
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 30_000);
        assertTrue(shop.bought.isEmpty());
    }
}
