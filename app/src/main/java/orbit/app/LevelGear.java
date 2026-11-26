package orbit.app;

import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;

/**
 * The leveling configs' P.E.T. by what the account owns (docs/LEVELING.md «Формации и P.E.T.», a user's choice 2026-11-26): a
 * P.E.T. owned is always out, passive (it is there for its level: the resource collector gear needs P.E.T. 8+), its fuel
 * bought. The formation X is written in the leveling configs themselves, as in any config. The game is looked at no more
 * than every {@link #SEEN_NS} and the config rebuilt only when it or the pet changes (configs are read many times a tick).
 */
final class LevelGear {

    /** How often the game is looked at for the pet. */
    static final long SEEN_NS = 2_000_000_000L;

    private LevelGear() {
    }

    static UnaryOperator<BotConfig> override(Supplier<WorldSnapshot> world, Supplier<String> configName) {
        return new UnaryOperator<>() {
            private String leveledName;
            private boolean leveling;
            private BotConfig lastIn;
            private boolean lastPet;
            private BotConfig lastOut;
            private long seenAt;
            private boolean pet;

            @Override
            public synchronized BotConfig apply(BotConfig c) {
                String name = configName.get();
                if (name == null || c == null) {
                    return c;
                }
                if (!name.equals(leveledName)) {
                    leveledName = name;
                    // the palladium stage's config keeps its own P.E.T. (the resource collector), not the passive one
                    var kit = orbit.bot.level.PalladiumKit.bundled();
                    leveling = OrbitMain.levelingConfig(name) && (kit == null || !name.equals(kit.config()));
                }
                if (!leveling) {
                    return c;
                }
                long now = System.nanoTime();
                if (now - seenAt > SEEN_NS) {
                    seenAt = now;
                    WorldSnapshot w = world.get();
                    if (w != null && w.hero() != null) {
                        pet = w.pet() != null && w.pet().owned();
                    }
                }
                if (!pet) {
                    return c;
                }
                if (c == lastIn && lastPet) {
                    return lastOut;
                }
                lastIn = c;
                lastPet = true;
                lastOut = gear(c, true);
                return lastOut;
            }
        };
    }

    static BotConfig gear(BotConfig c, boolean pet) {
        if (!pet) {
            return c;
        }
        BotConfig.Pet p = c.pet();
        return c.withPet(new BotConfig.Pet(true, "PASSIVE", p.locatorTarget(), p.noRepair(), p.comboRepairBelow(),
                p.kamikaze(), true, p.hpLinkBelow()));
    }
}
