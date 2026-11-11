package orbit.bot.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A full cargo hold, as the config says ({@code collect.cargo}):
 * <ul>
 *   <li>{@code STOP} — the boxes that fill the hold are left ({@link #full}, read by the box picker);</li>
 *   <li>{@code BASE} — to the nearest own base (x-1 or x-8), to its refinery (asset 50/51/52), open the trade
 *       window ({@code TradeRequest}, as the client's click on the refinery) and sell the config's ores
 *       ({@code TradeSellOreRequest} each); then the module takes the ship back to work;</li>
 *   <li>{@code PET} — the pet's trade pod opens the trade window anywhere ({@code PetTradeWindowShowCommand}):
 *       sell the same way while the ship keeps working, then the pet's own mode again.</li>
 * </ul>
 * As recorded on ru1 2026-11-11: at the refinery (2-8, ~500 away) the server offers it
 * ({@code MapAssetActionAvailableCommand}), the client clicks it ({@code MapAssetActivationRequest}) and asks for the
 * trade window in the same moment ({@code TradeRequest}) → {@code OreTradeInfoCommand}
 * with the prices; with the pet the trade pod alone brings the same prices and the window, no request; then one
 * {@code TradeSellOreRequest} per ore, whole amounts, and {@code TWC} closes the window. Nothing to sell (only ores the config keeps) — no trip,
 * the hold simply stops filling. A sale that does not open is not tried again for {@link #RETRY_MS}.
 */
final class CargoCare {

    private static final Logger log = LoggerFactory.getLogger(CargoCare.class);

    static final long ANSWER_MS = 6_000;
    /** No prices this long after the pet was asked for its trade pod: ask once more. */
    static final long PET_ASK_AGAIN_MS = 7_000;
    /** A sale that did not open is tried again after the trade pod's longest cooldown (300 s at level 1). */
    static final long RETRY_MS = 5 * 60_000L;
    /** Close enough to the refinery to trade (the client trades by clicking its icon at the base). */
    static final double AT_REFINERY = 400;

    private enum Step { TO_BASE, TO_REFINERY, ASKED, SELLING }

    private final BotContext ctx;
    private final PetCare pet;
    private Step step;
    private long since;
    private long failedAt = Long.MIN_VALUE / 2;
    private long nextSellAt;
    private final List<int[]> toSell = new ArrayList<>();
    /** After a sale the hold is recounted by the game a moment later: no new sale before that. */
    static final long SOLD_SETTLE_MS = 10_000;
    /** A sale the game took shows in the hold within this; the same ore still there after it — not taken. */
    static final long RECOUNT_MS = 60_000;
    static final int PALLADIUM = 8;
    private String lastList = "";
    private long exchanged;
    private String soldList = "";
    private long soldAt = Long.MIN_VALUE / 2;
    private long soldUnits;
    /** When the ship came within {@link #AT_REFINERY} of the refinery (0 not yet). */
    private long nearSince;
    private String lastMode = "";
    private boolean askedAgain;
    private int baseMap;
    private String told = "";

    CargoCare(BotContext ctx, PetCare pet) {
        this.ctx = ctx;
        this.pet = pet;
    }

    /** The hold is full by the config's measure, and something is done about it ({@code mode} not NONE). */
    static boolean full(BotConfig c, WorldSnapshot w, String mode) {
        long free = w.cargoFree();
        return !BotConfig.Collect.CARGO_NONE.equals(mode) && w.hero().cargoMax() > 0
                && free <= c.collect().cargoFullAt();
    }

    private CargoPlan plan = new CargoPlan(BotConfig.Collect.CARGO_NONE, 0, List.of());

    /** Russian names of the ores, for the log. */
    static String oreName(int type) {
        return orbit.config.LootNames.ore(type);
    }

    /** What the plan sells and the hold has: ore → count. */
    static List<int[]> sellable(CargoPlan plan, WorldSnapshot w) {
        List<int[]> out = new ArrayList<>();
        Map<Integer, Integer> prices = w.inventory().tradePrices();
        for (int ore : plan.ores()) {
            long n = Math.round(w.inventory().ores().getOrDefault(ore, 0.0));
            if (ore == PALLADIUM && prices.getOrDefault(PALLADIUM, 0) > 0) {
                // exchanged by the rate's whole parts (the client: gain = ⌊count / rate⌋); the rest stays aboard
                int rate = prices.get(PALLADIUM);
                n -= n % rate;
            }
            // a trade window seen: only what it buys
            if (n > 0 && (prices.isEmpty() || prices.getOrDefault(ore, 0) > 0)) {
                out.add(new int[] {ore, (int) Math.min(Integer.MAX_VALUE, n)});
            }
        }
        return out;
    }

    /**
     * One step; true while it flies the ship (to the base and the refinery). The pet sale runs beside the work.
     *
     * @param busy the module is in the middle of something (a fight): the trip waits for it
     */
    boolean tick(boolean busy, CargoPlan modulePlan) {
        BotConfig c = ctx.config().get();
        WorldSnapshot w = ctx.world().get();
        long now = ctx.now();
        plan = modulePlan != null ? modulePlan : new CargoPlan(c.collect().cargo(), 0, c.collect().sellOres());
        String mode = plan.mode();
        ctx.stats().cargoMode = mode;
        boolean wantSale = BotConfig.Collect.CARGO_BASE.equals(mode) || BotConfig.Collect.CARGO_PET.equals(mode);
        if (!mode.equals(lastMode)) {
            // another way of selling chosen: the pause after the old way's failure does not hold it back
            // (live 2026-11-11: switched to the base after a failed pet sale, and nothing happened for 5 min)
            lastMode = mode;
            failedAt = Long.MIN_VALUE / 2;
            told = "";
            if (step != null && !BotConfig.Collect.CARGO_NONE.equals(mode)) {
                pet.setTrade(false);
                step = null;
            }
        }
        if (step == null) {
            if (!wantSale || !(full(c, w, mode) || plan.always() && !sellable(plan, w).isEmpty()) || ms(now - failedAt) < RETRY_MS
                    || ms(now - soldAt) < SOLD_SETTLE_MS) {
                if (!(full(c, w, mode) || plan.always() && !sellable(plan, w).isEmpty())) {
                    told = "";
                }
                return false;
            }
            List<int[]> sell = sellable(plan, w);
            lastList = list(sell);
            if (!sell.isEmpty() && lastList.equals(soldList) && ms(now - soldAt) < RECOUNT_MS) {
                // the very same ore aboard after a sale: the game did not take it (live 2026-11-11: palladium
                // sold as ore every 12 s for 6 minutes) — no loop, the usual pause after a failure
                soldList = "";
                return fail("игра не забрала руду после продажи (" + lastList + ")");
            }
            if (sell.isEmpty()) {
                tell("Трюм полон, а продавать нечего (" + held(w) + ") — больше не беру груз");
                return false;
            }
            if (BotConfig.Collect.CARGO_PET.equals(mode)) {
                if (!pet.canTrade()) {
                    tell("Трюм полон: продать через питомца нельзя — он не выпущен или у него нет «Торгового модуля»;"
                            + " больше не беру груз");
                    return false;
                }
                log.info("Трюм полон (свободно {}) — продаю через питомца: {}", w.cargoFree(), list(sell));
                pet.setTrade(true); // the trade pod opens the window and sends the prices by itself
                step = Step.ASKED;
                since = now;
                askedAgain = false;
                return false;
            }
            if (busy) {
                return false; // the fight first
            }
            baseMap = plan.map() != 0 ? plan.map() : nearestBase(w.hero());
            if (baseMap == 0) {
                tell("Трюм полон, но пути к своей базе нет — больше не беру груз");
                return false;
            }
            log.info("Трюм полон (свободно {}) — лечу продавать на {}: {}", w.cargoFree(),
                    ctx.maps().info(baseMap).name(), list(sell));
            step = Step.TO_BASE;
            since = now;
            nearSince = 0;
            ctx.traveler().travel(baseMap, Double.NaN, Double.NaN);
        }
        ctx.stats().selling = true;
        return switch (step) {
            case TO_BASE -> toBase(w, now);
            case TO_REFINERY -> toRefinery(w, now);
            case ASKED -> asked(c, w, now);
            case SELLING -> selling(w, now);
        };
    }

    private boolean toBase(WorldSnapshot w, long now) {
        if (w.hero().mapId() != baseMap) {
            if (ctx.traveler().state() == orbit.bot.nav.Traveler.State.FAILED) {
                return fail("нет пути на " + ctx.maps().info(baseMap).name());
            }
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.traveler().tick();
            return true;
        }
        step = Step.TO_REFINERY;
        since = now;
        return true;
    }

    private boolean toRefinery(WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Asset r = w.assets().values().stream()
                .filter(a -> a.type() >= 50 && a.type() <= 52 && (a.factionId() == 0 || a.factionId() == h.factionId()))
                .min(java.util.Comparator.comparingDouble(a -> Math.hypot(a.x() - h.x(), a.y() - h.y()))).orElse(null);
        if (r == null) {
            // the base's objects come a moment after the arrival
            return ms(now - since) > 10_000 ? fail("на " + ctx.maps().info(h.mapId()).name() + " не видно переработчика") : true;
        }
        double dist = Math.hypot(r.x() - h.x(), r.y() - h.y());
        // close enough once the server offers the refinery's action (the client trades only then)
        boolean offered = w.activatable().containsKey(r.id());
        if (dist <= AT_REFINERY && nearSince == 0) {
            nearSince = now;
        }
        if (dist > AT_REFINERY || !offered) {
            if (nearSince != 0 && ms(now - nearSince) > 20_000) {
                return fail("у переработчика, а сервер не предлагает торговлю");
            }
            if (ctx.nav().idle() || ctx.nav().destination().x() != r.x()) {
                ctx.nav().moveTo(r.x(), r.y(), true);
            }
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.nav().tick();
            return true;
        }
        ctx.nav().stop();
        log.info("У переработчика — открываю торговлю");
        ctx.actions().activateAsset(r.id());
        ctx.actions().tradeOpen();
        step = Step.ASKED;
        since = now;
        return true;
    }

    private boolean asked(BotConfig c, WorldSnapshot w, long now) {
        boolean base = BotConfig.Collect.CARGO_BASE.equals(plan.mode());
        if (w.inventory().tradeNanos() > since) {
            toSell.clear();
            toSell.addAll(sellable(plan, w));
            if (toSell.isEmpty()) {
                log.info("Торговец не покупает то, что в трюме ({}) — больше не беру груз", held(w));
                return done(false);
            }
            step = Step.SELLING;
            nextSellAt = now + ctx.pause(600, 1_400);
            return base;
        }
        // the pet: the mode is asked for after a moment's pause, the answer comes at once; no answer (seen twice
        // live, cause unknown) — the trade pod is asked for once more before giving up
        if (!base && !askedAgain && ms(now - since) > PET_ASK_AGAIN_MS) {
            askedAgain = true;
            log.info("Торговый модуль не ответил — прошу ещё раз");
            pet.askTradeAgain();
        }
        if (ms(now - since) > (base ? ANSWER_MS : ANSWER_MS + 12_000)) {
            return fail(base ? "торговля у переработчика не открылась" : "торговый модуль питомца не открыл торговлю");
        }
        return base;
    }

    private boolean selling(WorldSnapshot w, long now) {
        boolean base = BotConfig.Collect.CARGO_BASE.equals(plan.mode());
        if (now < nextSellAt) {
            return base;
        }
        if (toSell.isEmpty()) {
            ctx.actions().tradeClose();
            if (soldUnits > 0) {
                log.info("Продал руду: {} ед. (трюм освободится, как только игра пересчитает)", soldUnits);
            }
            if (exchanged > 0) {
                log.info("Обменял палладий: {} ед.", exchanged);
            }
            soldUnits = 0;
            exchanged = 0;
            return done(true);
        }
        int[] o = toSell.removeFirst();
        if (o[0] == PALLADIUM) {
            exchanged += o[1];
        } else {
            soldUnits += o[1];
        }
        if (o[0] == PALLADIUM) {
            log.info("Меняю палладий × {}", o[1]);
            ctx.actions().exchangePalladium(o[1]);
        } else {
            log.info("Продаю {} × {}", oreName(o[0]), o[1]);
            ctx.actions().tradeSell(o[0], o[1]);
        }
        nextSellAt = now + ctx.pause(700, 1_500);
        return base;
    }

    private boolean done(boolean sold) {
        ctx.stats().selling = false;
        if (!sold) {
            failedAt = ctx.now();
        } else {
            soldAt = ctx.now();
            soldList = lastList;
        }
        pet.setTrade(false);
        step = null;
        return false;
    }

    private boolean fail(String why) {
        ctx.stats().selling = false;
        log.warn("Продажа не вышла: {} — попробую через {} мин, пока не беру груз", why, RETRY_MS / 60_000);
        failedAt = ctx.now();
        pet.setTrade(false);
        step = null;
        ctx.nav().stop();
        return false;
    }

    /** The own base map nearest by portals: x-1 or x-8 of the hero's faction. */
    private int nearestBase(WorldSnapshot.Hero h) {
        int best = 0;
        int bestHops = Integer.MAX_VALUE;
        for (String n : List.of(h.factionId() + "-1", h.factionId() + "-8")) {
            MapCatalog.MapInfo m = ctx.maps().byName(n);
            if (m == null) {
                continue;
            }
            int hops = m.id() == h.mapId() ? 0 : orbit.bot.nav.Router.route(ctx.maps(), h.mapId(), h.x(), h.y(), m.id(),
                    h.factionId(), h.level() > 0 ? h.level() : Integer.MAX_VALUE).size();
            if ((hops > 0 || m.id() == h.mapId()) && hops < bestHops) {
                best = m.id();
                bestHops = hops;
            }
        }
        return best;
    }

    private static String list(List<int[]> sell) {
        return String.join(", ", sell.stream().map(o -> oreName(o[0]) + " " + o[1]).toList());
    }

    private static String held(WorldSnapshot w) {
        List<String> out = new ArrayList<>();
        w.inventory().ores().forEach((t, n) -> {
            if (n >= 1 && t != WorldSnapshot.Inventory.XENOMIT) {
                out.add(oreName(t) + " " + Math.round(n));
            }
        });
        return out.isEmpty() ? "пусто" : "в трюме: " + String.join(", ", out);
    }

    private void tell(String s) {
        if (!s.equals(told)) {
            told = s;
            log.info(s);
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
