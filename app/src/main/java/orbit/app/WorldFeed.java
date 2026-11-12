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
    private final LootLog loot = new LootLog();
    private final AtomicLong packets = new AtomicLong();
    private final String mode;
    private volatile String state = "Запуск";
    /** The server ended the session after a logout ({@code 0|l}). */
    private volatile boolean loggedOut;
    /** When the first ship initialisation arrived (session start), 0 before. */
    private volatile long sessionStart;
    /** When the session ended (logout, stop): its clock stops there; 0 while it runs. */
    private volatile long sessionEnd;
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
        return sessionNanos() / 3.6e12;
    }

    /** How long the session has run (frozen once it ended), 0 before it started. */
    public long sessionNanos() {
        long since = sessionStart;
        if (since == 0) {
            return 0;
        }
        long end = sessionEnd;
        return Math.max(0, (end != 0 ? end : now()) - since);
    }

    private void endSession() {
        if (sessionStart != 0 && sessionEnd == 0) {
            sessionEnd = now();
        }
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
        LIVE.add(this);
    }

    /** The feeds of this program, e.g. for picking players seen by any running account. */
    private static final java.util.List<WorldFeed> LIVE = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static java.util.List<WorldFeed> live() {
        return java.util.List.copyOf(LIVE);
    }

    /** A player this account saw (in the game, any map) and when. */
    public record SeenPlayer(String name, String clanTag, int factionId, int mapId, long atMillis) {
    }

    private final java.util.Map<String, SeenPlayer> seen = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long seenCheckedAt;

    /** Players seen in this program run, the latest first. */
    public java.util.List<SeenPlayer> seenPlayers() {
        return seen.values().stream().sorted(java.util.Comparator.comparingLong(SeenPlayer::atMillis).reversed())
                .toList();
    }

    private void rememberPlayers(long nanos) {
        if (nanos - seenCheckedAt < 1_000_000_000L) {
            return;
        }
        seenCheckedAt = nanos;
        WorldSnapshot w = world.snapshot(nanos);
        long now = System.currentTimeMillis();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.kind() == WorldSnapshot.ShipKind.PLAYER && s.id() != w.hero().id() && s.name() != null
                    && !s.name().isBlank()) {
                seen.put(s.name(), new SeenPlayer(s.name(), s.clanTag() == null ? "" : s.clanTag(), s.factionId(),
                        w.hero().mapId(), now));
            }
        }
    }

    public String account() {
        return account;
    }

    private volatile String server = "";

    public String server() {
        return server;
    }

    /** The account's server code ({@code int1}), for titles. */
    public void setServer(String s) {
        server = s == null ? "" : s;
    }

    private volatile java.util.function.Supplier<String> proxy = () -> "";
    private volatile boolean proxied;

    /** What the window shows about the account's proxy ({@code SOCKS5 1.2.3.4:1080 · IP 5.6.7.8}). */
    public void setProxy(java.util.function.Supplier<String> describe, boolean viaProxy) {
        proxy = describe;
        proxied = viaProxy;
    }

    /** The proxy line, empty without a proxy. */
    public String proxyInfo() {
        return proxied ? proxy.get() : "";
    }

    /** {@code int1 · FREEMASON} once in game, {@code int1 · main} before (the account's name). */
    public String title() {
        String nick = snapshot().hero().name();
        String who = nick != null && !nick.isBlank() ? nick : account != null ? account : mode;
        return server.isEmpty() ? who : server + " · " + who;
    }

    /** Remember every NPC met with its map ({@link orbit.world.maps.NpcMaps}); the app turns it on, tests do not. */
    public static volatile boolean learnNpcMaps;

    public void onPacket(Packet p, long nanos) {
        if (learnNpcMaps && p instanceof orbit.protocol.gen.ShipCreateCommand c && c.npc) {
            orbit.world.maps.NpcMaps.shared().learn(world.snapshot(nanos).hero().mapId(), c.userName);
        }
        if (learnNpcMaps && p instanceof orbit.protocol.gen.AddOreCommand o && o.oreType != null) {
            orbit.world.maps.OreMaps.shared().learn(world.snapshot(nanos).hero().mapId(), o.oreType.typeValue);
        }
        packets.incrementAndGet();
        WorldSnapshot.Hero before = world.snapshot(nanos).hero();
        int heroBefore = before.id();
        world.apply(p, nanos);
        rememberPlayers(nanos);
        loot.flush(nanos, false);
        if (p instanceof orbit.protocol.gen.LegacyModule lm) {
            loot.legacy(lm.message, nanos);
        } else if (p instanceof orbit.protocol.gen.AttributeCreditsUpdateCommand) {
            loot.compare(before, world.snapshot(nanos).hero());
        }
        if (sessionStart == 0 && (p instanceof ShipInitializationCommand || p instanceof orbit.protocol.gen.KillScreenPostCommand)) {
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
                endSession();
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

    private volatile String alert;
    private volatile long alertAt;
    private volatile boolean alertStopped;

    /** A sign of trouble that stopped the account (plan 5в.7г): the window shows it once. */
    public void alert(String why) {
        alertStopped = true;
        alert = why;
        alertAt = System.nanoTime();
        state = "Остановлен: " + why;
    }

    /** A warning the window shows once; the account goes on (plan 5в.5: the proxy's country changed). */
    public void warn(String why) {
        alertStopped = false;
        alert = why;
        alertAt = System.nanoTime();
    }

    /** The last alert stopped the account ({@link #alert}), not only warned ({@link #warn}). */
    public boolean alertStopped() {
        return alertStopped;
    }

    /** The last alert, or {@code null}. */
    public String alertText() {
        return alert;
    }

    /** When the last alert came ({@link System#nanoTime}), 0 if none: tells a new one from one shown. */
    public long alertAt() {
        return alertAt;
    }

    public void setState(String state) {
        this.state = state;
    }

    private volatile boolean linkLost;

    /** The connection dropped and is being restored (until the ship is in game again). */
    public boolean linkLost() {
        return linkLost;
    }

    public void setLinkLost(boolean lost) {
        linkLost = lost;
    }

    public String state() {
        return state;
    }

    /** A new login is about to start: the last one's logout and clock no longer count. */
    public void newSession() {
        loggedOut = false;
        sessionStart = 0;
        sessionEnd = 0;
    }

    /**
     * The same session goes on with a new login (a ship change: logout, the site, login): its clock runs on from
     * the first login, only the logout is forgotten.
     */
    public void resumeSession() {
        loggedOut = false;
        sessionEnd = 0;
    }

    /** The account was stopped: not in game any more, whatever the last packets said. */
    public void stopped() {
        loggedOut = true;
        linkLost = false;
        endSession();
    }

    public boolean loggedOut() {
        return loggedOut;
    }

    /** {@link #now()} when the session started, 0 before the ship is known. */
    public long sessionStartNanos() {
        return sessionStart;
    }

    private volatile orbit.bot.run.BotRunner runner;

    /** The bot driving this ship now, for the map's orders ({@code null}: none). */
    public void setRunner(orbit.bot.run.BotRunner r) {
        runner = r;
    }

    public orbit.bot.run.BotRunner runner() {
        return runner;
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
        if (!mode.startsWith("бот")) {
            return "Ведёт игрок";
        }
        if (loggedOut) {
            return "Не в игре";
        }
        return stats != null ? "Ожидание" : "Только держит связь";
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
