package orbit.app;

import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import orbit.app.gui.MainWindow;
import orbit.auth.AccountLogin;
import orbit.auth.AuthClient;
import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.MapServers;
import orbit.game.ClientProfile;
import orbit.game.ConnectionSettings;
import orbit.game.GameCheck;
import orbit.game.GameConnection;
import orbit.game.GameListener;
import orbit.game.SessionSource;
import orbit.game.StartupSequence;
import orbit.net.Recording;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.secrets.MasterPassword;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;
import orbit.secrets.VaultTool;
import orbit.sniffer.PacketTap;
import orbit.sniffer.SnifferMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot application.
 *
 * <pre>
 *   orbit bot --account main [--headless] [--profile local/client-profile.json] [--vault local/vault.bin]
 *   orbit observe [--port 1080] [--direct IP:6001-6007] [--out recordings]   # watch the official client
 *   orbit replay recordings/session-….dorec [--speed 2]
 *   orbit render recordings/session-….dorec --at 30 [--out minimap.png]   # minimap at second 30 as PNG
 * </pre>
 * The bot itself does nothing in game yet: it logs in, keeps the connection and builds the world model.
 */
public final class OrbitMain {

    private static final Logger log = LoggerFactory.getLogger(OrbitMain.class);
    /** How often the headless mode writes the world summary. */
    private static final long HEADLESS_SUMMARY_MS = 30_000;

    private OrbitMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        Options o = Options.parse(rest);
        switch (args[0]) {
            case "bot" -> bot(o);
            case "observe" -> observe(o);
            case "replay" -> replay(o);
            case "render" -> render(o);
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("""
                Режимы:
                  orbit bot --account <имя> [--headless]   бот входит в игру сам
                  orbit observe [--direct IP:порты]        окно бота по трафику официального клиента (через сниффер)
                  orbit replay <запись.dorec> [--speed N]  проиграть запись сниффера
                  orbit render <запись.dorec> --at <сек> [--out файл.png]  мини-карта на момент записи""");
        System.exit(2);
    }

    // ---- bot ----

