package orbit.bot.mod;

import java.util.List;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.bot.run.CargoPlan;
import orbit.config.BotConfig;

/**
 * PALLADIUM, as the user's DarkBot runs it (profile PALLADIUM, 2026-11-11): collect palladium ({@code ore_8}) on
 * the working map (5-3) by the config's zones — roam in the preferred one (the field), fly round the avoided one —
 * kill what the NPC list says, and with a full hold sell the palladium at 5-2's trader (the open «Palladium
 * Module»: «Loot & collect, but when cargo is full travels to 5-2 to sell»).
 * <p>With two ships (DmPlugin «Palladium Hangar», the user's main way; settings as in the profile:
 * {@code collectHangar}, {@code sellHangar}, {@code goPortalChange}, {@code sellOnDie}, {@code aditionalWaitingTime}):
 * the collect ship works on 5-3 and stops taking palladium when full; then the bot logs out, the app makes the sell
 * ship active on the website and logs in — that ship waits parked at 5-2's trader — sells the palladium, and the
 * collect ship is taken again. {@code sellOnDie}: destroyed with palladium aboard — the sell ship instead of a
 * revive. {@code twoShips} false (or a hangar missing) — the one-ship way.
 */
public final class PalladiumModule implements BotModule {

    /** 5-2: the trader that takes palladium. */
    public static final int SELL_MAP = 92;
    /** The palladium ore ({@code OreTypeModule.PALLADIUM}). */
    public static final int PALLADIUM = 8;

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PalladiumModule.class);
    private final BotContext ctx;
    private final KillModule work;

    public PalladiumModule(BotContext ctx) {
        this.ctx = ctx;
        this.work = new KillModule(ctx, true, true);
    }

    /** The module's settings ({@code modules.palladium}, imported from DmPlugin's «Palladium Hangar»). */
    private com.fasterxml.jackson.databind.JsonNode settings() {
        com.fasterxml.jackson.databind.JsonNode s = ctx.config().get().modules().get(BotConfig.Module.PALLADIUM.key());
        return s == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : s;
    }

    /**
     * The account's ships (account settings; hangar ids are an account's own — DarkBot's ids in the config are
     * moved there at a login only when that account has both ships).
     */
    int collectHangar() {
        return ctx.ships().palladiumCollect();
    }

    int sellHangar() {
        return ctx.ships().palladiumSell();
    }

    /** Two ships: both hangars set and not turned off. */
    boolean twoShips() {
        return settings().path("twoShips").asBoolean(true) && collectHangar() > 0 && sellHangar() > 0
                && collectHangar() != sellHangar();
    }

    private boolean onSellShip(int active) {
        return twoShips() && active == sellHangar();
    }

    /**
     * Palladium to exchange aboard: at least one rate's worth once 5-2's rate is known (less stays aboard — the
     * exchange takes whole parts), else any.
     */
    private static boolean hasPalladium(orbit.world.WorldSnapshot w) {
        int rate = w.inventory().tradePrices().getOrDefault(PALLADIUM, 0);
        return w.inventory().ores().getOrDefault(PALLADIUM, 0.0) >= Math.max(1, rate);
    }

    private int active;

    private boolean toldNoShips;

    @Override
    public int hangarWanted(int active, orbit.world.WorldSnapshot w) {
        this.active = active;
        if (!toldNoShips && settings().path("twoShips").asBoolean(true) && !twoShips()) {
            toldNoShips = true;
            log.warn("Палладий: два корабля, но ангары сбора и продажи для аккаунта не выбраны — продаю одним кораблём");
        }
        if (!twoShips() || active == 0) {
            return 0;
        }
        if (active == collectHangar()) {
            BotConfig c = ctx.config().get();
            boolean full = w.hero().cargoMax() > 0 && w.cargoFree() <= c.collect().cargoFullAt();
            return full && hasPalladium(w) ? sellHangar() : 0;
        }
        if (active == sellHangar()) {
            return hasPalladium(w) || ctx.stats().selling ? 0 : collectHangar(); // sold: back to collecting
        }
        return collectHangar(); // a third ship: the collect one
    }

    @Override
    public int hangarOnDeath(int active, orbit.world.WorldSnapshot w) {
        boolean sellOnDie = settings().path("sellOnDie").asBoolean(true);
        return sellOnDie && twoShips() && active == collectHangar() && hasPalladium(w) ? sellHangar() : 0;
    }

    @Override
    public boolean hangarAtPortal() {
        return settings().path("goPortalChange").asBoolean(false);
    }

    @Override
    public String status() {
        return onSellShip(active) ? "Палладий: корабль продажи" : "Палладий: " + work.status();
    }

    @Override
    public int workingMap() {
        // the sell ship stays where it is parked (at 5-2's trader)
        return onSellShip(active) ? 0 : ctx.config().get().general().workingMap();
    }

    @Override
    public void tick() {
        if (onSellShip(active)) {
            return; // selling is the full-hold handling's; then back to the collect ship
        }
        work.tick();
    }

    @Override
    public void pause() {
        work.pause();
    }

    @Override
    public boolean busy() {
        return work.busy();
    }

    /**
     * The full hold: palladium only, at 5-2. With two ships the collect ship only stops taking it (the switch
     * sells), the sell ship sells whatever palladium is aboard, full or not.
     */
    @Override
    public CargoPlan cargoPlan(BotConfig c) {
        if (twoShips() && active == collectHangar()) {
            return new CargoPlan(BotConfig.Collect.CARGO_STOP, 0, List.of(PALLADIUM));
        }
        return new CargoPlan(BotConfig.Collect.CARGO_BASE, SELL_MAP, List.of(PALLADIUM), onSellShip(active));
    }
}
