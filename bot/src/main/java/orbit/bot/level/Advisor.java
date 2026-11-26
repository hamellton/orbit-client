package orbit.bot.level;

import java.util.ArrayList;
import java.util.List;

/**
 * The leveling advisor (docs/LEVELING.md): from the account's state and the plan, the goal to work on now, what it
 * lacks and what the bot would buy. A pure function: it buys, fits and switches nothing.
 */
public final class Advisor {

    private Advisor() {
    }

    /** A purchase the advisor would make: {@code amount} × {@code item} at {@code price} each. */
    public record Purchase(String item, int amount, long price, boolean uridium) {

        public long total() {
            return price * amount;
        }
    }

    /**
     * The advice.
     *
     * @param goal     the goal to work on ({@code null}: every goal of the plan is done)
     * @param done     the goals done before it, by id
     * @param missing  units still lacking (items to get, or 1 for a ship); 0 when only fitting is left or unknown
     * @param refit    items owned but not in the slots: to be fitted, no purchase needed
     * @param purchase what the bot would buy now ({@code null}: nothing affordable or nothing to buy)
     * @param text     the advice in words, for the leveling log
     */
    public record Advice(LevelPlan.Goal goal, List<String> done, int missing, int refit, Purchase purchase, String text,
                         Purchase other) {

        public Advice(LevelPlan.Goal goal, List<String> done, int missing, int refit, Purchase purchase, String text) {
            this(goal, done, missing, refit, purchase, text, null);
        }
    }

    /** A goal's check against the state. */
    record Check(boolean done, boolean known, int missing, int refit, String detail) {
    }

    public static Advice advise(AccountState s, LevelPlan plan) {
        Advice main = chain(s, plan);
        List<String> side = new ArrayList<>();
        for (LevelPlan.Goal g : plan.goals()) {
            if (g.side()) {
                Check c = check(s, g);
                side.add(g.id() + " «" + g.title() + "»: " + (c.done() ? "есть" : c.known() ? c.detail() : "не умею проверить ("
                        + c.detail() + ")") + (c.done() || g.auction().isEmpty() ? "" : ", аукцион: " + String.join(", ", g.auction())));
            }
        }
        Purchase other = otherCurrency(s, plan, main);
        if (other == null) {
            other = sideBuy(s, plan);
        }
        String text = main.text() + (other == null ? "" : " | в другой валюте: купил бы " + other.amount() + " × " + other.item()
                + " за " + other.total() + (other.uridium() ? " уридия" : " кредитов"))
                + (side.isEmpty() ? "" : " | попутно: " + String.join("; ", side));
        return new Advice(main.goal(), main.done(), main.missing(), main.refit(), main.purchase(), text, other);
    }

    /**
     * The other currency's purchase: while the current goal waits for one currency (credits for the scalpels), the next
     * goal of the chain bought with the other (uridium for the G3N-7900) is bought when affordable — the uridium does not
     * lie idle (live 2026-11-25: 727 uridium while the first goal needed 4 million credits).
     */
    static Purchase otherCurrency(AccountState s, LevelPlan plan, Advice main) {
        if (main.goal() == null || main.goal().buy() == null) {
            return null;
        }
        boolean waitsFor = main.goal().buy().uridium();
        boolean after = false;
        for (LevelPlan.Goal g : plan.goals()) {
            if (g.side()) {
                continue;
            }
            if (g == main.goal()) {
                after = true;
                continue;
            }
            if (!after || g.buy() == null || g.buy().uridium() == waitsFor) {
                continue;
            }
            if (g.buy().needs() != null && owned(s, java.util.Set.of(g.buy().needs())) == 0) {
                continue; // waits for another item first (the Moth after the resource collector gear)
            }
            Check c = check(s, g);
            if (!c.known()) {
                return null; // the chain is not known past here
            }
            if (c.done() || c.missing() <= 0) {
                continue;
            }
            long money = g.buy().uridium() ? s.uridium() : s.credits();
            long reserve = g.buy().uridium() ? plan.reserveUridium() : plan.reserveCredits();
            long free = Math.max(0, money - reserve);
            int can = (int) Math.min(c.missing(), g.buy().price() <= 0 ? 0 : free / g.buy().price());
            return can > 0 ? new Purchase(g.buy().item(), can, g.buy().price(), g.buy().uridium()) : null;
        }
        return null;
    }

    /** The goal is done for the account (its check passes). */
    public static boolean done(AccountState s, LevelPlan.Goal g) {
        return check(s, g).done();
    }

    /** The goal the parallel purchases wait for: all lasers for credits (the user's choice 2026-11-26). */
    static final String PARALLEL_AFTER = "Ц1";

    /**
     * A parallel track's purchase (docs/LEVELING.md «План v2»): once {@link #PARALLEL_AFTER} is done, the first side goal
     * with a shop purchase for credits (the Flax, Ц4) not done is bought when the free credits allow — beside the chain.
     */
    static Purchase sideBuy(AccountState s, LevelPlan plan) {
        LevelPlan.Goal after = plan.goals().stream().filter(g -> PARALLEL_AFTER.equals(g.id())).findFirst().orElse(null);
        if (after == null || !check(s, after).done()) {
            return null;
        }
        for (LevelPlan.Goal g : plan.goals()) {
            // credits only: the uridium is the chain's (P.E.T., the gear, the Moth), LF-3 is won on the auction
            if (!g.side() || g.buy() == null || g.buy().uridium()) {
                continue;
            }
            if (g.buy().needs() != null && owned(s, java.util.Set.of(g.buy().needs())) == 0) {
                continue;
            }
            Check c = check(s, g);
            if (!c.known() || c.done() || c.missing() <= 0) {
                continue;
            }
            long money = g.buy().uridium() ? s.uridium() : s.credits();
            long reserve = g.buy().uridium() ? plan.reserveUridium() : plan.reserveCredits();
            long free = Math.max(0, money - reserve);
            int can = (int) Math.min(c.missing(), g.buy().price() <= 0 ? 0 : free / g.buy().price());
            if (can > 0) {
                return new Purchase(g.buy().item(), can, g.buy().price(), g.buy().uridium());
            }
        }
        return null;
    }

