package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import orbit.bot.run.BotContext;
import orbit.config.GateCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Zeta chromin farmer (docs/GALAXY-GATES.md §12), AstraBot's «Zeta chromin farmer» ({@code zetaChrominFarmer},
 * {@code ZetaChrominFarmerModule}). AstraBot's code is closed: rebuilt from its status line («Chromin Farmer | Lives: … |
 * Status: Killing / Collect box / Suiciding | Chromins: … | Bought lives: …»), SharedPlugin's ZetaGate (the Devourer below
 * 25 % — the boxes picked up amid the fight) and the game's Chromin Rush (the gates' NPCs drop «Chromin Boxes», the
 * event's cap told by {@code ChrominUpdateCommand}).
 *
 * <p>While the event is open and its cap not reached: in ζ the Devourer is shot down to {@code holdHp} and kept alive,
 * the rest of its stage killed, the chromin boxes picked up; nothing left to shoot or pick up for {@code waitSec} — the
 * ship lets the Devourer destroy it (fire stopped, the pet passive, no repair) while the gate keeps two lives or more;
 * back in the gate the Devourer's stage is played again. A gate's lives are bought as the module's settings say. Two
 * deaths in a row with no chromin gained, or no death in {@link #SUICIDE_MAX_MS} — the farmer stops for this gate, the
 * Devourer is killed and the gate played to its end.
 *
 * <p>Settings ({@code modules.gate}): {@code zetaChrominFarmer} (off), {@code chromin}: {@code holdHp} (0.1),
 * {@code waitSec} (10).
 */
final class ZetaChromin {

    private static final Logger log = LoggerFactory.getLogger(ZetaChromin.class);

    /** The Devourer's bare name (ζ25, ζ27). */
    static final String DEVOURER = "devourer";
    /** No death this long after the ship gave itself up — the farmer gives up for this gate. */
    static final long SUICIDE_MAX_MS = 180_000;
    /** Within this of the Devourer the ship stays still (EBG's suicide brake: 1 000). */
    static final double SUICIDE_NEAR = 800;
    /** Deaths in a row with no chromin gained that stop the farmer for the gate. */
    static final int NO_GAIN_MAX = 2;

    private final BotContext ctx;
    private boolean suiciding;
    private long suicideAt;
    private long heldSince;
    private boolean off;
    private double chrominAtDeath = -1;
    private int noGain;
    private int deaths;
    private String state = "";

    ZetaChromin(BotContext ctx) {
        this.ctx = ctx;
    }

    static boolean on(JsonNode s) {
        return s.path("zetaChrominFarmer").asBoolean(false);
    }

    private static double holdHp(JsonNode s) {
        return s.path("chromin").path("holdHp").asDouble(0.1);
    }

    private static long waitMs(JsonNode s) {
        return Math.max(0, s.path("chromin").path("waitSec").asLong(10)) * 1_000;
    }

    /** The farmer plays this gate now: on, ζ, the event open, not given up for this gate. */
    boolean farming(JsonNode s, GateCatalog.Gate g, WorldSnapshot w) {
        return on(s) && g != null && "zeta".equals(g.key()) && !off && w.gates().chromin().open();
    }

    /** A Devourer the farmer keeps alive: shot down to {@code holdHp}. */
    boolean held(JsonNode s, WorldSnapshot.Ship npc) {
        return npc.npc() && DEVOURER.equals(GateCatalog.gateNpc(npc.name())) && npc.health().known()
                && npc.health().hpPercent() < holdHp(s);
    }

    boolean suiciding() {
        return suiciding;
    }

    /** The farmer's word for the status: «бой», «подбираю ящик», «жду гибели». */
    String state() {
        return state;
    }

    int deaths() {
        return deaths;
    }

    /**
     * One tick in ζ while farming, after the fight's pick ({@code target}: the NPC to shoot, {@code null} none — the held
     * Devourer is never picked). True when the farmer steered the ship (the box, the wait, the suicide).
     */
    boolean tick(JsonNode s, WorldSnapshot w, WorldSnapshot.Ship target, Attacker attacker, BoxPicker boxes,
                 double boxRadius) {
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship devourer = w.ships().values().stream().filter(sh -> held(s, sh))
                .min(Comparator.comparingDouble(sh -> sh.distanceTo(h.x(), h.y()))).orElse(null);
        if (suiciding) {
            if (ms(now - suicideAt) > SUICIDE_MAX_MS) {
                stop("за " + SUICIDE_MAX_MS / 60_000 + " мин корабль не уничтожен");
                return false;
            }
            WorldSnapshot.Ship near = devourer != null ? devourer : w.ships().values().stream().filter(WorldSnapshot.Ship::npc)
                    .min(Comparator.comparingDouble(sh -> sh.distanceTo(h.x(), h.y()))).orElse(null);
            if (near != null && near.distanceTo(h.x(), h.y()) > SUICIDE_NEAR) {
                if (ctx.nav().idle() || ctx.nav().destination().x() != near.x() || ctx.nav().destination().y() != near.y()) {
                    ctx.nav().moveTo(near.x(), near.y(), false);
                }
                ctx.nav().tick();
            } else {
                ctx.nav().stop();
            }
            state = "жду гибели";
            return true;
        }
        if (target != null || devourer == null) {
            heldSince = 0;
            state = "бой";
            return false;
        }
        // the Devourer held, nothing else to shoot: its boxes, then the ship given up
        if (boxes.tick(h.x(), h.y(), boxRadius)) {
            heldSince = 0;
            state = "подбираю ящик";
            return true;
        }
        if (heldSince == 0) {
            heldSince = now;
        }
        WorldSnapshot.Gate st = w.gates().gate(GateCatalog.byKey("zeta").builderId());
        if (st != null && st.lives() >= 0 && st.lives() < 2) {
            stop("жизней " + st.lives() + " — последнюю не трачу");
            return false;
        }
        if (ms(now - heldSince) < waitMs(s)) {
            ceaseFire(h, attacker);
            ctx.nav().stop();
            state = "Devourer держу, жду ящики";
            return true;
        }
        double chromin = w.gates().chromin().current();
        if (chrominAtDeath >= 0 && chromin <= chrominAtDeath) {
            noGain++;
            if (noGain >= NO_GAIN_MAX) {
                stop(NO_GAIN_MAX + " раза подряд без хромина");
                return false;
            }
        } else {
            noGain = 0;
        }
        chrominAtDeath = chromin;
        suiciding = true;
        suicideAt = now;
        deaths++;
        ceaseFire(h, attacker);
        log.info("Хромин-фарм: Devourer держу на {}%, ящиков нет — жду гибели у него (хромин {} из {}, жизней {})",
                Math.round(devourer.health().hpPercent() * 100), (long) chromin, (long) w.gates().chromin().max(),
                st != null ? st.lives() : -1);
        state = "жду гибели";
        return true;
    }

    /** The fire stopped once (the lasers on the held Devourer would kill it): no new stop while none is on. */
    void ceaseFire(WorldSnapshot.Hero h, Attacker attacker) {
        if (attacker.targetId() != 0 || h.attackTargetId() != 0) {
            attacker.clear();
            ctx.actions().stopAttack();
        }
    }

    private void stop(String why) {
        if (!off) {
            log.warn("Хромин-фарм: {} — дальше врата как обычно (Devourer добиваю)", why);
        }
        off = true;
        suiciding = false;
        state = "";
    }

    /** Out of the gate (destroyed or done): the next entry plays the Devourer's stage again. */
    void left(boolean completed) {
        suiciding = false;
        heldSince = 0;
        state = "";
        if (completed) {
            off = false; // a new gate: the farmer again
            noGain = 0;
            chrominAtDeath = -1;
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
