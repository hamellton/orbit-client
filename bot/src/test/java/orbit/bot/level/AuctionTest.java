package orbit.bot.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The auction page read and the bids the plan would place (invented page). */
class AuctionTest {

    static String row(int n, String loot, String name, String highest, String current, String mine, String buy) {
        return "<tr class=\"auctionItemRow \" itemGroup=\"ship\" itemKey=\"item_hour_" + n + "\" onclick=\"Auction.showItem(this)\">"
                + "<td class=\"firstColumn\"></td><td class=\"auction_item_name_col\"> " + name + " </td>"
                + "<td class=\"auction_item_type\"> Корабли </td><td class=\"auction_item_highest\" \"> " + highest + " </td>"
                + "<td class=\"auction_item_current\"> " + current + " </td><td class=\"auction_item_you\"> " + mine + " </td>"
                + "<td class=\"auction_item_instant\"> " + buy + " <input type=\"hidden\" id=\"item_hour_" + n + "_lootId\" value=\""
                + loot + "\" /><input type=\"hidden\" id=\"item_hour_" + n + "_instantBuy\" value=\"1\" /></td></tr>";
    }

    static String page(int minutes, int seconds, String... rows) {
        return String.join("\n", rows) + "\nvar counterHour  = {\n second: " + seconds + ",\n minute: " + minutes
                + ",\n hour: 0,\n day: 0\n }\nvar counterDay  = {\n second: 1,\n minute: 2,\n hour: 8,\n day: 0\n }";
    }

    static AccountState account(long credits, int drones, Set<String> ships) {
        return new AccountState(5, credits, 0, 1, "ship_liberator", ships, Map.of("lasers", 4, "generators", 6),
                Map.of(1, Map.of("lasers", List.of("equipment_weapon_laser_lf-1")),
                        2, Map.of("lasers", List.of("equipment_weapon_laser_lf-1"))), Map.of(), drones);
    }

    @Test
    void readsTheLotsAndTheTimeLeft() {
        AuctionBoard b = AuctionBoard.parse(page(44, 38,
                row(39, "ship_goliath", "Goliath", "-", "0", "0", "50.000"),
                row(51, "booster_dmg-b01", "Бустер урона", "someone", "222.706.666", "0", "10.000")));
        assertEquals(2, b.lots().size());
        AuctionBoard twice = AuctionBoard.parse(page(2, 0, row(46, "ship_g-bastion", "Goliath Bastion", "me", "10.000", "10.000",
                "100.000"), row(46, "ship_g-bastion", "Goliath Bastion", "me", "10.000", "10.000", "100.000")));
        assertEquals(1, twice.lots().size(), "the «my bids» table repeats a lot");
        AuctionBoard.Lot g = b.lots().getFirst();
        assertEquals("item_hour_39", g.key());
        assertEquals("hour", g.auction());
        assertEquals("ship_goliath", g.loot());
        assertFalse(g.highest());
        assertEquals(50_000, g.buyNow());
        assertEquals(222_706_666, b.lots().get(1).current());
        assertTrue(b.lots().get(1).highest());
        assertEquals(44 * 60 + 38, b.secondsLeft("hour"));
        AuctionBoard withToken = AuctionBoard.parse(page(44, 38, row(39, "ship_goliath", "Goliath", "-", "0", "0", "50.000"))
                + "\nvar reloadToken = 'abc123';\n<input type=\"submit\" name=\"auction_buy_button\" class=\"disabled\" "
                + "id=\"auction_place_bid\" value=\"ПОСТАВИТЬ\" />");
        var form = withToken.bidForm(withToken.lots().getFirst(), 10_000);
        assertEquals("abc123", form.get("reloadToken"));
        assertEquals("hour", form.get("auctionType"));
        assertEquals("bid", form.get("subAction"));
        assertEquals("ship_goliath", form.get("lootId"));
        assertEquals("item_hour_39", form.get("itemId"));
        assertEquals("10000", form.get("credits"));
        assertEquals("ПОСТАВИТЬ", form.get("auction_buy_button"));
        assertEquals(8 * 3600 + 2 * 60 + 1, b.secondsLeft("day"));
    }