    /** The first goal of the chain (side goals left out) not done. */
    private static Advice chain(AccountState s, LevelPlan plan) {
        List<String> done = new ArrayList<>();
        for (LevelPlan.Goal g : plan.goals()) {
            if (g.side()) {
                continue;
            }
            Check c = check(s, g);
            if (c.done()) {
                done.add(g.id());
                continue;
            }
            String head = g.id() + " «" + g.title() + "»";
            if (!c.known()) {
                return new Advice(g, done, 0, 0, null, head + ": не умею проверить (" + c.detail() + ") — дальше не иду");
            }
            Purchase p = null;
            StringBuilder text = new StringBuilder(head + ": " + c.detail());
            if (c.refit() > 0) {
                text.append("; поставить из запаса ").append(c.refit());
            }
            if (c.missing() > 0 && g.buy() != null) {
                LevelPlan.Buy b = g.buy();
                long money = b.uridium() ? s.uridium() : s.credits();
                long reserve = b.uridium() ? plan.reserveUridium() : plan.reserveCredits();
                String cur = b.uridium() ? "уридия" : "кредитов";
                if (money < 0) {
                    text.append("; денег не знаю — не решаю");
                } else {
                    long free = Math.max(0, money - reserve);
                    int can = (int) Math.min(c.missing(), b.price() <= 0 ? c.missing() : free / b.price());
                    if (can > 0) {
                        p = new Purchase(b.item(), can, b.price(), b.uridium());
                        text.append("; купил бы ").append(can).append(" × ").append(b.item()).append(" за ")
                                .append(p.total()).append(' ').append(cur);
                    } else {
                        text.append("; копить ещё ").append(b.price() - free).append(' ').append(cur)
                                .append(" до первой (цена ").append(b.price()).append(", резерв ").append(reserve).append(')');
                    }
                    if (can < c.missing()) {
                        text.append("; на все ").append(c.missing()).append(" нужно ")
                                .append(Math.max(0, b.price() * c.missing() - free)).append(" сверх свободных");
                    }
                }
            } else if (c.missing() > 0) {
                text.append("; покупки в плане нет");
            }
            if (!g.auction().isEmpty()) {
                text.append("; аукцион: ").append(String.join(", ", g.auction()));
            }
            if (!g.farm().isEmpty()) {
                text.append("; фарм: ").append(g.farm());
            }
            return new Advice(g, done, c.missing(), c.refit(), p, text.toString());
        }
        return new Advice(null, done, 0, 0, null, "все цели плана выполнены");
    }

    static Check check(AccountState s, LevelPlan.Goal g) {
        return switch (g.kind()) {
            case LEVEL -> s.level() <= 0 ? new Check(false, false, 0, 0, "уровень не известен")
                    : new Check(s.level() >= g.min(), true, 0, 0, "уровень " + s.level() + " из " + g.min());
            case SHIP -> {
                boolean has = g.accepted().stream().anyMatch(s.ownedShips()::contains);
                yield new Check(has, true, has ? 0 : 1, 0, has ? "есть" : "нет корабля");
            }
            case FILL -> fill(s, g);
            case HAS -> {
                int n = owned(s, g.accepted());
                int need = Math.max(1, g.min());
                yield new Check(n >= need, true, Math.max(0, need - n), 0, "есть " + n + " из " + need);
            }
            case DRONES -> s.drones() < 0 ? new Check(false, false, 0, 0, "дроны не известны")
                    : new Check(s.drones() >= g.min(), true, Math.max(0, g.min() - s.drones()), 0,
                            "дронов " + s.drones() + " из " + g.min());
            case UNKNOWN -> new Check(false, false, 0, 0, g.why().isEmpty() ? "проверки нет" : g.why());
        };
    }

    /** Items of {@code accepted} the account has: the most any config of the active ship fits (any slotset) plus spares. */
    static int owned(AccountState s, java.util.Set<String> accepted) {
        int most = 0;
        for (var config : s.fitted().values()) {
            int n = 0;
            for (var ids : config.values()) {
                n += (int) ids.stream().filter(accepted::contains).count();
            }
            most = Math.max(most, n);
        }
        return most + s.spareOf(accepted);
    }

    /**
     * Every slot in each config holds an accepted item. One item may stand in both configs, so the items owned are the
     * most any config fits plus the spares; lacking = slots − owned; owned but not fitted somewhere = to refit.
     */
    private static Check fill(AccountState s, LevelPlan.Goal g) {
        int slots = s.slots(g.slotset());
        if (slots <= 0 || s.fitted().isEmpty()) {
            return new Check(false, false, 0, 0, "слоты «" + g.slotset() + "» или снаряжение корабля не известны");
        }
        int most = 0;
        int least = slots;
        for (int c : g.configs()) {
            int n = s.fittedOf(c, g.slotset(), g.accepted());
            most = Math.max(most, n);
            least = Math.min(least, n);
        }
        int owned = most + s.spareOf(g.accepted());
        int missing = Math.max(0, slots - owned);
        int refit = Math.max(0, Math.min(slots, owned) - least);
        boolean done = least >= slots;
        return new Check(done, true, missing, refit, "«" + g.slotset() + "»: подходящих " + least + "–" + most + " из " + slots
                + " по конфигам " + g.configs() + ", в запасе " + s.spareOf(g.accepted()));
    }
}
