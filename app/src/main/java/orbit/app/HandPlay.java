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
        if (!PLAYING.isEmpty()) {
            status.accept("руками уже играется другой аккаунт — сначала закрой его клиент");
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
        Path profile = Path.of("local", "client-profile-" + account.replaceAll("[^\\w.-]", "_")).toAbsolutePath();
        List<String> cmd = List.of(app.resolve("Contents/MacOS/DarkOrbit Client").toString(),
                "--proxy-server=socks5://127.0.0.1:" + relay.socksPort(),
                "--proxy-bypass-list=<-loopback>",
                "--host-resolver-rules=MAP *.prod.darkorbit.com 127.0.0.1",
                "--user-data-dir=" + profile,
                "--url=" + session.host() + "/indexInternal.es?action=internalMapRevolution",
                "--dosid=" + session.dosid());
        Process process;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.environment().remove("ELECTRON_RUN_AS_NODE");
            process = pb.start();
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
