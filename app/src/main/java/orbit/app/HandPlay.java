package orbit.app;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import orbit.auth.GameSession;
import orbit.auth.proxy.ProxyRoute;
import orbit.bot.run.BotRunner;
import orbit.game.GameConnection;
import orbit.sniffer.HandRelay;
import orbit.sniffer.Upstream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * «Играть руками» (docs/PLAN.md, a user's wish 2026-11-26): the account's game handed to the official client and back, no
 * logout. The bot is paused and its connection let go ({@link GameConnection#hold}); the client starts with the same site
 * session ({@code --url} the game page, {@code --dosid}, its own profile folder), its site and game traffic through a
 * relay on loopback and out by the account's proxy (never direct when the account has one); the game packets go to the
 * account's window (map, «Пакеты») and to a recording. When the client is closed the bot logs in again and goes on.
 */
public final class HandPlay {

    private static final Logger LOG = LoggerFactory.getLogger(HandPlay.class);

    /** What the hand play needs of a running account. */
    record Handle(Supplier<GameConnection> conn, Supplier<BotRunner> runner, ProxyRoute route, WorldFeed feed) {
    }

    private static final Map<String, Handle> ACCOUNTS = new ConcurrentHashMap<>();
    private static final Map<String, Process> PLAYING = new ConcurrentHashMap<>();

    /** The game ports the client's Flash sockets come to (cluster 22: 6001–6007; the session's own port added). */
    static final List<Integer> GAME_PORTS = List.of(6001, 6002, 6003, 6004, 6005, 6006, 6007);

    private HandPlay() {
    }

    static void register(String account, Supplier<GameConnection> conn, Supplier<BotRunner> runner, ProxyRoute route,
                         WorldFeed feed) {
        ACCOUNTS.put(account, new Handle(conn, runner, route, feed));
    }

    /** The account can be handed over (it runs in this program). */
    public static boolean available(String account) {
        return account != null && ACCOUNTS.containsKey(account);
    }

    /** The account is played by hand now. */
    public static boolean active(String account) {
        return account != null && PLAYING.containsKey(account);
    }

    /** The official client: {@code ORBIT_CLIENT_APP}, else next to the project, else in Applications. */
    static Path clientApp() {
        List<Path> tries = new ArrayList<>();
        String env = System.getenv("ORBIT_CLIENT_APP");
        if (env != null && !env.isBlank()) {
            tries.add(Path.of(env));
        }
        tries.add(Path.of("..", "DO Client", "DarkOrbit Client.app"));
        tries.add(Path.of("/Applications", "DarkOrbit Client.app"));
        for (Path p : tries) {
            if (Files.isDirectory(p)) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    /**
     * Hands the game to the official client. {@code status} hears the steps and the end (Russian, for the window); the
     * call returns once the client is started or it could not be.
     */
    public static synchronized void start(String account, Consumer<String> status) {
        Handle h = ACCOUNTS.get(account);
        if (h == null) {
            status.accept("этот аккаунт не запущен ботом в этой программе");
            return;
        }
        if (PLAYING.containsKey(account)) {
            status.accept("уже играешь руками — закрой клиент или нажми «Вернуть боту»");
            return;
        }
        GameConnection conn = h.conn().get();
        GameSession session = conn == null ? null : conn.session();
        if (session == null || session.dosid() == null || session.host() == null) {
            status.accept("нет игровой сессии — сначала бот должен войти в игру");
            return;
        }
        if (!PLAYING.isEmpty() || !WATCHING.isEmpty()) {
            status.accept("клиент уже открыт для другого аккаунта (порты игры одни) — сначала закрой его");
            return;
        }
        Path app = clientApp();
        if (app == null) {
            status.accept("не нашёл DarkOrbit Client.app (задай ORBIT_CLIENT_APP)");
            return;
        }
        List<Integer> ports = new ArrayList<>(GAME_PORTS);
        if (!ports.contains(session.gamePort())) {
            ports.add(session.gamePort());
        }
        for (int port : ports) {
            if (!free(port)) {
                status.accept("порт " + port + " занят (запущен сниффер?) — закрой его и попробуй снова");
                return;
            }
        }
        HandRelay relay;
        Path dir = Path.of("recordings", "hand-" + account.replaceAll("[^\\w.-]", "_") + "-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now()));
        try {
            if (!h.route().direct()) {
                h.route().verify(); // a dead proxy: no client at all, never a direct one
            }
            String gameIp = InetAddress.getByName(session.gameHost()).getHostAddress();
            Upstream up = h.route().direct() ? Upstream.DIRECT
                    : (host, port) -> h.route().connector().open(host, port, 15_000);
            WorldFeed feed = h.feed();
            relay = new HandRelay(gameIp, ports, dir, up, (upstream, p, nanos) -> {
                if (upstream) {
                    feed.onSent(p, nanos);
                } else {
                    feed.onPacket(p, nanos);
                }
            });
        } catch (IOException | orbit.auth.AuthException | RuntimeException e) {
            status.accept("ретранслятор не запустился: " + e.getMessage());
            return;
        }
        BotRunner runner = h.runner().get();
        if (runner != null) {
            runner.setPaused(true);
        }
        conn.hold();
        LOG.info("Играть руками: игра отдана официальному клиенту, бот на паузе; запись — {}", dir);
        Process process;
        try {
            process = launch(app, account, relay.socksPort(), session);
        } catch (IOException e) {
            relay.close();
            conn.release();
            if (runner != null) {
                runner.setPaused(false);
            }
            status.accept("клиент не запустился: " + e.getMessage());
            return;
        }
        PLAYING.put(account, process);
        status.accept("играешь руками — закрой клиент, и бот продолжит");
        Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofPlatform().daemon().name("hand-" + account).start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                relay.close();
                PLAYING.remove(account);
                GameConnection now = h.conn().get();
                if (now != null) {
                    now.release();
                }
                BotRunner r = h.runner().get();
                if (r != null) {
                    r.setPaused(false);
                }
                LOG.info("Играть руками: клиент закрыт — бот входит снова; запись — {}", dir);
                status.accept("клиент закрыт — бот входит снова");
            }
        });
    }

    /**
     * The official client with the account's site session ({@code --url} the game page, {@code --dosid}), its own profile
     * folder, the site through the relay's SOCKS5 port and the game hosts mapped to loopback.
     */
    static Process launch(Path app, String account, int socksPort, GameSession session) throws IOException {
        return launch(app, "client-profile-", account, socksPort, session, "internalMapRevolution");
    }

    /** @param profilePrefix the profile folder's name before the account; {@code action} the site page opened first */
    static Process launch(Path app, String profilePrefix, String account, int socksPort, GameSession session, String action)
            throws IOException {
        Path profile = Path.of("local", profilePrefix + account.replaceAll("[^\\w.-]", "_")).toAbsolutePath();
        List<String> cmd = List.of(app.resolve("Contents/MacOS/DarkOrbit Client").toString(),
                "--proxy-server=socks5://127.0.0.1:" + socksPort,
                "--proxy-bypass-list=<-loopback>",
                "--host-resolver-rules=MAP *.prod.darkorbit.com 127.0.0.1",
                "--user-data-dir=" + profile,
                "--url=" + session.host() + "/indexInternal.es?action=" + action,
                "--dosid=" + session.dosid());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().remove("ELECTRON_RUN_AS_NODE");
        return pb.start();
    }

    private static final Map<String, Process> WATCHING = new ConcurrentHashMap<>();

    /** The account's bot is watched in the official client now. */
    public static boolean watching(String account) {
        return account != null && WATCHING.containsKey(account);
    }

    /**
     * «Смотреть» (docs/PLAN.md, a user's wish 2026-11-26): the official client shows the bot's game and can change nothing.
     * The bot plays on, untouched; the client's game sockets meet the app's {@link MirrorServer} (the bot's login, then the
     * server's packets to the bot live, the client's own packets dropped), its site pages go through the account's proxy,
     * the game hosts refused there.
     */
    public static synchronized void watch(String account, Consumer<String> status) {
        Handle h = ACCOUNTS.get(account);
        if (h == null) {
            status.accept("этот аккаунт не запущен ботом в этой программе");
            return;
        }
        if (WATCHING.containsKey(account)) {
            status.accept("уже смотришь — закрой клиент");
            return;
        }
        if (!PLAYING.isEmpty() || !WATCHING.isEmpty()) {
            status.accept("клиент уже открыт для другого аккаунта (порты игры одни) — сначала закрой его");
            return;
        }
        GameConnection conn = h.conn().get();
        GameSession session = conn == null ? null : conn.session();
        if (session == null || session.dosid() == null || session.host() == null || !h.feed().mirror().ready()) {
            status.accept("бот ещё не в игре — подожди входа");
            return;
        }
        Path app = clientApp();
        if (app == null) {
            status.accept("не нашёл DarkOrbit Client.app (задай ORBIT_CLIENT_APP)");
            return;
        }
        List<Integer> ports = new ArrayList<>(GAME_PORTS);
        if (!ports.contains(session.gamePort())) {
            ports.add(session.gamePort());
        }
        for (int port : ports) {
            if (!free(port)) {
                status.accept("порт " + port + " занят (запущен сниффер?) — закрой его и попробуй снова");
                return;
            }
        }
        HandRelay relay;
        MirrorServer mirror;
        try {
            if (!h.route().direct()) {
                h.route().verify();
            }
            Upstream up = h.route().direct() ? Upstream.DIRECT
                    : (host, port) -> h.route().connector().open(host, port, 15_000);
            relay = HandRelay.siteOnly(Path.of("recordings"), up);
            try {
                mirror = new MirrorServer(h.feed().mirror(), h.feed()::snapshot, ports);
            } catch (IOException e) {
                relay.close();
                throw e;
            }
        } catch (IOException | orbit.auth.AuthException | RuntimeException e) {
            status.accept("зеркало не запустилось: " + e.getMessage());
            return;
        }
        Process process;
        try {
            process = launch(app, account, relay.socksPort(), session);
        } catch (IOException e) {
            relay.close();
            mirror.close();
            status.accept("клиент не запустился: " + e.getMessage());
            return;
        }
        WATCHING.put(account, process);
        LOG.info("Смотреть: официальный клиент показывает игру бота, нажатия клиента в игру не идут");
        status.accept("смотришь игру бота — нажатия клиента никуда не идут; закрой клиент, когда хватит");
        Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofPlatform().daemon().name("watch-" + account).start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                relay.close();
                mirror.close();
                WATCHING.remove(account);
                LOG.info("Смотреть: клиент закрыт");
                status.accept("клиент закрыт");
            }
        });
    }

    private static final Map<String, Process> SITES = new ConcurrentHashMap<>();

    /** The account's site is open in a client window now. */
    public static boolean siteOpen(String account) {
        return account != null && SITES.containsKey(account);
    }

    /**
     * «Сайт» (docs/PLAN.md «Сайт аккаунта», a user's wish 2026-11-26): the account's site in the official client (it has
     * Flash for the hangar page) with the bot's site session, through the account's proxy; the bot goes on untouched. The
     * site's «Выйти» is not let through ({@link orbit.sniffer.SiteGuard}), and the game page cannot reach the game servers
     * (their hosts are refused): a login there would throw the bot out. No game ports: several site windows at once.
     */
    public static synchronized void site(String account, Consumer<String> status) {
        Handle h = ACCOUNTS.get(account);
        if (h == null) {
            status.accept("этот аккаунт не запущен ботом в этой программе");
            return;
        }
        if (SITES.containsKey(account)) {
            status.accept("сайт уже открыт — закрой его окно");
            return;
        }
        GameConnection conn = h.conn().get();
        GameSession session = conn == null ? null : conn.session();
        if (session == null || session.dosid() == null || session.host() == null) {
            status.accept("нет сессии сайта — подожди, пока бот войдёт");
            return;
        }
        Path app = clientApp();
        if (app == null) {
            status.accept("не нашёл DarkOrbit Client.app (задай ORBIT_CLIENT_APP)");
            return;
        }
        HandRelay relay;
        try {
            if (!h.route().direct()) {
                h.route().verify();
            }
            Upstream up = h.route().direct() ? Upstream.DIRECT
                    : (host, port) -> h.route().connector().open(host, port, 15_000);
            relay = HandRelay.siteOnly(Path.of("recordings"), up,
                    new orbit.sniffer.SiteGuard(Path.of("local", "site-guard.p12")));
        } catch (IOException | orbit.auth.AuthException | RuntimeException e) {
            status.accept("окно сайта не запустилось: " + e.getMessage());
            return;
        }
        Process process;
        try {
            process = launch(app, "client-site-", account, relay.socksPort(), session, "internalStart");
        } catch (IOException e) {
            relay.close();
            status.accept("клиент не запустился: " + e.getMessage());
            return;
        }
        SITES.put(account, process);
        LOG.info("Сайт: окно сайта аккаунта открыто (выход с сайта не пропускается)");
        status.accept("сайт открыт — бот работает дальше; «Выйти» на сайте не сработает, игра там не откроется");
        Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofPlatform().daemon().name("site-" + account).start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                relay.close();
                SITES.remove(account);
                LOG.info("Сайт: окно закрыто");
                status.accept("клиент закрыт");
            }
        });
    }

    /** Closes the account's site window. */
    public static void closeSite(String account) {
        Process p = SITES.get(account);
        if (p != null) {
            p.toHandle().descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    /** Ends the watching: the client is closed. */
    public static void stopWatch(String account) {
        Process p = WATCHING.get(account);
        if (p != null) {
            p.toHandle().descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    /** Ends the hand play: the client is closed, the bot logs in again. */
    public static void stop(String account) {
        Process p = PLAYING.get(account);
        if (p != null) {
            p.toHandle().descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    private static boolean free(int port) {
        try (ServerSocket s = new ServerSocket()) {
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