    private static void bot(Options o) throws Exception {
        if (o.account == null) {
            System.err.println("Укажите аккаунт: --account <имя> (vault add <имя>)");
            System.exit(2);
        }
        char[] master = MasterPassword.obtain(false);
        Vault vault;
        try {
            vault = Vault.open(o.vault, master);
        } catch (VaultException e) {
            System.err.println("Хранилище: " + e.getMessage());
            System.exit(1);
            return;
        } finally {
            Arrays.fill(master, '\0');
        }
        ClientProfile profile = Files.exists(o.profile) ? ClientProfile.load(o.profile) : ClientProfile.minimal();
        if (profile.windowSettings() == null) {
            log.warn("Профиль клиента {} не найден — раскладка окон и клавиши отправляться не будут "
                    + "(создайте его: client-profile recordings).", o.profile);
        }
        AuthClient auth = new AuthClient(new HttpSession("", Proxy.NO_PROXY, null), AuthClient.Endpoints.production());
        AccountLogin login = new AccountLogin(auth, vault, o.account);

        WorldFeed feed = new WorldFeed("бот, " + o.account);
        GameListener listener = new GameListener() {
            @Override
            public void onPacket(Packet p) {
                feed.onPacket(p, System.nanoTime());
            }

            @Override
            public void onSent(Packet p) {
                feed.onSent(p, System.nanoTime());
            }

            @Override
            public void onState(State state, String reason) {
                feed.setState(reason);
                log.info("Состояние: {} — {}", state, reason);
            }
        };
        SessionSource source = new SessionSource() {
            @Override
            public GameSession fresh() throws AuthException {
                return login.login();
            }

            @Override
            public MapServers.Address serverForMap(GameSession s, int mapId) throws AuthException {
                return auth.serverForMap(s, mapId);
            }
        };
        GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(Proxy.NO_PROXY),
                StartupSequence.clientLike(profile));
        Thread runner = Thread.ofPlatform().name("game").start(conn::run);
        Runtime.getRuntime().addShutdownHook(new Thread(conn::close));
        runUntilClosed(feed, o.headless, conn::close, runner);
    }

    // ---- observer ----

    private static void observe(Options o) throws Exception {
        WorldFeed feed = new WorldFeed("наблюдение за клиентом");
        PacketTap.register((upstream, p, nanos) -> {
            if (upstream) {
                feed.onSent(p, nanos);
            } else {
                feed.onPacket(p, nanos);
            }
        });
        Thread socks = SnifferMain.start(o.port, o.direct, o.out);
        feed.setState("Жду клиент на 127.0.0.1:" + o.port + (o.direct != null ? " и " + o.direct : ""));
        log.info("Сниффер запущен: socks5://127.0.0.1:{}; запустите клиент через scripts/sniff-session.sh --observe", o.port);
        runUntilClosed(feed, o.headless, () -> System.exit(0), socks);
    }

    // ---- replay ----

    private static void replay(Options o) throws Exception {
        if (o.positional.isEmpty()) {
            usage();
        }
        Path file = Path.of(o.positional.getFirst());
        List<Recording.PacketRecord> packets = Recording.readPackets(file);
        WorldFeed feed = new WorldFeed("запись " + file.getFileName());
        long base = System.nanoTime();
        double speed = o.speed;
        feed.setClock(() -> base + (long) ((System.nanoTime() - base) * speed));
        Thread player = Thread.ofPlatform().name("replay").daemon().start(() -> {
            try {
                for (Recording.PacketRecord r : packets) {
                    long at = base + r.nanos();
                    long wait = (long) ((at - feed.now()) / speed / 1_000_000);
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                    Packet p = PacketCodec.decode(r.bytes());
                    if (r.up()) {
                        feed.onSent(p, at);
                    } else {
                        feed.onPacket(p, at);
                    }
                }
                feed.setState("Запись закончилась");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Запись не проигрывается: {}", e.toString());
            }
        });
        feed.setState("Проигрываю " + file.getFileName() + (speed != 1 ? " ×" + speed : ""));
        runUntilClosed(feed, o.headless, () -> System.exit(0), player);
    }

    // ---- render ----

    private static void render(Options o) throws Exception {
        if (o.positional.isEmpty() || o.at < 0) {
            usage();
        }
        Path file = Path.of(o.positional.getFirst());
        WorldFeed feed = new WorldFeed("запись " + file.getFileName());
        long at = (long) (o.at * 1e9);
        for (Recording.PacketRecord r : Recording.readPackets(file)) {
            if (r.nanos() > at) {
                break;
            }
            Packet p = PacketCodec.decode(r.bytes());
            if (r.up()) {
                feed.onSent(p, r.nanos());
            } else {
                feed.onPacket(p, r.nanos());
            }
        }
        feed.setClock(() -> at);
        Path out = o.out.toString().endsWith(".png") ? o.out : Path.of("minimap.png");
        javax.imageio.ImageIO.write(MainWindow.renderMinimap(feed, 1000, 624), "png", out.toFile());
        System.out.println(GameCheck.describe(feed.snapshot()));
        System.out.println("Сохранено: " + out);
    }

    // ---- common ----

    /** Shows the window (or logs summaries when headless) until the window is closed or {@code worker} ends. */
    private static void runUntilClosed(WorldFeed feed, boolean headless, Runnable stop, Thread worker) throws InterruptedException {
        if (!headless && !java.awt.GraphicsEnvironment.isHeadless()) {
            CountDownLatch closed = new CountDownLatch(1);
            MainWindow.open(feed, closed::countDown);
            closed.await();
            stop.run();
            worker.join(5_000);
            return;
        }
        while (worker.isAlive()) {
            worker.join(HEADLESS_SUMMARY_MS);
            log.info("{}. {}", feed.state(), GameCheck.describe(feed.snapshot()));
        }
    }

    /** Command-line options shared by the modes. */
    record Options(String account, Path vault, Path profile, boolean headless, int port, String direct, Path out,
                   double speed, double at, List<String> positional) {

        static Options parse(List<String> args) {
            String account = null;
            Path vault = VaultTool.DEFAULT_FILE;
            Path profile = Path.of("local", "client-profile.json");
            boolean headless = false;
            int port = 1080;
            String direct = null;
            Path out = Path.of("recordings");
            double speed = 1;
            double at = -1;
            List<String> positional = new java.util.ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                switch (args.get(i)) {
                    case "--account" -> account = args.get(++i);
                    case "--vault" -> vault = Path.of(args.get(++i));
                    case "--profile" -> profile = Path.of(args.get(++i));
                    case "--headless" -> headless = true;
                    case "--port" -> port = Integer.parseInt(args.get(++i));
                    case "--direct" -> direct = args.get(++i);
                    case "--out" -> out = Path.of(args.get(++i));
                    case "--speed" -> speed = Double.parseDouble(args.get(++i));
                    case "--at" -> at = Double.parseDouble(args.get(++i));
                    default -> {
                        if (args.get(i).startsWith("--")) {
                            System.err.println("Неизвестный параметр: " + args.get(i));
                            System.exit(2);
                        }
                        positional.add(args.get(i));
                    }
                }
            }
            if (speed <= 0) {
                throw new IllegalArgumentException("--speed должен быть больше 0");
            }
            return new Options(account, vault, profile, headless, port, direct, out, speed, at, List.copyOf(positional));
        }
    }
}
