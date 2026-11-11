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
    /**
     * The server ignores a formation change for a while after a configuration switch (live 2026-11-11: asked
     * 2.5 s after it, no answer, the ship kept its formation; the client's configuration cooldown is 5 s).
     */
    static final long FORMATION_AFTER_CONFIG_MS = 5_500;
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

    /** No «ready» from the server this long after a formation timer started: try anyway, not to get stuck. */
    static final long FORMATION_READY_WAIT_MS = 180_000;

    /**
     * The formations' cooldown ends when the server says «ready» (timer state 0), not when its «2000 ms» run
     * out: live 2026-11-11 «ready» came 5, 18 and 30 s after a change, and every request before it was ignored.
     */
    static boolean cooledDown(WorldSnapshot.Item item, long now) {
        if (item.timerState() == orbit.protocol.gen.TimerStateModule.TIMERSTATE_READY) {
            return true;
        }
        return now - item.timerNanos() > FORMATION_READY_WAIT_MS * 1_000_000;
    }

    /**
     * The number the server gives a formation in {@code DroneFormationChangeCommand}: {@code f-11-he} is 11
     * (recordings), the numbered ones follow their names; Ring and Drill are 14 and 15, next in the game's
     * list after Bat (13) and the two numbers other ships show most in the recordings. {@code null} for the
     * others: the bot learns them from the server's answers.
     */
    static Integer formationId(String item) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("drone_formation_f-(\\d\\d)-").matcher(item);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return switch (item) {
            case "drone_formation_f-3d-rg" -> 14;
            case "drone_formation_f-3d-dr" -> 15;
            default -> null;
        };
    }

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
                Integer table = formationId(asked);
                if (table != null && table != h.formation()) {
                    log.warn("Формация {}: сервер называет её №{}, а не №{} — запомнил", asked, h.formation(), table);
                }
                ids.put(asked, h.formation()); // the server's answer wins over the table
                log.debug("Формация {}: сервер подтвердил (№{})", asked, h.formation());
                asked = null;
            }
            if (asked != null && !asked.equals(f)) {
                asked = null; // another formation is wanted now: a later request for the old one is new, not a repeat
            }
            Integer known = ids.containsKey(f) ? ids.get(f) : formationId(f);
            // asking for the formation the ship already has gets no answer: check the server's number first
            boolean on = item != null && item.selected() || known != null && known == h.formation();
            boolean waiting = asked != null && now - formationAt < FORMATION_ANSWER_MS * 1_000_000;
            if (item != null && !on && !waiting && item.available() && cooledDown(item, now)
                    && now - formationAt > FORMATION_GAP_MS * 1_000_000
                    && now - configAt > FORMATION_AFTER_CONFIG_MS * 1_000_000) {
                if (item.timerState() != orbit.protocol.gen.TimerStateModule.TIMERSTATE_READY) {
                    log.warn("Формация {}: «готово» от сервера не пришло за {} с — пробую всё равно",
                            orbit.config.ItemCatalog.label(f), FORMATION_READY_WAIT_MS / 1_000);
                }
                if (f.equals(asked)) {
                    log.warn("Формация {}: сервер не ответил за {} с (на корабле №{}, ждём №{}) — прошу ещё раз",
                            orbit.config.ItemCatalog.label(f), FORMATION_ANSWER_MS / 1_000, h.formation(), known);
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
