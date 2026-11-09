package orbit.bot.run;

import java.util.function.LongSupplier;
import java.util.function.Supplier;
import orbit.bot.act.Actions;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the ship configuration (1/2) and the drone formation of the current situation (attack, roam,
 * run, repair), the way a player switches them: a short pause after the situation changes, never
 * more often than the game allows.
 */
public final class ShipSetup {

    private static final Logger log = LoggerFactory.getLogger(ShipSetup.class);

    /** The game refuses configuration switches more often than this (client: 5 s cooldown). */
    static final long CONFIG_GAP_MS = 6_000;
    static final long FORMATION_GAP_MS = 3_000;

    private final Supplier<WorldSnapshot> world;
    private final Actions actions;
    private final LongSupplier clock;
    private final java.util.Random random;
    private BotConfig.ShipMode wanted;
    private long actAt;
    private long configAt = Long.MIN_VALUE / 2;
    private long formationAt = Long.MIN_VALUE / 2;

    public ShipSetup(Supplier<WorldSnapshot> world, Actions actions, LongSupplier clock, java.util.Random random) {
        this.world = world;
        this.actions = actions;
        this.clock = clock;
        this.random = random;
    }

    /** The mode wanted now; call every tick. */
    public void want(BotConfig.ShipMode mode) {
        long now = clock.getAsLong();
        if (!mode.equals(wanted)) {
            wanted = mode;
            actAt = now + (300 + (long) (random.nextDouble() * 700)) * 1_000_000;
        }
        if (now < actAt) {
            return;
        }
        WorldSnapshot w = world.get();
        WorldSnapshot.Hero h = w.hero();
        if (h.config() != 0 && h.config() != mode.config() && now - configAt > CONFIG_GAP_MS * 1_000_000) {
            log.info("Конфигурация {}", mode.config());
            actions.switchConfig(mode.config());
            configAt = now;
            return;
        }
        String f = mode.formation();
        if (f != null) {
            WorldSnapshot.Item item = w.items().get(f);
            if (item != null && !item.selected() && item.available() && item.remainingMs(now) == 0
                    && now - formationAt > FORMATION_GAP_MS * 1_000_000) {
                log.info("Формация {}", orbit.config.ItemCatalog.label(f));
                actions.useItem(f);
                formationAt = now;
            }
        }
    }
}
