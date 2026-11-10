package orbit.app;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import orbit.protocol.Packet;
import orbit.protocol.gen.JumpInitiatedCommand;
import orbit.protocol.gen.ReloginCommand;
import orbit.protocol.gen.ShipDestroyedCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the window shows: the world model plus the connection state, fed by whichever source runs
 * (the bot's own connection, the sniffer in observer mode, or a recording).
 */
public final class WorldFeed {

    private static final Logger log = LoggerFactory.getLogger(WorldFeed.class);

    private final World world = new World();
    private final AtomicLong packets = new AtomicLong();
    private final String mode;
    private volatile String state = "Запуск";
    /** The server ended the session after a logout ({@code 0|l}). */
    private volatile boolean loggedOut;
    /** When the first ship initialisation arrived (session start), 0 before. */
    private volatile long sessionStart;
    /** What the bot is doing; set by the behaviour modules (stage 4–5), none yet. */
    private volatile String action;
    /** Time base of the model: real time, or the replay clock. */
    private volatile LongSupplier clock = System::nanoTime;
    private volatile orbit.bot.run.Stats stats;

    /** The running bot's counters (none in observer or replay mode). */
    public void setStats(orbit.bot.run.Stats s) {
        stats = s;
    }

    public orbit.bot.run.Stats stats() {
        return stats;
    }

    /** Session length in hours (0 before the session starts). */
    public double sessionHours() {
        long since = sessionStart;
        return since == 0 ? 0 : (now() - since) / 3.6e12;
    }

    /** The session's results in one line (for the log). */
    public String summary() {
        return SessionSummary.text(snapshot().rewards(), stats, sessionHours());
    }

    private final String account;

    public WorldFeed(String mode) {
        this(mode, null);
    }

    /** @param account the account the window shows (its log lines only); {@code null} = all */
    public WorldFeed(String mode, String account) {
        this.mode = mode;
        this.account = account;
    }

    public String account() {
        return account;
    }

    private volatile String server = "";

    /** The account's server code ({@code int1}), for titles. */
    public void setServer(String s) {
        server = s == null ? "" : s;
    }

    /** {@code int1 · FREEMASON} once in game, {@code int1 · main} before (the account's name). */
    public String title() {
        String nick = snapshot().hero().name();
        String who = nick != null && !nick.isBlank() ? nick : account != null ? account : mode;
        return server.isEmpty() ? who : server + " · " + who;
    }

    public void onPacket(Packet p, long nanos) {
        packets.incrementAndGet();
        int heroBefore = world.snapshot(nanos).hero().id();
        world.apply(p, nanos);
        if (sessionStart == 0 && p instanceof ShipInitializationCommand) {
            sessionStart = nanos;
        }
        logEvent(p, heroBefore);
    }

    /** Events worth a line in the window's log; the state line follows them in observer and replay modes. */
    private void logEvent(Packet p, int heroId) {
        MapCatalog maps = MapCatalog.bundled();
        switch (p) {
            case ShipInitializationCommand si -> {
                String where = maps.info(si.mapId).name();
                log.info("Корабль {} на карте {} ({}/{})", si.userName, where, si.x / 100, si.y / 100);
                if (!mode.startsWith("бот")) {
                    state = "В игре: " + where;
                }
            }
            case JumpInitiatedCommand j -> log.info("Прыжок на карту {}", maps.info(j.mapId).name());
            case orbit.protocol.gen.LegacyModule m when "0|l".equals(m.message) -> {
                loggedOut = true;
                log.info("Сервер завершил сессию (выход)");
            }
            case orbit.protocol.gen.KillScreenPostCommand k -> {
                WorldSnapshot.Death d = world.snapshot(now()).death();
                log.warn("Корабль уничтожен ({}). Варианты: {}", k.killerName, d == null ? "—"
                        : d.options().stream().map(o -> o.button() + (o.price() > 0 ? " за " + o.price() : "")
                                + (o.cooldownSeconds() > 0 ? " через " + o.cooldownSeconds() + " с" : "")).toList());
                if (!mode.startsWith("бот")) {
                    state = "Корабль уничтожен";
                }
            }
            case ReloginCommand r -> log.info("Переход на карту {} (другой сервер)", maps.info(r.mapID).name());
            case ShipDestroyedCommand d when d.destroyedUserId == heroId && heroId != 0 -> {
                log.warn("Корабль уничтожен");
                if (!mode.startsWith("бот")) {
                    state = "Корабль уничтожен";
                }
            }
            default -> {
            }
        }
    }

    public void onSent(Packet p, long nanos) {
        world.applyOutgoing(p, nanos);
    }

    public void setState(String state) {
        this.state = state;
    }

    public String state() {
        return state;
    }

    /** A new login is about to start: the last one's logout and clock no longer count. */
    public void newSession() {
        loggedOut = false;
        sessionStart = 0;
    }

    public boolean loggedOut() {
        return loggedOut;
    }

    /** {@link #now()} when the session started, 0 before the ship is known. */
    public long sessionStartNanos() {
        return sessionStart;
    }

    public void setAction(String action) {
        this.action = action;
    }

    /** Current bot action, or a note when the bot does not control the ship. */
    public String action() {
        String a = action;
        if (a != null) {
            return a;
        }
        return mode.startsWith("бот") ? "нет (бот пока только держит связь)" : "— (корабль ведёт игрок)";
    }

    public String mode() {
        return mode;
    }

    public long packets() {
        return packets.get();
    }

    public void setClock(LongSupplier clock) {
        this.clock = clock;
    }

    public long now() {
        return clock.getAsLong();
    }

    /** The world as of now (by the feed's clock). */
    public WorldSnapshot snapshot() {
        return world.snapshot(now());
    }
}
