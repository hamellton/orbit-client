package orbit.bot.run;

import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import orbit.bot.act.Actions;
import orbit.bot.nav.Navigator;
import orbit.bot.nav.Traveler;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/**
 * What modules work with: the world, the config, the actions and the movement. One per running bot;
 * {@link #config} is replaced when the config is switched.
 *
 * @param clock   nanoseconds ({@link System#nanoTime} in the bot, a fake clock in tests)
 * @param repair  takes a kill screen option ({@code KillScreenOptionTypeModule}) with the faction id
 * @param dataDir where modules keep what they learn ({@code local}); {@code null} = in memory only (tests)
 * @param stats   session counters for the window and the summary
 */
public record BotContext(Supplier<WorldSnapshot> world, Supplier<BotConfig> config, Actions actions, Navigator nav,
                         Traveler traveler, MapCatalog maps, Random random, LongSupplier clock, Repair repair,
                         ShipSetup ship, java.nio.file.Path dataDir, Stats stats) {

    @FunctionalInterface
    public interface Repair {
        void take(int optionType, int factionId);
    }

    public long now() {
        return clock.getAsLong();
    }

    /** A random pause in nanoseconds, like a player's reaction. */
    public long pause(long minMs, long maxMs) {
        return (minMs + (long) (random.nextDouble() * (maxMs - minMs))) * 1_000_000;
    }
}
