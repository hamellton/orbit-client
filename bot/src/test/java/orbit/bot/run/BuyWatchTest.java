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
    static class FakeShop implements ShopAccess {
        final List<String> bought = new ArrayList<>();

        @Override
        public Map<String, Offer> offers() {
            return Map.of(
                    "ammunition_laser_lcb-10", new Offer("ammunition_laser_lcb-10", 10, false, 30_000),
                    "ammunition_laser_mcb-25", new Offer("ammunition_laser_mcb-25", 25, false, 100_000),
                    "ammunition_rocket_plt-2026", new Offer("ammunition_rocket_plt-2026", 2, true, 10_000));
        }

        @Override
        public Map<String, Offer> boosterOffers() {
            return Map.of("DMG-B01", new Offer("3", 10_000, true, 6), "SHD-B01", new Offer("4", 10_000, true, 6));
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
        runFor(sim, w, 60_000);
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
        runFor(sim, w, 60_000);
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
        runFor(sim, w, 60_000);
        assertTrue(shop.bought.isEmpty());
    }

    @Test
    void neverBuysBeforeTheGameHasCountedTheItems() throws Exception {
        BotConfig.Buy buy = new BotConfig.Buy(true, List.of(new BotConfig.BuyRule("ammunition_laser_lcb-10", 20_000, 50_000)),
                0, 0);
        Sim sim = new Sim(BotConfig.empty().withBuy(buy), 26, 7_500, 3_900); // logged in, item bar not sent yet
        FakeShop shop = new FakeShop();
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 60_000);
        assertTrue(shop.bought.isEmpty(), "nothing known, nothing bought: " + shop.bought);
    }

    static void boosters(Sim sim, String lootId, int secondsLeft) {
        orbit.protocol.gen.AttributeBoosterUpdateCommand u = new orbit.protocol.gen.AttributeBoosterUpdateCommand();
        if (lootId != null) {
            orbit.protocol.gen.BoosterPlayerDetailModule d = new orbit.protocol.gen.BoosterPlayerDetailModule();
            d.lootId = lootId;
            d.secondsLeft = secondsLeft;
            u.boostedDetailsUpdate.add(d);
        }
        sim.world.apply(u, sim.now);
    }

    @Test
    void buysAListedBoosterThatRunsOutAndNotOneThatLasts() throws Exception {
        BotConfig.Buy buy = new BotConfig.Buy(true, List.of(), 0, 5_000, List.of("DMG-B01", "SHD-B01"), 30);
        Sim sim = sim(buy, 0, 50_000);
        boosters(sim, "booster_dmg-b01", 600);      // 10 minutes left: under 30, buy
        boosters(sim, "booster_shd-b01", 36_000);   // 10 hours left: keep
        FakeShop shop = new FakeShop();
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 60_000);
        assertEquals(List.of("3 x1"), shop.bought);
    }

    @Test
    void noBoosterBeforeTheGameListedThemNorBelowTheUridiumToKeep() throws Exception {
        BotConfig.Buy buy = new BotConfig.Buy(true, List.of(), 0, 45_000, List.of("DMG-B01"), 0);
        Sim sim = sim(buy, 0, 50_000);
        FakeShop shop = new FakeShop();
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 60_000);
        assertTrue(shop.bought.isEmpty(), "boosters not known yet");
        boosters(sim, null, 0); // the game lists none: DMG-B01 has run out
        runFor(sim, w, 60_000);
        assertTrue(shop.bought.isEmpty(), "50 000 - 10 000 is under the 45 000 to keep");
    }

    @Test
    void theShopsBalanceCountsWhenTheGameSendsNone() throws Exception {
        // the game keeps showing 35 000 uridium after a purchase; the shop says 25 000 are left
        BotConfig.Buy buy = new BotConfig.Buy(true, List.of(), 0, 20_000, List.of("DMG-B01", "SHD-B01"), 30);
        Sim sim = sim(buy, 0, 35_000);
        boosters(sim, null, 0);
        FakeShop shop = new FakeShop() {
            @Override
            public synchronized String buy(String item, int amount) {
                super.buy(item, amount);
                return "{\"result\":\"success\",\"message\":\"\\u0411\\u0443\\u0441\\u0442\\u0435\\u0440\","
                        + "\"userBalance\":{\"uridium\":25000,\"credits\":100}}";
            }
        };
        BuyWatch w = new BuyWatch(sim.ctx);
        w.setShop(shop);
        runFor(sim, w, 90_000);
        assertEquals(List.of("3 x1"), shop.bought, "25 000 - 10 000 is under the 20 000 to keep: no second booster");
        assertEquals("Бустер", BuyWatch.unescape("\\u0411\\u0443\\u0441\\u0442\\u0435\\u0440"));
    }
}
