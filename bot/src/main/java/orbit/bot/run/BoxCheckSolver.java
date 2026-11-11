package orbit.bot.run;

import java.util.Comparator;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Does the game's box check as a player does it with the client: reads the task (a pause), then clicks the nearest
 * box of the asked colour, flies straight onto it, picks it on arrival ({@code CaptchaCollectRequest}, as the
 * client's {@code EventManager.handleMoveComplete}) and goes on to the next one after a short pause, until as many
 * as asked are picked; then it stands and waits for the server's verdict. Only boxes of the asked colour are ever
 * touched. Call {@link #tick} every bot tick while {@link WorldSnapshot#boxCheck} is up.
 */
final class BoxCheckSolver {

    private static final Logger log = LoggerFactory.getLogger(BoxCheckSolver.class);

    /** Reading the task before the first click. */
    static final long READ_MIN_MS = 1_500;
    static final long READ_MAX_MS = 3_500;
    /** Between picking one box and clicking the next. */
    static final long NEXT_MIN_MS = 300;
    static final long NEXT_MAX_MS = 900;
    /** A click the ship did not follow is repeated after this. */
    static final long RETRY_MS = 1_500;
    /** On the box: the ship stands within this of it. */
    static final double ON_BOX = 3;

    private final BotContext ctx;
    private long checkSince = Long.MIN_VALUE;
    private long nextAt;
    private WorldSnapshot.BoxCheck.CheckBox flyingTo;
    private long clickedAt;
    private boolean doneLogged;

    BoxCheckSolver(BotContext ctx) {
        this.ctx = ctx;
    }

    void tick(WorldSnapshot w) {
        WorldSnapshot.BoxCheck c = w.boxCheck();
        if (c == null) {
            return;
        }
        long now = ctx.now();
        if (c.sinceNanos() != checkSince) {
            checkSince = c.sinceNanos();
            flyingTo = null;
            doneLogged = false;
            long read = ctx.pause(READ_MIN_MS, READ_MAX_MS);
            if (c.seconds() > 0) {
                read = Math.min(read, c.seconds() * 1_000_000_000L / 8);
            }
            nextAt = now + read;
            log.info("Выполняю проверку: {}", c.task());
        }
        WorldSnapshot.Hero h = w.hero();
        if (!h.alive()) {
            return;
        }
        if (c.left() == 0) {
            if (!doneLogged) {
                doneLogged = true;
                log.info("Ящики проверки собраны — жду ответа сервера");
            }
            return;
        }
        if (flyingTo != null) {
            WorldSnapshot.BoxCheck.CheckBox target = flyingTo;
            boolean still = c.targets().stream().anyMatch(b -> b.index() == target.index());
            if (!still) {
                flyingTo = null;
            } else if (!h.moving() && Math.hypot(h.x() - target.x(), h.y() - target.y()) <= ON_BOX) {
                ctx.actions().pickCheckBox(target.index());
                log.info("Ящик проверки собран ({} {}), осталось {}", target.red() ? "красный" : "чёрный", target.index(),
                        c.left() - 1);
                flyingTo = null;
                nextAt = now + ctx.pause(NEXT_MIN_MS, NEXT_MAX_MS);
                return;
            } else if (!h.moving() && now - clickedAt > RETRY_MS * 1_000_000) {
                ctx.actions().flyToCheckBox(target.x(), target.y()); // the ship did not go: click the box again
                clickedAt = now;
                return;
            } else {
                return;
            }
        }
        if (now < nextAt) {
            return;
        }
        WorldSnapshot.BoxCheck.CheckBox next = c.targets().stream()
                .min(Comparator.comparingDouble(b -> Math.hypot(b.x() - h.x(), b.y() - h.y())))
                .orElse(null);
        if (next == null) {
            return;
        }
        flyingTo = next;
        clickedAt = now;
        ctx.actions().flyToCheckBox(next.x(), next.y());
    }
}