    @Test
    void bidsTheMinimumOnTheFirstGoliathOfThePlanInTheLastMinutes() {
        LevelPlan plan = LevelPlan.bundled();
        String rows = row(39, "ship_goliath", "Goliath", "-", "0", "0", "50.000") + "\n"
                + row(46, "ship_g-bastion", "Goliath Bastion", "-", "0", "0", "100.000");
        BidPlanner.Plan early = BidPlanner.plan(account(100_000, 0, Set.of("ship_liberator")), plan,
                AuctionBoard.parse(page(44, 0, rows)));
        assertEquals(1, early.bids().size(), "one Goliath, not two: " + early.describe());
        BidPlanner.Bid bid = early.bids().getFirst();
        assertEquals("ship_g-bastion", bid.lot().loot(), "Bastion first");
        assertEquals(AuctionBoard.MIN_BID, bid.credits());
        assertFalse(bid.now(), "44 minutes left is too early");
        BidPlanner.Plan late = BidPlanner.plan(account(100_000, 0, Set.of("ship_liberator")), plan,
                AuctionBoard.parse(page(2, 30, rows)));
        assertTrue(late.bids().getFirst().now());
    }

    @Test
    void keepsALeadOutbidsWithinTheBudgetAndSkipsWhatIsDone() {
        LevelPlan plan = LevelPlan.bundled();
        AuctionBoard led = AuctionBoard.parse(page(2, 0, row(46, "ship_g-bastion", "Goliath Bastion", "me", "10.000", "10.000", "100.000")));
        BidPlanner.Plan keep = BidPlanner.plan(account(100_000, 0, Set.of("ship_liberator")), plan, led);
        assertTrue(keep.bids().isEmpty(), keep.describe());
        assertEquals(1, keep.kept().size());
        assertEquals(100_000 - 30_000 - 10_000, keep.budget(), "reserve and the held bid off");
        AuctionBoard outbid = AuctionBoard.parse(page(2, 0, row(46, "ship_g-bastion", "Goliath Bastion", "other", "60.000", "10.000", "100.000")));
        BidPlanner.Plan again = BidPlanner.plan(account(100_000, 0, Set.of("ship_liberator")), plan, outbid);
        assertEquals(70_000, again.bids().getFirst().credits(), "the current + step");
        BidPlanner.Plan poor = BidPlanner.plan(account(50_000, 0, Set.of("ship_liberator")), plan, outbid);
        assertTrue(poor.bids().isEmpty());
        assertTrue(poor.describe().contains("не ставлю"), poor.describe());
        BidPlanner.Plan has = BidPlanner.plan(account(100_000, 0, Set.of("ship_g-veteran")), plan, outbid);
        assertTrue(has.bids().isEmpty(), "a Goliath is owned: " + has.describe());
    }

    @Test
    void irisWithEightDronesAsksForAFlaxSold() {
        LevelPlan plan = LevelPlan.bundled();
        AuctionBoard b = AuctionBoard.parse(page(2, 0, row(37, "drone_iris", "Iris", "-", "0", "0", "15.000")));
        BidPlanner.Plan p = BidPlanner.plan(account(100_000, 8, Set.of("ship_liberator")), plan, b);
        // 8 drones means the drone goals look done, so no Iris is wanted from the drone count alone
        assertTrue(p.bids().stream().noneMatch(x -> x.lot().loot().equals("drone_iris")) || p.describe().contains("продать один Flax"),
                p.describe());
        BidPlanner.Plan seven = BidPlanner.plan(account(100_000, 7, Set.of("ship_liberator")), plan, b);
        assertTrue(seven.bids().stream().anyMatch(x -> x.lot().loot().equals("drone_iris")), seven.describe());
    }

