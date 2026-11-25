package orbit.bot.level;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The auction's bids the leveling plan would place now (docs/LEVELING.md «Аукцион»): a pure function over the account,
 * the plan and the board — it bids nothing.
 *
 * <ul>
 *   <li>wanted: the auction lots of every goal not done, chain order first, side goals after; one lot per goal (a
 *       second Goliath design is no use);</li>
 *   <li>budget: credits − the plan's reserve (ammunition) − my bids that lead now (assumed held by the site);</li>
 *   <li>a bid: {@link AuctionBoard#MIN_BID} on a lot nobody bid on, else the current bid + {@link #STEP}; a lot I lead
 *       is kept, an outbid one bid on again when the budget allows;</li>
 *   <li>time: only in the last {@link #HOUR_WINDOW_S} of the hourly, {@link #DAY_WINDOW_S} of the daily auction — fewer
 *       people outbid then; earlier the plan says when;</li>
 *   <li>Iris with 8 drones already: one Flax is to be sold first.</li>
 * </ul>
 */
public final class BidPlanner {

    /** Above the current bid (the site's own step is not known yet). */
    static final long STEP = 10_000;
    static final long HOUR_WINDOW_S = 180;
    static final long DAY_WINDOW_S = 300;
    static final int DRONE_PLACES = 8;
    static final String IRIS = "drone_iris";

    private BidPlanner() {
    }

    /** One bid: the lot, the credits, the goal it serves; {@code now}: false while it is too early. */
    public record Bid(AuctionBoard.Lot lot, long credits, String goal, boolean now) {
    }

    /**
     * The plan for this moment.
     *
     * @param bids  the bids to place (or to place later, {@link Bid#now})
     * @param kept  lots I lead, left as they are
     * @param notes what else is to be said: no money, a Flax to sell, nothing wanted on the board
     */
    public record Plan(List<Bid> bids, List<AuctionBoard.Lot> kept, List<String> notes, long budget) {

        public String describe() {
            List<String> parts = new ArrayList<>();
            parts.add("бюджет " + budget);
            for (Bid b : bids) {
                parts.add((b.now() ? "поставил бы сейчас " : "поставил бы позже ") + b.credits() + " на " + b.lot().name() + " ("
                        + b.lot().loot() + ", " + b.lot().auction() + ", сейчас " + b.lot().current() + ", цель " + b.goal() + ")");
            }
            for (AuctionBoard.Lot l : kept) {
                parts.add("веду " + l.name() + " за " + l.mine());
            }
            parts.addAll(notes);
            return String.join("; ", parts);
        }
    }

    public static Plan plan(AccountState s, LevelPlan plan, AuctionBoard board) {
        Map<String, LevelPlan.Goal> wanted = new LinkedHashMap<>();
        List<LevelPlan.Goal> order = new ArrayList<>();
        plan.goals().stream().filter(g -> !g.side()).forEach(order::add);
        plan.goals().stream().filter(LevelPlan.Goal::side).forEach(order::add);
        for (LevelPlan.Goal g : order) {
            if (g.auction().isEmpty() || Advisor.check(s, g).done()) {
                continue;
            }
            wanted.put(g.id(), g);
        }
        List<AuctionBoard.Lot> kept = new ArrayList<>();
        long held = 0;
        for (AuctionBoard.Lot l : board.lots()) {
            if (l.leading()) {
                kept.add(l);
                held += l.mine();
            }
        }
        long budget = s.credits() < 0 ? -1 : Math.max(0, s.credits() - plan.reserveCredits() - held);
        List<String> notes = new ArrayList<>();
        List<Bid> bids = new ArrayList<>();
        if (budget < 0) {
            notes.add("кредиты не известны — ставок не считаю");
            return new Plan(bids, kept, notes, budget);
        }
        long free = budget;
        Set<String> takenLoot = new HashSet<>();
        for (LevelPlan.Goal g : wanted.values()) {
            if (g.auction().stream().anyMatch(takenLoot::contains)) {
                continue; // another goal already bids on the same kind (Ц2 and Ц5 share the Goliath lots)
            }
            AuctionBoard.Lot pick = null;
            // the goal's lots in its order: one I lead ends the search, else the first one the budget allows
            boolean leads = false;
            long bid = 0;
            List<String> tooDear = new ArrayList<>();
            for (String loot : g.auction()) {
                AuctionBoard.Lot l = board.lots().stream().filter(x -> x.loot().equals(loot)).findFirst().orElse(null);
                if (l == null) {
                    continue;
                }
                if (l.leading()) {
                    leads = true;
                    break;
                }
                long b = bidFor(l);
                if (b <= free) {
                    pick = l;
                    bid = b;
                    break;
                }
                tooDear.add(l.name() + ": нужно " + b);
            }
            if (leads || pick == null) {
                if (!leads && !tooDear.isEmpty()) {
                    takenLoot.addAll(g.auction());
                    notes.add(String.join(", ", tooDear) + ", свободно " + free + " — не ставлю");
                }
                if (leads) {
                    takenLoot.addAll(g.auction());
                }
                continue;
            }
            takenLoot.addAll(g.auction());
            if (IRIS.equals(pick.loot()) && s.drones() >= DRONE_PLACES) {
                notes.add("под Iris нет места (дронов " + s.drones() + ") — сначала продать один Flax");
            }
            long left = board.secondsLeft(pick.auction());
            long window = "day".equals(pick.auction()) ? DAY_WINDOW_S : "hour".equals(pick.auction()) ? HOUR_WINDOW_S : 0;
            boolean now = left >= 0 && left <= window;
            if (!now && left >= 0) {
                notes.add(pick.name() + ": до конца " + left / 60 + " мин — ставка в последние " + window / 60 + " мин");
            }
            bids.add(new Bid(pick, bid, g.id(), now));
            free -= bid;
        }
        if (bids.isEmpty() && kept.isEmpty() && notes.isEmpty()) {
            notes.add("на аукционе нет нужного по плану");
        }
        return new Plan(bids, kept, notes, budget);
    }

    /** {@link AuctionBoard#MIN_BID} on a lot nobody bid on, else the current bid + {@link #STEP}. */
    static long bidFor(AuctionBoard.Lot l) {
        return l.current() <= 0 && !l.highest() ? AuctionBoard.MIN_BID : l.current() + STEP;
    }
}
