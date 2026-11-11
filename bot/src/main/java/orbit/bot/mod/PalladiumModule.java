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
 * Module»: «Loot & collect, but when cargo is full travels to 5-2 to sell»). The two-ship way (DmPlugin «Palladium
 * Hangar»: a sell ship parked at 5-2, hangars switched on the website) is in {@code HangarSale}.
 */
public final class PalladiumModule implements BotModule {

    /** 5-2: the trader that takes palladium. */
    public static final int SELL_MAP = 92;
    /** The palladium ore ({@code OreTypeModule.PALLADIUM}). */
    public static final int PALLADIUM = 8;

    private final BotContext ctx;
    private final KillModule work;

    public PalladiumModule(BotContext ctx) {
        this.ctx = ctx;
        this.work = new KillModule(ctx, true, true);
    }

    @Override
    public String status() {
        return "Палладий: " + work.status();
    }

    @Override
    public int workingMap() {
        return ctx.config().get().general().workingMap();
    }

    @Override
    public void tick() {
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

    /** The full hold: palladium only, at 5-2. */
    @Override
    public CargoPlan cargoPlan(BotConfig c) {
        return new CargoPlan(BotConfig.Collect.CARGO_BASE, SELL_MAP, List.of(PALLADIUM));
    }
}
