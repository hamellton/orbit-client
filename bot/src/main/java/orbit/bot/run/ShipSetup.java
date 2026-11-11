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
    /** After a configuration switch the server sets that configuration's formation itself: let it, then ask. */
    static final long FORMATION_AFTER_CONFIG_MS = 2_500;
    /** No word from the server after a formation request this long: ask once more. */
    static final long FORMATION_ANSWER_MS = 8_000;

    private final Supplier<WorldSnapshot> world;
    private final Actions actions;
    private final LongSupplier clock;
    private final java.util.Random random;
    private BotConfig.ShipMode wanted;
    private long actAt;
    private long configAt = Long.MIN_VALUE / 2;
    private long formationAt = Long.MIN_VALUE / 2;
    private String missingTold;
    /** The formation asked for and not yet confirmed (null: none pending). */
    private String asked;
    /** Formation item → the id the server reported right after it was asked for (learned per session). */
    private final java.util.Map<String, Integer> ids = new java.util.HashMap<>();
    private long missingSince;
    static final long MISSING_WARN_MS = 20_000;

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
            // the bar fills in over the first seconds after a login: only a formation missing for a while counts
            if (item != null || w.items().isEmpty()) {
                missingSince = 0;
            } else if (missingSince == 0) {
                missingSince = now;
            } else if (now - missingSince > MISSING_WARN_MS * 1_000_000 && !f.equals(missingTold)) {
                missingTold = f;
                log.warn("Формации {} нет на панели игры — оставляю текущую", orbit.config.ItemCatalog.label(f));
            }
            // the server answers a formation change with DroneFormationChangeCommand at once; the item bar's
            // «selected» may come much later, so the answer counts — asking again every few seconds did not help
            if (asked != null && h.formationNanos() > formationAt) {
                ids.putIfAbsent(asked, h.formation());
                log.debug("Формация {}: сервер подтвердил (№{})", asked, h.formation());
                asked = null;
            }
            Integer known = ids.get(f);
            boolean on = item != null && item.selected() || known != null && known == h.formation();
            boolean waiting = asked != null && now - formationAt < FORMATION_ANSWER_MS * 1_000_000;
            if (item != null && !on && !waiting && item.available() && item.remainingMs(now) == 0
                    && now - formationAt > FORMATION_GAP_MS * 1_000_000
                    && now - configAt > FORMATION_AFTER_CONFIG_MS * 1_000_000) {
                if (f.equals(asked)) {
                    log.warn("Формация {}: сервер не ответил за {} с — прошу ещё раз", orbit.config.ItemCatalog.label(f),
                            FORMATION_ANSWER_MS / 1_000);
                } else {
                    log.info("Формация {}", orbit.config.ItemCatalog.label(f));
                }
                actions.useItem(f);
                formationAt = now;
                asked = f;
            }
        }
    }
}
