package orbit.bot.run;

import orbit.bot.mod.CubikonModule;
import orbit.bot.mod.KillModule;
import orbit.bot.mod.SpawnMemory;
import orbit.config.BotConfig;

/** The module object for a config's main module; {@code null} if the bot cannot run it yet. */
public final class Modules {

    private Modules() {
    }

    public static BotModule create(BotConfig.Module m, BotContext ctx) {
        return switch (m) {
            case KILL_COLLECT -> new KillModule(ctx, true, true);
            case PALLADIUM -> new orbit.bot.mod.PalladiumModule(ctx);
            case KILL -> new KillModule(ctx, true, false);
            case COLLECT -> new KillModule(ctx, false, true);
            case CUBIKON -> new CubikonModule(ctx, ctx.dataDir() == null ? SpawnMemory.inMemory()
                    : SpawnMemory.open(ctx.dataDir().resolve(SpawnMemory.DEFAULT_FILE.getFileName())));
            default -> null;
        };
    }
}
