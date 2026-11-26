package orbit.app;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;

/**
 * The leveling configs' gear by what the account owns (docs/LEVELING.md «Формации и P.E.T.», a user's choice 2026-11-26): the
 * program's configs are read-only, so the formation and the pet are set on the config as it is read —
 *
 * <ul>
 *   <li>formation «X» owned: attacking, roaming and running in it, in both ship configs;</li>
 *   <li>«Moth» owned too: attacking in the Moth (the Zeta's formation), roaming and running still in X;</li>
 *   <li>a P.E.T. owned: always out, passive (it is there for its level: the resource collector gear needs P.E.T. 8+), its
 *       fuel bought.</li>
 * </ul>
 *
 * The config is rebuilt only when the config or what is owned changes (configs are read many times a tick).
 */
final class LevelGear {

    static final String X = "drone_formation_f-3d-x";
    static final String MOTH = "drone_formation_f-09-mo";

    private LevelGear() {
    }

    static UnaryOperator<BotConfig> override(Supplier<WorldSnapshot> world, Supplier<String> configName) {
        return new UnaryOperator<>() {
            private String leveledName;
            private boolean leveling;
            private BotConfig lastIn;
            private String lastKey;
            private BotConfig lastOut;

            @Override
            public synchronized BotConfig apply(BotConfig c) {
                String name = configName.get();
                if (name == null) {
                    return c;
                }
                if (!name.equals(leveledName)) {
                    leveledName = name;
                    leveling = OrbitMain.levelingConfig(name);
                }
                WorldSnapshot w = world.get();
                if (!leveling || c == null || w == null || w.hero() == null) {
                    return c;
                }
                boolean x = owned(w, X);
                boolean moth = owned(w, MOTH);
                boolean pet = w.pet() != null && w.pet().owned();
                String key = x + "/" + moth + "/" + pet;
                if (c == lastIn && key.equals(lastKey)) {
                    return lastOut;
                }
                lastIn = c;
                lastKey = key;
                lastOut = gear(c, x, moth, pet);
                return lastOut;
            }
        };
    }

    static BotConfig gear(BotConfig c, boolean x, boolean moth, boolean pet) {
        BotConfig out = c;
        if (x || moth) {
            BotConfig.General g = c.general();
            String attack = moth ? MOTH : X;
            String rest = x ? X : null;
            out = out.withGeneral(new BotConfig.General(g.workingMap(), mode(g.offensive(), attack), mode(g.roam(), rest),
                    mode(g.run(), rest), g.runFromEnemies(), g.runFromEnemiesInSight(), g.rememberEnemiesSec(),
                    g.roamSequential(), g.onlyKillPreferred(), g.runModeTravel(), g.runModeChase(), g.avoidMines(),
                    g.baseRadius()));
        }
        if (pet) {
            BotConfig.Pet p = c.pet();
            out = out.withPet(new BotConfig.Pet(true, "PASSIVE", p.locatorTarget(), p.noRepair(), p.comboRepairBelow(),
                    p.kamikaze(), true, p.hpLinkBelow()));
        }
        return out;
    }

    private static BotConfig.ShipMode mode(BotConfig.ShipMode m, String formation) {
        return formation == null ? m : new BotConfig.ShipMode(m.config(), formation);
    }

    private static boolean owned(WorldSnapshot w, String id) {
        WorldSnapshot.Item i = w.items().get(id);
        return i != null && i.available();
    }
}