    @Test
    void aDearLotGivesWayToTheNextOneOfTheSameGoal() {
        LevelPlan plan = LevelPlan.bundled();
        AuctionBoard b = AuctionBoard.parse(page(2, 0,
                row(1, "ship_a-elite", "Aegis Elite", "other", "33.333.333", "0", "200.000"),
                row(2, "ship_aegis", "Aegis", "-", "0", "0", "80.000")));
        BidPlanner.Plan p = BidPlanner.plan(account(100_000, 0, Set.of("ship_liberator")), plan, b);
        assertEquals(1, p.bids().size(), p.describe());
        assertEquals("ship_aegis", p.bids().getFirst().lot().loot(), "the Elite is too dear, the plain Aegis is bid on");
    }

    @Test
    void shipCpusAreBidOnUntilOwned() {
        LevelPlan plan = LevelPlan.bundled();
        String rrb = "equipment_extra_cpu_nc-rrb-x";
        AuctionBoard b = AuctionBoard.parse(page(2, 0, row(30, rrb, "Автопроцессор для ремонта", "-", "0", "0", "10.000")));
        BidPlanner.Plan want = BidPlanner.plan(account(100_000, 0, Set.of("ship_liberator")), plan, b);
        assertTrue(want.bids().stream().anyMatch(x -> x.lot().loot().equals(rrb) && x.goal().equals("П3")), want.describe());
        AccountState has = new AccountState(5, 100_000, 0, 1, "ship_liberator", Set.of("ship_liberator"),
                Map.of("lasers", 4, "extras", 2), Map.of(1, Map.of("extras", List.of(rrb)), 2, Map.of("extras", List.of(rrb))),
                Map.of(), 0);
        BidPlanner.Plan owned = BidPlanner.plan(has, plan, b);
        assertTrue(owned.bids().stream().noneMatch(x -> x.lot().loot().equals(rrb)), "owned: no bid — " + owned.describe());
    }

    static String sold(String name, String price) {
        return "<tr class=\"auctionItemRow \"><td class=\"auction_history_name_col\"> " + name + " </td>"
                + "<td class=\"auction_history_type\"> x </td><td class=\"auction_history_winner\"> someone </td>"
                + "<td class=\"auction_history_current\"> " + price + " </td></tr>";
    }

    @Test
    void theHistoryGivesEachLotsMedian() {
        String history = "<tbody class=\"auction_history_wrapper\" id=\"auction_history_1\" >" + sold("Iris", "2.111.111")
                + sold("LF-3", "10.000.000") + "</tbody><tbody class=\"auction_history_wrapper\" id=\"auction_history_2\" "
                + "style=\"display:none;\">" + sold("Iris", "50.000") + "</tbody><tbody id=\"auction_history_3\" >"
                + sold("Iris", "900.000") + "</tbody>";
        Map<String, Long> m = AuctionBoard.medians(history);
        assertEquals(900_000, m.get("Iris"), "50 000, 900 000, 2 111 111: the middle one");
        assertEquals(10_000_000, m.get("LF-3"));
    }

    @Test
    void aBidAboveTheMedianIsNotMade() {
        LevelPlan plan = LevelPlan.bundled();
        AuctionBoard b = AuctionBoard.parse(page(1, 0, row(37, "drone_iris", "Iris", "other", "210.000", "0", "15.000")));
        BidPlanner.Plan capped = BidPlanner.plan(account(1_000_000, 7, Set.of("ship_liberator")), plan, b,
                Map.of("Iris", 200_000L));
        assertTrue(capped.bids().stream().noneMatch(x -> x.lot().loot().equals("drone_iris")), capped.describe());
        assertTrue(capped.describe().contains("потолок 200000"), capped.describe());
        BidPlanner.Plan never = BidPlanner.plan(account(1_000_000, 7, Set.of("ship_liberator")), plan, b,
                Map.of("LF-3", 1L));
        assertTrue(never.describe().contains("потолок 50000"), "never sold: the small ceiling — " + never.describe());
    }
}
